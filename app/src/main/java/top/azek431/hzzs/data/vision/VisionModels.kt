// 火崽崽助手（HZZS）视觉识别 — 数据结构定义。
//
// 职责：
// - 定义视觉识别模块的核心数据类
// - VisionFrame：传入分析器的画面帧
// - VisionResults：分析结果集合（绿瓶 + 坑位 + 其他检测器）
// - PlayerReference：玩家参考坐标（用于归一化检测区域）
// - ActionTrigger：触发的自动操作指令
//
// 设计原因：
// - 使用数据类承载结构化信息，便于在调度层/算法层/绘制层之间传递
// - 所有坐标使用归一化值（0.0 ~ 1.0），适配不同分辨率
// - 检测结果与自动操作指令分离，符合单一职责原则

package top.azek431.hzzs.data.vision

import android.graphics.RectF

/**
 * 画面帧数据。
 *
 * 由截图层采集后传入分析层。
 *
 * @property pixels ARGB 像素数组
 * @property width 屏幕宽度
 * @property height 屏幕高度
 * @property density 屏幕密度 DPI
 * @property timestampMs 采集时间戳
 */
data class VisionFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val density: Int,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * 玩家参考坐标。
 *
 * 由视觉识别层或用户校准提供，用于确定检测区域的相对位置。
 * 所有坐标使用归一化值（0.0 ~ 1.0）。
 *
 * @property left 玩家左边界
 * @property top 玩家上边界
 * @property right 玩家右边界
 * @property bottom 玩家下边界
 */
data class PlayerReference(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** 是否为有效坐标 */
    val isValid: Boolean get() = left < right && top < bottom && width > 0f
}

/**
 * 绿瓶检测结果。
 *
 * @property found 是否检测到绿瓶
 * @property leftX 左边界 X（归一化）
 * @property rightX 右边界 X（归一化）
 * @property centerX 中心 X（归一化）
 * @property scanY 扫描线 Y（像素）
 * @property confidence 置信度（0.0 ~ 1.0）
 * @property edgeGapPx 左边缘到玩家右侧的距离（像素）
 * @property costMs 检测耗时（毫秒）
 */
data class GreenBottleResult(
    val found: Boolean = false,
    val leftX: Float = 0f,
    val rightX: Float = 0f,
    val centerX: Float = 0f,
    val scanY: Int = 0,
    val confidence: Float = 0f,
    val edgeGapPx: Int = 0,
    val costMs: Float = 0f,
) {
    val isValid: Boolean get() = found && confidence > 0f
}

/**
 * 坑位检测结果。
 *
 * @property found 是否检测到坑位
 * @property left 坑位左边界（归一化）
 * @property right 坑位右边界（归一化）
 * @property center 坑位中心（归一化）
 * @property width 坑位宽度（归一化）
 * @property scanY 扫描线 Y（像素）
 * @property confidence 置信度（0.0 ~ 1.0）
 * @property costMs 检测耗时（毫秒）
 */
data class PitResult(
    val found: Boolean = false,
    val left: Float = 0f,
    val right: Float = 0f,
    val center: Float = 0f,
    val width: Float = 0f,
    val scanY: Int = 0,
    val confidence: Float = 0f,
    val costMs: Float = 0f,
) {
    val isValid: Boolean get() = found && confidence > 0f
}

/**
 * 完整的帧分析结果。
 *
 * 由 VisionDetectionController.analyzeFrame() 产出，
 * 包含所有检测器的结果 + 触发的自动操作指令。
 *
 * @property frame 输入画面帧
 * @property playerReference 玩家参考坐标
 * @property greenBottle 绿瓶检测结果
 * @property pit 坑位检测结果
 * @property actions 需要触发的自动操作列表
 * @property costMs 总分析耗时
 * @property timestampMs 分析时间戳
 */
data class VisionFrameResult(
    val frame: VisionFrame,
    val playerReference: PlayerReference,
    val greenBottle: GreenBottleResult = GreenBottleResult(),
    val pit: PitResult = PitResult(),
    val actions: List<ActionTrigger> = emptyList(),
    val costMs: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * 自动操作触发指令。
 *
 * 由检测结果生成，传递给 AutoActionQueue.enqueue()。
 *
 * @property type 操作类型
 * @property targetX 目标 X（归一化）
 * @property targetY 目标 Y（归一化）
 * @property reason 触发原因（用于日志和调试）
 * @property confidence 触发时的置信度
 */
data class ActionTrigger(
    val type: ActionType,
    val targetX: Float,
    val targetY: Float,
    val reason: String = "",
    val confidence: Float = 0f,
) {
    enum class ActionType {
        JUMP,      // 跳跃 — 应对坑位
        TAP,       // 点击 — 收集绿瓶
        SLIDE,     // 滑铲 — 应对悬垂障碍
        DOUBLE_JUMP, // 二连跳
    }
}
