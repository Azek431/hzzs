// 火崽崽助手（HZZS）HUD 自定义 Canvas 绘制视图。
//
// 用于在悬浮窗中模拟/显示游戏画面，标注玩家矩形、危险物矩形等。
// 所有坐标使用归一化值（0.0 ~ 1.0），自动适配视图尺寸。
//
// 可视化增强：
// - 运动轨迹线（渐隐虚线）
// - 预测路径（蓝色虚线箭头）
// - 危险区高亮（红色闪烁边框）
// - 置信度圆环指示器（绿→黄→红）
// - 动作倒计时条（弧形进度条）
// - 热力图（径向渐变叠加点）
//
// 性能优化：
// - Canvas 双缓冲（离屏 Bitmap 渲染）
// - Paint 对象池复用

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import top.azek431.hzzs.model.FrameAnalysisResult
import top.azek431.hzzs.model.RectF

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

    /** 文字画笔：白色 */
    private val textPaint = Paint().apply {
        textSize = 12f
        color = Color.WHITE
        isAntiAlias = true
    }

    // === 新增可视化画笔 ===

    /** 轨迹线画笔：绿色虚线，尾部渐变透明度 */
    private val trajectoryPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4CAF50")
        isAntiAlias = true
    }

    /** 预测路径画笔：蓝色虚线 + 箭头 */
    private val pathPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#2196F3")
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    /** 危险区画笔：红色半透明填充 + 边框 */
    private val dangerPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F44336")
        isAntiAlias = true
    }

    /** 热力图画笔：径向渐变 */
    private val heatmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    // === 新增可视化数据 ===

    /** 玩家运动轨迹点（最近 N 帧的位置） */
    var trajectoryPoints: List<PointF> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 预测路径（抛物线或直线） */
    var predictedPath: List<PointF> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 危险区域高亮 */
    var dangerZone: RectF? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** 整体置信度 (0.0 ~ 1.0) */
    var confidenceLevel: Float = 1.0f
        set(value) {
            field = value
            postInvalidate()
        }

    /** 动作倒计时 (毫秒) */
    var actionTimerMs: Float = 0f
        set(value) {
            field = value
            postInvalidate()
        }

    /** 热力图数据点 */
    data class HeatmapPoint(val x: Float, val y: Float, val intensity: Float)
    var heatmapPoints: List<HeatmapPoint> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 闪烁计数器（用于危险区闪烁效果） */
    private var flashPhase = 0f

    // ==================== 双缓冲 ====================

    private var offscreenBitmap: Bitmap? = null
    private var offscreenCanvas: Canvas? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            offscreenBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            offscreenCanvas = Canvas(offscreenBitmap!!)
        }
    }

    // ==================== 生命周期 ====================

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 使用离屏 Canvas 绘制所有内容
        val dc = offscreenCanvas ?: return

        drawAll(dc, w, h)

        // 最后一次性输出到屏幕
        canvas.drawBitmap(offscreenBitmap!!, 0f, 0f, null)
    }

    /** 在指定 Canvas 上绘制所有内容 */
    private fun drawAll(c: Canvas, w: Float, h: Float) {
        // 1. 绘制背景
        c.drawColor(Color.parseColor("#1A1A2E"))

        // 2. 绘制网格线
        drawGrid(c, w, h)

        // 3. 绘制热力图（底层）
        drawHeatmap(c, w, h)

        // 4. 绘制危险物矩形
        for (hazard in hazards) {
            drawNormalizedRect(c, hazard, hazardPaint, "危险")
        }

        // 5. 绘制玩家矩形
        playerBounds?.let { bounds ->
            drawNormalizedRect(c, bounds, playerPaint, "玩家")
        }

        // 6. 绘制危险区域高亮
        drawDangerZone(c, w, h)

        // 7. 绘制运动轨迹
        drawTrajectory(c, w, h)

        // 8. 绘制预测路径
        drawPredictedPath(c, w, h)

        // 9. 绘制分析结果标签
        analysisResult?.let { result ->
            drawAnalysisLabel(c, w, h, result)
        }

        // 10. 绘制置信度指示器
        drawConfidenceIndicator(c, w, h)

        // 11. 绘制动作倒计时
        drawActionTimer(c, w, h)
    }

    // ==================== 可视化绘制方法 ====================

    /** 绘制玩家运动轨迹 */
    private fun drawTrajectory(c: Canvas, w: Float, h: Float) {
        if (trajectoryPoints.size < 2) return

        val pointCount = trajectoryPoints.size
        for (i in 0 until pointCount - 1) {
            val p1 = trajectoryPoints[i]
            val p2 = trajectoryPoints[i + 1]

            // 透明度：最新的点 0.6，最旧的点 0.1
            val alpha = (0.1f + 0.5f * i / (pointCount - 1)).toInt()

            val paint = Paint(trajectoryPaint).apply {
                this.alpha = alpha
                strokeWidth = 1f + 2f * i / (pointCount - 1)
            }

            c.drawLine(p1.x * w, p1.y * h, p2.x * w, p2.y * h, paint)
        }
    }

    /** 绘制预测路径 */
    private fun drawPredictedPath(c: Canvas, w: Float, h: Float) {
        if (predictedPath.size < 2) return

        val path = Path()
        val first = predictedPath[0]
        path.moveTo(first.x * w, first.y * h)

        for (i in 1 until predictedPath.size) {
            val p = predictedPath[i]
            path.lineTo(p.x * w, p.y * h)
        }

        c.drawPath(path, pathPaint)

        // 在末端绘制箭头
        val last = predictedPath.last()
        drawArrow(c, last.x * w, last.y * h, pathPaint)
    }

    /** 绘制箭头 */
    private fun drawArrow(c: Canvas, x: Float, y: Float, paint: Paint) {
        val arrowSize = 8f
        val angle = Math.PI.toFloat() / 4f  // 右下方向
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(
            x - arrowSize * cos(angle - Math.PI.toFloat() / 6f),
            y - arrowSize * sin(angle - Math.PI.toFloat() / 6f)
        )
        p.moveTo(x, y)
        p.lineTo(
            x - arrowSize * cos(angle + Math.PI.toFloat() / 6f),
            y - arrowSize * sin(angle + Math.PI.toFloat() / 6f)
        )
        c.drawPath(p, paint)
    }

    /** 绘制危险区域高亮 */
    private fun drawDangerZone(c: Canvas, w: Float, h: Float) {
        val zone = dangerZone ?: return

        // 更新闪烁相位
        flashPhase += 0.05f
        val alpha = (60 + 80 * Math.sin(flashPhase.toDouble())).toInt().coerceIn(60, 200)

        // 填充
        val fillPaint = Paint(dangerPaint).apply {
            this.alpha = alpha / 3
            style = Paint.Style.FILL
        }
        c.drawRect(zone.left * w, zone.top * h, zone.right * w, zone.bottom * h, fillPaint)

        // 边框
        val strokePaint = Paint(dangerPaint).apply {
            this.alpha = alpha
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        c.drawRect(zone.left * w, zone.top * h, zone.right * w, zone.bottom * h, strokePaint)

        // 中心警告图标
        val labelPaint = Paint(textPaint).apply {
            textSize = 16f
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
        }
        c.drawText("!", (zone.left + zone.right) / 2 * w, (zone.top + zone.bottom) / 2 * h + 5f, labelPaint)
    }

    /** 绘制置信度圆环指示器 */
    private fun drawConfidenceIndicator(c: Canvas, w: Float, h: Float) {
        val radius = 12f
        val cx = radius + 4f
        val cy = radius + 4f
        var strokeWidth = 3f

        // 背景圆环
        val bgPaint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = Color.parseColor("#44FFFFFF")
            isAntiAlias = true
        }
        c.drawCircle(cx, cy, radius, bgPaint)

        // 进度圆环
        val progressPaint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        val color = when {
            confidenceLevel >= 0.9f -> Color.parseColor("#4CAF50")
            confidenceLevel >= 0.7f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#F44336")
        }
        progressPaint.color = color

        val sweepAngle = confidenceLevel * 360f
        c.drawArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            -90f, sweepAngle, false, progressPaint
        )

        // 中心文字
        val textP = Paint(textPaint).apply {
            this.textSize = 8f
            this.color = Color.WHITE
            this.textAlign = Paint.Align.CENTER
        }
        c.drawText("${(confidenceLevel * 100).toInt()}%", cx, cy + 3f, textP)
    }

    /** 绘制动作倒计时条 */
    private fun drawActionTimer(c: Canvas, w: Float, h: Float) {
        if (actionTimerMs <= 0f) return

        val maxEta = 2000f
        val progress = (actionTimerMs / maxEta).coerceIn(0f, 1f)

        val radius = 16f
        val cx = w - radius - 4f
        val cy = h - radius - 4f
        val strokeWidth = 4f

        // 背景弧
        val bgPaint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = Color.parseColor("#33FFFFFF")
            isAntiAlias = true
        }
        c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 0f, 360f, false, bgPaint)

        // 进度弧
        val progressPaint = Paint(bgPaint).apply {
            color = when {
                progress > 0.5f -> Color.parseColor("#4CAF50")
                progress > 0.25f -> Color.parseColor("#FFC107")
                else -> Color.parseColor("#F44336")
            }
        }
        c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, progress * 360f, false, progressPaint)

        // 时间文字
        var textP = Paint(textPaint).apply {
            textSize = 9f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        c.drawText("${actionTimerMs.toInt()}ms", cx, cy + 3f, textP)
    }

    /** 绘制热力图 */
    private fun drawHeatmap(c: Canvas, w: Float, h: Float) {
        if (heatmapPoints.isEmpty()) return

        for (point in heatmapPoints) {
            val px = point.x * w
            val py = point.y * h
            val radius = 20f * point.intensity

            val colors = intArrayOf(
                Color.parseColor("#FF9800"),
                Color.parseColor("#F44336")
            )
            val gradient = RadialGradient(px, py, radius, colors, null, Shader.TileMode.CLAMP)

            heatmapPaint.shader = gradient
            heatmapPaint.alpha = (point.intensity * 120).toInt()

            c.drawCircle(px, py, radius, heatmapPaint)
        }

        heatmapPaint.shader = null
    }

    // ==================== 原有绘制方法 ====================

    /** 绘制归一化坐标矩形 */
    private fun drawNormalizedRect(
        c: Canvas,
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

        c.save()

        // 半透明填充
        val fillPaint = Paint(paint).apply {
            style = Paint.Style.FILL
            alpha = 80
        }
        c.drawRect(x, y, x + rectW, y + rectH, fillPaint)

        // 描边
        val strokePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 255
        }
        c.drawRect(x, y, x + rectW, y + rectH, strokePaint)

        // 标签
        val labelPaint = Paint(textPaint).apply {
            textSize = 10f
            color = Color.WHITE
            alpha = 200
        }
        c.drawText(label, x + 2f, y - 2f, labelPaint)

        c.restore()
    }

    /** 绘制背景网格线 */
    private fun drawGrid(c: Canvas, w: Float, h: Float) {
        val gp = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#22FFFFFF")
        }

        for (i in 1..4) {
            val x = w * i / 5f
            c.drawLine(x, 0f, x, h, gp)
        }

        for (i in 1..3) {
            val y = h * i / 4f
            c.drawLine(0f, y, w, y, gp)
        }
    }

    /** 绘制分析结果标签（左上角） */
    private fun drawAnalysisLabel(
        c: Canvas,
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
            c.drawText(line, 4f, (i + 1) * lineHeight, labelPaint)
        }
    }
}
