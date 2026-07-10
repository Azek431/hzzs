// 火崽崽助手（HZZS）屏幕调试叠加视图。
//
// 职责：
// - 在全屏屏幕上绘制视觉识别调试信息
// - 模仿 Python 脚本 hzzs_vision_batch_test.py 的绘制风格
// - 显示玩家参考框、危险物框、扫描线、标签面板
//
// 绘制内容（参照 Python 脚本）：
// 1. 玩家参考框：细红色边框 + 中心点
// 2. 危险物框：发光边框 + 半透明填充 + 边缘线 + 中心点
// 3. 扫描线：青色细线，只在检测区域绘制
// 4. 左上角汇总面板：场景/姿态/跳段/提示/坐标
// 5. 无检测提示：黄色标签
//
// 使用方式：
// 1. 创建 ScreenOverlayView 实例
// 2. 通过 WindowManager 添加到 TYPE_APPLICATION_OVERLAY
// 3. 调用 updateResult() 传入 FrameAnalysisResult 触发重绘
//
// 线程安全：
// - updateResult() 可在任意线程调用
// - 绘制在 onDraw() 中执行（主线程）
// - 使用 @Volatile 保护 currentResult

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

/**
 * 屏幕调试叠加视图。
 *
 * 全屏绘制 C++ 分析引擎的输出结果，风格模仿 Python 离线测试脚本。
 * 所有坐标使用屏幕像素坐标，不归一化。
 */
class ScreenOverlayView(
    context: Context,
) : View(context) {

    // ==================== 画笔 ====================

    /** 玩家边框画笔：红色细线 */
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 80, 100)
        strokeWidth = 2f
    }

    /** 玩家中心点画笔 */
    private val playerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 255, 80, 100)
    }

    /** 危险物发光外框画笔（外层淡色） */
    private val hazardOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(100, 255, 140, 50)
        strokeWidth = 6f
    }

    /** 危险物发光内框画笔（内层亮色） */
    private val hazardInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 105, 35)
        strokeWidth = 3f
    }

    /** 危险物半透明填充 */
    private val hazardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(58, 255, 70, 40)
    }

    /** 危险物左边缘线：红色 */
    private val hazardLeftEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 40, 40)
        strokeWidth = 3f
    }

    /** 危险物右边缘线：黄色 */
    private val hazardRightEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 230, 0)
        strokeWidth = 3f
    }

    /** 危险物中心点画笔 */
    private val hazardDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 255, 80, 40)
    }

    /** 文字画笔：白色 */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 14f
    }

    /** 小字画笔 */
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = 11f
    }

    /** 标签背景画笔：黑色半透明 */
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
    }

    /** 标签边框画笔：彩色 */
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 0, 255, 105)
        strokeWidth = 1f
    }

    // ==================== 绘制数据 ====================

    /** 当前分析结果（来自 C++ 分析引擎） */
    @Volatile
    private var currentResult: FrameAnalysisResult? = null

    /** 当前视觉识别结果（来自 C++ 绿瓶/坑位检测） */
    /** 绿瓶检测结果 */
    var bottleFound: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }
    var bottleLeft: Int = 0
    var bottleRight: Int = 0
    var bottleCenterX: Int = 0
    var bottleCenterY: Int = 0
    var bottleWidth: Int = 0
    var bottleScanY: Int = 0
    var bottleConfidence: Float = 0f
    var bottleCostMs: Double = 0.0

    /** 坑位检测结果 */
    var pitFound: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }
    var pitLeft: Int = 0
    var pitRight: Int = 0
    var pitCenterX: Int = 0
    var pitScanY: Int = 0
    var pitWidth: Int = 0
    var pitEdgeGap: Int = 0
    var pitConfidence: Float = 0f
    var pitCostMs: Double = 0.0

    @Volatile
    private var screenWidth: Int = 0
    @Volatile
    private var screenHeight: Int = 0

    // ==================== 公开 API ====================

    /**
     * 更新分析结果并触发重绘。
     *
     * 可在任意线程调用。
     */
    fun updateResult(result: FrameAnalysisResult?) {
        synchronized(this) {
            currentResult = result
        }
        postInvalidate()
    }

    /** 清除绘制 */
    fun clearResult() {
        synchronized(this) {
            currentResult = null
        }
        postInvalidate()
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val result = synchronized(this) { currentResult } ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) return

        // 更新屏幕尺寸
        screenWidth = width
        screenHeight = height

        // 1. 玩家参考框（来自分析引擎）
        drawPlayerReference(canvas, result, w, h)

        // 2. 危险物框（来自分析引擎）
        for (i in result.hazardBounds.indices) {
            drawHazardBox(canvas, result.hazardBounds[i], result.hazardDetails.getOrNull(i), w, h)
        }

        // 3. 绿瓶检测结果（来自视觉识别）
        if (bottleFound) {
            drawGreenBottle(canvas, w, h)
        }

        // 4. 坑位检测结果（来自视觉识别）
        if (pitFound) {
            drawPit(canvas, w, h)
        }

        // 5. 左上角汇总面板
        drawSummaryPanel(canvas, result, w, h)

        // 6. 无检测提示
        if (result.hazardsCount == 0 && !bottleFound && !pitFound) {
            drawNoDetectionTip(canvas, w, h)
        }
    }

    // ==================== 绘制函数 ====================

    /**
     * 绘制玩家参考框 + 中心点。
     */
    private fun drawPlayerReference(canvas: Canvas, result: FrameAnalysisResult, w: Float, h: Float) {
        val pb = result.playerBounds ?: return

        val px = pb.left * w
        val py = pb.top * h
        val pw = pb.width * w
        val ph = pb.height * h

        canvas.drawRect(px, py, px + pw, py + ph, playerPaint)

        val cx = (pb.left + pb.right) / 2f * w
        val cy = (pb.top + pb.bottom) / 2f * h
        canvas.drawCircle(cx, cy, 3f, playerDotPaint)
    }

    /**
     * 绘制危险物发光框。
     */
    private fun drawHazardBox(
        canvas: Canvas,
        bounds: RectF,
        detail: HazardDetail?,
        w: Float,
        h: Float,
    ) {
        val px = bounds.left * w
        val py = bounds.top * h
        val pw = bounds.width * w
        val ph = bounds.height * h

        canvas.drawRect(px, py, px + pw, py + ph, hazardFillPaint)
        canvas.drawRect(px - 2f, py - 2f, px + pw + 2f, py + ph + 2f, hazardOuterPaint)
        canvas.drawRect(px, py, px + pw, py + ph, hazardInnerPaint)
        canvas.drawLine(px, py, px, py + ph, hazardLeftEdgePaint)
        canvas.drawLine(px + pw, py, px + pw, py + ph, hazardRightEdgePaint)

        val cx = (bounds.left + bounds.right) / 2f * w
        val cy = (bounds.top + bounds.bottom) / 2f * h
        canvas.drawCircle(cx, cy, 5f, hazardDotPaint)

        if (detail != null) {
            val label = buildLabel(detail, bounds, w, h)
            drawLabel(canvas, px - 5f, py - 24f, label, hazardInnerPaint.color)
        }
    }

    private fun buildLabel(detail: HazardDetail, bounds: RectF, w: Float, h: Float): String {
        return buildString {
            append("HAZ type=${detail.type} ")
            append("W=${(bounds.width * 100).toInt()}% ")
            append("conf=${String.format("%.0f%%", detail.confidence * 100)}")
        }
    }

    /**
     * 绘制绿瓶检测结果。
     *
     * 模仿 Python 脚本中的绿瓶绘制：
     * - 扫描线（青色细线）
     * - 半透明识别区域
     * - 发光框
     * - 左右边缘线
     * - 中心点
     * - 标签
     */
    private fun drawGreenBottle(canvas: Canvas, w: Float, h: Float) {
        val bottleTopY = (screenHeight * 802 / 1536).toFloat()
        val bottleBottomY = (screenHeight * 1006 / 1536).toFloat()

        // 扫描线
        val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(170, 0, 220, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(bottleScanY.toFloat(), bottleScanY.toFloat(),
            w - 12, bottleScanY.toFloat(), scanLinePaint)

        // 半透明识别区域
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(42, 0, 255, 90)
        }
        canvas.drawRect(bottleLeft.toFloat(), bottleTopY,
            bottleRight.toFloat(), bottleBottomY, fillPaint)

        // 发光框
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 0, 255, 105)
            strokeWidth = 3f
        }
        canvas.drawRect(bottleLeft.toFloat(), bottleTopY,
            bottleRight.toFloat(), bottleBottomY, glowPaint)

        // 左右边缘线
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 255, 230, 0)
            strokeWidth = 3f
        }
        canvas.drawLine(bottleLeft.toFloat(), bottleTopY,
            bottleLeft.toFloat(), bottleBottomY, edgePaint)
        canvas.drawLine(bottleRight.toFloat(), bottleTopY,
            bottleRight.toFloat(), bottleBottomY, edgePaint)

        // 中心点
        val cx = bottleCenterX.toFloat()
        val cy = (bottleTopY + bottleBottomY) / 2f
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(200, 0, 255, 105)
        }
        canvas.drawCircle(cx, cy, 5f, dotPaint)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(100, 255, 255, 255)
            strokeWidth = 2f
        }
        canvas.drawCircle(cx, cy, 5f, ringPaint)

        // 标签
        val label = String.format("BOT L=%d R=%d Cx=%d gap=%dpx %.2fms",
            bottleLeft, bottleRight, bottleCenterX, bottleWidth, bottleConfidence)
        drawLabel(canvas, bottleLeft - 80f, bottleTopY - 34f, label,
            Color.argb(200, 0, 255, 105))
    }

    /**
     * 绘制坑位检测结果。
     *
     * 模仿 Python 脚本中的坑位绘制：
     * - 扫描线（青色细线）
     * - 危险区半透明填充
     * - 发光框
     * - 左右边缘线
     * - 中心点
     * - 标签
     */
    private fun drawPit(canvas: Canvas, w: Float, h: Float) {
        // 扫描线
        val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(170, 0, 190, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(pitScanY.toFloat(), pitScanY.toFloat(),
            w - 8, pitScanY.toFloat(), scanLinePaint)

        val dangerTop = (pitScanY - screenHeight * 18 / 1000).coerceAtLeast(0)
        val dangerBottom = (pitScanY + screenHeight * 185 / 1000).coerceAtMost(screenHeight - 1)

        // 半透明危险区
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(58, 255, 70, 40)
        }
        canvas.drawRect(pitLeft.toFloat(), dangerTop.toFloat(),
            pitRight.toFloat(), dangerBottom.toFloat(), fillPaint)

        // 发光框
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 255, 105, 35)
            strokeWidth = 3f
        }
        canvas.drawRect(pitLeft.toFloat(), dangerTop.toFloat(),
            pitRight.toFloat(), dangerBottom.toFloat(), glowPaint)

        // 左右边缘线
        val leftEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 255, 40, 40)
            strokeWidth = 3f
        }
        val rightEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 255, 230, 0)
            strokeWidth = 3f
        }
        canvas.drawLine(pitLeft.toFloat(), dangerTop.toFloat(),
            pitLeft.toFloat(), dangerBottom.toFloat(), leftEdgePaint)
        canvas.drawLine(pitRight.toFloat(), dangerTop.toFloat(),
            pitRight.toFloat(), dangerBottom.toFloat(), rightEdgePaint)

        // 中心点
        val cx = pitCenterX.toFloat()
        val cy = pitScanY.toFloat()
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(200, 255, 80, 40)
        }
        canvas.drawCircle(cx, cy, 5f, dotPaint)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(100, 255, 255, 255)
            strokeWidth = 2f
        }
        canvas.drawCircle(cx, cy, 5f, ringPaint)

        // 标签
        val label = String.format("PIT L=%d R=%d W=%d gap=%dpx %.2fms",
            pitLeft, pitRight, pitWidth, pitEdgeGap, pitConfidence)
        drawLabel(canvas, pitLeft - 55f, dangerTop.toFloat() - 34f, label,
            Color.argb(200, 255, 170, 40))
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, text: String, accentColor: Int) {
        val paint = Paint(textPaint).apply { this.color = accentColor }
        val textWidth = paint.measureText(text)
        val boxW = (textWidth + 16f).toInt()
        val boxH = 20

        val clampedX = if (x < 8f) 8f else x.coerceAtMost(width.toFloat() - boxW - 8f)
        val clampedY = if (y < 8f) 8f else y.coerceAtMost(height.toFloat() - boxH - 8f)

        val bg = GraphicsRect(
            (clampedX - 8f).toInt(),
            (clampedY - 2f).toInt(),
            (clampedX + boxW).toInt(),
            (clampedY + boxH).toInt(),
        )
        canvas.drawRect(bg, labelBgPaint)

        labelBorderPaint.color = accentColor
        canvas.drawRect(bg, labelBorderPaint)

        canvas.drawText(text, clampedX.toFloat(), clampedY.toFloat() + 14f, paint)
    }

    private fun drawSummaryPanel(canvas: Canvas, result: FrameAnalysisResult, w: Float, h: Float) {
        var y = 50f

        canvas.drawText("HZZS Engine", 8f, y, textPaint)
        y += 22f
        canvas.drawText("场景: ${result.sceneText}", 8f, y, smallTextPaint)
        y += 16f
        canvas.drawText("姿态: ${result.poseText}", 8f, y, smallTextPaint)
        y += 16f
        canvas.drawText("跳段: ${result.jumpStage}", 8f, y, smallTextPaint)
        y += 16f

        if (result.hasPrompt) {
            val promptPaint = Paint(smallTextPaint).apply { color = Color.argb(220, 0, 255, 105) }
            canvas.drawText("提示: ${result.promptText} (${result.etaText})", 8f, y, promptPaint)
            y += 16f
        }

        canvas.drawText("危险物: ${result.hazardsCount}", 8f, y, smallTextPaint)
        y += 16f

        // 视觉识别状态
        canvas.drawText("绿瓶: ${if (bottleFound) "✓" else "✗"} ${String.format("%.0f%%", bottleConfidence * 100)}",
            8f, y, Paint(smallTextPaint).apply {
                color = if (bottleFound) Color.argb(220, 0, 255, 105) else Color.argb(180, 255, 255, 255)
            })
        y += 16f
        canvas.drawText("坑位: ${if (pitFound) "✓" else "✗"} ${String.format("%.0f%%", pitConfidence * 100)}",
            8f, y, Paint(smallTextPaint).apply {
                color = if (pitFound) Color.argb(220, 255, 170, 40) else Color.argb(180, 255, 255, 255)
            })
        y += 16f

        result.playerBounds?.let { pb ->
            canvas.drawText("玩家: [${fmt(pb.left)},${fmt(pb.top)}-${fmt(pb.right)},${fmt(pb.bottom)}]", 8f, y, smallTextPaint)
        }

        if (result.hazardBounds.isNotEmpty()) {
            y += 4f
            canvas.drawText("--- 危险物坐标 ---", 8f, y, Paint(smallTextPaint).apply {
                color = Color.argb(180, 255, 170, 40)
            })
            y += 16f
            for (i in result.hazardBounds.indices) {
                val hb = result.hazardBounds[i]
                val det = result.hazardDetails.getOrNull(i)
                canvas.drawText(
                    "H${i + 1}: [${fmt(hb.left)},${fmt(hb.top)}-${fmt(hb.right)},${fmt(hb.bottom)}]" +
                    (det?.let { " eta=${it.etaMs.toInt()}ms" } ?: ""),
                    8f, y, smallTextPaint,
                )
                y += 14f
            }
        }
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)

    private fun drawNoDetectionTip(canvas: Canvas, w: Float, h: Float) {
        val tip = "No hazards detected"
        val paint = Paint(textPaint).apply {
            color = Color.argb(180, 255, 220, 80)
            textSize = 12f
        }
        canvas.drawText(tip, 8f, h - 16f, paint)
    }
}
