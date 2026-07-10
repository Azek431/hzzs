// 火崽崽助手（HZZS）视觉识别 — 调试叠加视图。
//
// 职责：
// - 在屏幕上绘制视觉识别的调试信息
// - 显示扫描线、检测结果（绿瓶/坑位）、置信度、耗时统计
// - 支持半透明覆盖层，不遮挡游戏画面
//
// 设计原因：
// - 悬浮窗只放控制按钮，详细信息绘制在屏幕上
// - 双层设计：悬浮窗 = 控制层，屏幕 = 信息层
// - 所有绘制逻辑为纯函数式扩展函数，不持有状态
//
// 使用方式：
// 1. 创建 VisionDebugOverlayView 并添加到 WindowManager
// 2. 设置 VisionDetectionController.drawCallback = { view.updateResult(it) }
// 3. 视图自动重绘

package top.azek431.hzzs.data.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import top.azek431.hzzs.R

/**
 * 视觉识别调试叠加视图。
 *
 * 在屏幕上绘制识别结果详情，包括：
 * - 扫描线（水平线）
 * - 绿瓶检测结果（边框 + 标签）
 * - 坑位检测结果（危险区域 + 标签）
 * - 置信度指示器
 * - 耗时统计
 *
 * @param context 上下文
 */
class VisionDebugOverlayView(
    context: Context,
) : View(context) {

    // ==================== 画笔 ====================

    /** 扫描线画笔 */
    private val scanLinePaint = Paint().apply {
        color = Color.argb(128, 0, 188, 212)
        strokeWidth = 2f
        isAntiAlias = true
    }

    /** 绿瓶边框画笔 */
    private val bottlePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 76, 175, 80)
        strokeWidth = 3f
        isAntiAlias = true
    }

    /** 绿瓶填充画笔 */
    private val bottleFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 76, 175, 80)
        isAntiAlias = true
    }

    /** 坑位危险区画笔 */
    private val pitPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        color = Color.argb(100, 244, 67, 54)
        isAntiAlias = true
    }

    /** 坑位边缘线画笔 */
    private val pitEdgePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 87, 34)
        strokeWidth = 3f
        isAntiAlias = true
    }

    /** 文字画笔 */
    private val textPaint = Paint().apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 14f
        isAntiAlias = true
    }

    /** 小字画笔 */
    private val smallTextPaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = 11f
        isAntiAlias = true
    }

    // ==================== 绘制数据 ====================

    /** 当前分析结果 */
    var currentResult: VisionFrameResult? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** 是否显示扫描线 */
    var showScanLine: Boolean = true

    /** 是否显示标签 */
    var showLabels: Boolean = true

    /** 是否显示中心点 */
    var showCenterPoint: Boolean = true

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = currentResult ?: return

        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制扫描线
        if (showScanLine) {
            drawScanLine(canvas, w, h, result)
        }

        // 绘制绿瓶结果
        if (result.greenBottle.found) {
            drawGreenBottle(canvas, w, h, result)
        }

        // 绘制坑位结果
        if (result.pit.found) {
            drawPit(canvas, w, h, result)
        }

        // 绘制调试面板
        if (showLabels) {
            drawDebugPanel(canvas, w, h, result)
        }
    }

    /**
     * 绘制扫描线。
     */
    private fun drawScanLine(canvas: Canvas, w: Float, h: Float, result: VisionFrameResult) {
        val scanY = result.greenBottle.scanY.toFloat()
        if (scanY > 0f && scanY < h) {
            canvas.drawLine(0f, scanY, w, scanY, scanLinePaint)
        }
    }

    /**
     * 绘制绿瓶检测结果。
     */
    private fun drawGreenBottle(canvas: Canvas, w: Float, h: Float, result: VisionFrameResult) {
        val gb = result.greenBottle

        // 填充区域
        val fillRect = RectF(
            gb.leftX * w, 0f,
            gb.rightX * w, h,
        )
        canvas.drawRect(fillRect, bottleFillPaint)

        // 边框
        val strokeRect = RectF(
            gb.leftX * w, 0f,
            gb.rightX * w, h,
        )
        canvas.drawRect(strokeRect, bottlePaint)

        // 中心点
        if (showCenterPoint) {
            val cx = gb.centerX * w
            canvas.drawCircle(cx, gb.scanY.toFloat(), 6f, bottlePaint)
        }

        // 标签
        if (showLabels) {
            val label = buildString {
                append("Bottle conf=${String.format("%.0f%%", gb.confidence * 100)}")
                append(" gap=${gb.edgeGapPx}px")
                append(" cost=${String.format("%.1f", gb.costMs)}ms")
            }
            canvas.drawText(label, 8f, 24f, smallTextPaint)
        }
    }

    /**
     * 绘制坑位检测结果。
     */
    private fun drawPit(canvas: Canvas, w: Float, h: Float, result: VisionFrameResult) {
        val pit = result.pit

        // 危险区域半透明填充
        val dangerRect = RectF(
            pit.left * w, h * 0.4f,
            pit.right * w, h,
        )
        canvas.drawRect(dangerRect, pitPaint)

        // 左右边缘线
        canvas.drawLine(pit.left * w, h * 0.3f, pit.left * w, h, pitEdgePaint)
        canvas.drawLine(pit.right * w, h * 0.3f, pit.right * w, h, pitEdgePaint)

        // 标签
        if (showLabels) {
            val label = buildString {
                append("PIT w=${String.format("%.1f", pit.width * 100)}%")
                append(" conf=${String.format("%.0f%%", pit.confidence * 100)}")
                append(" cost=${String.format("%.1f", pit.costMs)}ms")
            }
            canvas.drawText(label, 8f, 44f, smallTextPaint)
        }
    }

    /**
     * 绘制调试信息面板（右上角）。
     */
    private fun drawDebugPanel(canvas: Canvas, w: Float, h: Float, result: VisionFrameResult) {
        var y = 16f

        // 标题
        canvas.drawText("HZZS Vision", w - 160f, y, textPaint)
        y += 20f

        // 总耗时
        canvas.drawText(
            "Total: ${String.format("%.1f", result.costMs)}ms",
            w - 160f, y, smallTextPaint,
        )
        y += 16f

        // 绿瓶状态
        val bottleStatus = if (result.greenBottle.found) {
            "GREEN ✓"
        } else {
            "GREEN ✗"
        }
        canvas.drawText(
            bottleStatus,
            w - 160f, y,
            if (result.greenBottle.found) bottlePaint else smallTextPaint,
        )
        y += 16f

        // 坑位状态
        val pitStatus = if (result.pit.found) {
            "PIT  ✓"
        } else {
            "PIT  ✗"
        }
        canvas.drawText(
            pitStatus,
            w - 160f, y,
            if (result.pit.found) pitEdgePaint else smallTextPaint,
        )
        y += 16f

        // 自动操作状态
        canvas.drawText(
            "AutoOp: ${if (result.actions.isNotEmpty()) "${result.actions.size} actions" else "idle"}",
            w - 160f, y, smallTextPaint,
        )
    }

    /**
     * 清空所有绘制结果。
     */
    fun clearResult() {
        currentResult = null
    }
}
