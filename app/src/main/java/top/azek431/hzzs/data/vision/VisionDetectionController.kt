// 火崽崽助手（HZZS）视觉识别 — 检测调度控制器。
//
// 职责：
// - 管理视觉识别的完整生命周期（启动循环 / 单次执行 / 停止）
// - 协调截图层 → 算法层 → 自动操作层 → 绘制层
// - 维护检测器实例池，根据配置启用/禁用不同检测器
// - 提供循环执行与单次执行的统一入口
//
// 架构原则：
// - 截图层只负责截图
// - 算法层只负责识别（各 Detector 独立）
// - 本类只负责调度、循环控制和生命周期
// - 绘制层由外部注入回调
//
// 线程模型：
// - 循环执行在独立后台线程运行
// - 单次执行同步执行
// - 截图、分析、操作触发均在后台线程
// - 绘制回调和自动操作触发在主线程

package top.azek431.hzzs.data.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import top.azek431.hzzs.features.service.AutoActionQueue
import top.azek431.hzzs.service.AutoOperationService
import top.azek431.hzzs.ui.overlay.ScreenshotCapture

/**
 * 视觉识别检测调度控制器。
 *
 * 这是视觉识别模块的入口点，负责：
 * 1. 管理截图采集（ScreenshotCapture）
 * 2. 管理检测器实例（GreenBottleLineDetector / PitLineDetector）
 * 3. 驱动循环执行 / 单次执行
 * 4. 将检测结果转换为自动操作指令
 * 5. 提供绘制回调接口供外部实现
 *
 * @param context 上下文
 * @param playerReference 玩家参考坐标（用于检测器定位）
 * @param drawCallback 绘制回调（由 VisionDebugOverlayView 等实现）
 */
class VisionDetectionController(
    private val context: Context,
    private val playerReference: PlayerReference = PlayerReference(),
) : AutoCloseable {

    companion object {
        private const val TAG = "HZZS-VisionCtrl"

        /** 循环执行间隔（毫秒） */
        private const val LOOP_INTERVAL_MS = 300L

        /** 最大连续截图失败次数，超过则自动停止 */
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }

    // ==================== 检测器实例 ====================

    /** 绿瓶检测器 */
    private val greenBottleDetector = GreenBottleLineDetector()

    /** 坑位检测器 */
    private val pitDetector = PitLineDetector()

    // ==================== 绘制回调 ====================

    /**
     * 绘制回调接口。
     *
     * 由外部（如 VisionDebugOverlayView）实现，
     * 在每次分析完成后回调，传入检测结果用于绘制。
     */
    var drawCallback: ((VisionFrameResult) -> Unit)? = null

    // ==================== 执行状态 ====================

    /** 当前执行状态 */
    private enum class ExecutionState {
        IDLE,
        CYCLE_RUNNING,
        SINGLE_PENDING,
    }

    @Volatile
    private var currentState = ExecutionState.IDLE

    /** 后台循环线程 */
    private var loopThread: Thread? = null

    /** Handler 用于主线程回调 */
    @Suppress("DEPRECATION")
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== 配置开关 ====================

    /** 是否启用自动绘制 */
    var autoDrawEnabled: Boolean = true

    /** 是否启用自动跳跃 */
    var autoJumpEnabled: Boolean = false

    /** 是否启用自动点击 */
    var autoTapEnabled: Boolean = false

    /** 是否启用自动滑动 */
    var autoSlideEnabled: Boolean = false

    /** 自动操作总开关 */
    var autoOpEnabled: Boolean = false

    /** 防误触灵敏度（0.0 ~ 1.0，越低越灵敏） */
    var antiMissSensitivity: Float = 0.5f

    // ==================== 循环执行 ====================

    /**
     * 启动循环执行。
     *
     * 在后台线程持续截图 → 分析 → 触发操作 → 回调绘制。
     * 每 LOOP_INTERVAL_MS 毫秒执行一次。
     *
     * @return true 如果成功启动
     */
    fun startLoop(): Boolean {
        if (currentState == ExecutionState.CYCLE_RUNNING) {
            Log.w(TAG, "[Loop] already running, ignoring.")
            return false
        }

        currentState = ExecutionState.CYCLE_RUNNING
        loopThread = Thread(this::loopExecution, "hzzs-vision-loop").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "[Loop] started.")
        return true
    }

    /**
     * 停止循环执行。
     *
     * 设置状态为 IDLE，等待线程自然退出（最多 2 秒）。
     */
    fun stopLoop() {
        if (currentState != ExecutionState.CYCLE_RUNNING) return

        currentState = ExecutionState.IDLE
        loopThread?.join(2000)
        loopThread = null

        Log.i(TAG, "[Loop] stopped.")
    }

    /**
     * 单次执行。
     *
     * 截图 → 分析 → 触发操作 → 回调绘制，完成后自动清空结果。
     */
    fun runOnce() {
        if (currentState == ExecutionState.CYCLE_RUNNING) {
            Log.w(TAG, "[Single] cycle running, skipping single.")
            return
        }

        currentState = ExecutionState.SINGLE_PENDING
        Log.i(TAG, "[Single] executing once.")

        try {
            val result = analyzeOneFrame()
            if (result != null) {
                onAnalysisComplete(result)
                // 延迟清空绘制结果
                mainHandler.postDelayed({
                    drawCallback?.let { cb ->
                        cb(createEmptyResult(result.frame))
                    }
                }, LOOP_INTERVAL_MS + 100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Single] execution failed.", e)
        } finally {
            currentState = ExecutionState.IDLE
        }
    }

    /**
     * 循环执行主循环。
     *
     * 每 LOOP_INTERVAL_MS 毫秒执行一次截图 → 分析 → 触发操作 → 回调绘制。
     * 连续失败 MAX_CONSECUTIVE_FAILURES 次后自动停止。
     */
    private fun loopExecution() {
        var consecutiveFailures = 0

        while (currentState == ExecutionState.CYCLE_RUNNING) {
            try {
                val result = analyzeOneFrame()
                if (result != null) {
                    consecutiveFailures = 0
                    onAnalysisComplete(result)
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "[Loop] max consecutive failures reached ($consecutiveFailures), stopping.")
                        currentState = ExecutionState.IDLE
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Loop] iteration failed.", e)
                consecutiveFailures++
            }

            // 等待下一个循环间隔
            Thread.sleep(LOOP_INTERVAL_MS)
        }
    }

    /**
     * 执行单帧分析。
     *
     * 流程：截图 → 创建 VisionFrame → 运行所有检测器 → 生成自动操作指令
     *
     * @return 分析结果，截图失败时返回 null
     */
    private fun analyzeOneFrame(): VisionFrameResult? {
        // 1. 截图
        val capture = ScreenshotCapture.takeScreenshot(context)
        if (capture == null) return null

        val frame = VisionFrame(
            pixels = capture.pixels,
            width = capture.width,
            height = capture.height,
            density = capture.density,
        )

        // 2. 运行检测器
        val startTime = System.nanoTime()

        val greenDetResult: GreenBottleDetection = greenBottleDetector.detectGreenBottle(frame, playerReference)
        val pitDetResult: PitDetection = pitDetector.detectPit(frame, playerReference)

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000f

        // 3. 生成自动操作指令
        val actions = generateActions(greenDetResult, pitDetResult)

        // 4. 构建结果
        return VisionFrameResult(
            frame = frame,
            playerReference = playerReference,
            greenBottle = GreenBottleResult(
                found = greenDetResult.detected,
                leftX = greenDetResult.leftX,
                rightX = greenDetResult.rightX,
                centerX = greenDetResult.centerX,
                scanY = greenDetResult.scanY,
                confidence = greenDetResult.confidence,
                edgeGapPx = greenDetResult.edgeGapPx,
                costMs = greenDetResult.costMs,
            ),
            pit = PitResult(
                found = pitDetResult.detected,
                left = pitDetResult.left,
                right = pitDetResult.right,
                center = pitDetResult.center,
                width = pitDetResult.width,
                scanY = pitDetResult.scanY,
                confidence = pitDetResult.confidence,
                costMs = pitDetResult.costMs,
            ),
            actions = actions,
            costMs = elapsedMs,
        )
    }

    /**
     * 分析完成后回调。
     *
     * 1. 触发自动操作（入队）
     * 2. 调用绘制回调
     */
    private fun onAnalysisComplete(result: VisionFrameResult) {
        // 触发自动操作
        if (autoOpEnabled) {
            for (action in result.actions) {
                val targetType = when (action.type) {
                    ActionTrigger.ActionType.JUMP -> top.azek431.hzzs.features.service.QueuedAction.ActionType.JUMP
                    ActionTrigger.ActionType.TAP -> top.azek431.hzzs.features.service.QueuedAction.ActionType.TAP
                    ActionTrigger.ActionType.SLIDE -> top.azek431.hzzs.features.service.QueuedAction.ActionType.SLIDE
                    ActionTrigger.ActionType.DOUBLE_JUMP -> top.azek431.hzzs.features.service.QueuedAction.ActionType.DOUBLE_JUMP
                }
                AutoActionQueue.enqueue(
                    top.azek431.hzzs.features.service.QueuedAction(
                        type = targetType,
                        targetX = action.targetX,
                        targetY = action.targetY,
                    )
                )
                Log.d(TAG, "[Action] enqueued ${action.type}: ${action.reason} (conf=${action.confidence})")
            }
        }

        // 调用绘制回调
        if (autoDrawEnabled) {
            mainHandler.post {
                drawCallback?.invoke(result)
            }
        }
    }

    /**
     * 根据检测结果生成自动操作指令。
     */
    private fun generateActions(
        greenResult: GreenBottleDetection,
        pitResult: PitDetection,
    ): List<ActionTrigger> {
        val actions = mutableListOf<ActionTrigger>()

        // 绿瓶 → 自动点击
        if (greenResult.detected && autoTapEnabled) {
            val tapX = greenResult.centerX
            val tapY = greenResult.scanY.toFloat() / context.resources.displayMetrics.heightPixels - 0.1f
            actions.add(ActionTrigger(
                type = ActionTrigger.ActionType.TAP,
                targetX = tapX,
                targetY = tapY.coerceIn(0f, 1f),
                reason = "green_bottle_detected",
                confidence = greenResult.confidence,
            ))
        }

        // 坑位 → 自动跳跃
        if (pitResult.detected && autoJumpEnabled) {
            // 在坑位左侧边缘前跳跃
            val jumpX = (pitResult.left - 0.02f).coerceIn(0f, 1f)
            val jumpY = playerReference.centerY
            actions.add(ActionTrigger(
                type = ActionTrigger.ActionType.JUMP,
                targetX = jumpX,
                targetY = jumpY,
                reason = "pit_detected",
                confidence = pitResult.confidence,
            ))
        }

        return actions
    }

    /**
     * 创建空的检测结果（用于清空绘制）。
     */
    private fun createEmptyResult(frame: VisionFrame): VisionFrameResult {
        return VisionFrameResult(
            frame = frame,
            playerReference = playerReference,
            costMs = 0f,
        )
    }

    // ==================== 资源清理 ====================

    /**
     * 释放所有资源。
     *
     * 停止循环 → 清空队列 → 清除绘制 → 断开回调。
     */
    override fun close() {
        stopLoop()
        AutoActionQueue.clear()
        drawCallback = null
        Log.i(TAG, "[Controller] released.")
    }
}
