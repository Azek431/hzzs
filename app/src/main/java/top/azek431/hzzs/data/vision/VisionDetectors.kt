// 火崽崽助手（HZZS）视觉识别 — 检测器接口。
//
// 职责：
// - 定义所有视觉检测器的统一接口
// - 每个检测器实现 detect() 方法，接收像素数据和玩家参考坐标
//
// 设计原因：
// - 密封类保证类型安全，编译器可检查所有检测器类型
// - 统一接口便于 VisionDetectionController 调度多个检测器
// - 每个检测器独立实现，互不耦合

package top.azek431.hzzs.data.vision

/**
 * 视觉检测器密封类。
 *
 * 每个检测器负责识别一种特定的画面元素。
 */
sealed interface VisionDetector {
    /** 检测器名称（用于日志和调试） */
    val name: String

    /**
     * 执行检测。
     *
     * @param frame 画面帧数据
     * @param playerReference 玩家参考坐标
     * @return 检测结果（具体类型由子类决定）
     */
    fun detect(frame: VisionFrame, playerReference: PlayerReference): DetectionResult
}

/**
 * 检测结果基类。
 *
 * @property detector 产生此结果的检测器
 * @property detected 是否检测到目标
 * @property confidence 置信度（0.0 ~ 1.0）
 * @property costMs 检测耗时（毫秒）
 */
sealed interface DetectionResult {
    val detector: VisionDetector
    val detected: Boolean
    val confidence: Float
    val costMs: Float
}

/**
 * 绿瓶检测结果。
 */
data class GreenBottleDetection(
    override val detector: VisionDetector,
    override val detected: Boolean,
    override val confidence: Float,
    override val costMs: Float,
    val leftX: Float = 0f,
    val rightX: Float = 0f,
    val centerX: Float = 0f,
    val scanY: Int = 0,
    val edgeGapPx: Int = 0,
) : DetectionResult

/**
 * 坑位检测结果。
 */
data class PitDetection(
    override val detector: VisionDetector,
    override val detected: Boolean,
    override val confidence: Float,
    override val costMs: Float,
    val left: Float = 0f,
    val right: Float = 0f,
    val center: Float = 0f,
    val width: Float = 0f,
    val scanY: Int = 0,
) : DetectionResult
