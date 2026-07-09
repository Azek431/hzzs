// 火崽崽助手（HZZS）悬浮窗 HUD 渲染器。
//
// 负责驱动 C++ 分析引擎运行，提供两种执行模式：
// 1. 循环执行（start）：按固定间隔（50ms）持续生成模拟跑酷帧
// 2. 单次执行（startSingle）：生成一帧后立即退出
//
// 核心职责：
// 1. 模拟帧生成：按固定间隔生成模拟跑酷画面数据
// 2. 调用 JNI：将模拟数据传入 C++ 引擎获取分析结果
// 3. 生命周期管理：开始/停止渲染循环，线程安全
//
// 模拟数据策略：
// - 玩家矩形：在屏幕中下部缓慢左右移动（正弦波驱动）
// - 危险物（蛋糕断层）：周期性出现在玩家前方，以世界滚动速度向左移动
// - 每帧时间戳递增 50ms（模拟 20fps 帧率）
// - 危险物类型循环切换：1=蛋糕断层, 2=毒瓶, 3=裱花袋
//
// 线程模型：
// - 模拟帧循环在独立后台线程运行
// - 使用 volatile 标志（isRunning）控制循环启停
// - stop() 中调用 thread.join(1000) 等待线程退出，超时强制放弃

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.util.Log
import top.azek431.hzzs.data.native.NativeEngineFacade
import top.azek431.hzzs.data.native.NativeLibraryLoader
import top.azek431.hzzs.model.RectF

/**
 * HUD 渲染器。
 *
 * 负责生成模拟跑酷帧数据并通过 JNI 驱动 C++ 分析引擎运行。
 * 重构后不再绑定 UI 视图，仅负责"模拟帧生成 + 引擎驱动"。
 *
 * @param context 上下文（用于获取 displayMetrics 等系统信息）
 */
class OverlayHUDRenderer(
    private val context: Context,
) {

    companion object {
        private const val TAG = "HZZS-HUD"

        /** 帧间隔（毫秒），模拟 20fps 帧率 */
        private const val FRAME_INTERVAL_MS = 50L

        /** 模拟帧的最大数量，防止无限运行（600 帧 = 30 秒） */
        private const val MAX_FRAMES = 600

        /** 危险物出现间隔（帧数），每 40 帧（~2s）出现一个危险物 */
        private const val HAZARD_INTERVAL = 40

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
     *
     * 幂等性：如果已经在运行，调用此方法会被忽略。
     */
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
            isDaemon = true  // 守护线程，进程退出时自动终止
            start()
        }

        Log.i(TAG, "[HUD] simulation started.")
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
        if (!isRunning) {
            Log.w(TAG, "[HUD] not running, ignoring stop.")
            return
        }

        isRunning = false
        simulationThread?.join(1000)
        simulationThread = null

        Log.i(TAG, "[HUD] simulation stopped.")
    }

    /**
     * 单次执行：运行一帧分析后立即停止。
     *
     * 用途：
     * - 点击"单次执行"按钮后触发
     * - 生成一帧模拟数据，送入 C++ 引擎分析
     * - 不启动持续循环，执行完毕后立即退出
     *
     * 线程模型：
     * - 在后台线程执行，不阻塞主线程
     * - 使用 join(500) 等待执行线程退出
     */
    fun startSingle() {
        if (isRunning) {
            Log.w(TAG, "[HUD] already running, ignoring single.")
            return
        }

        isRunning = true
        frameCount = 0

        simulationThread = Thread(this::singleExecutionLoop, "hzzs-hud-single").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "[HUD] single execution started.")
    }

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
     * 6. 休眠 FRAME_INTERVAL_MS（50ms）
     *
     * 循环条件：isRunning == true 且 frameCount < MAX_FRAMES
     */
    private fun simulationLoop() {
        while (isRunning && frameCount < MAX_FRAMES) {
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

            // 调用 C++ 引擎分析（结果不再更新 UI，仅驱动引擎运行）
            if (NativeLibraryLoader.isAvailable) {
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

        Log.d(TAG, "[HUD] simulation loop exited after $frameCount frames.")
    }

    /**
     * 单次执行循环：生成一帧模拟数据并送入引擎分析，然后退出。
     *
     * 流程：
     * 1. 重置模拟数据状态
     * 2. 生成一帧玩家 + 危险物数据
     * 3. 调用 C++ 引擎分析
     * 4. 退出循环
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

        // 调用 C++ 引擎分析（单次）
        if (NativeLibraryLoader.isAvailable) {
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
        }

        Log.d(TAG, "[HUD] single execution completed.")

        // 标记停止
        isRunning = false
        simulationThread = null
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
}
