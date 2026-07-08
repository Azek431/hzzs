// 火崽崽助手（HZZS）Kotlin 与 C++ 算法核心之间的最小 JNI 桥接层。
//
// 此对象负责：
// 1. 加载 C++ 共享库（libhzzs_native.so）
// 2. 暴露库加载状态（isAvailable），供上层判断是否可用
// 3. 提供 engineInfo()、runSelfCheck() 两个诊断接口
// 4. 提供 analyzeFrame() 单帧分析接口，供 HUD 渲染器调用
//
// 架构定位：
// - 使用 object（单例）而非 class，因为 JNI 库只需加载一次
// - 库加载在静态初始化阶段完成，失败时记录异常但不崩溃
// - 所有公共方法在库加载失败时返回描述性错误字符串或 null，而非抛异常

package top.azek431.hzzs

import top.azek431.hzzs.model.FrameAnalysisResult
import top.azek431.hzzs.model.RectF
import top.azek431.hzzs.util.ThreadSafeQueue

object NativeAnalysisBridge {

    /** C++ 共享库名称（不含 lib 前缀和 .so 后缀） */
    private const val LIBRARY_NAME = "hzzs_native"

    /** 库不可用时的统一错误消息模板 */
    private const val UNAVAILABLE_TEMPLATE = "Native library unavailable: %s"

    /** 分析结果队列，供主线程消费 */
    private val resultQueue = ThreadSafeQueue<FrameAnalysisResult>(64)

    /** 尝试加载 C++ 共享库，捕获任何加载异常 */
    private val libraryLoadError: Throwable? = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    /** 指示 C++ 原生库是否已成功加载并可用的只读属性 */
    val isAvailable: Boolean
        get() = libraryLoadError == null

    // ==================== 诊断接口 ====================

    /** 获取引擎版本信息字符串 */
    fun engineInfo(): String {
        return if (libraryLoadError == null) {
            nativeGetEngineInfo()
        } else {
            String.format(UNAVAILABLE_TEMPLATE, libraryLoadError.javaClass.simpleName)
        }
    }

    /** 执行 C++ 引擎的自检程序 */
    fun runSelfCheck(): String {
        return if (libraryLoadError == null) {
            nativeRunSelfCheck()
        } else {
            String.format(UNAVAILABLE_TEMPLATE, libraryLoadError.javaClass.simpleName)
        }
    }

    // ==================== 单帧分析接口 ====================

    /**
     * 分析单帧模拟数据并返回结构化结果。
     *
     * @param timestampMs 帧时间戳（毫秒）
     * @param playerBounds 玩家矩形归一化坐标
     * @param playerConfidence 玩家检测可信度（0.0 ~ 1.0）
     * @param hazardType 危险物类型（1=蛋糕断层, 2=毒瓶, 3=裱花袋, 0=无）
     * @param hazardBounds 危险物矩形归一化坐标（可为 null）
     * @param hazardConfidence 危险物检测可信度
     * @param hazardVelocityX 危险物 X 方向速度
     * @param worldScrollSpeed 背景滚动速度
     * @return 结构化分析结果，或 null（库不可用时）
     */
    fun analyzeFrame(
        timestampMs: Long,
        playerBounds: RectF,
        playerConfidence: Float,
        hazardType: Int = 0,
        hazardBounds: RectF? = null,
        hazardConfidence: Float = 0f,
        hazardVelocityX: Float = -0.45f,
        worldScrollSpeed: Float = -0.45f,
    ): FrameAnalysisResult? {
        if (libraryLoadError != null) return null

        val json = nativeAnalyzeFrame(
            timestampMs,
            playerBounds.left, playerBounds.top,
            playerBounds.right, playerBounds.bottom,
            playerConfidence,
            hazardType,
            hazardBounds?.left ?: 0f,
            hazardBounds?.top ?: 0f,
            hazardBounds?.right ?: 0f,
            hazardBounds?.bottom ?: 0f,
            hazardConfidence,
            hazardVelocityX,
            worldScrollSpeed,
        )

        return parseFrameResult(json)
    }

    // ==================== 私有辅助方法 ====================

    /** 将 C++ 返回的 JSON 字符串解析为 [FrameAnalysisResult] */
    private fun parseFrameResult(json: String): FrameAnalysisResult {
        fun extractFloat(key: String): Float {
            val pattern = "\"$key\":([-0-9.eE+]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toFloatOrNull() ?: 0f
        }

        fun extractInt(key: String): Int {
            val pattern = "\"$key\":(-?[0-9]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        }

        fun extractBoolean(key: String): Boolean {
            val pattern = "\"$key\":(true|false)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value == "true"
        }

        return FrameAnalysisResult(
            sceneMode = extractInt("scene_mode"),
            sceneConfidence = extractFloat("scene_confidence"),
            runnerPose = extractInt("runner_pose"),
            runnerGrounded = extractBoolean("runner_grounded"),
            jumpStage = extractInt("jump_stage"),
            promptAction = extractInt("prompt_action"),
            promptTarget = extractInt("prompt_target"),
            promptEtaMs = extractFloat("prompt_eta_ms"),
            promptConfidence = extractFloat("prompt_confidence"),
            hazardsCount = extractInt("hazards_count"),
            collectiblesCount = extractInt("collectibles_count"),
        )
    }

    // ==================== JNI 原生方法声明 ====================

    /** 获取引擎信息字符串 */
    private external fun nativeGetEngineInfo(): String

    /** 执行引擎自检程序 */
    private external fun nativeRunSelfCheck(): String

    /** 分析单帧模拟数据并返回结构化 JSON 结果 */
    private external fun nativeAnalyzeFrame(
        timestampMs: Long,
        playerLeft: Float,
        playerTop: Float,
        playerRight: Float,
        playerBottom: Float,
        playerConfidence: Float,
        hazardType: Int,
        hazardLeft: Float,
        hazardTop: Float,
        hazardRight: Float,
        hazardBottom: Float,
        hazardConfidence: Float,
        hazardVelocityX: Float,
        worldScrollSpeed: Float,
    ): String
}
