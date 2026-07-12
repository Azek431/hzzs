package top.azek431.hzzs.runtime.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import top.azek431.hzzs.runtime.capture.CapturePreferences
import top.azek431.hzzs.runtime.vision.VisionObjectType
import java.util.concurrent.atomic.AtomicReference

class VisionOverlayView(context: Context) : View(context) {
    private val state = AtomicReference(VisionOverlayState())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    fun update(value: VisionOverlayState) { state.set(value); postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state.get(); val result = s.result; val frame = s.frame
        val panel = RectF(12f, height * .055f, width * .68f, height * .29f)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(210, 8, 15, 25); canvas.drawRoundRect(panel, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(0, 190, 255); canvas.drawRoundRect(panel, 16f, 16f, paint)
        paint.style = Paint.Style.FILL; paint.textSize = (width / 36f).coerceIn(15f, 24f); paint.color = Color.WHITE
        val primary = result?.primary
        val essentialLines = listOf(
            "HZZS C++ VISION · ${s.captureMode}",
            "STATE  ${s.status}",
            "TARGET ${primary?.type ?: VisionObjectType.NONE}/${primary?.sizeClass ?: "--"}",
            "DIST   ${primary?.distanceP?.let { String.format("%.2fP", it) } ?: "--"} / 1.50P",
            "ACTION ${s.lastAction}",
        )
        val lines = if (s.detailed) {
            essentialLines + "NATIVE ${result?.nativeCostMs?.let { String.format("%.2fms", it) } ?: "--"}  TOTAL ${String.format("%.2fms", s.totalCostMs)}  FPS ${String.format("%.1f", s.fps)}"
        } else {
            essentialLines
        }
        lines.forEachIndexed { i, text -> canvas.drawText(text, 24f, panel.top + paint.textSize * (1.35f + i * 1.25f), paint) }
        if (result != null && frame != null) drawResult(canvas, s)
        paint.textSize = (width / 38f).coerceIn(14f, 22f); paint.color = Color.rgb(185, 246, 255)
        canvas.drawText("QQ：130330601   TG：@AzekMain", 12f, height - 12f, paint)
    }

    private fun drawResult(canvas: Canvas, state: VisionOverlayState) {
        val result = state.result ?: return; val frame = state.frame ?: return
        val vx = frame.viewport.left; val vy = frame.viewport.top
        val scaleX = frame.scaleToSourceX; val scaleY = frame.scaleToSourceY
        fun sx(x: Float) = vx + x * scaleX
        fun sy(y: Float) = vy + y * scaleY
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        paint.color = Color.rgb(110, 168, 255)
        val pHalf = result.playerWidth * scaleX * .82f
        canvas.drawRect(sx(result.playerLeft.toFloat()), sy(result.playerCenterY.toFloat()) - pHalf, sx(result.playerRight.toFloat()), sy(result.playerCenterY.toFloat()) + pHalf, paint)
        paint.color = Color.rgb(167, 199, 255); paint.strokeWidth = 2f
        val triggerX = sx(result.playerRight + result.playerWidth * 1.5f)
        canvas.drawLine(triggerX, vy + frame.viewport.height() * .38f, triggerX, vy + frame.viewport.height() * .84f, paint)
        result.detections.forEach { d ->
            paint.color = when (d.type) {
                VisionObjectType.POISON_BOTTLE -> Color.rgb(0, 200, 255)
                VisionObjectType.CAKE_STRUCTURE -> Color.rgb(255, 59, 157)
                VisionObjectType.HANGING_SPIKE -> Color.rgb(194, 120, 255)
                else -> Color.WHITE
            }
            val pad = 6f
            canvas.drawRect(sx(d.bounds.left) - pad, sy(d.bounds.top) - pad, sx(d.bounds.right) + pad, sy(d.bounds.bottom) + pad, paint)
        }
    }
}
