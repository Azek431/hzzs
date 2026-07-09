// 火崽崽助手（HZZS）JNI 引擎门面。
//
// 职责：
// - 封装 JNI 原生方法调用（nativeGetEngineInfo / nativeRunSelfCheck / nativeAnalyzeFrame）
// - 提供高层接口：engineInfo()、runSelfCheck()、analyzeFrame()
// - 库不可用时返回描述性错误/null，而非抛异常
//
// 不负责：
// - 不处理库加载（由 NativeLibraryLoader 处理）
// - 不处理 JSON 解析（由 NativeJsonParser 处理）
//
// 设计原因：
// - JNI 原生方法声明与业务逻辑分离，便于维护
// - 所有公共方法在库不可用时返回描述性错误/null，而非抛异常
// - 外部调用者只需知道 NativeEngineFacade，不直接接触 JNI 细节
// - 作为门面类，隐藏了"加载 → 调用 → 解析"的三步流程

package top.azek431.hzzs.data.native

import top.azek431.hzzs.model.FrameAnalysisResult
import top.azek431.hzzs.model.RectF

/**
 * JNI 引擎门面。
 *
 * 封装所有 JNI 调用，为上层提供简洁的分析接口。
 * 库加载失败时，所有方法返回描述性错误消息或 null。
 *
 * 调用流程：
 * - engineInfo()/runSelfCheck()：检查 NativeLibraryLoader.isAvailable → 调用 JNI → 返回字符串
 * - analyzeFrame()：检查 isAvailable → 调用 JNI → NativeJsonParser.parse → 返回 FrameAnalysisResult
 */
object NativeEngineFacade {

    /** 库不可用时的统一错误消息模板 */
    private const val UNAVAILABLE_TEMPLATE = "Native library unavailable: %s"

    /**
     * 获取引擎版本信息字符串。
     *
     * 返回内容："HZZS native core ready | C++17 | scene + runner + double-jump + hazard ETA"
     * 用于诊断和日志记录。
     *
     * @return 引擎信息，或库不可用时的错误消息
     */
    fun engineInfo(): String {
        return if (NativeLibraryLoader.isAvailable) {
            nativeGetEngineInfo()
        } else {
            String.format(UNAVAILABLE_TEMPLATE, "library not loaded")
        }
    }

    /**
     * 执行 C++ 引擎的自检程序。
     *
     * 自检流程（在 C++ 端）：
     * 1. 创建 NativeAnalysisEngine 实例
     * 2. 注入两帧模拟地面跑酷数据（时间戳 16ms 和 32ms）
     * 3. 分析第二帧，验证 scene_mode/runner_pose/prompt_action/jump_stage 是否正确
     * 4. 返回 "PASS: ..." 或 "FAIL: ..."
     *
     * @return 自检结果字符串，或库不可用时的错误消息
     */
    fun runSelfCheck(): String {
        return if (NativeLibraryLoader.isAvailable) {
            nativeRunSelfCheck()
        } else {
            String.format(UNAVAILABLE_TEMPLATE, "library not loaded")
        }
    }

    /**
     * 分析单帧模拟数据并返回结构化结果。
     *
     * 完整调用流程：
     * 1. 检查 NativeLibraryLoader.isAvailable，失败则返回 null
     * 2. 调用 nativeAnalyzeFrame() JNI 方法，传入归一化坐标参数
     * 3. 将 C++ 返回的 JSON 字符串交给 NativeJsonParser.parse() 解析
     * 4. 返回 FrameAnalysisResult 对象
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
        // 库不可用时直接返回 null，不调用 JNI
        if (!NativeLibraryLoader.isAvailable) return null

        // 调用 JNI 原生方法，将归一化坐标展开为独立参数
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

        // 将 JSON 字符串解析为 FrameAnalysisResult
        return NativeJsonParser.parse(json)
    }

    /** 重置分析引擎状态 */
    private external fun nativeResetEngine()

    /**
     * 公开方法：重置 C++ 分析引擎状态机。
     *
     * 在停止循环执行时调用，清除场景/姿态/跳跃阶段等所有子模块状态。
     */
    fun resetEngine() {
        if (NativeLibraryLoader.isAvailable) {
            nativeResetEngine()
        }
    }

    // ==================== JNI 原生方法声明 ====================

    /** 获取引擎信息字符串 */
    private external fun nativeGetEngineInfo(): String

    /** 执行引擎自检程序 */
    private external fun nativeRunSelfCheck(): String

    /**
     * 分析单帧模拟数据并返回结构化 JSON 结果。
     *
     * 对应 C++ 端的 Java_top_azek431_hzzs_NativeAnalysisBridge_nativeAnalyzeFrame。
     * 参数按 C++ 端 JNI 签名顺序排列，与 FrameDetections 结构体字段对应。
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
