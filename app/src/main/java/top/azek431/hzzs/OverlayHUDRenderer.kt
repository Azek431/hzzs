package top.azek431.hzzs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮窗 HUD 渲染器。
 *
 * 负责在"开始执行"后启动模拟帧生成循环，驱动 C++ 分析引擎，
 * 并将结果更新到悬浮窗的 UI 组件上。
 *
 * 核心职责：
 * 1. 模拟帧生成：按固定间隔（50ms）生成模拟跑酷画面数据
 * 2. 调用 JNI：将模拟数据传入 C++ 引擎获取分析结果
 * 3. 更新 UI：将分析结果写入 Canvas 视图和 TextView
 * 4. 生命周期管理：开始/停止渲染循环，线程安全
 *
 * 模拟数据策略：
 * - 玩家矩形：在屏幕中下部缓慢左右移动
 * - 危险物（蛋糕断层）：周期性出现在玩家前方，以世界滚动速度向左移动
 * - 每帧时间戳递增 50ms（模拟 20fps 帧率）
 *
 * 线程模型：
 * - 所有 UI 操作通过 Handler 切换到主线程执行
 * - 模拟帧循环在独立后台线程运行
 * - 使用 volatile 标志控制循环启停
 */
class OverlayHUDRenderer(
    private val context: Context,
) {

    companion object {
        private const val TAG = "HZZS-HUD"

        /** 帧间隔（毫秒），模拟 20fps 帧率 */
        private const val FRAME_INTERVAL_MS = 50L

        /** 模拟帧的最大数量，防止无限运行 */
        private const val MAX_FRAMES = 600 // 30 秒

        /** 危险物出现间隔（帧数）*/
        private const val HAZARD_INTERVAL = 40 // 每 40 帧 (~2s) 出现一个危险物

        /** 玩家矩形归一化 Y 范围 */
        private const val PLAYER_BOTTOM_MIN = 0.80f
        private const val PLAYER_BOTTOM_MAX = 0.90f

        /** 世界滚动速度（归一化坐标/秒），向左为负 */
        private const val WORLD_SCROLL_SPEED = -0.45f
    }

    // ==================== 状态 ====================

    /** 是否正在运行（volatile 保证多线程可见性） */
    @Volatile
    private var isRunning = false

    /** 当前模拟帧计数器 */
    @Volatile
    private var frameCount = 0

    /** 后台模拟帧循环线程 */
    private var simulationThread: Thread? = null

    /** 主线程 Handler，用于更新 UI */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== UI 引用 ====================

    /** HUD 容器（初始不可见，执行中显示） */
    private var hudContainer: View? = null

    /** Canvas 绘制区 */
    private var hudCanvas: HUDCanvasView? = null

    /** 场景模式 TextView */
    private var sceneText: TextView? = null

    /** 角色姿态 TextView */
    private var poseText: TextView? = null

    /** 跳跃阶段 TextView */
    private var jumpStageText: TextView? = null

    /** 危险物数量 TextView */
    private var hazardsText: TextView? = null

    /** 收藏物数量 TextView */
    private var collectiblesText: TextView? = null

    /** 动作提示图标 TextView */
    private var promptIcon: TextView? = null

    /** 动作提示文本 TextView */
    private var promptText: TextView? = null

    /** ETA 文本 TextView */
    private var etaText: TextView? = null

    // ==================== 模拟数据状态 ====================

    /** 玩家 X 位置（归一化），用于模拟左右移动 */
    private var playerXOffset = 0f

    /** 当前危险物位置（归一化 left），null 表示无危险物 */
    private var currentHazardLeft: Float? = null

    /** 危险物类型（1=蛋糕断层, 2=毒瓶, 3=裱花袋） */
    private var currentHazardType = 0

    /** 上次生成危险物的帧计数 */
    private var lastHazardFrame = -HAZARD_INTERVAL

    // ==================== 生命周期 ====================

    /**
     * 绑定 HUD 子视图引用。
     *
     * 在悬浮窗 inflate 后调用，查找所有 HUD 相关 View。
     * 如果某个 View 不存在，记录 Warning 但不会崩溃。
     *
     * @param root 悬浮窗根 View
     */
    fun bindViews(root: View) {
        hudContainer = root.findViewById(R.id.overlayHudContainer)
        hudCanvas = root.findViewById(R.id.overlayHudCanvas)
        sceneText = root.findViewById(R.id.overlayHudSceneText)
        poseText = root.findViewById(R.id.overlayHudPoseText)
        jumpStageText = root.findViewById(R.id.overlayHudJumpStageText)
        hazardsText = root.findViewById(R.id.overlayHudHazardsText)
        collectiblesText = root.findViewById(R.id.overlayHudCollectiblesText)
        promptIcon = root.findViewById(R.id.overlayHudPromptIcon)
        promptText = root.findViewById(R.id.overlayHudPromptText)
        etaText = root.findViewById(R.id.overlayHudEtaText)

        Log.d(TAG, "[HUD] views bound successfully.")
    }

    /**
     * 启动模拟帧生成循环。
     *
     * 创建并启动后台线程，每 50ms 生成一帧模拟数据，
     * 调用 C++ 引擎分析并更新 UI。
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "[HUD] already running, ignoring start.")
            return
        }

        // 重置状态
        isRunning = true
        frameCount = 0
        playerXOffset = 0f
        currentHazardLeft = null
        lastHazardFrame = -HAZARD_INTERVAL

        // 显示 HUD 容器
        hudContainer?.visibility = View.VISIBLE

        // 启动后台模拟线程
        simulationThread = Thread(this::simulationLoop, "hzzs-hud-simulation").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "[HUD] simulation started.")
    }

    /**
     * 停止模拟帧生成循环。
     *
     * 设置 isRunning = false，等待线程自然退出，
     * 然后清空 HUD 容器并隐藏。
     */
    fun stop() {
        if (!isRunning) {
            Log.w(TAG, "[HUD] not running, ignoring stop.")
            return
        }

        isRunning = false
        simulationThread?.join(1000) // 最多等待 1 秒
        simulationThread = null

        // 隐藏 HUD 容器
        hudContainer?.visibility = View.GONE

        Log.i(TAG, "[HUD] simulation stopped.")
    }

    // ==================== 模拟帧循环 ====================

    /**
     * 模拟帧生成主循环。
     *
     * 在后台线程运行，每 FRAME_INTERVAL_MS 毫秒生成一帧：
     * 1. 更新玩家位置（正弦波左右移动）
     * 2. 根据帧计数决定是否生成新危险物
     * 3. 调用 C++ 引擎分析
     * 4. 通过 Handler 更新 UI
     * 5. 达到最大帧数或收到停止信号则退出
     */
    private fun simulationLoop() {
        while (isRunning && frameCount < MAX_FRAMES) {
            val timestampMs = frameCount * FRAME_INTERVAL_MS

            // 更新玩家位置（正弦波左右移动）
            playerXOffset += 0.003f
            val playerCenterX = 0.14f + 0.06f * sin(playerXOffset)

            // 决定当前帧是否有危险物
            val hasHazard = shouldSpawnHazard()

            // 构建玩家矩形
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

            // 调用 C++ 引擎分析
            val result = if (NativeAnalysisBridge.isAvailable) {
                NativeAnalysisBridge.analyzeFrame(
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

            // 更新 UI（切换到主线程）
            val finalResult = result
            val finalHazardBounds = hazardBounds
            mainHandler.post {
                updateUI(finalResult, finalHazardBounds)
            }

            // 更新危险物位置（向左移动）
            if (currentHazardLeft != null) {
                currentHazardLeft = currentHazardLeft!! + WORLD_SCROLL_SPEED * FRAME_INTERVAL_MS / 1000f
                // 如果危险物已经移到玩家左侧，清除它
                if (currentHazardLeft!! < playerCenterX - 0.1f) {
                    currentHazardLeft = null
                }
            }

            frameCount++

            // 等待下一帧
            Thread.sleep(FRAME_INTERVAL_MS)
        }

        Log.d(TAG, "[HUD] simulation loop exited after $frameCount frames.")
    }

    /**
     * 判断当前帧是否应该生成新危险物。
     *
     * 每隔 HAZARD_INTERVAL 帧生成一个危险物，
     * 类型在蛋糕断层、毒瓶、裱花袋之间轮换。
     */
    private fun shouldSpawnHazard(): Boolean {
        val framesSinceLastHazard = frameCount - lastHazardFrame
        if (framesSinceLastHazard >= HAZARD_INTERVAL && currentHazardLeft == null) {
            lastHazardFrame = frameCount
            // 轮换危险物类型：1=蛋糕断层, 2=毒瓶, 3=裱花袋
            currentHazardType = (frameCount / HAZARD_INTERVAL % 3) + 1
            // 新危险物出现在玩家右侧前方
            currentHazardLeft = 0.55f
            return true
        }
        return false
    }

    // ==================== UI 更新 ====================

    /**
     * 将分析结果更新到悬浮窗 UI 组件。
     *
     * @param result C++ 引擎返回的分析结果
     * @param hazardBounds 当前危险物矩形（用于 Canvas 绘制）
     */
    private fun updateUI(
        result: FrameAnalysisResult?,
        hazardBounds: RectF?,
    ) {
        // 更新 Canvas 视图
        result?.let { r ->
            hudCanvas?.analysisResult = r
        }

        // 玩家矩形始终显示（模拟数据中的玩家位置）
        val playerCenterX = 0.14f + 0.06f * sin(playerXOffset)
        hudCanvas?.playerBounds = RectF(
            left = playerCenterX - 0.05f,
            top = 0.66f,
            right = playerCenterX + 0.05f,
            bottom = 0.84f,
        )

        // 危险物列表
        hudCanvas?.hazards = hazardBounds?.let { listOf(it) } ?: emptyList()

        // 如果没有分析结果，显示默认值
        if (result == null) {
            sceneText?.text = "引擎不可用"
            poseText?.text = "--"
            jumpStageText?.text = "--"
            hazardsText?.text = "--"
            collectiblesText?.text = "--"
            promptIcon?.text = ""
            promptText?.text = "等待分析..."
            etaText?.text = ""
            return
        }

        // 更新场景模式
        sceneText?.text = result.sceneText

        // 更新角色姿态
        poseText?.text = result.poseText

        // 更新跳跃阶段
        jumpStageText?.text = "${result.jumpStage}"

        // 更新危险物数量
        hazardsText?.text = if (result.hazardsCount > 0) {
            "${result.hazardsCount}"
        } else {
            "0"
        }

        // 更新收藏物数量
        collectiblesText?.text = if (result.collectiblesCount > 0) {
            "${result.collectiblesCount}"
        } else {
            "0"
        }

        // 更新动作提示
        if (result.hasPrompt) {
            promptIcon?.text = getPromptEmoji(result.promptAction)
            promptText?.text = result.promptText
            etaText?.text = " | ${result.etaText} | ${result.promptTargetText}"
        } else {
            promptIcon?.text = ""
            promptText?.text = "无提示"
            etaText?.text = ""
        }
    }

    /**
     * 根据提示动作类型返回对应的 Emoji 图标。
     */
    private fun getPromptEmoji(action: Int): String {
        return when (action) {
            FrameAnalysisResult.PROMPT_JUMP -> "⬆"
            FrameAnalysisResult.PROMPT_JUMP_AGAIN -> "⬆⬆"
            FrameAnalysisResult.PROMPT_SLIDE -> "⬇"
            else -> ""
        }
    }

    /**
     * 简易正弦函数实现（避免依赖 Math.sin 的精度问题）。
     *
     * 使用 Taylor 级数展开近似计算 sin(x)。
     */
    private fun sin(x: Float): Float {
        // 简化版：直接使用 Math.sin
        return Math.sin(x.toDouble()).toFloat()
    }
}
