// 火崽崽助手（HZZS）悬浮窗 HUD 渲染器。
//
// 负责在"开始执行"后启动模拟帧生成循环，驱动 C++ 分析引擎。
// 不再绑定 UI 视图（HUD 可视化区和自动操作控件已从布局中移除）。
//
// 核心职责：
// 1. 模拟帧生成：按固定间隔（50ms）生成模拟跑酷画面数据
// 2. 调用 JNI：将模拟数据传入 C++ 引擎获取分析结果
// 3. 生命周期管理：开始/停止渲染循环，线程安全
//
// 模拟数据策略：
// - 玩家矩形：在屏幕中下部缓慢左右移动
// - 危险物（蛋糕断层）：周期性出现在玩家前方，以世界滚动速度向左移动
// - 每帧时间戳递增 50ms（模拟 20fps 帧率）
//
// 线程模型：
// - 模拟帧循环在独立后台线程运行
// - 使用 volatile 标志控制循环启停

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.util.Log
import top.azek431.hzzs.NativeAnalysisBridge
import top.azek431.hzzs.model.RectF

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

            // 调用 C++ 引擎分析（结果不再更新 UI，仅驱动引擎运行）
            if (NativeAnalysisBridge.isAvailable) {
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
