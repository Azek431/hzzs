package top.azek431.hzzs.data.vision

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.domain.automation.ActionCommitLedger
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.GestureArbiter
import top.azek431.hzzs.domain.automation.GestureSpec
import top.azek431.hzzs.domain.vision.Avoidance
import top.azek431.hzzs.domain.vision.Detection
import top.azek431.hzzs.domain.vision.FrameMeta
import top.azek431.hzzs.domain.vision.NormalizedRect
import top.azek431.hzzs.domain.vision.ObjectKind
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.domain.vision.VisionFrame
import top.azek431.hzzs.domain.vision.VisionResult
import top.azek431.hzzs.domain.vision.withApproximateDisplayContour
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.capture.CaptureState
import top.azek431.hzzs.service.capture.FrameSource
import top.azek431.hzzs.service.capture.FrameSourceFactory
import top.azek431.hzzs.service.overlay.OverlayController
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视觉运行时控制器：帧循环的唯一所有者。
 *
 * 职责：
 * - 串行编排「截图 → Native 分析 → 跨帧追踪 → 悬浮窗 → 可选自动动作」；
 * - 持有 [MultiObjectTracker]、动作账本与手势仲裁器，避免多入口并发 reset/analyze；
 * - 通过 [generation] 令牌丢弃已停止会话的陈旧帧与动作结果。
 *
 * 线程与所有权：
 * - 生命周期（start/stop/restart）在 [lifecycleMutex] 下串行；
 * - 帧循环运行于 [scope]（Default）；动作在独立 [actionJob] 中执行，与分析解耦；
 * - [CapturedFrame] 仅在 `frame.use { }` 内借用，循环不跨帧持有像素缓冲。
 *
 * 安全不变量：
 * - 自动操作默认关闭；需当前免责声明版本，并受 arm / 前台白名单 / 帧龄门控；
 * - 场景或算法 generation 变化时必须进入安全点：取消动作、清空 tracker 与去重缓存、disarm；
 * - 设置收集器只替换不可变配置快照，不直接操作引擎。
 *
 * 坐标：视觉结果与手势规划使用视口归一化坐标 `[0,1]`。
 */
@Singleton
class VisionRuntimeController @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sources: FrameSourceFactory,
    private val engine: VisionEngine,
    private val overlay: OverlayController,
    private val debugFrameRecorder: DebugFrameRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    /** 会话代数：stop/start 递增，用于 fail-closed 丢弃陈旧帧与动作。 */
    private val generation = AtomicLong(0)
    private val latestConfig = AtomicReference(AppConfig())
    private val tracker = MultiObjectTracker()
    private val ledger = ActionCommitLedger()
    private val arbiter = GestureArbiter(
        clock = SystemClock::uptimeMillis,
        dispatcher = { action -> HzzsAccessibilityService.dispatchCurrent(action) },
    )
    private val mutableStatus = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private val mutableLatestResult = MutableStateFlow<VisionResult?>(null)
    val latestResult: StateFlow<VisionResult?> = mutableLatestResult.asStateFlow()

    @Volatile
    private var runtimeJob: Job? = null

    /** 与帧循环解耦的动作协程；场景/算法切换或 stop 时必须取消。 */
    @Volatile
    private var actionJob: Job? = null

    @Volatile
    private var activeSource: FrameSource? = null

    private val armedWindowClass = AtomicReference<String?>(null)
    private val actionIds = AtomicLong(1)
    private val recentActionTimes = ArrayDeque<Long>()
    private val detectedPlayerReference = AtomicReference<Detection?>(null)
    private val actionMutex = Mutex()
    private val actionInFlight = AtomicBoolean(false)
    private val trackRetryCounts = mutableMapOf<Long, Int>()
    private var lastOverlaySignature: Int = Int.MIN_VALUE

    init {
        scope.launch {
            settingsRepository.config.collect { next ->
                val previous = latestConfig.getAndSet(next)
                val previousBackend = previous.effectiveCaptureBackend()
                val nextBackend = next.effectiveCaptureBackend()
                val safetyBoundaryChanged =
                    previous.selectedScene != next.selectedScene ||
                        previousBackend != nextBackend ||
                        previous.automation.allowedPackages != next.automation.allowedPackages
                // 安全边界变化：立即解除 arm，防止旧会话权限延续到新场景/后端。
                if (safetyBoundaryChanged) disarmAutomation()
                mutableStatus.update { it.copy(activeScene = next.selectedScene) }
                if (
                    previousBackend != nextBackend &&
                    mutableStatus.value.running
                ) {
                    // 设置页预览阶段会抑制截图后端切换；能走到这里说明已落盘配置变更。
                    launch { restart() }
                }
            }
        }
    }

    /**
     * 启动帧循环。
     *
     * 输入：当前设置中的截图后端与场景；输出：更新 [status]/[latestResult]。
     * 在 [lifecycleMutex] 内推进 [generation] 并 [resetPipeline]，保证单会话独占引擎与 tracker。
     */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (runtimeJob?.isActive == true) return@withLock

            val config = settingsRepository.config.first().also(latestConfig::set)
            val backend = config.effectiveCaptureBackend()
            val source = sources.source(backend)
            val token = generation.incrementAndGet()
            resetPipeline()
            // 启动时把当前激活算法快照同步到 native（默认 builtin）；失败保留 native 默认。
            runCatching {
                engine.configureAlgorithm(engine.currentActivation().profile)
            }
            activeSource = source
            mutableStatus.value = RuntimeStatus(
                running = true,
                activeScene = config.selectedScene,
                activeBackend = backend,
            )

            try {
                source.start()
                runtimeJob = scope.launch {
                    runLoop(
                        token = token,
                        source = source,
                        startedBackend = backend,
                    )
                }
            } catch (error: Throwable) {
                activeSource = null
                runCatching { source.stop() }
                mutableStatus.value = RuntimeStatus(
                    running = false,
                    activeScene = config.selectedScene,
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    /** 停止后重新启动；用于截图后端等需要换源的配置变更。 */
    suspend fun restart() {
        stop()
        start()
    }

    /**
     * 停止帧循环并释放截图源。
     *
     * 先递增 [generation] 使进行中的循环/动作 fail-closed，再 cancelAndJoin、隐藏悬浮窗并重置管线。
     */
    suspend fun stop() = lifecycleMutex.withLock {
        generation.incrementAndGet()
        runtimeJob?.cancelAndJoin()
        runtimeJob = null
        val source = activeSource
        activeSource = null
        runCatching { source?.stop() }
        overlay.hide()
        resetPipeline()
        mutableStatus.value = mutableStatus.value.copy(
            running = false,
            captureReady = false,
            overlayVisible = false,
            automationArmed = false,
            fps = 0f,
        )
    }

    /**
     * 会话级解锁自动操作（arm 门控）。
     *
     * 失败条件（fail-closed）：未启用自动操作、免责声明版本不足、视觉未运行、
     * 无障碍未连接/前台过期、包名不在白名单、无法确认窗口类名。
     * 成功时绑定当前前台 [armedWindowClass]。
     */
    suspend fun armAutomation(): Result<Unit> {
        val config = latestConfig.get()
        if (!config.automation.enabled) {
            return Result.failure(IllegalStateException("请先在设置中启用自动操作"))
        }
        if (config.automation.disclaimerAcceptedVersion < AppConfig.DISCLAIMER_VERSION) {
            return Result.failure(IllegalStateException("请先阅读并确认自动操作风险说明"))
        }
        if (!mutableStatus.value.running) {
            return Result.failure(IllegalStateException("请先启动视觉分析"))
        }
        val window = HzzsAccessibilityService.foregroundSnapshot()
            ?: return Result.failure(IllegalStateException("无障碍服务未连接或前台页面未知"))
        if (SystemClock.elapsedRealtime() - window.observedAtMs > FOREGROUND_MAX_AGE_MS) {
            return Result.failure(IllegalStateException("前台页面状态已过期，请重新进入游戏页面"))
        }
        if (window.packageName !in config.automation.allowedPackages) {
            return Result.failure(IllegalStateException("当前应用不在自动操作允许列表"))
        }
        if (window.className.isBlank()) {
            return Result.failure(IllegalStateException("无法确认当前游戏页面"))
        }
        armedWindowClass.set(window.className)
        mutableStatus.update { it.copy(automationArmed = true) }
        return Result.success(Unit)
    }

    /** 解除自动操作会话绑定；配置/场景/算法边界变化时也应调用。 */
    fun disarmAutomation() {
        armedWindowClass.set(null)
        mutableStatus.update { it.copy(automationArmed = false) }
    }

    /**
     * 帧循环主体：在 [token] 与 [generation] 一致期间持续取帧分析。
     *
     * 关键分支：
     * - 截图后端与启动时不一致 → 抛错要求重启；
     * - 场景禁用 → disarm、藏悬浮窗并退避；
     * - 场景或算法 generation 变化 → 安全点：停 [actionJob]、清 tracker/ledger/去重、disarm；
     * - 帧在 [frame.use] 内处理完即释放，分析前后再次校验 [generation]。
     */
    private suspend fun runLoop(
        token: Long,
        source: FrameSource,
        startedBackend: CaptureBackend,
    ) {
        var lastSequence = -1L
        var failureCount = 0
        var frameCount = 0
        var fpsWindowStart = SystemClock.elapsedRealtime()
        // Tracker 稳定帧按已分析帧计数，避免 conflated/排空帧让序号跳跃。
        var trackingSequence = -1L
        var pipelineScene: SceneId? = null
        var pipelineSceneConfig: SceneConfig? = null
        var pipelineAlgorithmGeneration = engine.activeAlgorithmGeneration()

        val stateJob = CoroutineScope(currentCoroutineContext()).launch {
            source.state.collectLatest { state ->
                when (state) {
                    CaptureState.Ready -> mutableStatus.update {
                        it.copy(captureReady = true, lastError = null)
                    }
                    CaptureState.Idle,
                    CaptureState.RequestingPermission -> mutableStatus.update { it.copy(captureReady = false) }
                    is CaptureState.Failed -> mutableStatus.update {
                        it.copy(captureReady = false, lastError = state.message)
                    }
                }
            }
        }

        try {
            while (currentCoroutineContext().isActive && generation.get() == token) {
                currentCoroutineContext().ensureActive()
                val config = latestConfig.get()
                if (config.effectiveCaptureBackend() != startedBackend) {
                    throw RuntimeRestartRequired("截图方式已更改，请重新启动视觉分析")
                }

                val sceneConfig = config.scenes[config.selectedScene]
                    ?: throw IllegalStateException("缺少场景配置：${config.selectedScene}")
                if (!sceneConfig.enabled) {
                    disarmAutomation()
                    overlay.hide()
                    mutableStatus.update {
                        it.copy(
                            overlayVisible = false,
                            activeScene = config.selectedScene,
                            lastError = "当前主题场景已禁用",
                        )
                    }
                    delay(DISABLED_SCENE_BACKOFF_MS)
                    continue
                }

                val algorithmGeneration = engine.activeAlgorithmGeneration()
                val sceneChanged =
                    pipelineScene != config.selectedScene || pipelineSceneConfig != sceneConfig
                val algorithmChanged = pipelineAlgorithmGeneration != algorithmGeneration
                if (sceneChanged || algorithmChanged) {
                    // 场景或算法切换必须进入安全点：停动作、清 tracker / 去重缓存。
                    // 不允许分析过程中半热切换；algorithm 配置应在帧循环外完成。
                    actionJob?.cancelAndJoin()
                    actionJob = null
                    actionInFlight.set(false)
                    tracker.reset()
                    trackingSequence = -1L
                    ledger.reset()
                    recentActionTimes.clear()
                    trackRetryCounts.clear()
                    detectedPlayerReference.set(null)
                    disarmAutomation()
                    lastOverlaySignature = Int.MIN_VALUE
                    pipelineScene = config.selectedScene
                    pipelineSceneConfig = sceneConfig
                    pipelineAlgorithmGeneration = algorithmGeneration
                    failureCount = 0
                    mutableStatus.update {
                        it.copy(activeScene = config.selectedScene, lastError = null)
                    }
                }

                // HZZS_V092_COMPLETION_DRIVEN_CAPTURE
                // 无固定 FPS sleep：上一轮完成后直接等待最新新帧。
                // HUD 已显示时先临时隐身并等待一次显示提交；MediaProjection/AUTO
                // 再排空一张可能含旧合成层的帧，随后取得干净输入缓冲。
                val overlaySuspended =
                    mutableStatus.value.overlayVisible &&
                        config.overlay.enabled &&
                        overlay.suspendForCapture()
                val frame = try {
                    val continuousProjection =
                        startedBackend == CaptureBackend.AUTO ||
                            startedBackend == CaptureBackend.MEDIA_PROJECTION
                    if (overlaySuspended && continuousProjection) {
                        val drained = source.nextFrame(lastSequence)
                        if (drained == null) {
                            null
                        } else {
                            drained.use {
                                if (drained.sequence > lastSequence) lastSequence = drained.sequence
                            }
                            source.nextFrame(lastSequence)
                        }
                    } else {
                        source.nextFrame(lastSequence)
                    }
                } finally {
                    if (overlaySuspended && generation.get() == token) {
                        overlay.resumeAfterCapture()
                    }
                }
                if (frame == null) {
                    when (val state = source.state.value) {
                        is CaptureState.Failed -> throw CaptureUnavailable(state.message)
                        CaptureState.RequestingPermission -> delay(PERMISSION_BACKOFF_MS)
                        CaptureState.Ready -> delay(READY_NULL_FRAME_BACKOFF_MS)
                        CaptureState.Idle -> delay(IDLE_BACKOFF_MS)
                    }
                    continue
                }

                // 帧所有权：仅在 use 块内借用像素；退出后必须已 close，禁止跨帧缓存。
                frame.use {
                    if (frame.sequence <= lastSequence || generation.get() != token) return@use
                    lastSequence = frame.sequence
                    debugFrameRecorder.offer(frame, config.developer)
                    val result = engine.analyze(
                        VisionFrame(
                            FrameMeta(
                                sequence = frame.sequence,
                                timestampNanos = frame.elapsedRealtimeNanos,
                                sourceWidth = frame.width,
                                sourceHeight = frame.height,
                            ),
                            frame.pixels,
                        ),
                        sceneConfig,
                        config.viewport,
                    )
                    // 分析可能耗时；返回后若会话已停，丢弃结果避免污染新会话。
                    if (generation.get() != token) return@use

                    if (result.error != null) {
                        failureCount++
                        disarmAutomation()
                        val showGrid = config.developer.enabled && config.developer.showCoordinateGrid
                        val visible = publishOverlay(config.overlay, result, showGrid, force = true)
                        mutableStatus.update {
                            it.copy(overlayVisible = visible, lastError = result.error)
                        }
                        if (failureCount >= MAX_CONSECUTIVE_VISION_FAILURES) {
                            throw VisionUnavailable("视觉分析连续失败 $failureCount 次：${result.error}")
                        }
                        return@use
                    }
                    failureCount = 0

                    val resultWithReference = result.withPlayerReference(sceneConfig)
                    // Tracker 稳定帧按已分析帧计数，避免 conflated/排空帧让序号跳跃。
                    trackingSequence++
                    val tracked = tracker.update(trackingSequence, resultWithReference.detections)
                    val trackedResult = resultWithReference.copy(
                        detections = tracked.map { it.detection.withApproximateDisplayContour() },
                    )
                    mutableLatestResult.value = trackedResult
                    val showGrid = config.developer.enabled && config.developer.showCoordinateGrid
                    val visible = publishOverlay(config.overlay, trackedResult, showGrid, force = false)
                    mutableStatus.update {
                        it.copy(
                            overlayVisible = visible,
                            lastError = null,
                            processingMs = trackedResult.processingNanos / 1_000_000f,
                            obstacleCount = trackedResult.detections.size,
                            activeBackend = startedBackend,
                        )
                    }
                    maybeDispatch(
                        token = token,
                        config = config,
                        result = trackedResult,
                        tracked = tracked,
                        frameTimestampNanos = frame.elapsedRealtimeNanos,
                    )

                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - fpsWindowStart
                    if (elapsed >= FPS_WINDOW_MS) {
                        val fps = frameCount * FPS_WINDOW_MS.toFloat() / elapsed.toFloat()
                        mutableStatus.update { it.copy(fps = fps) }
                        frameCount = 0
                        fpsWindowStart = now
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            disarmAutomation()
            mutableStatus.update {
                it.copy(lastError = error.message ?: error.javaClass.simpleName)
            }
        } finally {
            stateJob.cancel()
            runCatching { source.stop() }
            overlay.hide()
            // 仅当仍是本会话 token 时写终态，避免覆盖已启动的新会话状态。
            if (generation.get() == token) {
                activeSource = null
                mutableStatus.update {
                    it.copy(
                        running = false,
                        captureReady = false,
                        overlayVisible = false,
                        automationArmed = false,
                        fps = 0f,
                    )
                }
            }
        }
    }

    /**
     * 在帧路径上评估是否派发自动动作。
     *
     * 门控：自动操作开关、场景置信度、帧龄、实验开关、窗口类名、CAS [actionInFlight]。
     * 真正手势在独立 [actionJob] 中执行，避免阻塞分析循环。
     */
    private fun maybeDispatch(
        token: Long,
        config: AppConfig,
        result: VisionResult,
        tracked: List<MultiObjectTracker.TrackedDetection>,
        frameTimestampNanos: Long,
    ) {
        if (!config.automation.enabled) {
            disarmAutomation()
            return
        }
        if (result.sceneConfidence < config.automation.minimumSceneConfidence) return

        // 帧龄门控：过旧的分析结果不再触发动作。
        val frameAgeMs = (SystemClock.elapsedRealtimeNanos() - frameTimestampNanos) / 1_000_000L
        if (frameAgeMs < 0L || frameAgeMs > MAX_FRAME_AGE_MS) return

        if (
            config.selectedScene == SceneId.BAMBOO_BOOKSTORE &&
            !config.automation.bambooExperimentalAutoAction
        ) {
            return
        }

        val requiredClass = resolveRequiredWindowClass(config) ?: return

        // CAS 占坑，保证同一时刻最多一个动作任务。
        if (!actionInFlight.compareAndSet(false, true)) return

        val sceneConfig = config.scenes.getValue(config.selectedScene)
        val player = result.player
        if (player == null) {
            actionInFlight.set(false)
            return
        }
        val playerWidth = player.bounds.width.coerceAtLeast(0.01f)
        val triggerDistance = when (config.selectedScene) {
            SceneId.SWEET_FACTORY -> config.automation.sweetTriggerDistancePlayerWidths
            SceneId.BAMBOO_BOOKSTORE -> config.automation.bambooTriggerDistancePlayerWidths
        } * playerWidth

        val candidate = tracked
            .asSequence()
            .filter { it.stableFrames >= sceneConfig.thresholds.stableFrames }
            .filter { it.detection.actionable && !it.detection.diagnosticOnly }
            .filter { it.detection.confidence >= sceneConfig.thresholds.minimumConfidence }
            .filter {
                it.detection.bounds.left >=
                    player.bounds.right - sceneConfig.thresholds.behindPlayerMarginRatio
            }
            .map { trackedDetection ->
                val gap = trackedDetection.detection.bounds.left - player.bounds.right
                trackedDetection to gap
            }
            .filter { (_, gap) -> gap <= triggerDistance }
            .minByOrNull { (_, gap) -> gap }
            ?.first

        if (candidate == null) {
            actionInFlight.set(false)
            return
        }

        val spatialKey = spatialKeyOf(candidate.detection)
        val now = SystemClock.uptimeMillis()
        actionJob = scope.launch {
            try {
                dispatchPlan(
                    token = token,
                    config = config,
                    requiredClass = requiredClass,
                    candidate = candidate,
                    spatialKey = spatialKey,
                    plannedAt = now,
                )
            } finally {
                actionInFlight.set(false)
            }
        }
    }

    /**
     * 解析动作所需的前台窗口类名前缀。
     *
     * - requireSessionArm=true：必须先 [armAutomation] 绑定窗口；
     * - requireSessionArm=false：每次读当前前台（仍校验白名单与时效），无需手动 arm。
     */
    private fun resolveRequiredWindowClass(config: AppConfig): String? {
        if (config.automation.requireSessionArm) {
            if (!mutableStatus.value.automationArmed) return null
            return armedWindowClass.get()
        }
        val foreground = HzzsAccessibilityService.foregroundSnapshot() ?: return null
        if (SystemClock.elapsedRealtime() - foreground.observedAtMs > FOREGROUND_MAX_AGE_MS) return null
        if (foreground.packageName !in config.automation.allowedPackages) return null
        if (foreground.className.isBlank()) return null
        return foreground.className
    }

    /**
     * 在 [actionMutex] 下规划并提交手势序列。
     *
     * 再次校验 [generation]、账本去重、前台包/窗口与速率上限；
     * 单次 stroke 失败可按 retryLimit 退避重试，耗尽后可选 disarm。
     */
    private suspend fun dispatchPlan(
        token: Long,
        config: AppConfig,
        requiredClass: String,
        candidate: MultiObjectTracker.TrackedDetection,
        spatialKey: String,
        plannedAt: Long,
    ) {
        if (generation.get() != token) return
        actionMutex.withLock {
            if (generation.get() != token) return
            if (!ledger.canPlan(candidate.trackId, spatialKey, plannedAt)) return

            val foreground = HzzsAccessibilityService.foregroundSnapshot() ?: run {
                disarmAutomation()
                return
            }
            if (
                SystemClock.elapsedRealtime() - foreground.observedAtMs > FOREGROUND_MAX_AGE_MS ||
                foreground.packageName !in config.automation.allowedPackages ||
                !foreground.className.startsWith(requiredClass)
            ) {
                if (config.automation.requireSessionArm) disarmAutomation()
                return
            }

            val now = SystemClock.uptimeMillis()
            while (
                recentActionTimes.isNotEmpty() &&
                now - recentActionTimes.first() >= ACTION_RATE_WINDOW_MS
            ) {
                recentActionTimes.removeFirst()
            }

            val plan = planGestures(config.selectedScene, candidate.detection.avoidance, now)
            if (plan.isEmpty()) return
            if (recentActionTimes.size + plan.size > config.automation.maxActionsPerSecond) return

            for ((index, stroke) in plan.withIndex()) {
                if (generation.get() != token) return
                if (index > 0) {
                    val wait = (stroke.dueAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                    if (wait > 0L) delay(wait)
                }
                if (generation.get() != token || !currentCoroutineContext().isActive) return

                var attempt = 0
                var completed = false
                while (attempt <= config.automation.retryLimit && !completed) {
                    if (generation.get() != token || !currentCoroutineContext().isActive) return
                    val created = SystemClock.uptimeMillis()
                    val action = AutomationAction(
                        id = actionIds.getAndIncrement(),
                        trackId = candidate.trackId,
                        avoidance = candidate.detection.avoidance,
                        gesture = stroke.gesture,
                        createdAtUptimeMs = created,
                        expiresAtUptimeMs = created + stroke.ttlMs,
                        allowedPackages = config.automation.allowedPackages,
                        requiredWindowClassPrefixes = setOf(requiredClass),
                        retryCount = attempt,
                    )
                    val receipt = arbiter.dispatch(action)
                    when (receipt.outcome) {
                        DispatchOutcome.COMPLETED -> {
                            ledger.commit(receipt, spatialKey)
                            recentActionTimes.addLast(SystemClock.uptimeMillis())
                            trackRetryCounts.remove(candidate.trackId)
                            completed = true
                        }
                        DispatchOutcome.EXPIRED,
                        DispatchOutcome.CANCELLED,
                        DispatchOutcome.REJECTED,
                        -> {
                            attempt++
                            val retriesUsed = (trackRetryCounts[candidate.trackId] ?: 0) + 1
                            trackRetryCounts[candidate.trackId] = retriesUsed
                            if (attempt > config.automation.retryLimit) {
                                if (config.automation.requireSessionArm) disarmAutomation()
                                return
                            }
                            delay(RETRY_BACKOFF_MS)
                        }
                    }
                }
                if (!completed) return
            }
        }
    }

    /**
     * 发布悬浮窗；非 force 时用签名跳过无变化刷新。
     * 悬浮窗绘制侧负责将归一化坐标转为像素。
     */
    private suspend fun publishOverlay(
        overlayConfig: top.azek431.hzzs.core.model.OverlayConfig,
        result: VisionResult?,
        showCoordinateGrid: Boolean,
        force: Boolean,
    ): Boolean {
        val signature = overlaySignature(overlayConfig, result, showCoordinateGrid)
        if (!force && signature == lastOverlaySignature) {
            return mutableStatus.value.overlayVisible
        }
        val visible = overlay.show(
            config = overlayConfig,
            result = result,
            showCoordinateGrid = showCoordinateGrid,
        )
        lastOverlaySignature = signature
        return visible
    }

    /** 粗粒度签名：用于抑制重复 overlay 刷新，非密码学哈希。 */
    private fun overlaySignature(
        config: top.azek431.hzzs.core.model.OverlayConfig,
        result: VisionResult?,
        showCoordinateGrid: Boolean,
    ): Int {
        var hash = config.hashCode()
        hash = 31 * hash + showCoordinateGrid.hashCode()
        hash = 31 * hash + (result?.detections?.size ?: -1)
        hash = 31 * hash + ((result?.sceneConfidence ?: -1f) * 1000f).toInt()
        result?.detections?.forEach { detection ->
            hash = 31 * hash + detection.kind.hashCode()
            hash = 31 * hash + detection.id.hashCode()
            hash = 31 * hash + (detection.bounds.left * 1000f).toInt()
            hash = 31 * hash + (detection.bounds.top * 1000f).toInt()
            hash = 31 * hash + (detection.bounds.right * 1000f).toInt()
            hash = 31 * hash + (detection.bounds.bottom * 1000f).toInt()
            hash = 31 * hash + (detection.confidence * 100f).toInt()
            hash = 31 * hash + detection.actionable.hashCode()
        }
        hash = 31 * hash + (result?.error?.hashCode() ?: 0)
        return hash
    }

    /** 空间去重键：按 kind + 量化中心，配合 trackId 防止重复提交同一障碍。 */
    private fun spatialKeyOf(detection: Detection): String {
        val cx = ((detection.bounds.left + detection.bounds.right) * 0.5f * 20f).toInt()
        val cy = ((detection.bounds.top + detection.bounds.bottom) * 0.5f * 20f).toInt()
        return "${detection.kind.name}:$cx:$cy"
    }

    private data class PlannedStroke(
        val gesture: GestureSpec,
        val dueAt: Long,
        val ttlMs: Long,
    )

    /**
     * 按避障类型规划归一化手势时序。
     *
     * - 地面大障碍 / 宽坑：双跳，间隔随赛季变化；
     * - 头顶障碍：下滑，TTL 更长；
     * - 手势坐标为归一化视口比例，由无障碍分发层转像素。
     */
    private fun planGestures(
        scene: SceneId,
        avoidance: Avoidance,
        now: Long,
    ): List<PlannedStroke> {
        val jump = GestureSpec(0.82f, 0.72f, durationMs = 24L)
        val slide = GestureSpec(0.82f, 0.68f, 0.82f, 0.88f, 220L)
        return when (avoidance) {
            Avoidance.NONE -> emptyList()
            Avoidance.JUMP -> listOf(PlannedStroke(jump, now, ACTION_TTL_MS))
            Avoidance.DOUBLE_JUMP -> {
                val gap = when (scene) {
                    SceneId.SWEET_FACTORY -> DOUBLE_JUMP_GAP_SWEET_MS
                    SceneId.BAMBOO_BOOKSTORE -> DOUBLE_JUMP_GAP_BAMBOO_MS
                }
                listOf(
                    PlannedStroke(jump, now, ACTION_TTL_MS),
                    PlannedStroke(jump, now + gap, ACTION_TTL_MS),
                )
            }
            Avoidance.SLIDE -> {
                val ttl = when (scene) {
                    SceneId.SWEET_FACTORY -> SLIDE_TTL_SWEET_MS
                    SceneId.BAMBOO_BOOKSTORE -> SLIDE_TTL_BAMBOO_MS
                }
                listOf(PlannedStroke(slide, now, ttl))
            }
        }
    }

    /**
     * 解析玩家参考框，不必每帧都跑玩家检测。
     *
     * CONTINUOUS / DETECT_ONCE / FIXED_RATIO 三种模式；FIXED_RATIO 直接合成归一化框。
     */
    private fun VisionResult.withPlayerReference(sceneConfig: SceneConfig): VisionResult {
        val thresholds = sceneConfig.thresholds
        val reference = when (thresholds.playerReferenceMode) {
            PlayerReferenceMode.CONTINUOUS -> player
            PlayerReferenceMode.DETECT_ONCE -> {
                player?.also { detectedPlayerReference.compareAndSet(null, it) }
                detectedPlayerReference.get()
            }
            PlayerReferenceMode.FIXED_RATIO -> fixedPlayerReference(thresholds.fixedPlayerXRatio)
        }
        return copy(player = reference)
    }

    private fun fixedPlayerReference(xRatio: Float): Detection {
        val center = xRatio.coerceIn(0.05f, 0.45f)
        val halfWidth = 0.025f
        return Detection(
            id = Long.MAX_VALUE,
            kind = ObjectKind.PLAYER,
            bounds = NormalizedRect(
                left = (center - halfWidth).coerceAtLeast(0f),
                top = 0.72f,
                right = (center + halfWidth).coerceAtMost(1f),
                bottom = 0.94f,
            ),
            confidence = 1f,
            actionable = false,
        )
    }

    /**
     * 清空运行时管线状态：取消动作、重置 tracker/ledger/引擎分析侧、解除 arm。
     * 不切换算法 profile；算法回退由引擎 [VisionEngine.configureAlgorithm] 负责。
     */
    private suspend fun resetPipeline() {
        actionJob?.cancelAndJoin()
        actionJob = null
        actionInFlight.set(false)
        tracker.reset()
        ledger.reset()
        engine.reset()
        mutableLatestResult.value = null
        detectedPlayerReference.set(null)
        armedWindowClass.set(null)
        recentActionTimes.clear()
        trackRetryCounts.clear()
        lastOverlaySignature = Int.MIN_VALUE
    }

    private class CaptureUnavailable(message: String) : IllegalStateException(message)
    private class VisionUnavailable(message: String) : IllegalStateException(message)
    private class RuntimeRestartRequired(message: String) : IllegalStateException(message)

    private fun AppConfig.effectiveCaptureBackend(): CaptureBackend =
        developer.forceCaptureBackend?.takeIf { developer.enabled } ?: captureBackend

    private companion object {
        const val FOREGROUND_MAX_AGE_MS = 1_500L
        const val MAX_CONSECUTIVE_VISION_FAILURES = 5
        const val FPS_WINDOW_MS = 1_000L
        const val ACTION_RATE_WINDOW_MS = 1_000L
        const val ACTION_TTL_MS = 650L
        const val DOUBLE_JUMP_GAP_SWEET_MS = 75L
        const val DOUBLE_JUMP_GAP_BAMBOO_MS = 80L
        const val SLIDE_TTL_SWEET_MS = 650L
        const val SLIDE_TTL_BAMBOO_MS = 600L
        const val MAX_FRAME_AGE_MS = 120L
        const val RETRY_BACKOFF_MS = 40L
        const val PERMISSION_BACKOFF_MS = 80L
        const val READY_NULL_FRAME_BACKOFF_MS = 12L
        const val IDLE_BACKOFF_MS = 80L
        const val DISABLED_SCENE_BACKOFF_MS = 250L
    }
}
