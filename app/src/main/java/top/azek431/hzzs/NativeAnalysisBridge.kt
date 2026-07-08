package top.azek431.hzzs

/**
 * Kotlin 与 C++ 算法核心之间的最小 JNI 桥接层。
 *
 * 此对象负责：
 * 1. 加载 C++ 共享库（libhzzs_native.so）
 * 2. 暴露库加载状态（isAvailable），供上层判断是否可用
 * 3. 提供 engineInfo() 和 runSelfCheck() 两个诊断接口
 *
 * 架构定位：
 * - 当前阶段不接入首页、悬浮窗、屏幕采集或真实帧分析
 * - 仅作为 JNI 入口，验证 C++ 库能否正常加载和执行自检
 * - 后续视觉模块准备好后，由上层（如悬浮窗面板）调用 C++ 算法引擎
 *
 * 设计决策：
 * - 使用 object（单例）而非 class，因为 JNI 库只需加载一次
 * - 库加载在静态初始化阶段完成，失败时记录异常但不崩溃
 * - 所有公共方法在库加载失败时返回描述性错误字符串，而非抛异常
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
     *
     * 上层组件（如首页 UI）可在使用分析功能前检查此属性，
     * 以决定是显示"功能开发中"还是实际调用分析接口。
     */
    val isAvailable: Boolean
        get() = libraryLoadError == null

    /**
     * 获取引擎版本信息字符串。
     *
     * 如果 C++ 库已成功加载，调用 nativeGetEngineInfo() 返回引擎信息；
     * 如果加载失败，返回描述性的错误字符串。
     *
     * @return 引擎信息字符串或错误描述
     */
    fun engineInfo(): String {
        val error = libraryLoadError

        return if (error == null) {
            // 库加载成功，调用 C++ 方法获取引擎信息
            nativeGetEngineInfo()
        } else {
            // 库加载失败，返回错误描述
            String.format(UNAVAILABLE_TEMPLATE, error.javaClass.simpleName)
        }
    }

    /**
     * 执行 C++ 引擎的自检程序。
     *
     * 自检流程（在 C++ 端）：
     * 1. 创建 NativeAnalysisEngine 实例
     * 2. 注入模拟的地面跑酷帧数据（含玩家矩形、障碍物等）
     * 3. 分析两帧数据，验证场景识别、角色姿态、危险检测和跳跃提示是否正确
     *
     * 如果 C++ 库加载失败，返回描述性的错误字符串。
     *
     * @return 自检结果字符串（"PASS: ..." 或 "FAIL: ..."）
     */
    fun runSelfCheck(): String {
        val error = libraryLoadError

        return if (error == null) {
            // 库加载成功，执行 C++ 自检
            nativeRunSelfCheck()
        } else {
            // 库加载失败，返回错误描述
            String.format(UNAVAILABLE_TEMPLATE, error.javaClass.simpleName)
        }
    }

    /**
     * 分析单帧模拟数据并返回结构化结果。
     *
     * 调用 C++ 引擎分析一帧模拟跑酷画面，解析 JSON 返回 [FrameAnalysisResult]。
     * 如果 C++ 库加载失败，返回 null。
     *
     * @param timestampMs 帧时间戳（毫秒）
     * @param playerBounds 玩家矩形归一化坐标
     * @param playerConfidence 玩家检测可信度（0.0 ~ 1.0）
     * @param hazardType 危险物类型（1=蛋糕断层, 2=毒瓶, 3=裱花袋, 0=无）
     * @param hazardBounds 危险物矩形归一化坐标
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
        val error = libraryLoadError
        if (error != null) return null

        val json = nativeAnalyzeFrame(
            timestampMs,
            playerBounds.left, playerBounds.top,
            playerBounds.right, playerBounds.bottom,
            playerConfidence,
            hazardType,
            hazardBounds?.let { it.left to it.top to it.right to it.bottom }?.let {
                it.first to it.second to it.third to it.fourth
            }?.let {
                // 展开为四个参数
                val (hl, ht, hr, hb) = it
                Triple(hl, ht, hr to hb)
            }?.let { _ ->
                // 简化：直接传默认值
                nativeAnalyzeFrameDirect(
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
            },
        )

        return json?.let { parseFrameResult(it) }
    }

    /**
     * 直接调用 native 方法（用于 hazardBounds 为空时的分支）。
     */
    private external fun nativeAnalyzeFrameDirect(
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

    /**
     * 将 JSON 字符串解析为 [FrameAnalysisResult]。
     */
    private fun parseFrameResult(json: String): FrameAnalysisResult {
        // 极简 JSON 解析（不依赖第三方库）
        fun extractString(key: String): String? {
            val pattern = "\"$key\":\"([^\"]*)\""
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value
        }

        fun extractFloat(key: String): Float {
            val pattern = "\"$key\":([-0-9.]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toFloatOrNull() ?: 0f
        }

        fun extractInt(key: String): Int {
            val pattern = "\"$key\":(-?[0-9]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        }

        return FrameAnalysisResult(
            sceneMode = extractInt("scene_mode"),
            sceneConfidence = extractFloat("scene_confidence"),
            runnerPose = extractInt("runner_pose"),
            runnerGrounded = extractString("runner_grounded") == "true",
            jumpStage = extractInt("jump_stage"),
            promptAction = extractInt("prompt_action"),
            promptTarget = extractInt("prompt_target"),
            promptEtaMs = extractFloat("prompt_eta_ms"),
            promptConfidence = extractFloat("prompt_confidence"),
            hazardsCount = extractInt("hazards_count"),
            collectiblesCount = extractInt("collectibles_count"),
        )
    }

    /**
     * C++ 原生方法声明：获取引擎信息。
     *
     * 对应 C++ 实现：
     * Java_top_azek431_hzzs_NativeAnalysisBridge_nativeGetEngineInfo()
     *
     * 返回值是一个描述字符串，包含引擎名称、C++ 标准和功能列表。
     */
    private external fun nativeGetEngineInfo(): String

    /**
     * C++ 原生方法声明：执行自检程序。
     *
     * 对应 C++ 实现：
     * Java_top_azek431_hzzs_NativeAnalysisBridge_nativeRunSelfCheck()
     *
     * 返回值是一个描述字符串，包含自检通过/失败的信息。
     */
    private external fun nativeRunSelfCheck(): String

    /**
     * C++ 原生方法声明：分析单帧模拟数据并返回结构化 JSON 结果。
     *
     * 对应 C++ 实现：
     * Java_top_azek431_hzzs_NativeAnalysisBridge_nativeAnalyzeFrame()
     *
     * @param timestampMs 帧时间戳（毫秒）
     * @param playerBounds 玩家矩形归一化坐标（left,top,right,bottom）
     * @param playerConfidence 玩家检测可信度（0.0 ~ 1.0）
     * @param hazardType 危险物类型（1=蛋糕断层, 2=毒瓶, 3=裱花袋）
     * @param hazardBounds 危险物矩形归一化坐标
     * @param hazardConfidence 危险物检测可信度
     * @param hazardVelocityX 危险物 X 方向速度
     * @param worldScrollSpeed 背景滚动速度
     * @return JSON 格式的 AnalysisResult 字符串
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
