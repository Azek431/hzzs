package top.azek431.hzzs

// ==================== 数据模型类 ====================

/**
 * 归一化矩形坐标（0.0 ~ 1.0）。
 *
 * 与 C++ 端 RectF 对应，用于描述玩家边界、危险物边界等。
 * 所有坐标在 0.0 ~ 1.0 范围内，与设备分辨率无关。
 */
data class RectF(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    /** 矩形宽度 */
    val width: Float get() = right - left

    /** 矩形高度 */
    val height: Float get() = bottom - top

    /** 中心点 X */
    val centerX: Float get() = (left + right) * 0.5f

    /** 中心点 Y */
    val centerY: Float get() = (top + bottom) * 0.5f

    /** 是否有效（right > left 且 bottom > top） */
    fun isValid(): Boolean = right > left && bottom > top
}

/**
 * 单帧分析结果数据类。
 *
 * 由 NativeAnalysisBridge.analyzeFrame() 解析 C++ 返回的 JSON 字符串得到。
 * 所有字段直接映射 C++ 端的 AnalysisResult 结构体。
 */
data class FrameAnalysisResult(
    // 场景信息
    val sceneMode: Int,                // SceneMode 枚举值
    val sceneConfidence: Float,        // 场景可信度（0.0 ~ 1.0）

    // 角色运动状态
    val runnerPose: Int,               // RunnerPose 枚举值
    val runnerGrounded: Boolean,       // 是否着地

    // 跳跃阶段
    val jumpStage: Int,                // 0=地面, 1=首跳, 2=二连跳

    // HUD 动作提示
    val promptAction: Int,             // PromptAction 枚举值（0=无, 1=跳跃, 2=二连跳, 3=滑铲）
    val promptTarget: Int,             // GameObjectType 枚举值
    val promptEtaMs: Float,            // 到达时间（毫秒），-1.0 表示不可用
    val promptConfidence: Float,       // 提示可信度（0.0 ~ 1.0）

    // 危险物信息
    val hazardsCount: Int,             // 检测到危险物数量

    // 收藏物信息
    val collectiblesCount: Int,        // 检测到可收集物品数量
) {
    companion object {
        // ==================== 场景模式常量 ====================
        const val SCENE_UNKNOWN = 0
        const val SCENE_MENU = 1
        const val SCENE_COUNTDOWN = 2
        const val SCENE_GROUND_RUN = 3
        const val SCENE_FLIGHT_RUN = 4
        const val SCENE_RESULT = 5
        const val SCENE_OCCLUDED = 6

        // ==================== 角色姿态常量 ====================
        const val POSE_UNKNOWN = 0
        const val POSE_RUN = 1
        const val POSE_JUMP_UP = 2
        const val POSE_JUMP_TOP = 3
        const val POSE_JUMP_DOWN = 4
        const val POSE_SLIDE = 5
        const val POSE_FLIGHT = 6

        // ==================== 提示动作常量 ====================
        const val PROMPT_NONE = 0
        const val PROMPT_JUMP = 1
        const val PROMPT_JUMP_AGAIN = 2
        const val PROMPT_SLIDE = 3
    }

    // ==================== 便捷属性 ====================

    /** 当前是否为地面跑酷场景 */
    val isGroundRun: Boolean get() = sceneMode == SCENE_GROUND_RUN

    /** 是否有有效的动作提示 */
    val hasPrompt: Boolean get() = promptAction != PROMPT_NONE

    /** 动作提示文本（中文） */
    val promptText: String
        get() = when (promptAction) {
            PROMPT_JUMP -> "⬆ 跳跃"
            PROMPT_JUMP_AGAIN -> "⬆⬆ 二连跳"
            PROMPT_SLIDE -> "⬇ 滑铲"
            else -> ""
        }

    /** 危险物类型文本（中文） */
    val promptTargetText: String
        get() = when (promptTarget) {
            1 -> "蛋糕断层"
            2 -> "毒瓶"
            3 -> "裱花袋"
            else -> "未知"
        }

    /** ETA 可读文本 */
    val etaText: String
        get() = if (promptEtaMs > 0f && promptEtaMs < 9999f) {
            "${promptEtaMs.toInt()}ms"
        } else {
            "--"
        }

    /** 场景模式可读文本 */
    val sceneText: String
        get() = when (sceneMode) {
            SCENE_UNKNOWN -> "场景未知"
            SCENE_MENU -> "菜单"
            SCENE_COUNTDOWN -> "倒计时"
            SCENE_GROUND_RUN -> "地面跑酷"
            SCENE_FLIGHT_RUN -> "飞行模式"
            SCENE_RESULT -> "结算"
            SCENE_OCCLUDED -> "已遮挡"
            else -> "未知"
        }

    /** 角色姿态可读文本 */
    val poseText: String
        get() = when (runnerPose) {
            POSE_UNKNOWN -> "姿态未知"
            POSE_RUN -> "奔跑"
            POSE_JUMP_UP -> "起跳上升"
            POSE_JUMP_TOP -> "滞空顶点"
            POSE_JUMP_DOWN -> "下落"
            POSE_SLIDE -> "滑铲"
            POSE_FLIGHT -> "飞行"
            else -> "未知"
        }
}

// ==================== JNI 桥接层 ====================

/**
 * 火崽崽助手（HZZS）Kotlin 与 C++ 算法核心之间的最小 JNI 桥接层。
 *
 * 此对象负责：
 * 1. 加载 C++ 共享库（libhzzs_native.so）
 * 2. 暴露库加载状态（isAvailable），供上层判断是否可用
 * 3. 提供 engineInfo()、runSelfCheck() 两个诊断接口
 * 4. 提供 analyzeFrame() 单帧分析接口，供 HUD 渲染器调用
 *
 * 架构定位：
 * - 使用 object（单例）而非 class，因为 JNI 库只需加载一次
 * - 库加载在静态初始化阶段完成，失败时记录异常但不崩溃
 * - 所有公共方法在库加载失败时返回描述性错误字符串或 null，而非抛异常
 *
 * 对应的 C++ 共享库：
 * - 库文件名：libhzzs_native.so
 * - 构建方式：CMakeLists.txt 定义的 SHARED 库
 * - 编译标准：C++17
 */
object NativeAnalysisBridge {

    /** C++ 共享库名称（不含 lib 前缀和 .so 后缀） */
    private const val LIBRARY_NAME = "hzzs_native"

    /** 库不可用时的统一错误消息模板 */
    private const val UNAVAILABLE_TEMPLATE = "Native library unavailable: %s"

    /**
     * 尝试加载 C++ 共享库，捕获任何加载异常。
     *
     * System.loadLibrary() 可能在以下情况失败：
     * - 库文件不存在于设备的 ABI 目录中（arm64-v8a、armeabi-v7a 等）
     * - 库依赖的其他符号缺失
     * - 进程权限不足
     * - ABI 不匹配（如在 x86 模拟器上运行 arm64 库）
     *
     * 使用 runCatching 将可能的异常转换为 Result，
     * 这样即使加载失败也不会导致应用崩溃。
     */
    private val libraryLoadError: Throwable? = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    /**
     * 指示 C++ 原生库是否已成功加载并可用的只读属性。
     *
     * @return true 如果库加载成功，false 如果加载失败或库文件不存在
     */
    val isAvailable: Boolean
        get() = libraryLoadError == null

    // ==================== 诊断接口 ====================

    /**
     * 获取引擎版本信息字符串。
     *
     * @return 引擎信息字符串或错误描述
     */
    fun engineInfo(): String {
        return if (libraryLoadError == null) {
            nativeGetEngineInfo()
        } else {
            String.format(UNAVAILABLE_TEMPLATE, libraryLoadError.javaClass.simpleName)
        }
    }

    /**
     * 执行 C++ 引擎的自检程序。
     *
     * @return 自检结果字符串（"PASS: ..." 或 "FAIL: ..."）
     */
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

    /**
     * 将 C++ 返回的 JSON 字符串解析为 [FrameAnalysisResult]。
     *
     * 使用极简正则表达式提取字段值，不依赖第三方 JSON 库。
     */
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

    /**
     * 分析单帧模拟数据并返回结构化 JSON 结果。
     *
     * 对应 C++ 实现：
     * Java_top_azek431_hzzs_NativeAnalysisBridge_nativeAnalyzeFrame()
     */
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
