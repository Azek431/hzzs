package top.azek431.hzzs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * HUD 自定义 Canvas 绘制视图。
 *
 * 用于在悬浮窗中模拟显示游戏画面，标注玩家矩形和危险物矩形。
 * 所有坐标使用归一化值（0.0 ~ 1.0），自动适配视图尺寸。
 *
 * 设计思路：
 * - 继承 View 而非 SurfaceView，因为 HUD 仅显示模拟数据，不需要高频刷新
 * - 每次收到新的 FrameAnalysisResult 后调用 invalidate() 重绘
 * - 使用 Paint 绘制玩家（绿色）、危险物（红色）、背景网格
 */
class HUDCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ==================== 绘制工具 ====================

    /** 玩家矩形画笔：绿色实线边框 + 半透明填充 */
    private val playerPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4CAF50")
    }

    /** 危险物矩形画笔：红色实线边框 + 半透明填充 */
    private val hazardPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = Color.parseColor("#F44336")
    }

    /** 网格线画笔：浅灰色虚线 */
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#33FFFFFF")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    // 注意：pathEffect 实际不生效，这里仅用于示意网格样式
    // 网格线通过手动绘制实现

    /** 文字画笔：白色 */
    private val textPaint = Paint().apply {
        textSize = 12f
        color = Color.WHITE
        isAntiAlias = true
    }

    // ==================== 绘制数据 ====================

    /** 当前帧的玩家矩形（归一化坐标） */
    var playerBounds: RectF? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** 当前帧的危险物矩形列表（归一化坐标） */
    var hazards: List<RectF> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 当前帧的分析结果（用于显示标签） */
    var analysisResult: FrameAnalysisResult? = null
        set(value) {
            field = value
            postInvalidate()
        }

    // ==================== 生命周期 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) return

        // 1. 绘制背景
        canvas.drawColor(Color.parseColor("#1A1A2E"))

        // 2. 绘制网格线
        drawGrid(canvas, w, h)

        // 3. 绘制危险物矩形
        for (hazard in hazards) {
            drawNormalizedRect(canvas, hazard, hazardPaint, "危险")
        }

        // 4. 绘制玩家矩形
        playerBounds?.let { bounds ->
            drawNormalizedRect(canvas, bounds, playerPaint, "玩家")
        }

        // 5. 绘制分析结果标签
        analysisResult?.let { result ->
            drawAnalysisLabel(canvas, w, h, result)
        }
    }

    /**
     * 绘制归一化坐标矩形。
     *
     * @param canvas 画布
     * @param bounds 归一化坐标矩形
     * @param paint 画笔
     * @param label 标签文本
     */
    private fun drawNormalizedRect(
        canvas: Canvas,
        bounds: RectF,
        paint: Paint,
        label: String,
    ) {
        val viewW = this@HUDCanvasView.width.toFloat()
        val viewH = this@HUDCanvasView.height.toFloat()
        val x = bounds.left * viewW
        val y = bounds.top * viewH
        val rectW = bounds.width * viewW
        val rectH = bounds.height * viewH

        // 保存当前画布状态
        canvas.save()

        // 绘制半透明填充
        val fillPaint = Paint(paint).apply {
            style = Paint.Style.FILL
            alpha = 80
        }
        canvas.drawRect(x, y, x + rectW, y + rectH, fillPaint)

        // 绘制边框
        val strokePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 255
        }
        canvas.drawRect(x, y, x + rectW, y + rectH, strokePaint)

        // 绘制标签
        val labelPaint = Paint(textPaint).apply {
            textSize = 10f
            color = Color.WHITE
            alpha = 200
        }
        canvas.drawText(label, x + 2f, y - 2f, labelPaint)

        canvas.restore()
    }

    /**
     * 绘制背景网格线。
     *
     * 水平线和垂直线各 5 条，辅助判断归一化坐标位置。
     */
    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val gridPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#22FFFFFF")
        }

        // 垂直网格线
        for (i in 1..4) {
            val x = w * i / 5f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        // 水平网格线
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    /**
     * 绘制分析结果标签（左上角）。
     *
     * 显示场景模式、角色姿态和跳跃阶段。
     */
    private fun drawAnalysisLabel(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        result: FrameAnalysisResult,
    ) {
        val labelPaint = Paint(textPaint).apply {
            textSize = 9f
            color = Color.parseColor("#AAAAAA")
        }

        val lines = mutableListOf<String>()
        lines.add(result.sceneText)
        lines.add(result.poseText)
        lines.add("跳段 ${result.jumpStage}")

        val lineHeight = 12f
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, 4f, (i + 1) * lineHeight, labelPaint)
        }
    }
}
