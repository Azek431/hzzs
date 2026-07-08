// 火崽崽助手（HZZS）对象池。
//
// 减少高频分配导致的 GC 压力。
// 使用场景：
// - 每帧创建大量 RectF → RectFPool
// - Canvas 重绘时创建 Paint → PaintPool

package top.azek431.hzzs.util

import android.graphics.Paint

// ==================== RectF 对象池 ====================

/**
 * RectF 对象池。
 *
 * 避免每帧创建新 RectF 导致 GC 压力。
 * 池大小限制为 32，超出时释放回池。
 */
object RectFPool {
    private val pool = ArrayDeque<top.azek431.hzzs.RectF>()
    private const val MAX_SIZE = 32

    /** 从池中获取或创建新的 RectF */
    fun acquire(left: Float, top: Float, right: Float, bottom: Float): top.azek431.hzzs.RectF {
        return pool.poll()?.apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        } ?: top.azek431.hzzs.RectF(left, top, right, bottom)
    }

    /** 归还 RectF 到池中 */
    fun release(rect: top.azek431.hzzs.RectF) {
        if (pool.size < MAX_SIZE) {
            pool.add(rect)
        }
    }

    /** 清空池 */
    fun clear() {
        pool.clear()
    }
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
