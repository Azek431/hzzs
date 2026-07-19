package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.View
import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.RectF

/** Transparent, allocation-aware HUD canvas. */
class HUDCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = HUDColorPalette.PLAYER_GREEN
    }
    private val hazardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = HUDColorPalette.HAZARD_RED
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = HUDColorPalette.GRID_LINE
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f
        color = HUDColorPalette.TEXT_WHITE
    }
    private val trajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = HUDColorPalette.TRAJECTORY_GREEN
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = HUDColorPalette.PATH_BLUE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val dangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
        color = HUDColorPalette.DANGER_RED
    }
    private val heatmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    @Volatile var playerBounds: RectF? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    @Volatile var hazards: List<RectF> = emptyList()
        set(value) { field = value.toList(); postInvalidateOnAnimation() }
    @Volatile var analysisResult: FrameAnalysisResult? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    @Volatile var trajectoryPoints: List<PointF> = emptyList()
        set(value) { field = value.map { PointF(it.x, it.y) }; postInvalidateOnAnimation() }
    @Volatile var predictedPath: List<PointF> = emptyList()
        set(value) { field = value.map { PointF(it.x, it.y) }; postInvalidateOnAnimation() }
    @Volatile var dangerZone: RectF? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    @Volatile var confidenceLevel: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); postInvalidateOnAnimation() }
    @Volatile var actionTimerMs: Float = 0f
        set(value) { field = value.coerceAtLeast(0f); postInvalidateOnAnimation() }

    data class HeatmapPoint(val x: Float, val y: Float, val intensity: Float)

    @Volatile var heatmapPoints: List<HeatmapPoint> = emptyList()
        set(value) { field = value.toList(); postInvalidateOnAnimation() }

    private var flashPhase = 0f
    private var offscreenBitmap: Bitmap? = null
    private var offscreenCanvas: Canvas? = null

    init {
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
    }

    override fun isOpaque(): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        releaseBuffer()
        if (w > 0 && h > 0) {
            offscreenBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            offscreenCanvas = Canvas(requireNotNull(offscreenBitmap))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        ensureBuffer(width, height)
        val bufferCanvas = offscreenCanvas ?: return
        val bitmap = offscreenBitmap ?: return
        // Clear to transparent. The previous opaque BACKGROUND caused full-view covering.
        bufferCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawAll(bufferCanvas, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun onDetachedFromWindow() {
        releaseBuffer()
        super.onDetachedFromWindow()
    }

    private fun ensureBuffer(w: Int, h: Int) {
        val bitmap = offscreenBitmap
        if (bitmap == null || bitmap.width != w || bitmap.height != h || bitmap.isRecycled) {
            releaseBuffer()
            if (w > 0 && h > 0) {
                offscreenBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                offscreenCanvas = Canvas(requireNotNull(offscreenBitmap))
            }
        }
    }

    private fun releaseBuffer() {
        offscreenCanvas = null
        offscreenBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        offscreenBitmap = null
    }

    private fun drawAll(canvas: Canvas, w: Float, h: Float) {
        canvas.drawGrid(w, h, gridPaint)
        if (heatmapPoints.isNotEmpty()) {
            canvas.drawHeatmap(
                w,
                h,
                heatmapPoints.map { HUDHeatmapPoint(it.x, it.y, it.intensity) },
                heatmapPaint,
            )
        }
        hazards.forEach { drawNormalizedRect(canvas, it, hazardPaint, "危险", w, h) }
        playerBounds?.let { drawNormalizedRect(canvas, it, playerPaint, "玩家", w, h) }
        dangerZone?.let { flashPhase = canvas.drawDangerZone(w, h, it, dangerPaint, textPaint, flashPhase) }
        canvas.drawTrajectory(w, h, trajectoryPoints, trajectoryPaint)
        canvas.drawPredictedPath(w, h, predictedPath, pathPaint)
        analysisResult?.let {
            canvas.drawAnalysisLabel(canvas, it, 12f, Paint(textPaint).apply { textSize = 9f })
        }
        canvas.drawConfidenceIndicator(confidenceLevel, indicatorBgPaint, indicatorProgressPaint, textPaint)
        if (actionTimerMs > 0f) {
            canvas.drawActionTimer(w, h, actionTimerMs, 2_000f, timerBgPaint, textPaint)
        }
    }

    private fun drawNormalizedRect(
        canvas: Canvas,
        bounds: RectF,
        paint: Paint,
        label: String,
        viewW: Float,
        viewH: Float,
    ) {
        canvas.drawNormalizedRect(canvas, bounds, paint, label, viewW, viewH, textPaint)
    }
}
