// 火崽崽助手（HZZS）对象池。
//
// 减少高频分配导致的 GC 压力。
// 使用场景：
// - 每帧创建大量 RectF → RectFPool
// - Canvas 重绘时创建 Paint → PaintPool

package top.azek431.hzzs.util

import android.graphics.Paint
import top.azek431.hzzs.model.RectF

// ==================== RectF 对象池 ====================
// 注意：RectF 是 data class（不可变），对象池复用会导致状态污染，
// 因此改为直接创建新实例。GC 对短生命周期小对象的回收成本很低。

/** 创建一个 RectF 实例 */
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
