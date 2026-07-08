// 火崽崽助手（HZZS）对象池。
//
// 减少高频分配导致的 GC 压力。
//
// 设计说明：
// - RectF 是 data class（不可变），对象池复用会导致状态污染，因此改为直接创建新实例。
//   Kotlin/Java 的 GC 对短生命周期小对象的回收成本很低，不必过度优化。
// - Paint 对象池是有效的，因为 Paint 有 state（alpha/color/strokeWidth），
//   模板模式避免每帧重复创建和配置 Paint 对象。获取时返回副本（new Paint(template)），
//   确保线程安全和状态隔离。
//
// 使用场景：
// - HUDCanvasView 每帧绘制时创建临时 Paint 对象 → 使用 PaintPool 复用模板
// - 未来视觉层大量创建 RectF → 直接 new（GC 成本低）

package top.azek431.hzzs.util

import android.graphics.Paint
import top.azek431.hzzs.model.RectF

// ==================== RectF 对象池 ====================
// 注意：RectF 是 data class（不可变），对象池复用会导致状态污染，
// 因此改为直接创建新实例。GC 对短生命周期小对象的回收成本很低。

/**
 * 创建一个新的 RectF 实例。
 *
 * 由于 RectF 是不可变 data class，不需要对象池复用，
 * 此函数仅为提供统一的创建入口，便于将来改为池化。
 *
 * @param left 左边界（归一化坐标 0~1）
 * @param top 上边界（归一化坐标 0~1）
 * @param right 右边界（归一化坐标 0~1）
 * @param bottom 下边界（归一化坐标 0~1）
 * @return 新的 RectF 实例
 */
fun newRectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
    return RectF(left, top, right, bottom)
}

// ==================== Paint 对象池 ====================

/**
 * Paint 对象池。
 *
 * 针对 HUDCanvasView 的几种固定画笔提供缓存。
 * 注意：Paint 对象有 state（alpha、color 等），
 * 获取时需要 clone 以避免状态污染。
 */
object PaintPool {

    // 预定义的画笔模板
    private val playerFillTemplate = Paint().apply {
        style = Paint.Style.FILL
        strokeWidth = 2f
        color = android.graphics.Color.parseColor("#4CAF50")
        alpha = 80
    }

    private val playerStrokeTemplate = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.parseColor("#4CAF50")
        alpha = 255
    }

    private val hazardFillTemplate = Paint().apply {
        style = Paint.Style.FILL
        strokeWidth = 2f
        color = android.graphics.Color.parseColor("#F44336")
        alpha = 80
    }

    private val hazardStrokeTemplate = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.parseColor("#F44336")
        alpha = 255
    }

    private val gridTemplate = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = android.graphics.Color.parseColor("#22FFFFFF")
    }

    private val textTemplate = Paint().apply {
        textSize = 12f
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }

    /** 获取玩家填充画笔（返回副本） */
    fun getPlayerFillPaint(): Paint = Paint(playerFillTemplate)

    /** 获取玩家描边画笔（返回副本） */
    fun getPlayerStrokePaint(): Paint = Paint(playerStrokeTemplate)

    /** 获取危险物填充画笔（返回副本） */
    fun getHazardFillPaint(): Paint = Paint(hazardFillTemplate)

    /** 获取危险物描边画笔（返回副本） */
    fun getHazardStrokePaint(): Paint = Paint(hazardStrokeTemplate)

    /** 获取网格画笔（返回副本） */
    fun getGridPaint(): Paint = Paint(gridTemplate)

    /** 获取文字画笔（返回副本） */
    fun getTextPaint(): Paint = Paint(textTemplate)
}
