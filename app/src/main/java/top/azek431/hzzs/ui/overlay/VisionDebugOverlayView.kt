// 火崽崽助手（HZZS）视觉调试叠加层。
//
// 独立于 HUDCanvasView 的专用调试视图，用于可视化视觉识别管线状态。
// 不污染原有 HUD 绘制逻辑，可单独启用/关闭。
//
// 绘制内容：
// - 扫描线动画（横向扫描 + 纵向扫描线）
// - 绿瓶识别框（霓虹绿 + 角标 + 发光效果）
// - 左右边缘线（青色，标示视野边界）
// - 中心十字准星
// - L/R/Cx/gap/cost/confidence 精确数值面板
// - 坐标同时支持像素值和比例值双行显示
// - 检测不到时显示简洁提示（非报错）
//
// 时序控制：
// - 循环执行：每 0.3 秒识别一次
// - 旧框消隐：检测到消失后在 0.2~0.28 秒内平滑清空
//
// 使用方式：
// 在布局文件中添加 <VisionDebugOverlayView> 或通过代码设置。
// 调用 setDetection() 传入检测数据，视图会自动绘制。

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

// ==================== 检测数据模型 ====================

/**
 * 视觉识别检测结果。
 *
 * 用于向 VisionDebugOverlayView 提供单帧识别数据。
 * 所有坐标使用归一化值（0.0 ~ 1.0），视图内部自动转换为像素坐标。
 *
 * @property bottleLeft 绿瓶左边界（归一化）
 * @property bottleTop 绿瓶上边界（归一化）
 * @property bottleRight 绿瓶右边界（归一化）
 * @property bottleBottom 绿瓶下边界（归一化）
 * @property labelL 左侧间距比例值
 * @property labelR 右侧间距比例值
 * @property labelCx 中心 X 比例值
 * @property labelGap 间距比例值
 * @property labelCost 成本/代价值
 * @property labelConfidence 置信度（0.0 ~ 1.0）
 * @property detected 是否检测到目标
 */
data class VisionDetection(
    val bottleLeft: Float = 0f,
    val bottleTop: Float = 0f,
    val bottleRight: Float = 0f,
    val bottleBottom: Float = 0f,
    val labelL: Float = 0f,
    val labelR: Float = 0f,
    val labelCx: Float = 0f,
    val labelGap: Float = 0f,
    val labelCost: Float = 0f,
    val labelConfidence: Float = 0f,
    val detected: Boolean = false,
) {
    val bottleRect: RectF get() = RectF(bottleLeft, bottleTop, bottleRight, bottleBottom)
}

// ==================== 颜色常量 ====================

/** 科技感配色方案 — 高对比度霓虹风格 */
private object DebugColors {
    // 扫描线
    const val SCAN_LINE_PRIMARY = 0xFF00E5FF.toInt()      // 青色主扫描线
    const val SCAN_LINE_SECONDARY = 0xFF00695C.toInt()    // 深蓝绿次扫描线
    const val SCAN_LINE_ALPHA = 0x30                       // 半透明

    // 绿瓶识别框
    const val BOTTLE_BORDER = 0xFF00FF88.toInt()          // 霓虹绿边框
    const val BOTTLE_FILL = 0x2200FF88.toInt()            // 半透明填充（alpha 分量）
    const val BOTTLE_GLOW = 0xFF00E676.toInt()            // 绿色发光
    const val BOTTLE_CORNER = 0xFF69F0AE.toInt()          // 角标亮绿

    // 边缘线
    const val EDGE_LEFT = 0xFF00BCD4.toInt()              // 左边缘青蓝
    const val EDGE_RIGHT = 0xFF00BCD4.toInt()             // 右边缘青蓝
    const val EDGE_ALPHA = 0x60                           // 半透明

    // 中心十字
    const val CROSSHAIR = 0xFFFFEB3B.toInt()              // 亮黄
    const val CROSSHAIR_ALPHA = 0xCC                      // 较高透明度

    // 数据面板
    const val PANEL_BG = 0xAA000000.toInt()               // 半透明黑底
    const val PANEL_TEXT = 0xFFFFFFFF.toInt()             // 白色文字
    const val PANEL_VALUE = 0xFF76FF03.toInt()            // 绿色数值
    const val PANEL_LABEL = 0xFFB0BEC5.toInt()            // 灰色标签
    const val PANEL_NO_DETECT = 0xFFFF9800.toInt()        // 橙色无检测提示

    // 扫描线动画
    const val SCAN_LINE_WIDTH = 2f
    const val SCAN_LINE_SPEED = 120f                       // 像素/秒
}

// ==================== 自定义 View ====================

/**
 * 视觉调试叠加层视图。
 *
 * 独立于 HUDCanvasView 的专用调试视图，用于可视化视觉识别管线状态。
 * 采用科技感霓虹风格绘制，高对比度，清晰易辨别。
 *
 * 绘制元素：
 * 1. 扫描线动画（横向 + 纵向）
 * 2. 绿瓶识别框（带发光和角标）
 * 3. 左右边缘线
 * 4. 中心十字准星
 * 5. 数据面板（L/R/Cx/gap/cost/confidence）
 * 6. 无检测提示
 *
 * 时序行为：
 * - 调用 setDetection() 设置新数据后立即绘制
 * - 旧框在 fadeOutDurationMs 时间内平滑消隐（默认 250ms）
 * - 调用 clearDetection() 触发消隐动画
 */
class VisionDebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ==================== 画笔 ====================

    /** 扫描线画笔 */
    private val scanLinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = DebugColors.SCAN_LINE_WIDTH
        color = DebugColors.SCAN_LINE_PRIMARY
        alpha = DebugColors.SCAN_LINE_ALPHA
    }

    /** 扫描线（纵向）画笔 */
    private val scanLineVertPaint = Paint(scanLinePaint).apply {
        color = DebugColors.SCAN_LINE_SECONDARY
        strokeWidth = 1f
    }

    /** 绿瓶识别框画笔 */
    private val bottleBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = DebugColors.BOTTLE_BORDER
    }

    /** 绿瓶识别框填充画笔 */
    private val bottleFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = DebugColors.BOTTLE_FILL
    }

    /** 绿瓶发光效果画笔 */
    private val bottleGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = DebugColors.BOTTLE_GLOW
        alpha = 0x40
    }

    /** 角标画笔 */
    private val cornerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = DebugColors.BOTTLE_CORNER
    }

    /** 边缘线画笔 */
    private val edgePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = DebugColors.EDGE_LEFT
        alpha = DebugColors.EDGE_ALPHA
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    /** 中心十字准星画笔 */
    private val crosshairPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = DebugColors.CROSSHAIR
        alpha = DebugColors.CROSSHAIR_ALPHA
    }

    /** 数据面板背景画笔 */
    private val panelBgPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = DebugColors.PANEL_BG
    }

    /** 数据面板文字画笔 */
    private val panelTextPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = DebugColors.PANEL_TEXT
        textSize = 11f
    }

    /** 数值文字画笔 */
    private val valueTextPaint = Paint(panelTextPaint).apply {
        color = DebugColors.PANEL_VALUE
        textSize = 12f
        isFakeBoldText = true
    }

    /** 标签文字画笔 */
    private val labelTextPaint = Paint(panelTextPaint).apply {
        color = DebugColors.PANEL_LABEL
        textSize = 9f
    }

    /** 无检测提示画笔 */
    private val noDetectPaint = Paint(panelTextPaint).apply {
        color = DebugColors.PANEL_NO_DETECT
        textSize = 12f
        textAlign = Paint.Align.CENTER
    }

    // ==================== 状态数据 ====================

    /** 当前检测数据 */
    private var currentDetection: VisionDetection? = null

    /** 上一帧检测数据（用于消隐动画） */
    private var prevDetection: VisionDetection? = null

    /** 消隐进度：0.0 = 完全显示，1.0 = 完全消失 */
    @Volatile
    private var fadeProgress: Float = 0f

    /** 消隐持续时间（毫秒），默认 250ms（落在 200~280ms 区间中段） */
    var fadeOutDurationMs: Long = 250L

    /** 扫描线动画偏移量（由 AnimationHandler 递增） */
    private var scanOffsetX: Float = 0f

    /** 扫描线动画偏移量（纵向） */
    private var scanOffsetY: Float = 0f

    /** 是否正在运行扫描线动画 */
    @Volatile
    private var animating: Boolean = false

    // ==================== 测量与绘制 ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化时重置扫描线偏移
        if (w != oldw || h != oldh) {
            scanOffsetX = 0f
            scanOffsetY = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. 绘制扫描线动画
        drawScanLines(canvas, w, h)

        // 2. 绘制左右边缘线
        drawEdgeLines(canvas, w, h)

        // 3. 绘制中心十字准星
        drawCrosshair(canvas, w, h)

        // 4. 绘制绿瓶识别框（带消隐动画）
        drawBottleDetection(canvas, w, h)

        // 5. 绘制数据面板
        drawDataPanel(canvas, w, h)

        // 6. 绘制无检测提示
        if (!animating && currentDetection == null && prevDetection == null) {
            drawNoDetectionHint(canvas, w, h)
        }
    }

    // ==================== 扫描线绘制 ====================

    /**
     * 绘制扫描线动画。
     *
     * 横向扫描线：从左向右移动的青色光线
     * 纵向扫描线：从上向下的深蓝绿辅助线
     */
    private fun drawScanLines(canvas: Canvas, w: Float, h: Float) {
        // 横向扫描线
        val scanY = scanOffsetY % h
        scanLinePaint.color = DebugColors.SCAN_LINE_PRIMARY
        canvas.drawLine(0f, scanY, w, scanY, scanLinePaint)

        // 扫描线光晕（更宽的半透明区域）
        val glowPaint = Paint(scanLinePaint).apply {
            strokeWidth = 20f
            alpha = 0x15
            color = DebugColors.SCAN_LINE_PRIMARY
        }
        canvas.drawLine(0f, scanY, w, scanY, glowPaint)

        // 纵向扫描线
        val scanX = scanOffsetX % w
        scanLineVertPaint.color = DebugColors.SCAN_LINE_SECONDARY
        canvas.drawLine(scanX, 0f, scanX, h, scanLineVertPaint)
    }

    // ==================== 边缘线绘制 ====================

    /**
     * 绘制左右边缘线。
     *
     * 在视图的 10% 和 90% 位置绘制虚线，标示视野边界。
     */
    private fun drawEdgeLines(canvas: Canvas, w: Float, h: Float) {
        val leftEdge = w * 0.10f
        val rightEdge = w * 0.90f

        // 左边缘
        edgePaint.color = DebugColors.EDGE_LEFT
        canvas.drawLine(leftEdge, 0f, leftEdge, h, edgePaint)

        // 右边缘
        edgePaint.color = DebugColors.EDGE_RIGHT
        canvas.drawLine(rightEdge, 0f, rightEdge, h, edgePaint)

        // 边缘标签
        val labelPaint = Paint(labelTextPaint).apply {
            color = DebugColors.EDGE_LEFT
            alpha = 0x80
        }
        canvas.drawText("L", leftEdge, 14f, labelPaint)
        canvas.drawText("R", rightEdge, 14f, labelPaint)
    }

    // ==================== 中心十字准星 ====================

    /**
     * 绘制中心十字准星。
     *
     * 在视图正中心绘制黄色十字，标示屏幕中心参考点。
     */
    private fun drawCrosshair(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val size = 12f

        // 横线
        canvas.drawLine(cx - size, cy, cx + size, cy, crosshairPaint)
        // 竖线
        canvas.drawLine(cx, cy - size, cx, cy + size, crosshairPaint)
        // 中心小圆
        val dotPaint = Paint(crosshairPaint).apply {
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
        canvas.drawCircle(cx, cy, 2f, dotPaint)
    }

    // ==================== 绿瓶识别框绘制 ====================

    /**
     * 绘制绿瓶识别框。
     *
     * 如果当前有检测数据，绘制带发光效果的霓虹绿框 + 角标。
     * 如果正在消隐，按 fadeProgress 比例降低透明度。
     */
    private fun drawBottleDetection(canvas: Canvas, w: Float, h: Float) {
        val detection = currentDetection ?: prevDetection ?: return

        // 计算消隐 alpha
        val fadeAlpha = if (fadeProgress > 0f) {
            ((1f - fadeProgress) * 255).toInt().coerceIn(0, 255)
        } else {
            255
        }

        // 将归一化坐标转换为像素坐标
        val px = detection.bottleRect.left * w
        val py = detection.bottleRect.top * h
        val pw = detection.bottleRect.width() * w
        val ph = detection.bottleRect.height() * h

        // 跳过无效矩形
        if (pw <= 0f || ph <= 0f) return

        // 发光效果（最外层）
        bottleGlowPaint.alpha = fadeAlpha * 0x40 / 255
        canvas.drawRect(px - 4f, py - 4f, px + pw + 4f, py + ph + 4f, bottleGlowPaint)

        // 半透明填充
        bottleFillPaint.color = (DebugColors.BOTTLE_FILL and 0xFF000000.toInt()) or fadeAlpha
        canvas.drawRect(px, py, px + pw, py + ph, bottleFillPaint)

        // 边框
        bottleBorderPaint.alpha = fadeAlpha
        canvas.drawRect(px, py, px + pw, py + ph, bottleBorderPaint)

        // 四角角标（L 形标记）
        val cornerLen = min(16f, min(pw, ph) * 0.3f)
        cornerPaint.alpha = fadeAlpha
        drawCornerMarks(canvas, px, py, pw, ph, cornerLen)
    }

    /**
     * 绘制四角 L 形角标。
     */
    private fun drawCornerMarks(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, len: Float) {
        // 左上
        canvas.drawLine(x, y + len, x, y, cornerPaint)
        canvas.drawLine(x, y, x + len, y, cornerPaint)
        // 右上
        canvas.drawLine(x + w, y, x + w, y + len, cornerPaint)
        canvas.drawLine(x + w, y, x + w - len, y, cornerPaint)
        // 左下
        canvas.drawLine(x, y + h - len, x, y + h, cornerPaint)
        canvas.drawLine(x, y + h, x + len, y + h, cornerPaint)
        // 右下
        canvas.drawLine(x + w, y + h - len, x + w, y + h, cornerPaint)
        canvas.drawLine(x + w, y + h, x + w - len, y + h, cornerPaint)
    }

    // ==================== 数据面板绘制 ====================

    /**
     * 绘制数据面板。
     *
     * 显示 L/R/Cx/gap/cost/confidence 等精确数值，
     * 同时显示像素值和比例值双行格式。
     */
    private fun drawDataPanel(canvas: Canvas, w: Float, h: Float) {
        val detection = currentDetection ?: return

        // 面板尺寸
        val panelWidth = 180f
        val panelHeight = 200f
        // 面板位置：右上角
        val px = w - panelWidth - 8f
        val py = 8f

        // 圆角矩形背景
        val bgRect = RectF(px, py, px + panelWidth, py + panelHeight)
        canvas.drawRoundRect(bgRect, 6f, 6f, panelBgPaint)

        // 面板标题
        val titlePaint = Paint(valueTextPaint).apply {
            color = DebugColors.PANEL_VALUE
            textSize = 10f
        }
        canvas.drawText("VISION DEBUG", px + 8f, py + 16f, titlePaint)

        // 分隔线
        val linePaint = Paint(panelBgPaint).apply {
            color = 0x40FFFFFF
            strokeWidth = 0.5f
        }
        canvas.drawLine(px + 4f, py + 22f, px + panelWidth - 4f, py + 22f, linePaint)

        // 数据行
        val lineHeight = 22f
        val startX = px + 8f
        var startY = py + 38f

        // 辅助函数：绘制一行数据
        fun drawDataRow(label: String, ratioVal: String, pixelVal: String) {
            // 标签
            canvas.drawText(label, startX, startY, labelTextPaint)
            // 比例值
            canvas.drawText(ratioVal, startX + 70f, startY, valueTextPaint)
            // 像素值
            canvas.drawText(pixelVal, startX + 140f, startY, Paint(labelTextPaint).apply {
                color = DebugColors.PANEL_LABEL
                alpha = 0x80
            })
            startY += lineHeight
        }

        // L（左侧间距）
        val lPixel = (detection.labelL * w).toString().take(6)
        drawDataRow("L", "%.3f".format(detection.labelL), "$lPixel px")

        // R（右侧间距）
        val rPixel = (detection.labelR * w).toString().take(6)
        drawDataRow("R", "%.3f".format(detection.labelR), "$rPixel px")

        // Cx（中心 X）
        val cxPixel = (detection.labelCx * w).toString().take(6)
        drawDataRow("Cx", "%.3f".format(detection.labelCx), "$cxPixel px")

        // gap（间距）
        val gapPixel = (detection.labelGap * w).toString().take(6)
        drawDataRow("gap", "%.3f".format(detection.labelGap), "$gapPixel px")

        // cost（代价）
        drawDataRow("cost", "%.2f".format(detection.labelCost), "--")

        // confidence（置信度）
        val confStr = "%.1f%%".format(detection.labelConfidence * 100f)
        drawDataRow("conf", confStr, "--")
    }

    // ==================== 无检测提示 ====================

    /**
     * 绘制无检测提示。
     *
     * 当没有检测数据且未运行动画时，显示简洁提示而非报错信息。
     */
    private fun drawNoDetectionHint(canvas: Canvas, w: Float, h: Float) {
        val text = "等待视觉识别..."
        val textWidth = noDetectPaint.measureText(text)
        canvas.drawText(text, (w - textWidth) / 2f, h * 0.5f, noDetectPaint)
    }

    // ==================== 公共 API ====================

    /**
     * 设置当前帧的检测数据。
     *
     * 如果之前有数据，旧数据进入消隐动画。
     * 调用后自动触发重绘。
     *
     * @param detection 当前帧检测数据
     */
    fun setDetection(detection: VisionDetection) {
        // 如果有旧数据，将其移入 prevDetection 触发消隐
        if (currentDetection != null) {
            prevDetection = currentDetection
            fadeProgress = 0f
        }
        currentDetection = detection
        postInvalidate()
    }

    /**
     * 清除检测数据并触发消隐动画。
     *
     * 旧框会在 fadeOutDurationMs 时间内平滑消失。
     */
    fun clearDetection() {
        if (currentDetection != null) {
            prevDetection = currentDetection
            currentDetection = null
            fadeProgress = 0f
            postInvalidate()
            startFadeOut()
        }
    }

    /**
     * 启动扫描线动画。
     *
     * 需要在循环执行时调用，每 0.3 秒识别一次。
     * 动画在 onDraw 中自动更新。
     */
    fun startAnimation() {
        if (!animating) {
            animating = true
            post(animationRunnable)
        }
    }

    /**
     * 停止扫描线动画。
     *
     * 同时清除 fadeHandler 的 pending 回调，防止消隐动画在 view detach 后继续执行。
     */
    fun stopAnimation() {
        animating = false
        removeCallbacks(animationRunnable)
        fadeHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 检查是否正在运行动画。
     */
    fun isAnimating(): Boolean = animating

    // ==================== 消隐动画 ====================

    /**
     * 启动消隐定时器。
     *
     * 在 fadeOutDurationMs 内将 fadeProgress 从 0 递增到 1，
     * 然后清除 prevDetection 并停止动画。
     */
    private fun startFadeOut() {
        val startTime = System.currentTimeMillis()
        fadeHandler.removeCallbacksAndMessages(null)
        fadeHandler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val targetProgress = elapsed.toFloat() / fadeOutDurationMs
                if (targetProgress >= 1f) {
                    fadeProgress = 1f
                    prevDetection = null
                    postInvalidate()
                } else {
                    fadeProgress = targetProgress
                    postInvalidate()
                    fadeHandler.post(this)
                }
            }
        })
    }

    private val fadeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ==================== 扫描线动画 ====================

    /**
     * 扫描线动画 Runnable。
     *
     * 每帧更新 scanOffsetX/scanOffsetY，触发重绘。
     * 16ms 间隔（约 60fps）保证动画流畅。
     */
    private val animationRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            scanOffsetX += DebugColors.SCAN_LINE_SPEED * 0.016f
            scanOffsetY += DebugColors.SCAN_LINE_SPEED * 0.016f
            postInvalidate()
            // 16ms ≈ 60fps
            postDelayed(this, 16)
        }
    }
}
