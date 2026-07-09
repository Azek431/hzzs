// 火崽崽助手（HZZS）HUD 颜色调色板。
//
// 职责：
// - 集中管理 HUDCanvasView 使用的所有颜色常量
// - 替代硬编码的 Color.parseColor() 调用
// - 为后续主题化（深色模式）预留扩展点
//
// 设计原因：
// - 避免 18+ 处 Color.parseColor() 分散在绘制代码中
// - 颜色统一管理，便于全局搜索和替换
// - 使用 ARGB 整数值提升性能（避免每次绘制时解析字符串）
//
// 注意：
// - 当前所有颜色硬编码在 Palette 中，未与 res/color 资源关联
// - 深色模式适配时只需替换此处常量的 ARGB 值即可
// - Color.parseColor() 不是编译时常量，所以使用 val 而非 const val

package top.azek431.hzzs.ui.overlay

import android.graphics.Color

/**
 * HUD 颜色调色板。
 *
 * 所有颜色使用 ARGB 整数格式（与 Android Color 类一致），
 * 便于直接赋值给 Paint.color 属性。
 * 颜色按功能分组（背景、检测对象、网格、文字、轨迹、危险区、置信度、倒计时、热力图），
 * 每组包含主色和辅助色（高/中/低状态）。
 */
object HUDColorPalette {

    // ==================== 背景颜色 ====================

    /** 深蓝色背景 (#1A1A2E) — HUD 画布底色，营造科技感视觉效果 */
    val BACKGROUND: Int = 0xFF1A1A2E.toInt()

    // ==================== 检测对象颜色 ====================

    /** 玩家矩形颜色 (#4CAF50 绿色) — 实线边框 + 半透明填充 */
    val PLAYER_GREEN: Int = Color.parseColor("#4CAF50")

    /** 危险物矩形颜色 (#F44336 红色) — 实线边框 + 半透明填充 */
    val HAZARD_RED: Int = Color.parseColor("#F44336")

    // ==================== 网格线颜色 ====================

    /** 网格线颜色 (#22FFFFFF 浅灰半透明) — 背景网格 */
    val GRID_LINE: Int = Color.parseColor("#22FFFFFF")

    // ==================== 文字颜色 ====================

    /** 文字颜色 — 白色 */
    const val TEXT_WHITE = Color.WHITE

    /** 标签灰色 (#AAAAAA) — 分析结果标签 */
    val LABEL_GRAY: Int = Color.parseColor("#AAAAAA")

    // ==================== 轨迹与路径颜色 ====================

    /** 轨迹线颜色 (#4CAF50 绿色) — 渐隐虚线，跟随玩家运动轨迹 */
    val TRAJECTORY_GREEN: Int = Color.parseColor("#4CAF50")

    /** 预测路径颜色 (#2196F3 蓝色) — 虚线 + 箭头，显示推荐运动方向 */
    val PATH_BLUE: Int = Color.parseColor("#2196F3")

    // ==================== 危险区颜色 ====================

    /** 危险区颜色 (#F44336 红色) — 闪烁边框 + 半透明填充 */
    val DANGER_RED: Int = Color.parseColor("#F44336")

    // ==================== 置信度颜色 ====================

    /** 置信度圆环背景 (#44FFFFFF 浅灰半透明) */
    val CONFIDENCE_BG: Int = Color.parseColor("#44FFFFFF")

    /** 置信度高 (#4CAF50 绿色) — >= 0.9 */
    val CONFIDENCE_HIGH: Int = Color.parseColor("#4CAF50")

    /** 置信度中 (#FFC107 黄色) — >= 0.7 */
    val CONFIDENCE_MEDIUM: Int = Color.parseColor("#FFC107")

    /** 置信度低 (#F44336 红色) — < 0.7 */
    val CONFIDENCE_LOW: Int = Color.parseColor("#F44336")

    // ==================== 倒计时颜色 ====================

    /** 倒计时背景 (#33FFFFFF 浅灰半透明) */
    val TIMER_BG: Int = Color.parseColor("#33FFFFFF")

    /** 倒计时高 (#4CAF50 绿色) — progress > 0.5 */
    val TIMER_HIGH: Int = Color.parseColor("#4CAF50")

    /** 倒计时中 (#FFC107 黄色) — progress > 0.25 */
    val TIMER_MEDIUM: Int = Color.parseColor("#FFC107")

    /** 倒计时低 (#F44336 红色) — progress <= 0.25 */
    val TIMER_LOW: Int = Color.parseColor("#F44336")

    // ==================== 热力图颜色 ====================

    /** 热力图起始色 (#FF9800 橙色) — 低强度 */
    val HEATMAP_START: Int = Color.parseColor("#FF9800")

    /** 热力图结束色 (#F44336 红色) — 高强度 */
    val HEATMAP_END: Int = Color.parseColor("#F44336")
}
