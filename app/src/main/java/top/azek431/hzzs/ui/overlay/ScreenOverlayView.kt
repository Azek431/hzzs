package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect as GraphicsRect
import android.view.View
import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.HazardDetail
import top.azek431.hzzs.core.model.RectF
import java.util.concurrent.atomic.AtomicReference

/** Transparent, atomic legacy debug overlay. */
class ScreenOverlayView(context: Context) : View(context) {
    private data class VisualState(
        val bottleFound: Boolean = false,
        val bottleLeft: Int = 0,
        val bottleRight: Int = 0,
        val bottleCenterX: Int = 0,
        val bottleScanY: Int = 0,
        val bottleWidth: Int = 0,
        val bottleConfidence: Float = 0f,
        val bottleCostMs: Double = 0.0,
        val pitFound: Boolean = false,
        val pitLeft: Int = 0,
        val pitRight: Int = 0,
        val pitCenterX: Int = 0,
        val pitScanY: Int = 0,
        val pitWidth: Int = 0,
        val pitEdgeGap: Int = 0,
        val pitConfidence: Float = 0f,
    )

    private val resultRef = AtomicReference<FrameAnalysisResult?>(null)
    private val visualRef = AtomicReference(VisualState())

    private val playerPaint = stroke(Color.argb(210, 255, 80, 100), 2f)
    private val playerDotPaint = fill(Color.argb(220, 255, 80, 100))
    private val hazardOuterPaint = stroke(Color.argb(100, 255, 140, 50), 6f)
    private val hazardInnerPaint = stroke(Color.argb(220, 255, 105, 35), 3f)
    private val hazardFillPaint = fill(Color.argb(52, 255, 70, 40))
    private val leftEdgePaint = stroke(Color.argb(220, 255, 40, 40), 3f)
    private val rightEdgePaint = stroke(Color.argb(220, 255, 230, 0), 3f)
    private val hazardDotPaint = fill(Color.argb(220, 255, 80, 40))
    private val scanPaint = stroke(Color.argb(180, 0, 210, 255), 2f)
    private val bottlePaint = stroke(Color.argb(220, 0, 255, 105), 3f)
    private val bottleFillPaint = fill(Color.argb(42, 0, 255, 90))
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = 14f
    }
    private val smallTextPaint = Paint(textPaint).apply {
        color = Color.argb(190, 255, 255, 255)
        textSize = 11f
    }
    private val labelBgPaint = fill(Color.argb(160, 0, 0, 0))
    private val labelBorderPaint = stroke(Color.WHITE, 1f)

    init {
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun isOpaque(): Boolean = false

    fun updateResult(result: FrameAnalysisResult?) {
        resultRef.set(result)
        postInvalidateOnAnimation()
    }

    fun updateVisualRecognition(
        bottleFound: Boolean,
        bottleLeft: Int,
        bottleRight: Int,
        bottleCenterX: Int,
        bottleScanY: Int,
        bottleWidth: Int,
        bottleConfidence: Float,
        bottleCostMs: Double,
        pitFound: Boolean,
        pitLeft: Int,
        pitRight: Int,
        pitCenterX: Int,
        pitScanY: Int,
        pitWidth: Int,
        pitEdgeGap: Int,
        pitConfidence: Float,
    ) {
        visualRef.set(
            VisualState(
                bottleFound = bottleFound,
                bottleLeft = bottleLeft,
                bottleRight = bottleRight,
                bottleCenterX = bottleCenterX,
                bottleScanY = bottleScanY,
                bottleWidth = bottleWidth,
                bottleConfidence = bottleConfidence.coerceIn(0f, 1f),
                bottleCostMs = bottleCostMs.coerceAtLeast(0.0),
                pitFound = pitFound,
                pitLeft = pitLeft,
                pitRight = pitRight,
                pitCenterX = pitCenterX,
                pitScanY = pitScanY,
                pitWidth = pitWidth,
                pitEdgeGap = pitEdgeGap,
                pitConfidence = pitConfidence.coerceIn(0f, 1f),
            ),
        )
        postInvalidateOnAnimation()
    }

    fun clearResult() {
        resultRef.set(null)
        visualRef.set(VisualState())
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val result = resultRef.get()
        val visual = visualRef.get()

        if (result != null) {
            drawPlayerReference(canvas, result, w, h)
            result.hazardBounds.forEachIndexed { index, bounds ->
                drawHazardBox(canvas, bounds, result.hazardDetails.getOrNull(index), w, h)
            }
        }
        if (visual.bottleFound) drawBottle(canvas, visual, w, h)
        if (visual.pitFound) drawPit(canvas, visual, w, h)
        drawSummaryPanel(canvas, result, visual, h)

        if (result?.hazardsCount == 0 && !visual.bottleFound && !visual.pitFound) {
            val paint = Paint(smallTextPaint).apply { color = Color.argb(200, 255, 220, 80) }
            canvas.drawText("No hazards detected", 8f, h - 16f, paint)
        }
    }

    private fun drawPlayerReference(canvas: Canvas, result: FrameAnalysisResult, w: Float, h: Float) {
        val bounds = result.playerBounds ?: return
        val left = bounds.left * w
        val top = bounds.top * h
        val right = bounds.right * w
        val bottom = bounds.bottom * h
        canvas.drawRect(left, top, right, bottom, playerPaint)
        canvas.drawCircle((left + right) / 2f, (top + bottom) / 2f, 3f, playerDotPaint)
    }

    private fun drawHazardBox(
        canvas: Canvas,
        bounds: RectF,
        detail: HazardDetail?,
        w: Float,
        h: Float,
    ) {
        val left = bounds.left * w
        val top = bounds.top * h
        val right = bounds.right * w
        val bottom = bounds.bottom * h
        canvas.drawRect(left, top, right, bottom, hazardFillPaint)
        canvas.drawRect(left - 2f, top - 2f, right + 2f, bottom + 2f, hazardOuterPaint)
        canvas.drawRect(left, top, right, bottom, hazardInnerPaint)
        canvas.drawLine(left, top, left, bottom, leftEdgePaint)
        canvas.drawLine(right, top, right, bottom, rightEdgePaint)
        canvas.drawCircle((left + right) / 2f, (top + bottom) / 2f, 5f, hazardDotPaint)
        detail?.let {
            drawLabel(
                canvas,
                left - 5f,
                top - 24f,
                "HAZ type=${it.type} W=${(bounds.width * 100).toInt()}% conf=${(it.confidence * 100).toInt()}%",
                hazardInnerPaint.color,
            )
        }
    }

    private fun drawBottle(canvas: Canvas, state: VisualState, w: Float, h: Float) {
        val top = h * 802f / 1536f
        val bottom = h * 1006f / 1536f
        // X must start at the screen edge, not at scanY.
        canvas.drawLine(12f, state.bottleScanY.toFloat(), w - 12f, state.bottleScanY.toFloat(), scanPaint)
        canvas.drawRect(state.bottleLeft.toFloat(), top, state.bottleRight.toFloat(), bottom, bottleFillPaint)
        canvas.drawRect(state.bottleLeft.toFloat(), top, state.bottleRight.toFloat(), bottom, bottlePaint)
        canvas.drawLine(state.bottleLeft.toFloat(), top, state.bottleLeft.toFloat(), bottom, rightEdgePaint)
        canvas.drawLine(state.bottleRight.toFloat(), top, state.bottleRight.toFloat(), bottom, rightEdgePaint)
        canvas.drawCircle(state.bottleCenterX.toFloat(), (top + bottom) / 2f, 5f, bottlePaint)
        drawLabel(
            canvas,
            state.bottleLeft - 80f,
            top - 34f,
            "BOT L=${state.bottleLeft} R=${state.bottleRight} W=${state.bottleWidth} " +
                "conf=${"%.2f".format(state.bottleConfidence)} cost=${"%.2f".format(state.bottleCostMs)}ms",
            bottlePaint.color,
        )
    }

    private fun drawPit(canvas: Canvas, state: VisualState, w: Float, h: Float) {
        // X must start at the screen edge, not at scanY.
        canvas.drawLine(8f, state.pitScanY.toFloat(), w - 8f, state.pitScanY.toFloat(), scanPaint)
        val top = (state.pitScanY - h * .018f).coerceAtLeast(0f)
        val bottom = (state.pitScanY + h * .185f).coerceAtMost(h - 1f)
        canvas.drawRect(state.pitLeft.toFloat(), top, state.pitRight.toFloat(), bottom, hazardFillPaint)
        canvas.drawRect(state.pitLeft.toFloat(), top, state.pitRight.toFloat(), bottom, hazardInnerPaint)
        canvas.drawLine(state.pitLeft.toFloat(), top, state.pitLeft.toFloat(), bottom, leftEdgePaint)
        canvas.drawLine(state.pitRight.toFloat(), top, state.pitRight.toFloat(), bottom, rightEdgePaint)
        canvas.drawCircle(state.pitCenterX.toFloat(), state.pitScanY.toFloat(), 5f, hazardDotPaint)
        // The callback carries no pit timing value. Do not label confidence as milliseconds.
        drawLabel(
            canvas,
            state.pitLeft - 55f,
            top - 34f,
            "PIT L=${state.pitLeft} R=${state.pitRight} W=${state.pitWidth} " +
                "gap=${state.pitEdgeGap}px conf=${"%.2f".format(state.pitConfidence)}",
            Color.argb(220, 255, 170, 40),
        )
    }

    private fun drawSummaryPanel(
        canvas: Canvas,
        result: FrameAnalysisResult?,
        state: VisualState,
        h: Float,
    ) {
        var y = 34f
        val lines = buildList {
            add("HZZS Engine")
            result?.let {
                add("场景: ${it.sceneText}")
                add("姿态: ${it.poseText}")
                add("跳段: ${it.jumpStage}")
                add("危险物: ${it.hazardsCount}")
            }
            add("绿瓶: ${if (state.bottleFound) "✓" else "✗"} ${(state.bottleConfidence * 100).toInt()}%")
            add("坑位: ${if (state.pitFound) "✓" else "✗"} ${(state.pitConfidence * 100).toInt()}%")
        }
        val maxWidth = lines.maxOfOrNull(textPaint::measureText) ?: 0f
        canvas.drawRoundRect(4f, 8f, maxWidth + 20f, (lines.size * 16f + 22f).coerceAtMost(h - 8f), 8f, 8f, labelBgPaint)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 10f, y, if (index == 0) textPaint else smallTextPaint)
            y += 16f
        }
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, text: String, accentColor: Int) {
        val paint = Paint(textPaint).apply { color = accentColor }
        val boxWidth = paint.measureText(text) + 16f
        val boxHeight = 22f
        val clampedX = x.coerceIn(8f, (width - boxWidth - 8f).coerceAtLeast(8f))
        val clampedY = y.coerceIn(8f, (height - boxHeight - 8f).coerceAtLeast(8f))
        val rect = GraphicsRect(
            (clampedX - 8f).toInt(),
            (clampedY - 2f).toInt(),
            (clampedX + boxWidth).toInt(),
            (clampedY + boxHeight).toInt(),
        )
        canvas.drawRect(rect, labelBgPaint)
        labelBorderPaint.color = accentColor
        canvas.drawRect(rect, labelBorderPaint)
        canvas.drawText(text, clampedX, clampedY + 14f, paint)
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
}
