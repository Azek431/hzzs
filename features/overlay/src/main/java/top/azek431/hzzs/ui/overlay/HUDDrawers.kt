// 火崽崽助手（HZZS）HUD 绘制器。
//
// 职责：
// - 封装所有 Canvas 绘制逻辑为扩展函数
// - 不持有状态，纯函数式绘制
//
// 设计原因：
// - 将 HUDCanvasView 中的 10+ 个 drawXxx 方法提取为独立扩展函数
// - 每个绘制函数自包含，便于理解和测试
// - 通过参数传递所有数据，不依赖 HUDCanvasView 内部状态

package top.azek431.hzzs.ui.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin
import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.RectF

// ==================== 网格绘制 ====================

/**
 * 绘制背景网格线。
 *
 * 在画布上绘制 4 条垂直线和 3 条水平线，形成 5x4 的网格参考。
 * 网格线使用浅灰色半透明虚线，不干扰主视觉内容。
 *
 * @param c 目标 Canvas
 * @param w 画布宽度
 * @param h 画布高度
 */
fun Canvas.drawGrid(w: Float, h: Float, gridPaint: Paint) {
    for (i in 1..4) {
        val x = w * i / 5f
        drawLine(x, 0f, x, h, gridPaint)
    }
    for (i in 1..3) {
        val y = h * i / 4f
        drawLine(0f, y, w, y, gridPaint)
    }
}

// ==================== 热力图绘制 ====================

/**
 * 绘制热力图（径向渐变叠加点）。
 *
 * 每个点使用径向渐变从橙色到红色，强度越高半径越大、透明度越高。
 *
 * @param c 目标 Canvas
 * @param w 画布宽度（用于归一化坐标转换）
 * @param h 画布高度（用于归一化坐标转换）
 * @param points 热力图数据点列表
 * @param heatmapPaint 热力图画笔（会修改其 shader 属性，绘制后恢复为 null）
 */
fun Canvas.drawHeatmap(
    w: Float,
    h: Float,
    points: List<HUDHeatmapPoint>,
    heatmapPaint: Paint,
) {
    if (points.isEmpty()) return

    for (point in points) {
        val px = point.x * w
        val py = point.y * h
        val radius = 20f * point.intensity

        val colors = intArrayOf(
            HUDColorPalette.HEATMAP_START,
            HUDColorPalette.HEATMAP_END,
        )
        val gradient = RadialGradient(px, py, radius, colors, null, Shader.TileMode.CLAMP)

        heatmapPaint.shader = gradient
        heatmapPaint.alpha = (point.intensity * 120).toInt()

        drawCircle(px, py, radius, heatmapPaint)
    }

    // 绘制完成后清除 shader，避免影响后续绘制
    heatmapPaint.shader = null
}

// ==================== 归一化矩形绘制 ====================

/**
 * 绘制归一化坐标矩形（带标签）。
 *
 * 绘制步骤：
 * 1. 半透明填充
 * 2. 实线描边
 * 3. 左上角标签
 *
 * @param c 目标 Canvas
 * @param bounds 归一化矩形坐标
 * @param paint 画笔（会被 clone 后修改，不影响原画笔）
 * @param label 标签文字
 * @param viewW 视图宽度
 * @param viewH 视图高度
 * @param textPaint 文字画笔
 */
fun Canvas.drawNormalizedRect(
    c: Canvas,
    bounds: RectF,
    paint: Paint,
    label: String,
    viewW: Float,
    viewH: Float,
    textPaint: Paint,
) {
    val x = bounds.left * viewW
    val y = bounds.top * viewH
    val rectW = bounds.width * viewW
    val rectH = bounds.height * viewH

    save()

    // 半透明填充
    val fillPaint = Paint(paint).apply {
        style = Paint.Style.FILL
        alpha = 80
    }
    drawRect(x, y, x + rectW, y + rectH, fillPaint)

    // 描边
    val strokePaint = Paint(paint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 255
    }
    drawRect(x, y, x + rectW, y + rectH, strokePaint)

    // 标签
    val labelPaint = Paint(textPaint).apply {
        textSize = 10f
        color = HUDColorPalette.TEXT_WHITE
        alpha = 200
    }
    drawText(label, x + 2f, y - 2f, labelPaint)

    restore()
}

// ==================== 运动轨迹绘制 ====================

/**
 * 绘制玩家运动轨迹（渐隐虚线）。
 *
 * 最近的点线条粗、透明度低；最旧的点线条细、透明度高。
 * 形成从淡到浓的视觉效果。
 *
 * @param c 目标 Canvas
 * @param w 画布宽度
 * @param h 画布高度
 * @param points 轨迹点列表（至少 2 个点）
 * @param trajectoryPaint 轨迹画笔（会被 clone 后修改）
 */
fun Canvas.drawTrajectory(
    w: Float,
    h: Float,
    points: List<PointF>,
    trajectoryPaint: Paint,
) {
    if (points.size < 2) return

    val pointCount = points.size
    for (i in 0 until pointCount - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]

        // 透明度：最新的点 0.6，最旧的点 0.1
        val alpha = (0.1f + 0.5f * i / (pointCount - 1)).toInt()

        val paint = Paint(trajectoryPaint).apply {
            this.alpha = alpha
            strokeWidth = 1f + 2f * i / (pointCount - 1)
        }

        drawLine(p1.x * w, p1.y * h, p2.x * w, p2.y * h, paint)
    }
}

// ==================== 预测路径绘制 ====================

/**
 * 绘制预测路径（虚线 + 末端箭头）。
 *
 * @param c 目标 Canvas
 * @param w 画布宽度
 * @param h 画布高度
 * @param path 路径点列表（至少 2 个点）
 * @param pathPaint 路径画笔
 */
fun Canvas.drawPredictedPath(
    w: Float,
    h: Float,
    path: List<PointF>,
    pathPaint: Paint,
) {
    if (path.size < 2) return

    val canvasPath = Path()
    val first = path[0]
    canvasPath.moveTo(first.x * w, first.y * h)

    for (i in 1 until path.size) {
        val p = path[i]
        canvasPath.lineTo(p.x * w, p.y * h)
    }

    drawPath(canvasPath, pathPaint)

    // 在末端绘制箭头
    val last = path.last()
    drawArrow(last.x * w, last.y * h, pathPaint)
}

/**
 * 绘制箭头（两条斜线组成的 V 形）。
 *
 * @param c 目标 Canvas
 * @param x 箭头尖端 X 坐标
 * @param y 箭头尖端 Y 坐标
 * @param paint 画笔
 */
private fun Canvas.drawArrow(x: Float, y: Float, paint: Paint) {
    val arrowSize = 8f
    val angle = Math.PI.toFloat() / 4f  // 右下方向 45 度
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
    drawPath(p, paint)
}

// ==================== 危险区绘制 ====================

/**
 * 绘制危险区域高亮（闪烁边框 + 感叹号）。
 *
 * 使用正弦波调制透明度，产生闪烁效果。
 *
 * @param c 目标 Canvas
 * @param w 画布宽度
 * @param h 画布高度
 * @param zone 危险区矩形
 * @param dangerPaint 危险区画笔
 * @param textPaint 文字画笔
 * @param flashPhase 闪烁相位（每帧递增 0.05f）
 * @return 更新后的闪烁相位
 */
fun Canvas.drawDangerZone(
    w: Float,
    h: Float,
    zone: RectF,
    dangerPaint: Paint,
    textPaint: Paint,
    flashPhase: Float,
): Float {
    // 更新闪烁相位
    val newPhase = flashPhase + 0.05f
    val alpha = (60 + 80 * Math.sin(newPhase.toDouble())).toInt().coerceIn(60, 200)

    // 填充
    val fillPaint = Paint(dangerPaint).apply {
        this.alpha = alpha / 3
        style = Paint.Style.FILL
    }
    drawRect(zone.left * w, zone.top * h, zone.right * w, zone.bottom * h, fillPaint)

    // 边框
    val strokePaint = Paint(dangerPaint).apply {
        this.alpha = alpha
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    drawRect(zone.left * w, zone.top * h, zone.right * w, zone.bottom * h, strokePaint)

    // 中心警告图标
    val labelPaint = Paint(textPaint).apply {
        textSize = 16f
        this.alpha = alpha
        textAlign = Paint.Align.CENTER
    }
    val centerX = (zone.left + zone.right) / 2 * w
    val centerY = (zone.top + zone.bottom) / 2 * h + 5f
    drawText("!", centerX, centerY, labelPaint)

    return newPhase
}

// ==================== 置信度指示器绘制 ====================

/**
 * 绘制置信度圆环指示器（左上角）。
 *
 * 圆环颜色根据置信度级别变化：
 * - >= 0.9 → 绿色
 * - >= 0.7 → 黄色
 * - < 0.7 → 红色
 *
 * @param c 目标 Canvas
 * @param level 置信度 (0.0 ~ 1.0)
 * @param bgPaint 背景画笔
 * @param progressPaint 进度画笔
 * @param textPaint 文字画笔
 */
fun Canvas.drawConfidenceIndicator(
    level: Float,
    bgPaint: Paint,
    progressPaint: Paint,
    textPaint: Paint,
) {
    val radius = 12f
    val cx = radius + 4f
    val cy = radius + 4f
    val strokeWidth = 3f

    // 背景圆环
    bgPaint.apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        color = HUDColorPalette.CONFIDENCE_BG
        isAntiAlias = true
    }
    drawCircle(cx, cy, radius, bgPaint)

    // 进度圆环
    progressPaint.apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        color = when {
            level >= 0.9f -> HUDColorPalette.CONFIDENCE_HIGH
            level >= 0.7f -> HUDColorPalette.CONFIDENCE_MEDIUM
            else -> HUDColorPalette.CONFIDENCE_LOW
        }
    }

    val sweepAngle = level * 360f
    drawArc(
        cx - radius, cy - radius, cx + radius, cy + radius,
        -90f, sweepAngle, false, progressPaint
    )

    // 中心百分比文字
    val textP = Paint(textPaint).apply {
        this.textSize = 8f
        this.color = HUDColorPalette.TEXT_WHITE
        this.textAlign = Paint.Align.CENTER
    }
    drawText("${(level * 100).toInt()}%", cx, cy + 3f, textP)
}

// ==================== 动作倒计时绘制 ====================

/**
 * 绘制动作倒计时弧形进度条（右下角）。
 *
 * @param c 目标 Canvas
 * @param w 画布宽度
 * @param h 画布高度
 * @param timerMs 倒计时毫秒数（<= 0 时不绘制）
 * @param maxEtaMs 最大 ETA 毫秒数
 * @param bgPaint 背景画笔
 * @param textPaint 文字画笔
 */
fun Canvas.drawActionTimer(
    w: Float,
    h: Float,
    timerMs: Float,
    maxEtaMs: Float = 2000f,
    bgPaint: Paint,
    textPaint: Paint,
) {
    if (timerMs <= 0f) return

    val progress = (timerMs / maxEtaMs).coerceIn(0f, 1f)
    val radius = 16f
    val cx = w - radius - 4f
    val cy = h - radius - 4f
    val strokeWidth = 4f

    // 背景弧
    bgPaint.apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        color = HUDColorPalette.TIMER_BG
        isAntiAlias = true
    }
    drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 0f, 360f, false, bgPaint)

    // 进度弧
    val progressPaint = Paint(bgPaint).apply {
        color = when {
            progress > 0.5f -> HUDColorPalette.TIMER_HIGH
            progress > 0.25f -> HUDColorPalette.TIMER_MEDIUM
            else -> HUDColorPalette.TIMER_LOW
        }
    }
    drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, progress * 360f, false, progressPaint)

    // 时间文字
    var textP = Paint(textPaint).apply {
        textSize = 9f
        color = HUDColorPalette.TEXT_WHITE
        textAlign = Paint.Align.CENTER
    }
    drawText("${timerMs.toInt()}ms", cx, cy + 3f, textP)
}

// ==================== 分析结果标签绘制 ====================

/**
 * 绘制分析结果标签（左上角）。
 *
 * 显示场景模式、角色姿态和跳跃阶段。
 * 标签使用灰色小号字体，不干扰主视觉内容。
 *
 * @param c 目标 Canvas
 * @param result 分析结果
 * @param lineHeight 行高
 * @param labelPaint 标签画笔
 */
fun Canvas.drawAnalysisLabel(
    c: Canvas,
    result: FrameAnalysisResult,
    lineHeight: Float = 12f,
    labelPaint: Paint,
) {
    val lines = mutableListOf<String>()
    lines.add(result.sceneText)
    lines.add(result.poseText)
    lines.add("跳段 ${result.jumpStage}")

    val paint = Paint(labelPaint).apply {
        textSize = 9f
        color = HUDColorPalette.LABEL_GRAY
    }

    for ((i, line) in lines.withIndex()) {
        c.drawText(line, 4f, (i + 1) * lineHeight, paint)
    }
}

// ==================== 数据类 ====================

/**
 * 热力图数据点。
 *
 * @property x 归一化 X 坐标 (0.0 ~ 1.0)
 * @property y 归一化 Y 坐标 (0.0 ~ 1.0)
 * @property intensity 强度 (0.0 ~ 1.0)，影响半径和透明度
 */
data class HUDHeatmapPoint(
    val x: Float,
    val y: Float,
    val intensity: Float,
)
