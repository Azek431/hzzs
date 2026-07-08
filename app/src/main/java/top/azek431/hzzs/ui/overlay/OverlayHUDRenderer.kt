// 火崽崽助手（HZZS）悬浮窗 HUD 渲染器。
//
// 负责在"开始执行"后启动模拟帧生成循环，驱动 C++ 分析引擎，
// 并将结果更新到悬浮窗的 UI 组件上。
//
// 核心职责：
// 1. 模拟帧生成：按固定间隔（50ms）生成模拟跑酷画面数据
// 2. 调用 JNI：将模拟数据传入 C++ 引擎获取分析结果
// 3. 更新 UI：将分析结果写入 Canvas 视图和 TextView
// 4. 生命周期管理：开始/停止渲染循环，线程安全
// 5. 自动操作：当检测到动作提示时，将操作加入队列
//
// 模拟数据策略：
// - 玩家矩形：在屏幕中下部缓慢左右移动
// - 危险物（蛋糕断层）：周期性出现在玩家前方，以世界滚动速度向左移动
// - 每帧时间戳递增 50ms（模拟 20fps 帧率）
// - 轨迹点、预测路径、热力图数据同步生成
//
// 线程模型：
// - 所有 UI 操作通过 Handler 切换到主线程执行
// - 模拟帧循环在独立后台线程运行
// - 使用 volatile 标志控制循环启停

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.graphics.PointF
import android.view.View
import android.widget.TextView
import top.azek431.hzzs.NativeAnalysisBridge
import top.azek431.hzzs.model.FrameAnalysisResult
import top.azek431.hzzs.model.RectF
import top.azek431.hzzs.service.AutoOperationService
import top.azek431.hzzs.R

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

    /**
     * 收藏物数量 TextView — 显示检测到的可收集物品数量
     * 对应布局文件中的 overlayHudCollectiblesText
     */
    private var collectiblesText: TextView? = null

    /**
     * 动作提示图标 TextView — 显示 Emoji 箭头（⬆ / ⬆⬆ / ⬇）
     * 对应布局文件中的 overlayHudPromptIcon
     */
    private var promptIcon: TextView? = null

    /**
     * 动作提示文本 TextView — 显示中文提示（"⬆ 跳跃"等）
     * 对应布局文件中的 overlayHudPromptText
     */
    private var promptText: TextView? = null

    /**
     * ETA 信息 TextView — 显示时间信息和目标类型
     * 格式如："500ms | 蛋糕断层"
     * 对应布局文件中的 overlayHudEtaText
     */
    private var etaText: TextView? = null

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

    // === 新增可视化数据状态 ===

    /** 玩家轨迹点列表（最多保留 20 个点） */
    private val playerTrajectory = mutableListOf<PointF>()
    private val MAX_TRAJECTORY_POINTS = 20

    /** 预测路径点列表 */
    private val predictedPathPoints = mutableListOf<PointF>()

    /** 热力图数据点列表 */
    private val heatmapPointList = mutableListOf<HUDCanvasView.HeatmapPoint>()
    private val MAX_HEATMAP_POINTS = 15
    private var confidenceOffset = 0f

    /** 上次生成热力点的帧计数 */
    private var lastHeatmapFrame = -10

    // ==================== 自动操作相关属性 ====================

    /** 自动操作是否启用 */
    var autoOperationEnabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                AutoOperationService.setEnabled(true)
            } else {
                AutoOperationService.clearQueue()
                AutoOperationService.setEnabled(false)
            }
        }

    /** 自动操作延迟（毫秒） */
    var autoOperationDelayMs: Int = 100
        set(value) {
            field = value
            AutoOperationService.setDelay(value)
        }

    /** 自动操作暂停状态 */
    var autoOperationPaused: Boolean = false
        set(value) {
            field = value
            AutoOperationService.setPaused(value)
        }

    // ==================== 生命周期 ====================

    /**
     * 绑定 HUD 子视图引用。
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

    /** 启动模拟帧生成循环 */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "[HUD] already running, ignoring start.")
            return
        }

        isRunning = true
        frameCount = 0
        playerXOffset = 0f
        currentHazardLeft = null
        lastHazardFrame = -HAZARD_INTERVAL
        playerTrajectory.clear()
        predictedPathPoints.clear()
        heatmapPointList.clear()
        confidenceOffset = 0f

        hudContainer?.visibility = View.VISIBLE

        simulationThread = Thread(this::simulationLoop, "hzzs-hud-simulation").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "[HUD] simulation started.")
    }

    /** 停止模拟帧生成循环 */
    fun stop() {
        if (!isRunning) {
            Log.w(TAG, "[HUD] not running, ignoring stop.")
            return
        }

        isRunning = false
        simulationThread?.join(1000)
        simulationThread = null

        hudContainer?.visibility = View.GONE
        AutoOperationService.clearQueue()

        Log.i(TAG, "[HUD] simulation stopped.")
    }

    // ==================== 模拟帧循环 ====================

    /** 模拟帧生成主循环 */
    private fun simulationLoop() {
        while (isRunning && frameCount < MAX_FRAMES) {
            val timestampMs = frameCount * FRAME_INTERVAL_MS

            // 更新玩家位置（正弦波左右移动）
            playerXOffset += 0.003f
            val playerCenterX = 0.14f + 0.06f * sin(playerXOffset)
            val playerCenterY = 0.75f

            // === 新增：更新轨迹点 ===
            playerTrajectory.add(PointF(playerCenterX, playerCenterY))
            if (playerTrajectory.size > MAX_TRAJECTORY_POINTS) {
                playerTrajectory.removeAt(0)
            }

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

            // === 新增：计算预测路径 ===
            updatePredictedPath(playerCenterX, playerCenterY, result)

            // === 新增：生成热力图点 ===
            if (frameCount - lastHeatmapFrame >= 10) {
                generateHeatmapPoint()
                lastHeatmapFrame = frameCount
            }

            // === 新增：置信度波动 ===
            confidenceOffset += 0.02f
            val dynamicConfidence = 0.85f + 0.14f * (0.5f + 0.5f * sin(confidenceOffset))

            // 更新 UI（切换到主线程）
            val finalResult = result
            val finalHazardBounds = hazardBounds
            mainHandler.post {
                updateUI(finalResult, finalHazardBounds, dynamicConfidence)
            }

            // 更新危险物位置（向左移动）
            if (currentHazardLeft != null) {
                currentHazardLeft = currentHazardLeft!! + WORLD_SCROLL_SPEED * FRAME_INTERVAL_MS / 1000f
                if (currentHazardLeft!! < playerCenterX - 0.1f) {
                    currentHazardLeft = null
                }
            }

            frameCount++
            Thread.sleep(FRAME_INTERVAL_MS)
        }

        Log.d(TAG, "[HUD] simulation loop exited after $frameCount frames.")
    }

    /** 判断当前帧是否应该生成新危险物 */
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

    // ==================== 可视化数据计算 ====================

    /**
     * 根据当前分析结果更新预测路径。
     *
     * 预测路径基于跳跃阶段和 ETA 计算：
     * - 跳跃：抛物线路径 y = playerY - 0.15 * frac * (1 - frac)，最高点在中间
     * - 滑铲：直线路径，终点为玩家前方 0.2 + 下方 0.05
     *
     * @param playerX 玩家当前 X 坐标（归一化）
     * @param playerY 玩家当前 Y 坐标（归一化）
     * @param result 当前帧分析结果（包含 ETA 信息）
     */
    private fun updatePredictedPath(playerX: Float, playerY: Float, result: FrameAnalysisResult?) {
        predictedPathPoints.clear()
        if (result == null) return

        predictedPathPoints.add(PointF(playerX, playerY))

        when (result.promptAction) {
            FrameAnalysisResult.PROMPT_JUMP -> {
                // 跳跃：抛物线路径
                val etaSeconds = result.promptEtaMs / 1000f
                if (etaSeconds > 0f) {
                    for (t in 0..10) {
                        val frac = t / 10f
                        val px = playerX + 0.1f * frac
                        val py = playerY - 0.15f * frac * (1 - frac)
                        predictedPathPoints.add(PointF(px, py))
                    }
                }
            }
            FrameAnalysisResult.PROMPT_SLIDE -> {
                // 滑铲：直线路径
                val etaSeconds = result.promptEtaMs / 1000f
                if (etaSeconds > 0f) {
                    predictedPathPoints.add(PointF(playerX + 0.2f, playerY + 0.05f))
                }
            }
            else -> {}
        }
    }

    /**
     * 随机生成一个热力图点。
     *
     * 热力图点分布在玩家矩形周围 ±0.1 范围内，强度 0.3~1.0 随机。
     * 每 10 帧生成一个点，最多保留 MAX_HEATMAP_POINTS 个，超出时移除最旧的。
     * 用于可视化"检测置信度分布"概念。
     */
    private fun generateHeatmapPoint() {
        val offsetX = (Math.random() - 0.5) * 0.2
        val offsetY = (Math.random() - 0.5) * 0.2
        val intensity = 0.3f + Math.random().toFloat() * 0.7f

        heatmapPointList.add(HUDCanvasView.HeatmapPoint(
            x = 0.14f + offsetX.toFloat(),
            y = 0.75f + offsetY.toFloat(),
            intensity = intensity
        ))
        if (heatmapPointList.size > MAX_HEATMAP_POINTS) {
            heatmapPointList.removeAt(0)
        }
    }

    // ==================== UI 更新 ====================

    /**
     * 将分析结果更新到悬浮窗 UI 组件。
     *
     * 此方法在主线程执行（通过 mainHandler.post 切换），负责：
     * 1. 更新 Canvas 视图的玩家/危险物矩形和可视化数据
     * 2. 更新 TextView 的场景模式、姿态、跳跃阶段等信息
     * 3. 如果有动作提示，更新提示图标/文本/ETA
     * 4. 如果自动操作已启用且未暂停，将动作加入队列
     *
     * @param result C++ 引擎返回的分析结果（null 表示引擎不可用）
     * @param hazardBounds 当前危险物边界（用于自动操作目标定位）
     * @param dynamicConfidence 动态置信度（0.85~0.99 波动，用于可视化指示器）
     */
    private fun updateUI(
        result: FrameAnalysisResult?,
        hazardBounds: RectF?,
        dynamicConfidence: Float = 0.95f,
    ) {
        // 更新 Canvas 视图
        result?.let { r ->
            hudCanvas?.analysisResult = r
        }

        // 玩家矩形始终显示
        val playerCenterX = 0.14f + 0.06f * sin(playerXOffset)
        hudCanvas?.playerBounds = RectF(
            left = playerCenterX - 0.05f,
            top = 0.66f,
            right = playerCenterX + 0.05f,
            bottom = 0.84f,
        )

        // 危险物列表
        hudCanvas?.hazards = hazardBounds?.let { listOf(it) } ?: emptyList()

        // === 新增：传递可视化数据 ===
        hudCanvas?.apply {
            trajectoryPoints = playerTrajectory.toList()
            predictedPath = predictedPathPoints.toList()
            dangerZone = hazardBounds
            confidenceLevel = dynamicConfidence
            actionTimerMs = result?.promptEtaMs ?: 0f
            heatmapPoints = heatmapPointList.map { it }.toList()
        }

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

            // === 新增：自动操作入队 ===
            if (autoOperationEnabled && !autoOperationPaused) {
                val actionType = when (result.promptAction) {
                    FrameAnalysisResult.PROMPT_JUMP -> AutoOperationService.QueuedAction.ActionType.JUMP
                    FrameAnalysisResult.PROMPT_SLIDE -> AutoOperationService.QueuedAction.ActionType.SLIDE
                    FrameAnalysisResult.PROMPT_JUMP_AGAIN -> AutoOperationService.QueuedAction.ActionType.DOUBLE_JUMP
                    else -> null
                }

                actionType?.let { type ->
                    val targetX = hazardBounds?.centerX ?: 0.5f
                    val targetY = hazardBounds?.centerY ?: 0.75f

                    AutoOperationService.enqueueAction(
                        AutoOperationService.QueuedAction(
                            type = type,
                            targetX = targetX,
                            targetY = targetY,
                            timestamp = android.os.SystemClock.uptimeMillis()
                        )
                    )
                }
            }
        } else {
            promptIcon?.text = ""
            promptText?.text = "无提示"
            etaText?.text = ""
        }
    }

    /**
     * 根据提示动作类型返回对应的 Emoji 图标。
     *
     * @param action FrameAnalysisResult.PROMPT_* 常量
     * @return Emoji 字符串（⬆ / ⬆⬆ / ⬇ / ""）
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
     * 简易正弦函数别名。
     *
     * 为避免每次调用 kotlin.math.sin 产生 lambda 开销，
     * 在此处定义简短别名方便阅读。
     */
    private fun sin(x: Float): Float {
        return kotlin.math.sin(x)
    }
}
