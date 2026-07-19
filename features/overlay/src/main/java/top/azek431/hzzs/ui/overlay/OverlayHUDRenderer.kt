// 火崽崽助手（HZZS）悬浮窗 HUD 渲染器。
//
// 负责驱动 C++ 分析引擎运行，提供两种执行模式：
// 1. 循环执行（start）：按固定间隔（100ms）持续生成模拟跑酷帧，直到用户手动停止
// 2. 单次执行（startSingle）：生成一帧后立即退出
//
// 核心职责：
// 1. 模拟帧生成：按固定间隔生成模拟跑酷画面数据
// 2. 引擎驱动：将模拟数据传入 C++ 引擎运行分析
// 3. 生命周期管理：开始/停止渲染循环，线程安全
// 4. 结果分发：通过 onFrameResultListener 将分析结果推送给 UI
//
// 模拟数据策略：
// - 玩家矩形：在屏幕中下部缓慢左右移动（正弦波驱动）
// - 危险物（蛋糕断层）：周期性出现在玩家前方，以世界滚动速度向左移动
// - 每帧时间戳递增 100ms（模拟 10fps 帧率）
// - 危险物类型循环切换：1=蛋糕断层, 2=毒瓶, 3=裱花袋
// - 循环执行默认无限运行，直到用户点击停止按钮
//
// 线程模型：
// - 模拟帧循环在独立后台线程运行
// - 使用 volatile 标志（isRunning）控制循环启停
// - stop() 中调用 thread.join(1000) 等待线程退出，超时强制放弃
// - onFrameResultListener 回调在主线程执行（通过 mainHandler.post）
// - 视觉识别在独立后台线程池执行（visualRecognitionExecutor），避免阻塞模拟帧循环

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import top.azek431.hzzs.core.data.native.NativeEngineFacade
import top.azek431.hzzs.core.data.native.NativeLibraryLoader
import top.azek431.hzzs.core.data.native.VisionBridge
import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.RectF
import top.azek431.hzzs.core.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * HUD 渲染器。
 *
 * 负责生成模拟跑酷帧数据并通过 JNI 驱动 C++ 分析引擎运行。
 * 通过 onFrameResultListener 回调将分析结果推送给 UI 层绘制。
 *
 * 循环执行模式默认无限运行（maxFrames=-1），直到用户点击停止按钮。
 * 可通过构造参数自定义最大帧数，设置为正数时达到上限自动停止。
 *
 * @param context 上下文（用于获取 displayMetrics 等系统信息）
 * @param maxFrames 最大模拟帧数，-1 表示无限运行（默认），正数表示达到该帧数后自动停止
 */
class OverlayHUDRenderer(
    private val context: Context,
    private val maxFrames: Int = -1,
) {

    // ==================== UI 结果回调 ====================

    /**
     * 帧分析结果回调监听器。
     *
     * 每次 analyzeFrame 返回结果后，通过此回调将 FrameAnalysisResult
     * 推送给 UI 层（HUDCanvasView）进行绘制。
     * 回调在主线程执行，确保 UI 线程安全。
     */
    private var onFrameResultListener: ((FrameAnalysisResult) -> Unit)? = null

    /**
     * 设置帧分析结果回调监听器。
     *
     * @param listener 回调函数，接收每次分析得到的 FrameAnalysisResult
     */
    fun setOnFrameResultListener(listener: (FrameAnalysisResult) -> Unit) {
        onFrameResultListener = listener
    }

    /** 主线程 Handler，用于将后台线程的分析结果切换到主线程执行 UI 回调 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 视觉识别后台线程池（单线程，避免并发截图冲突） */
    private val visualRecognitionExecutorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor {
            Thread(it, "hzzs-vision-recognition").apply { isDaemon = true }
        }
    }
    private val visualRecognitionExecutor: ExecutorService by visualRecognitionExecutorDelegate

    // ==================== 视觉识别回调 ====================

    /**
     * 视觉识别结果回调监听器。
     *
     * 每次视觉识别完成后，通过此回调将绿瓶/坑位检测结果推送给 UI 层。
     */
    private var onVisualRecognitionListener: ((Boolean, Int, Int, Int, Int, Int, Float, Double,
                                                Boolean, Int, Int, Int, Int, Int, Int, Float) -> Unit)? = null

    /**
     * 设置视觉识别结果回调监听器。
     *
     * @param listener 回调函数，参数依次为：
     *   bottleFound, bottleLeft, bottleRight, bottleCenterX, bottleScanY, bottleWidth, bottleConfidence, bottleCostMs,
     *   pitFound, pitLeft, pitRight, pitCenterX, pitScanY, pitWidth, pitEdgeGap, pitConfidence
     */
    fun setOnVisualRecognitionListener(listener: (Boolean, Int, Int, Int, Int, Int, Float, Double,
                                                   Boolean, Int, Int, Int, Int, Int, Int, Float) -> Unit) {
        onVisualRecognitionListener = listener
    }
    /** Releases all threads and callbacks. A disposed renderer must not be reused. */
    fun dispose() {
        stop()
        onFrameResultListener = null
        onVisualRecognitionListener = null
        if (visualRecognitionExecutorDelegate.isInitialized()) {
            visualRecognitionExecutorDelegate.value.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "HZZS-HUD"

        /** 帧间隔（毫秒），模拟 10fps 帧率 */
        private const val FRAME_INTERVAL_MS = 100L

        /** 危险物出现间隔（帧数），每 10 帧（~1s）出现一个危险物 */
        private const val HAZARD_INTERVAL = 10

        /** 世界滚动速度（归一化坐标/秒），向左为负值 */
        private const val WORLD_SCROLL_SPEED = -0.45f
    }

    // ==================== 状态 ====================

    /** 是否正在运行（volatile 保证多线程可见性） */
    @Volatile
    private var isRunning = false

    /** 当前模拟帧计数器，用于计算时间戳和判断是否达到 MAX_FRAMES */
    @Volatile
    private var frameCount = 0

    /** 后台模拟帧循环线程，在 start() 时创建，stop() 时 join 等待退出 */
    private var simulationThread: Thread? = null

    /**
     * 是否已达到最大帧数限制。
     *
     * 当 maxFrames > 0 时，达到该帧数后自动停止循环。
     * maxFrames <= 0 时永不自动停止，必须由用户手动点击停止。
     */
    @Volatile
    private var reachedMaxFrames = false

    /**
     * 单次执行是否已完成。
     *
     * 用于 startSingle() 的线程与主线程之间的同步：
     * 主线程通过轮询此标志来等待单次执行真正完成，
     * 避免 400ms 定时器在线程还没跑完时就恢复 UI 状态。
     */
    @Volatile
    private var singleDone = false

    // ==================== 模拟数据状态 ====================

    /**
     * 玩家 X 位置偏移量（归一化），用于驱动正弦波左右移动动画。
     * 每帧递增 0.003，通过 sin(playerXOffset) 产生周期性摆动。
     */
    private var playerXOffset = 0f

    /**
     * 当前危险物位置（归一化 left 坐标），null 表示当前帧无危险物。
     * 危险物以 WORLD_SCROLL_SPEED 向左移动，移出屏幕后重置为 null。
     */
    private var currentHazardLeft: Float? = null

    /**
     * 当前危险物类型（1=蛋糕断层, 2=毒瓶, 3=裱花袋）。
     * 按 HAZARD_INTERVAL 循环切换，确保三种危险物依次出现。
     */
    private var currentHazardType = 0

    /** 上次生成危险物的帧计数，用于计算距上次生成的间隔 */
    private var lastHazardFrame = -HAZARD_INTERVAL

    // ==================== 生命周期 ====================

    /**
     * 启动模拟帧生成循环。
     *
     * 流程：
     * 1. 重置所有状态（frameCount/playerXOffset/currentHazardLeft 等）
     * 2. 创建后台线程并启动 simulationLoop
     * 3. 线程以 FRAME_INTERVAL_MS 间隔生成模拟帧
     * 4. 循环条件：isRunning == true 且（maxFrames <= 0 或 frameCount < maxFrames）
     *
     * 默认无限运行，必须由用户手动点击停止按钮才会退出。
     * 幂等性：如果已经在运行，调用此方法会被忽略。
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "[HUD] already running, ignoring start.")
            return
        }

        isRunning = true
        frameCount = 0
        reachedMaxFrames = false
        playerXOffset = 0f
        currentHazardLeft = null
        lastHazardFrame = -HAZARD_INTERVAL
        singleDone = false

        simulationThread = Thread(this::simulationLoop, "hzzs-hud-simulation").apply {
            isDaemon = true  // 守护线程，进程退出时自动终止
            start()
        }

        Log.i(TAG, "[HUD] simulation started. maxFrames=$maxFrames")
        Logger.i("HUD", "循环执行已启动，maxFrames=$maxFrames")
    }

    /**
     * 停止模拟帧生成循环。
     *
     * 流程：
     * 1. 设置 isRunning = false，通知 simulationLoop 退出 while 循环
     * 2. 调用 thread.join(1000) 等待线程退出，最多等待 1 秒
     * 3. 将 simulationThread 置为 null
     *
     * 幂等性：如果未在运行，调用此方法会被忽略。
     */
    fun stop() {
        isRunning = false
        val thread = simulationThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1_000L) }
                .onFailure { Log.w(TAG, "[HUD] interrupted while joining simulation thread.", it) }
        }
        simulationThread = null
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "[HUD] simulation stopped.")
        Logger.i("HUD", "循环执行已停止，共处理 $frameCount 帧")
    }

    /**
     * 单次执行：生成一帧分析后立即停止。
     *
     * 流程：
     * 1. 如果引擎不可用，立即返回并记录警告（不创建线程）
     * 2. 重置所有模拟数据状态
     * 3. 创建后台线程执行 singleExecutionLoop
     * 4. 调用 stop() 等待线程退出（join 500ms）
     * 5. 线程退出后设置 singleDone = true
     *
     * 线程模型：
     * - 此方法是同步的：调用后会阻塞等待单次执行完成
     * - 单次执行完成后 singleDone = true，调用方可轮询此标志确认完成
     */
    fun startSingle() {
        if (isRunning) {
            Log.w(TAG, "[HUD] already running, ignoring single.")
            return
        }

        // 引擎不可用时直接返回，避免创建无用线程
        if (!NativeLibraryLoader.isAvailable) {
            Log.w(TAG, "[HUD] native engine unavailable, single execution skipped.")
            return
        }

        isRunning = true
        frameCount = 0
        singleDone = false

        simulationThread = Thread(this::singleExecutionLoop, "hzzs-hud-single").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "[HUD] single execution started.")

        // 同步等待单次执行完成（最多 500ms）
        simulationThread?.join(500)

        // 标记完成（即使线程已 join，也确保标志被设置）
        singleDone = true
        isRunning = false
        simulationThread = null

        Log.d(TAG, "[HUD] single execution completed.")
        Logger.i("HUD", "单次执行完成，已推送 1 帧分析结果")
    }

    /**
     * 检查单次执行是否已完成。
     *
     * @return true 如果单次执行已完成（或尚未开始）
     */
    fun isSingleDone(): Boolean = singleDone

    // ==================== 模拟帧循环 ====================

    /**
     * 模拟帧生成主循环。
     *
     * 每帧执行：
     * 1. 更新玩家位置（正弦波左右移动）
     * 2. 决定是否生成危险物（shouldSpawnHazard）
     * 3. 构建玩家矩形和危险物矩形
     * 4. 调用 C++ 引擎分析（NativeEngineFacade.analyzeFrame）
     * 5. 更新危险物位置（向左移动，移出屏幕后销毁）
     * 6. 休眠 FRAME_INTERVAL_MS（100ms）
     *
     * 循环条件：isRunning == true 且（maxFrames <= 0 或 frameCount < maxFrames）
     * 默认无限运行，必须由用户手动点击停止按钮才会退出。
     */
    private fun simulationLoop() {
        while (isRunning && (maxFrames <= 0 || frameCount < maxFrames)) {
            val timestampMs = frameCount * FRAME_INTERVAL_MS

            // 更新玩家位置（正弦波左右移动）
            playerXOffset += 0.003f
            val playerCenterX = 0.14f + 0.06f * sin(playerXOffset)
            val playerCenterY = 0.75f

            // 决定当前帧是否有危险物
            val hasHazard = shouldSpawnHazard()

            // 构建玩家矩形（归一化坐标）
            val playerBounds = RectF(
                left = playerCenterX - 0.05f,
                top = 0.66f,
                right = playerCenterX + 0.05f,
                bottom = 0.84f,
            )

            // 构建危险物矩形（如果有）
            val hazardBounds: RectF? = if (hasHazard && currentHazardLeft != null) {
                val hLeft = currentHazardLeft!!
                RectF(
                    left = hLeft,
                    top = 0.78f,
                    right = hLeft + 0.14f,
                    bottom = 0.98f,
                )
            } else {
                null
            }

            // 调用 C++ 引擎分析，并将结果推送给 UI 绘制
            val frameResult = if (NativeLibraryLoader.isAvailable) {
                NativeEngineFacade.analyzeFrame(
                    timestampMs = timestampMs,
                    playerBounds = playerBounds,
                    playerConfidence = 0.96f,
                    hazardType = currentHazardType,
                    hazardBounds = hazardBounds,
                    hazardConfidence = 0.93f,
                    hazardVelocityX = WORLD_SCROLL_SPEED,
                    worldScrollSpeed = WORLD_SCROLL_SPEED,
                )
            } else {
                null
            }

            // 记录每帧分析结果摘要（每 10 帧记录一次，避免日志过多）
            if (frameCount % 10 == 0) {
                Logger.d("HUD-FPS", "帧 #$frameCount | 时间戳=${timestampMs}ms | 玩家X=${"%.3f".format(playerCenterX)} | 危险物=${currentHazardType.takeIf { hasHazard } ?: 0}")
            }

            // 将分析结果切换到主线程推送给 HUDCanvasView 绘制
            frameResult?.let { result ->
                onFrameResultListener?.let { listener ->
                    mainHandler.post { listener(result) }
                }

                // 视觉识别在后台线程执行（避免阻塞主线程和模拟帧循环）
                visualRecognitionExecutor.execute {
                    runVisualRecognition(result)
                }
            }

            // 更新危险物位置（向左移动）
            if (currentHazardLeft != null) {
                currentHazardLeft = currentHazardLeft!! + WORLD_SCROLL_SPEED * FRAME_INTERVAL_MS / 1000f
                // 危险物移动到玩家左侧 0.1 以外时销毁
                if (currentHazardLeft!! < playerCenterX - 0.1f) {
                    currentHazardLeft = null
                }
            }

            frameCount++
            Thread.sleep(FRAME_INTERVAL_MS)
        }

        val reason = if (maxFrames > 0 && frameCount >= maxFrames) "reached max frames" else "stopped by user"
        Log.d(TAG, "[HUD] simulation loop exited after $frameCount frames ($reason).")
    }

    /**
     * 单次执行循环：生成一帧模拟数据并送入引擎分析，然后退出。
     *
     * 流程：
     * 1. 重置模拟数据状态
     * 2. 生成一帧玩家 + 危险物数据
     * 3. 调用 C++ 引擎分析
     * 4. 线程自然退出（由 startSingle() join 等待）
     */
    private fun singleExecutionLoop() {
        // 重置模拟数据状态
        playerXOffset = 0f
        currentHazardLeft = null
        lastHazardFrame = -HAZARD_INTERVAL
        currentHazardType = 1

        val timestampMs = 0L

        // 生成一帧玩家位置
        playerXOffset += 0.003f
        val playerCenterX = 0.14f + 0.06f * sin(playerXOffset)
        val playerBounds = RectF(
            left = playerCenterX - 0.05f,
            top = 0.66f,
            right = playerCenterX + 0.05f,
            bottom = 0.84f,
        )

        // 生成一个危险物
        currentHazardLeft = 0.55f
        val hazardBounds = RectF(
            left = currentHazardLeft!!,
            top = 0.78f,
            right = currentHazardLeft!! + 0.14f,
            bottom = 0.98f,
        )

        // 调用 C++ 引擎分析（单次），并将结果推送给 UI 绘制
        if (NativeLibraryLoader.isAvailable) {
            val frameResult = NativeEngineFacade.analyzeFrame(
                timestampMs = timestampMs,
                playerBounds = playerBounds,
                playerConfidence = 0.96f,
                hazardType = currentHazardType,
                hazardBounds = hazardBounds,
                hazardConfidence = 0.93f,
                hazardVelocityX = WORLD_SCROLL_SPEED,
                worldScrollSpeed = WORLD_SCROLL_SPEED,
            )

            // 将分析结果切换到主线程推送给 HUDCanvasView 绘制
            frameResult?.let { result ->
                onFrameResultListener?.let { listener ->
                    mainHandler.post { listener(result) }
                }

                // 视觉识别在后台线程执行（避免阻塞主线程和模拟帧循环）
                visualRecognitionExecutor.execute {
                    runVisualRecognition(result)
                }
            }
        }

        Log.d(TAG, "[HUD] single execution frame completed.")
    }

    /**
     * 判断当前帧是否应该生成新危险物。
     *
     * 生成条件：
     * 1. 距上次生成 >= HAZARD_INTERVAL 帧
     * 2. 当前没有活跃危险物（currentHazardLeft == null）
     *
     * 生成时：
     * - 更新 lastHazardFrame 为当前帧计数
     * - 循环切换危险物类型（% 3 + 1 → 1/2/3）
     * - 设置 currentHazardLeft = 0.55f（屏幕右侧 55% 处）
     *
     * @return true 如果当前帧应该生成新危险物
     */
    private fun shouldSpawnHazard(): Boolean {
        val framesSinceLastHazard = frameCount - lastHazardFrame
        if (framesSinceLastHazard >= HAZARD_INTERVAL && currentHazardLeft == null) {
            lastHazardFrame = frameCount
            currentHazardType = (frameCount / HAZARD_INTERVAL % 3) + 1
            currentHazardLeft = 0.55f
            return true
        }
        return false
    }

    /**
     * 简易正弦函数别名。
     *
     * 为避免每次调用 kotlin.math.sin 产生 lambda 开销，
     * 在此处定义简短别名方便阅读。
     */
    private fun sin(x: Float): Float {
        return kotlin.math.sin(x)
    }

    /**
     * 运行视觉识别（绿瓶 + 坑位检测）。
     *
     * 流程：
     * 1. 尝试截取当前屏幕（ScreenshotCapture.takeScreenshot）
     * 2. 如果截图失败，直接跳过视觉识别（不调用 C++ 算法）
     * 3. 如果截图成功，调用 C++ 视觉算法（VisionBridge.detectGreenBottle / detectPit）
     * 4. 解析 C++ 返回的 byte[] 结果，通过 onVisualRecognitionListener 回调推送
     *
     * 线程模型：此方法在后台线程调用（由 visualRecognitionExecutor 调度），
     * 回调结果通过 onVisualRecognitionListener 推送回主线程消费。
     * 截图失败时静默跳过，不阻塞也不崩溃。
     */
    private fun runVisualRecognition(result: FrameAnalysisResult) {
        val pb = result.playerBounds ?: return
        val appContext = context.applicationContext
        val metrics = (context as? android.content.Context)?.let {
            android.content.ContextWrapper(it).resources?.displayMetrics
        } ?: appContext.resources.displayMetrics

        // 1. 尝试真实截图
        val capture = ScreenshotCapture.takeScreenshot(appContext)

        // 2. 截图失败时直接跳过视觉识别（不再传入空数组）
        if (capture == null) {
            Log.d(TAG, "[HUD] screenshot unavailable, skipping visual recognition.")
            if (frameCount % 50 == 0) {
                Logger.w("Screenshot", "截图不可用（API=${android.os.Build.VERSION.SDK_INT}），跳过视觉识别")
            }
            return
        }

        val pixelArray = capture.pixels
        val actualW = capture.width
        val actualH = capture.height

        // 3. 调用 C++ 视觉算法
        try {
            val bottleBytes = VisionBridge.detectGreenBottle(
                pixelArray, actualW, actualH,
                pb.left.toFloat(), pb.right.toFloat(), pb.width.toFloat(),
                pb.centerX.toFloat(), pb.centerY.toFloat()
            )

            val pitBytes = VisionBridge.detectPit(
                pixelArray, actualW, actualH,
                pb.left.toFloat(), pb.right.toFloat(), pb.width.toFloat(),
                pb.centerX.toFloat(), pb.centerY.toFloat()
            )

            // 4. 解析绿瓶结果
            var bottleFound = false
            var bottleLeft = 0
            var bottleRight = 0
            var bottleCenterX = 0
            var bottleScanY = 0
            var bottleWidth = 0
            var bottleConfidence = 0f
            var bottleCostMs = 0.0

            if (bottleBytes.size >= 48) {
                val buf = ByteBuffer.wrap(bottleBytes).order(ByteOrder.LITTLE_ENDIAN)
                bottleFound = buf.get(0) != 0.toByte()
                if (bottleFound) {
                    bottleScanY = buf.getInt(4)
                    bottleLeft = buf.getInt(8)
                    bottleRight = buf.getInt(12)
                    bottleCenterX = buf.getInt(16)
                    bottleWidth = buf.getInt(20)
                    val edgeGap = buf.getInt(24)
                    val centerDist = buf.getInt(28)
                    bottleConfidence = buf.getFloat(32)
                    bottleCostMs = buf.getDouble(36)
                }
            }

            // 5. 解析坑位结果
            var pitFound = false
            var pitLeft = 0
            var pitRight = 0
            var pitCenterX = 0
            var pitScanY = 0
            var pitWidth = 0
            var pitEdgeGap = 0
            var pitConfidence = 0f
            var pitCostMs = 0.0

            if (pitBytes.size >= 48) {
                val buf = ByteBuffer.wrap(pitBytes).order(ByteOrder.LITTLE_ENDIAN)
                pitFound = buf.get(0) != 0.toByte()
                if (pitFound) {
                    pitScanY = buf.getInt(4)
                    pitLeft = buf.getInt(8)
                    pitRight = buf.getInt(12)
                    pitCenterX = buf.getInt(16)
                    pitWidth = buf.getInt(20)
                    pitEdgeGap = buf.getInt(24)
                    val centerDist = buf.getInt(28)
                    pitConfidence = buf.getFloat(32)
                    pitCostMs = buf.getDouble(36)
                }
            }

            // 6. 回调推送给 UI 层
            onVisualRecognitionListener?.invoke(
                bottleFound, bottleLeft, bottleRight, bottleCenterX, bottleScanY, bottleWidth,
                bottleConfidence, bottleCostMs,
                pitFound, pitLeft, pitRight, pitCenterX, pitScanY, pitWidth, pitEdgeGap,
                pitConfidence
            )

            // 调试日志
            if (bottleFound) {
                Log.d(TAG, "[HUD] C++ bottle detected: L=$bottleLeft R=$bottleRight Cx=$bottleCenterX conf=$bottleConfidence cost=${bottleCostMs}ms")
                Logger.d("Vision-Bottle", "绿瓶检测: L=$bottleLeft R=$bottleRight Cx=$bottleCenterX W=$bottleWidth 置信度=${"%.2f".format(bottleConfidence)} 耗时=${"%.1f".format(bottleCostMs)}ms")
            }
            if (pitFound) {
                Log.d(TAG, "[HUD] C++ pit detected: L=$pitLeft R=$pitRight Cx=$pitCenterX W=$pitWidth conf=$pitConfidence cost=${pitCostMs}ms")
                Logger.d("Vision-Pit", "坑位检测: L=$pitLeft R=$pitRight Cx=$pitCenterX W=$pitWidth 置信度=${"%.2f".format(pitConfidence)} 耗时=${"%.1f".format(pitCostMs)}ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[HUD] visual recognition failed: ${e.message}")
        }
    }
}
