package top.azek431.hzzs.ui.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF as AndroidRectF
import android.graphics.Shader
import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.RectF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun Canvas.drawGrid(w: Float, h: Float, gridPaint: Paint) {
    for (i in 1..4) drawLine(w * i / 5f, 0f, w * i / 5f, h, gridPaint)
    for (i in 1..3) drawLine(0f, h * i / 4f, w, h * i / 4f, gridPaint)
}

fun Canvas.drawHeatmap(
    w: Float,
    h: Float,
    points: List<HUDHeatmapPoint>,
    heatmapPaint: Paint,
) {
    points.forEach { point ->
        val intensity = point.intensity.coerceIn(0.001f, 1f)
        val px = point.x.coerceIn(0f, 1f) * w
        val py = point.y.coerceIn(0f, 1f) * h
        val radius = 20f * intensity
        val startAlpha = (130f * intensity).roundToInt().coerceIn(1, 130)
        val endAlpha = 0
        val start = HUDColorPalette.HEATMAP_START and 0x00FFFFFF or (startAlpha shl 24)
        val end = HUDColorPalette.HEATMAP_END and 0x00FFFFFF or (endAlpha shl 24)
        heatmapPaint.shader = RadialGradient(
            px,
            py,
            radius,
            intArrayOf(start, end),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        drawCircle(px, py, radius, heatmapPaint)
    }
    heatmapPaint.shader = null
}

fun Canvas.drawNormalizedRect(
    c: Canvas,
    bounds: RectF,
    paint: Paint,
    label: String,
    viewW: Float,
    viewH: Float,
    textPaint: Paint,
) {
    val left = bounds.left.coerceIn(0f, 1f) * viewW
    val top = bounds.top.coerceIn(0f, 1f) * viewH
    val right = bounds.right.coerceIn(0f, 1f) * viewW
    val bottom = bounds.bottom.coerceIn(0f, 1f) * viewH
    if (right <= left || bottom <= top) return

    val fill = Paint(paint).apply {
        style = Paint.Style.FILL
        alpha = 72
    }
    val stroke = Paint(paint).apply {
        style = Paint.Style.STROKE
        alpha = 230
    }
    c.drawRect(left, top, right, bottom, fill)
    c.drawRect(left, top, right, bottom, stroke)

    val labelPaint = Paint(textPaint).apply {
        textSize = 10f
        alpha = 230
    }
    c.drawText(label, left + 3f, (top - 3f).coerceAtLeast(labelPaint.textSize), labelPaint)
}

fun Canvas.drawTrajectory(
    w: Float,
    h: Float,
    points: List<PointF>,
    trajectoryPaint: Paint,
) {
    if (points.size < 2) return
    val denominator = (points.size - 1).coerceAtLeast(1)
    for (i in 0 until points.lastIndex) {
        val p1 = points[i]
        val p2 = points[i + 1]
        val progress = i.toFloat() / denominator
        val paint = Paint(trajectoryPaint).apply {
            alpha = ((0.1f + 0.5f * progress) * 255f).roundToInt().coerceIn(0, 255)
            strokeWidth = 1f + 2f * progress
        }
        drawLine(
            p1.x.coerceIn(0f, 1f) * w,
            p1.y.coerceIn(0f, 1f) * h,
            p2.x.coerceIn(0f, 1f) * w,
            p2.y.coerceIn(0f, 1f) * h,
            paint,
        )
    }
}

fun Canvas.drawPredictedPath(
    w: Float,
    h: Float,
    path: List<PointF>,
    pathPaint: Paint,
) {
    if (path.size < 2) return
    val androidPath = Path()
    val first = path.first()
    androidPath.moveTo(first.x.coerceIn(0f, 1f) * w, first.y.coerceIn(0f, 1f) * h)
    path.drop(1).forEach { point ->
        androidPath.lineTo(point.x.coerceIn(0f, 1f) * w, point.y.coerceIn(0f, 1f) * h)
    }
    drawPath(androidPath, pathPaint)

    val before = path[path.lastIndex - 1]
    val end = path.last()
    val x1 = before.x * w
    val y1 = before.y * h
    val x2 = end.x * w
    val y2 = end.y * h
    drawArrow(x2, y2, atan2(y2 - y1, x2 - x1), pathPaint)
}

private fun Canvas.drawArrow(x: Float, y: Float, direction: Float, paint: Paint) {
    val size = 9f
    val spread = (PI / 6.0).toFloat()
    val arrow = Path().apply {
        moveTo(x, y)
        lineTo(x - size * cos(direction - spread), y - size * sin(direction - spread))
        moveTo(x, y)
        lineTo(x - size * cos(direction + spread), y - size * sin(direction + spread))
    }
    drawPath(arrow, paint)
}

fun Canvas.drawDangerZone(
    w: Float,
    h: Float,
    zone: RectF,
    dangerPaint: Paint,
    textPaint: Paint,
    flashPhase: Float,
): Float {
    val nextPhase = (flashPhase + 0.05f) % (2f * PI.toFloat())
    val flashAlpha = ((0.35f + 0.35f * ((sin(nextPhase) + 1f) / 2f)) * 255f)
        .roundToInt()
        .coerceIn(40, 220)
    val left = zone.left.coerceIn(0f, 1f) * w
    val top = zone.top.coerceIn(0f, 1f) * h
    val right = zone.right.coerceIn(0f, 1f) * w
    val bottom = zone.bottom.coerceIn(0f, 1f) * h
    if (right <= left || bottom <= top) return nextPhase

    val fill = Paint(dangerPaint).apply {
        style = Paint.Style.FILL
        alpha = (flashAlpha * .35f).roundToInt()
    }
    val border = Paint(dangerPaint).apply {
        style = Paint.Style.STROKE
        alpha = flashAlpha
    }
    drawRect(left, top, right, bottom, fill)
    drawRect(left, top, right, bottom, border)
    val warning = Paint(textPaint).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f
        alpha = flashAlpha
    }
    drawText("!", (left + right) / 2f, (top + bottom) / 2f + 7f, warning)
    return nextPhase
}

fun Canvas.drawConfidenceIndicator(
    level: Float,
    bgPaint: Paint,
    progressPaint: Paint,
    textPaint: Paint,
) {
    val safeLevel = level.coerceIn(0f, 1f)
    val radius = 12f
    val cx = radius + 4f
    val cy = radius + 4f
    bgPaint.apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = HUDColorPalette.CONFIDENCE_BG
        isAntiAlias = true
    }
    drawCircle(cx, cy, radius, bgPaint)
    progressPaint.apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = when {
            safeLevel >= .9f -> HUDColorPalette.CONFIDENCE_HIGH
            safeLevel >= .7f -> HUDColorPalette.CONFIDENCE_MEDIUM
            else -> HUDColorPalette.CONFIDENCE_LOW
        }
        isAntiAlias = true
    }
    val oval = AndroidRectF(cx - radius, cy - radius, cx + radius, cy + radius)
    drawArc(oval, -90f, 360f * safeLevel, false, progressPaint)
    val text = Paint(textPaint).apply {
        textSize = 8f
        color = HUDColorPalette.TEXT_WHITE
        textAlign = Paint.Align.CENTER
    }
    drawText("${(safeLevel * 100f).roundToInt()}%", cx, cy + 3f, text)
}

fun Canvas.drawActionTimer(
    w: Float,
    h: Float,
    timerMs: Float,
    maxEtaMs: Float,
    bgPaint: Paint,
    textPaint: Paint,
) {
    if (timerMs <= 0f || maxEtaMs <= 0f) return
    val progress = (timerMs / maxEtaMs).coerceIn(0f, 1f)
    val radius = 15f
    val cx = w - radius - 6f
    val cy = h - radius - 6f
    bgPaint.apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = HUDColorPalette.TIMER_BG
        isAntiAlias = true
    }
    drawCircle(cx, cy, radius, bgPaint)
    val progressPaint = Paint(bgPaint).apply {
        strokeCap = Paint.Cap.ROUND
        color = when {
            progress > .5f -> HUDColorPalette.TIMER_HIGH
            progress > .25f -> HUDColorPalette.TIMER_MEDIUM
            else -> HUDColorPalette.TIMER_LOW
        }
    }
    val oval = AndroidRectF(cx - radius, cy - radius, cx + radius, cy + radius)
    drawArc(oval, -90f, 360f * progress, false, progressPaint)
    val text = Paint(textPaint).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9f
    }
    drawText("${timerMs.roundToInt()}ms", cx, cy + 3f, text)
}

fun Canvas.drawAnalysisLabel(
    c: Canvas,
    result: FrameAnalysisResult,
    lineHeight: Float = 12f,
    labelPaint: Paint,
) {
    val paint = Paint(labelPaint).apply {
        textSize = 9f
        color = HUDColorPalette.LABEL_GRAY
    }
    listOf(result.sceneText, result.poseText, "跳段 ${result.jumpStage}").forEachIndexed { index, line ->
        c.drawText(line, 4f, (index + 1) * lineHeight, paint)
    }
}

data class HUDHeatmapPoint(
    val x: Float,
    val y: Float,
    val intensity: Float,
)
