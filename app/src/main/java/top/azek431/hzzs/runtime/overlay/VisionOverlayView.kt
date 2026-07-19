package top.azek431.hzzs.runtime.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import top.azek431.hzzs.runtime.vision.VisionAppearance
import top.azek431.hzzs.runtime.vision.VisionSceneState
import java.util.concurrent.atomic.AtomicReference

class VisionOverlayView(context: Context) : View(context) {
    private val state = AtomicReference(VisionOverlayState())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }

    init {
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun isOpaque(): Boolean = false

    fun update(value: VisionOverlayState) {
        state.set(value)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state.get()
        val result = s.result
        val frame = s.frame

        val textSize = (width / 40f).coerceIn(14f, 22f)
        val primary = result?.primary
        val essentialLines = mutableListOf(
            "HZZS · ${s.algorithm.displayName}",
            "CAPTURE ${s.captureMode}",
            "STATE ${s.status}",
            "TARGET ${primary?.appearance?.displayName() ?: "--"}/${primary?.sizeClass ?: "--"}",
            "DIST ${primary?.distanceP?.let { String.format("%.2fP", it) } ?: "--"}",
            "ACTION ${s.lastAction}",
        )
        if (s.detailed) {
            essentialLines += "SCENE ${result?.sceneState ?: VisionSceneState.UNSAFE} PLAYER ${result?.playerConfidence?.let { String.format("%.0f%%", it * 100f) } ?: "--"}"
            essentialLines += "NATIVE ${result?.nativeCostMs?.let { String.format("%.2fms", it) } ?: "--"} TOTAL ${String.format("%.2fms", s.totalCostMs)} FPS ${String.format("%.1f", s.fps)}"
        }

        val lineHeight = textSize * 1.28f
        val panelTop = height * 0.045f
        val panelHeight = (lineHeight * (essentialLines.size + 1.2f)).coerceAtMost(height * 0.62f)
        val panel = RectF(
            12f,
            panelTop,
            (width * 0.72f).coerceAtMost(width - 12f),
            panelTop + panelHeight,
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 8, 15, 25)
        canvas.drawRoundRect(panel, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(0, 190, 255)
        canvas.drawRoundRect(panel, 16f, 16f, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = textSize
        paint.color = Color.WHITE
        essentialLines.forEachIndexed { index, text ->
            val baseline = panel.top + lineHeight * (1.15f + index)
            if (baseline <= panel.bottom - 6f) canvas.drawText(text, 24f, baseline, paint)
        }

        if (result != null && frame != null && result.sceneState == VisionSceneState.RUNNING) {
            drawResult(canvas, s)
        }

        paint.textSize = (width / 44f).coerceIn(12f, 18f)
        paint.color = Color.rgb(185, 246, 255)
        canvas.drawText("QQ：130330601  TG：@AzekMain", 12f, height - 12f, paint)
    }

    private fun drawResult(canvas: Canvas, state: VisionOverlayState) {
        val result = state.result ?: return
        val frame = state.frame ?: return
        val vx = frame.viewport.left
        val vy = frame.viewport.top
        val scaleX = frame.scaleToSourceX
        val scaleY = frame.scaleToSourceY

        fun sx(x: Float): Float = vx + x * scaleX
        fun sy(y: Float): Float = vy + y * scaleY

        if (result.playerWidth > 0 && result.playerConfidence >= 0.45f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.rgb(110, 168, 255)
            val pHalf = result.playerWidth * scaleX * 0.82f
            canvas.drawRect(
                sx(result.playerLeft.toFloat()),
                sy(result.playerCenterY.toFloat()) - pHalf,
                sx(result.playerRight.toFloat()),
                sy(result.playerCenterY.toFloat()) + pHalf,
                paint,
            )

            paint.color = Color.rgb(167, 199, 255)
            paint.strokeWidth = 2f
            val triggerX = sx(result.playerRight + result.playerWidth * 1.5f)
            canvas.drawLine(
                triggerX,
                vy + frame.viewport.height() * 0.38f,
                triggerX,
                vy + frame.viewport.height() * 0.84f,
                paint,
            )
        }

        result.detections.forEach { detection ->
            paint.color = detection.appearance.color()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (detection.actionable) 3f else 1.5f
            paint.alpha = if (detection.actionable) 255 else 125
            val pad = 6f
            canvas.drawRect(
                sx(detection.bounds.left) - pad,
                sy(detection.bounds.top) - pad,
                sx(detection.bounds.right) + pad,
                sy(detection.bounds.bottom) + pad,
                paint,
            )
            paint.alpha = 255
        }
    }

    private fun VisionAppearance.displayName(): String = when (this) {
        VisionAppearance.SWEET_BOTTLE -> "绿瓶"
        VisionAppearance.SWEET_CAKE_GAP -> "蛋糕坑位"
        VisionAppearance.SWEET_PIPING_BAG -> "裱花袋"
        VisionAppearance.BAMBOO_PANDA_STATUE -> "熊猫石像"
        VisionAppearance.BAMBOO_PIT -> "木框深坑"
        VisionAppearance.BAMBOO_BRUSH -> "悬挂毛笔"
        VisionAppearance.UNKNOWN -> "未知"
    }

    private fun VisionAppearance.color(): Int = when (this) {
        VisionAppearance.SWEET_BOTTLE -> Color.rgb(0, 200, 255)
        VisionAppearance.SWEET_CAKE_GAP -> Color.rgb(255, 59, 157)
        VisionAppearance.SWEET_PIPING_BAG -> Color.rgb(194, 120, 255)
        VisionAppearance.BAMBOO_PANDA_STATUE -> Color.rgb(105, 190, 255)
        VisionAppearance.BAMBOO_PIT -> Color.rgb(255, 164, 64)
        VisionAppearance.BAMBOO_BRUSH -> Color.rgb(180, 110, 255)
        VisionAppearance.UNKNOWN -> Color.rgb(255, 215, 64)
    }
}
