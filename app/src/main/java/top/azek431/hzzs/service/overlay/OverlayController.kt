package top.azek431.hzzs.service.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.azek431.hzzs.core.model.OverlayConfig
import top.azek431.hzzs.core.model.OverlayOrientation
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.OverlayTheme
import top.azek431.hzzs.domain.vision.Detection
import top.azek431.hzzs.domain.vision.VisionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Main-thread-owned persistent overlay.
 *
 * Click-through mode uses a full-screen, non-touchable Canvas so detection boxes
 * never block the game. Interactive mode intentionally switches to a small HUD
 * window that can be dragged and snapped to an edge; drawing a touchable
 * full-screen window would swallow every game gesture.
 */
@Singleton
class OverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: VisionOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentConfig = OverlayConfig()
    private var positionX = 12
    private var positionY = 96

    suspend fun show(
        config: OverlayConfig,
        result: VisionResult?,
        showCoordinateGrid: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!config.enabled || !Settings.canDrawOverlays(context)) {
                hideInternal()
                return@withContext false
            }

            currentConfig = config
            val nextParams = createLayoutParams(config)
            val current = view
            if (current == null) {
                val created = VisionOverlayView(context, ::moveInteractiveWindow)
                if (runCatching { windowManager.addView(created, nextParams) }.isFailure) {
                    return@withContext false
                }
                view = created
                layoutParams = nextParams
                created.update(config, result, showCoordinateGrid)
            } else {
                val previous = layoutParams
                if (previous == null || previous.layoutSignature() != nextParams.layoutSignature()) {
                    if (runCatching { windowManager.updateViewLayout(current, nextParams) }.isFailure) {
                        hideInternal()
                        return@withContext false
                    }
                    layoutParams = nextParams
                }
                current.update(config, result, showCoordinateGrid)
            }
            true
        }

    suspend fun hide() = withContext(Dispatchers.Main.immediate) { hideInternal() }

    private fun hideInternal() {
        view?.let { current -> runCatching { windowManager.removeViewImmediate(current) } }
        view = null
        layoutParams = null
    }

    private fun createLayoutParams(config: OverlayConfig): WindowManager.LayoutParams {
        val interactive = !config.clickThrough
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (interactive) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            })
        return WindowManager.LayoutParams(
            if (interactive) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            if (interactive) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (interactive) positionX else 0
            y = if (interactive) positionY else 0
        }
    }

    private fun moveInteractiveWindow(deltaX: Int, deltaY: Int, released: Boolean) {
        val current = view ?: return
        val params = layoutParams ?: return
        if (currentConfig.clickThrough || currentConfig.lockPosition) return

        val metrics = context.resources.displayMetrics
        val maxX = max(0, metrics.widthPixels - current.width)
        val maxY = max(0, metrics.heightPixels - current.height)
        positionX = (positionX + deltaX).coerceIn(0, maxX)
        positionY = (positionY + deltaY).coerceIn(0, maxY)
        if (released && currentConfig.snapToEdge) {
            positionX = if (positionX + current.width / 2 < metrics.widthPixels / 2) 0 else maxX
        }
        params.x = positionX
        params.y = positionY
        runCatching { windowManager.updateViewLayout(current, params) }
    }

    private fun WindowManager.LayoutParams.layoutSignature(): List<Int> =
        listOf(width, height, type, flags, x, y)
}

private class VisionOverlayView(
    context: Context,
    private val onMove: (deltaX: Int, deltaY: Int, released: Boolean) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.BOLD,
        )
    }

    private var config = OverlayConfig()
    private var result: VisionResult? = null
    private var showCoordinateGrid = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    fun update(config: OverlayConfig, result: VisionResult?, showCoordinateGrid: Boolean) {
        val sizeMayChange = this.config.style != config.style ||
            this.config.orientation != config.orientation ||
            this.config.scale != config.scale ||
            this.config.textScale != config.textScale ||
            this.config.clickThrough != config.clickThrough
        this.config = config
        this.result = result
        this.showCoordinateGrid = showCoordinateGrid
        if (sizeMayChange) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (config.clickThrough) {
            setMeasuredDimension(
                View.MeasureSpec.getSize(widthMeasureSpec),
                View.MeasureSpec.getSize(heightMeasureSpec),
            )
            return
        }
        val scale = config.scale.coerceIn(0.6f, 2f)
        val vertical = config.orientation == OverlayOrientation.VERTICAL
        val desiredWidthDp = when (config.style) {
            OverlayStyle.MINIMAL -> if (vertical) 76f else 106f
            OverlayStyle.COMPACT -> if (vertical) 160f else 300f
            OverlayStyle.DEBUG_HUD -> if (vertical) 220f else 360f
        }
        val desiredHeightDp = when (config.style) {
            OverlayStyle.MINIMAL -> if (vertical) 76f else 54f
            OverlayStyle.COMPACT -> if (vertical) 126f else 66f
            OverlayStyle.DEBUG_HUD -> if (vertical) 180f else 92f
        }
        setMeasuredDimension(
            resolveSize((desiredWidthDp * density * scale).toInt(), widthMeasureSpec),
            resolveSize((desiredHeightDp * density * scale).toInt(), heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (config.clickThrough || config.lockPosition) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                lastRawX = event.rawX
                lastRawY = event.rawY
                onMove(dx, dy, false)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onMove(0, 0, true)
                performClick()
                true
            }
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val current = result ?: return
        val accent = accentColor(config)
        val scale = config.scale.coerceIn(0.6f, 2f)
        val strokeWidth = config.strokeWidthDp * density * scale
        stroke.strokeWidth = strokeWidth
        text.textSize = 12f * density * config.textScale * scale

        if (showCoordinateGrid && config.clickThrough) {
            drawCoordinateGrid(canvas, accent, scale)
        }

        // Full-screen boxes are only safe in non-touchable mode.
        if (config.showBoxes && config.clickThrough) {
            current.player?.takeIf { config.style == OverlayStyle.DEBUG_HUD }?.let {
                drawDetection(canvas, it, Color.WHITE, strokeWidth, "玩家")
            }
            current.detections
                .asSequence()
                .filter { config.showDiagnostics || !it.diagnosticOnly }
                .forEach { detection ->
                    val color = if (detection.actionable) accent else withAlpha(accent, 145)
                    drawDetection(canvas, detection, color, strokeWidth, detection.kind.name)
                }
        }

        when (config.style) {
            OverlayStyle.MINIMAL -> drawMinimalHud(canvas, current, accent, scale)
            OverlayStyle.COMPACT -> drawCompactHud(canvas, current, accent, scale)
            OverlayStyle.DEBUG_HUD -> drawDebugHud(canvas, current, accent, scale)
        }
    }

    private fun drawCoordinateGrid(canvas: Canvas, accent: Int, scale: Float) {
        stroke.color = withAlpha(accent, 100)
        stroke.strokeWidth = (1f * density * scale).coerceAtLeast(1f)
        for (step in 1 until 10) {
            val ratio = step / 10f
            val x = width * ratio
            val y = height * ratio
            canvas.drawLine(x, 0f, x, height.toFloat(), stroke)
            canvas.drawLine(0f, y, width.toFloat(), y, stroke)
        }
        if (config.showText) {
            text.color = withAlpha(readableTextColor(), 180)
            text.textSize = 10f * density * config.textScale * scale
            for (step in 1 until 10) {
                val ratio = step / 10f
                canvas.drawText("%.1f".format(ratio), width * ratio + 2f, text.textSize, text)
                canvas.drawText("%.1f".format(ratio), 2f, height * ratio - 2f, text)
            }
        }
    }

    private fun drawMinimalHud(canvas: Canvas, result: VisionResult, accent: Int, scale: Float) {
        val radius = 7f * density * scale
        val x = 16f * density * scale
        val y = 20f * density * scale
        fill.color = accent
        canvas.drawCircle(x, y, radius, fill)
        if (config.showText) {
            text.color = readableTextColor()
            canvas.drawText("${result.detections.size}", x + radius * 1.8f, y + text.textSize * 0.35f, text)
        }
    }

    private fun drawCompactHud(canvas: Canvas, result: VisionResult, accent: Int, scale: Float) {
        val scene = if (result.scene.name.contains("BAMBOO")) "竹影书屋" else "甜甜圈"
        val parts = mutableListOf(scene, "障碍 ${result.detections.size}")
        if (config.showConfidence) parts += "置信度 ${(result.sceneConfidence * 100f).toInt()}%"
        if (config.showFps && result.processingNanos > 0) {
            val nativeFps = (1_000_000_000.0 / result.processingNanos).coerceAtMost(999.0)
            parts += "Native ${"%.0f".format(nativeFps)} fps"
        }
        drawHudPanel(canvas, oriented(parts), accent, scale)
    }

    private fun drawDebugHud(canvas: Canvas, result: VisionResult, accent: Int, scale: Float) {
        val parts = mutableListOf(
            result.scene.name,
            "objects=${result.detections.size}",
            "confidence=${(result.sceneConfidence * 100f).toInt()}%",
            "native=${"%.2f".format(result.processingNanos / 1_000_000.0)}ms",
        )
        result.error?.takeIf(String::isNotBlank)?.let { parts += "error=${it.take(48)}" }
        drawHudPanel(canvas, oriented(parts), accent, scale)
    }

    private fun oriented(parts: List<String>): List<String> =
        if (config.orientation == OverlayOrientation.VERTICAL) parts else listOf(parts.joinToString("  "))

    private fun drawHudPanel(canvas: Canvas, lines: List<String>, accent: Int, scale: Float) {
        if (!config.showText || lines.isEmpty()) return
        val padding = 8f * density * scale
        val left = 10f * density * scale
        val top = 10f * density * scale
        val lineGap = 4f * density * scale
        val width = lines.maxOf { line -> text.measureText(line) } + padding * 2f
        val height = lines.size * text.textSize + (lines.size - 1) * lineGap + padding * 2f
        fill.color = panelColor(config)
        canvas.drawRoundRect(left, top, left + width, top + height, 12f * density, 12f * density, fill)
        fill.color = accent
        canvas.drawRoundRect(left, top, left + 4f * density, top + height, 4f * density, 4f * density, fill)
        text.color = readableTextColor()
        lines.forEachIndexed { index, line ->
            val baseline = top + padding + text.textSize + index * (text.textSize + lineGap)
            canvas.drawText(line, left + padding, baseline, text)
        }
    }

    private fun drawDetection(
        canvas: Canvas,
        detection: Detection,
        color: Int,
        strokeWidth: Float,
        label: String,
    ) {
        val bounds = detection.bounds
        val left = bounds.left * width
        val top = bounds.top * height
        val right = bounds.right * width
        val bottom = bounds.bottom * height
        stroke.color = color
        stroke.strokeWidth = strokeWidth
        canvas.drawRect(left, top, right, bottom, stroke)
        if (config.showConfidence || config.style == OverlayStyle.DEBUG_HUD) {
            val caption = "$label ${(detection.confidence * 100f).toInt()}%"
            val baseline = (top - 4f * density).coerceAtLeast(text.textSize)
            text.color = color
            canvas.drawText(caption, left, baseline, text)
        }
    }

    private fun panelColor(config: OverlayConfig): Int {
        val alpha = (config.backgroundAlpha * 255f).toInt().coerceIn(0, 255)
        return when (config.theme) {
            OverlayTheme.LIGHT_GLASS -> Color.argb(alpha, 245, 245, 248)
            OverlayTheme.AMOLED -> Color.argb(alpha, 0, 0, 0)
            else -> Color.argb(alpha, 10, 10, 14)
        }
    }

    private fun readableTextColor(): Int =
        if (config.theme == OverlayTheme.LIGHT_GLASS) Color.BLACK else Color.WHITE

    private fun accentColor(config: OverlayConfig): Int = when (config.theme) {
        OverlayTheme.NEON_GREEN -> Color.rgb(32, 232, 155)
        OverlayTheme.WARNING_ORANGE -> Color.rgb(255, 159, 28)
        OverlayTheme.FIRE_ORANGE -> Color.rgb(255, 107, 44)
        OverlayTheme.BAMBOO -> Color.rgb(42, 176, 120)
        OverlayTheme.CUSTOM -> config.customColor
        OverlayTheme.LIGHT_GLASS -> Color.rgb(25, 90, 170)
        OverlayTheme.AMOLED, OverlayTheme.DARK_GLASS -> Color.rgb(150, 220, 255)
        OverlayTheme.FOLLOW_APP, OverlayTheme.AUTO_CONTRAST -> Color.WHITE
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
