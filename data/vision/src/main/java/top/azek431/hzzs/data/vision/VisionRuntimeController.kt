package top.azek431.hzzs.data.vision

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.domain.automation.*
import top.azek431.hzzs.domain.vision.*
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.capture.CaptureState
import top.azek431.hzzs.service.capture.FrameSource
import top.azek431.hzzs.service.capture.FrameSourceFactory
import top.azek431.hzzs.service.overlay.OverlayController
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One serialized runtime pipeline:
 * capture -> C++ vision -> multi-object tracking -> overlay -> optional action.
 * Generation tokens prevent stale frames from reappearing after stop/restart.
 */
@Singleton
class VisionRuntimeController @Inject constructor(
    settingsRepository: SettingsRepository,
    private val sources: FrameSourceFactory,
    private val engine: VisionEngine,
    private val overlay: OverlayController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val generation = AtomicLong(0)
    private val latestConfig = AtomicReference(AppConfig())
    private val tracker = MultiObjectTracker()
    private val ledger = ActionCommitLedger()
    private val arbiter = GestureArbiter(SystemClock::uptimeMillis) { action ->
        HzzsAccessibilityService.dispatchCurrent(action)
    }
    private val mutableStatus = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var runtimeJob: Job? = null
    private var activeSource: FrameSource? = null
    private var armedWindowClass: String? = null
    private val actionIds = AtomicLong(1)
    private val recentActionTimes = ArrayDeque<Long>()

    init {
        scope.launch {
            settingsRepository.config.collect { next ->
                val previous = latestConfig.getAndSet(next)
                if (previous.selectedScene != next.selectedScene || previous.captureBackend != next.captureBackend) {
                    disarmAutomation()
                    tracker.reset()
                    ledger.reset()
                    engine.reset()
                }
                mutableStatus.update { it.copy(activeScene = next.selectedScene) }
            }
        }
    }

    suspend fun start() = lifecycleMutex.withLock {
        if (runtimeJob?.isActive == true) return
        val config = latestConfig.get()
        val source = sources.source(config.captureBackend)
        activeSource = source
        val token = generation.incrementAndGet()
        tracker.reset(); ledger.reset(); engine.reset(); armedWindowClass = null
        mutableStatus.value = RuntimeStatus(running = true, activeScene = config.selectedScene)
        source.start()
        runtimeJob = scope.launch { runLoop(token, source) }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        generation.incrementAndGet()
        runtimeJob?.cancelAndJoin()
        runtimeJob = null
        activeSource?.stop()
        activeSource = null
        tracker.reset(); ledger.reset(); engine.reset(); overlay.hide(); armedWindowClass = null
        mutableStatus.value = mutableStatus.value.copy(
            running = false, captureReady = false, overlayVisible = false,
            automationArmed = false, fps = 0f,
        )
    }

    suspend fun armAutomation(): Result<Unit> {
        val config = latestConfig.get()
        if (!mutableStatus.value.running) return Result.failure(IllegalStateException("请先启动视觉分析"))
        val window = HzzsAccessibilityService.foregroundSnapshot()
            ?: return Result.failure(IllegalStateException("无障碍服务未连接或前台页面未知"))
        if (window.packageName !in config.automation.allowedPackages) {
            return Result.failure(IllegalStateException("当前应用不在自动操作允许列表"))
        }
        if (window.className.isBlank()) return Result.failure(IllegalStateException("无法确认当前游戏页面"))
        armedWindowClass = window.className
        mutableStatus.update { it.copy(automationArmed = true) }
        return Result.success(Unit)
    }

    fun disarmAutomation() {
        armedWindowClass = null
        mutableStatus.update { it.copy(automationArmed = false) }
    }

    private suspend fun runLoop(token: Long, source: FrameSource) {
        var lastSequence = -1L
        var failureCount = 0
        var frameCount = 0
        var fpsWindowStart = SystemClock.elapsedRealtime()
        val stateJob = CoroutineScope(currentCoroutineContext()).launch {
            source.state.collectLatest { state ->
                if (state is CaptureState.Failed) mutableStatus.update { it.copy(lastError = state.message, captureReady = false) }
                else if (state == CaptureState.Ready) mutableStatus.update { it.copy(captureReady = true, lastError = null) }
            }
        }
        try {
            while (currentCoroutineContext().isActive && generation.get() == token) {
                val frame = source.nextFrame(lastSequence) ?: continue
                frame.use {
                    if (frame.sequence <= lastSequence || generation.get() != token) return@use
                    lastSequence = frame.sequence
                    val config = latestConfig.get()
                    val sceneConfig = config.scenes.getValue(config.selectedScene)
                    val result = engine.analyze(
                        VisionFrame(FrameMeta(frame.sequence, frame.elapsedRealtimeNanos, frame.width, frame.height), frame.pixels),
                        sceneConfig,
                    )
                    if (generation.get() != token) return@use
                    if (result.error != null) {
                        failureCount++
                        mutableStatus.update { status -> status.copy(lastError = result.error) }
                        if (failureCount >= 5) throw RuntimeException("视觉分析连续失败 $failureCount 次")
                    } else failureCount = 0
                    val tracked = tracker.update(frame.sequence, result.detections)
                    val trackedResult = result.copy(detections = tracked.map { it.detection })
                    overlay.show(config.overlay, trackedResult)
                    mutableStatus.update { it.copy(overlayVisible = true, lastError = null) }
                    maybeDispatch(config, trackedResult, tracked)
                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - fpsWindowStart
                    if (elapsed >= 1_000L) {
                        val fps = frameCount * 1_000f / elapsed
                        mutableStatus.update { it.copy(fps = fps) }
                        frameCount = 0; fpsWindowStart = now
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            disarmAutomation()
            mutableStatus.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
        } finally {
            stateJob.cancel()
            runCatching { source.stop() }
            overlay.hide()
            if (generation.get() == token) {
                mutableStatus.update { it.copy(running = false, captureReady = false, overlayVisible = false, automationArmed = false) }
            }
        }
    }

    private suspend fun maybeDispatch(
        config: AppConfig,
        result: VisionResult,
        tracked: List<MultiObjectTracker.TrackedDetection>,
    ) {
        val requiredClass = armedWindowClass ?: return
        if (!mutableStatus.value.automationArmed || result.sceneConfidence < config.automation.minimumSceneConfidence) return
        val sceneConfig = config.scenes.getValue(config.selectedScene)
        val player = result.player ?: return
        val candidate = tracked
            .asSequence()
            .filter { it.stableFrames >= sceneConfig.thresholds.stableFrames }
            .filter { it.detection.actionable && !it.detection.diagnosticOnly }
            .filter { it.detection.confidence >= sceneConfig.thresholds.minimumConfidence }
            .filter { it.detection.bounds.left >= player.bounds.right - sceneConfig.thresholds.behindPlayerMarginRatio }
            .minByOrNull { it.detection.bounds.left - player.bounds.right }
            ?: return
        if (!ledger.canPlan(candidate.trackId)) return
        val now = SystemClock.uptimeMillis()
        while (recentActionTimes.isNotEmpty() && now - recentActionTimes.first() >= 1_000L) recentActionTimes.removeFirst()
        val strokes = if (candidate.detection.avoidance == Avoidance.DOUBLE_JUMP) 2 else 1
        if (recentActionTimes.size + strokes > config.automation.maxActionsPerSecond) return
        var finalReceipt: DispatchReceipt? = null
        repeat(strokes) { index ->
            if (index > 0) delay(72L)
            val created = SystemClock.uptimeMillis()
            val action = AutomationAction(
                id = actionIds.getAndIncrement(),
                trackId = candidate.trackId,
                avoidance = candidate.detection.avoidance,
                gesture = gestureFor(candidate.detection.avoidance),
                createdAtUptimeMs = created,
                expiresAtUptimeMs = now + 650L,
                allowedPackages = config.automation.allowedPackages,
                requiredWindowClassPrefixes = setOf(requiredClass),
            )
            val receipt = arbiter.dispatch(action)
            finalReceipt = receipt
            if (receipt.outcome != DispatchOutcome.COMPLETED) {
                disarmAutomation()
                return
            }
            recentActionTimes.addLast(SystemClock.uptimeMillis())
        }
        finalReceipt?.let { ledger.commit(it) }
    }

    private fun gestureFor(avoidance: Avoidance): GestureSpec = when (avoidance) {
        Avoidance.SLIDE -> GestureSpec(.82f, .68f, .82f, .88f, 220L)
        Avoidance.DOUBLE_JUMP -> GestureSpec(.82f, .72f, durationMs = 24L)
        Avoidance.JUMP -> GestureSpec(.82f, .72f, durationMs = 24L)
        Avoidance.NONE -> GestureSpec(.82f, .72f, durationMs = 24L)
    }

}
