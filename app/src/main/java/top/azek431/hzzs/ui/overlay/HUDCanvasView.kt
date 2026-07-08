// 火崽崽助手（HZZS）HUD 自定义 Canvas 绘制视图。
//
// 用于在悬浮窗中模拟/显示游戏画面，标注玩家矩形、危险物矩形等。
// 所有坐标使用归一化值（0.0 ~ 1.0），自动适配视图尺寸。
//
// 可视化增强：
// - 运动轨迹线（渐隐虚线）
// - 预测路径（蓝色虚线箭头）
// - 危险区高亮（红色闪烁边框）
// - 置信度圆环指示器（绿→黄→红）
// - 动作倒计时条（弧形进度条）
// - 热力图（径向渐变叠加点）
//
// 性能优化：
// - Canvas 双缓冲（离屏 Bitmap 渲染）
// - Paint 对象池复用
//
// 设计原因：
// - 颜色常量提取到 HUDColorPalette（避免 18+ 处 Color.parseColor()）
// - 绘制逻辑提取到 HUDDrawers（扩展函数，纯函数式）
// - 本文件只负责视图生命周期、属性 setter 和画笔初始化

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import top.azek431.hzzs.model.FrameAnalysisResult
import top.azek431.hzzs.model.RectF

/**
 * HUD 自定义 Canvas 绘制视图。
 *
 * 负责在悬浮窗中绘制游戏画面模拟数据，包括玩家矩形、危险物矩形、
 * 运动轨迹、预测路径、热力图等可视化元素。
 *
 * 所有坐标使用归一化值（0.0 ~ 1.0），自动适配不同视图尺寸。
 * 使用双缓冲（离屏 Bitmap）避免绘制闪烁。
 */
class HUDCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ==================== 画笔初始化（使用颜色调色板） ====================

    /** 玩家矩形画笔：绿色实线边框 + 半透明填充 */
    private val playerPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = HUDColorPalette.PLAYER_GREEN
    }

    /** 危险物矩形画笔：红色实线边框 + 半透明填充 */
    private val hazardPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = HUDColorPalette.HAZARD_RED
    }

    /** 网格线画笔：浅灰色虚线 */
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = HUDColorPalette.GRID_LINE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    /** 文字画笔：白色 */
    private val textPaint = Paint().apply {
        textSize = 12f
        color = HUDColorPalette.TEXT_WHITE
        isAntiAlias = true
    }

    /** 轨迹线画笔：绿色虚线，尾部渐变透明度 */
    private val trajectoryPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = HUDColorPalette.TRAJECTORY_GREEN
        isAntiAlias = true
    }

    /** 预测路径画笔：蓝色虚线 + 箭头 */
    private val pathPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = HUDColorPalette.PATH_BLUE
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    /** 危险区画笔：红色半透明填充 + 边框 */
    private val dangerPaint = Paint().apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
        color = HUDColorPalette.DANGER_RED
        isAntiAlias = true
    }

    /** 热力图画笔：径向渐变（shader 在 drawHeatmap 中动态设置） */
    private val heatmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ==================== 绘制数据 ====================

    /** 当前帧的玩家矩形（归一化坐标） */
    var playerBounds: RectF? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** 当前帧的危险物矩形列表（归一化坐标） */
    var hazards: List<RectF> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 当前帧的分析结果（用于显示标签） */
    var analysisResult: FrameAnalysisResult? = null
        set(value) {
            field = value
            postInvalidate()
        }

    // === 新增可视化数据 ===

    /** 玩家运动轨迹点（最近 N 帧的位置） */
    var trajectoryPoints: List<PointF> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 预测路径（抛物线或直线） */
    var predictedPath: List<PointF> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 危险区域高亮 */
    var dangerZone: RectF? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** 整体置信度 (0.0 ~ 1.0) */
    var confidenceLevel: Float = 1.0f
        set(value) {
            field = value
            postInvalidate()
        }

    /** 动作倒计时 (毫秒) */
    var actionTimerMs: Float = 0f
        set(value) {
            field = value
            postInvalidate()
        }

    /** 热力图数据点 */
    data class HeatmapPoint(val x: Float, val y: Float, val intensity: Float)
    var heatmapPoints: List<HeatmapPoint> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    /** 闪烁计数器（用于危险区闪烁效果） */
    private var flashPhase = 0f

    // ==================== 双缓冲 ====================

    /** 离屏 Bitmap，用于双缓冲渲染 */
    private var offscreenBitmap: Bitmap? = null

    /** 离屏 Canvas，绑定到 offscreenBitmap */
    private var offscreenCanvas: Canvas? = null

    /**
     * 视图尺寸变化时创建/调整离屏 Bitmap。
     *
     * 双缓冲原理：先在离屏 Bitmap 上绘制所有内容，
     * 然后一次性拷贝到屏幕，避免闪烁。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            offscreenBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            offscreenCanvas = Canvas(offscreenBitmap!!)
        }
    }

    // ==================== 绘制入口 ====================

    /**
     * 绘制入口。
     *
     * 使用离屏 Canvas 绘制所有内容，最后一次性输出到屏幕。
     * 这是 Android 自定义 View 的标准双缓冲模式。
     */
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 使用离屏 Canvas 绘制所有内容
        val dc = offscreenCanvas ?: return

        drawAll(dc, w, h)

        // 最后一次性输出到屏幕
        canvas.drawBitmap(offscreenBitmap!!, 0f, 0f, null)
    }

    /**
     * 绘制所有内容（从底层到顶层）。
     *
     * 绘制顺序：
     * 1. 背景色 → 2. 网格线 → 3. 热力图 → 4. 危险物 → 5. 玩家
     * 6. 危险区 → 7. 轨迹 → 8. 预测路径 → 9. 分析标签
     * 10. 置信度 → 11. 倒计时
     */
    private fun drawAll(c: Canvas, w: Float, h: Float) {
        // 1. 背景
        c.drawColor(HUDColorPalette.BACKGROUND)

        // 2. 网格线
        c.drawGrid(w, h, gridPaint)

        // 3. 热力图
        val heatPoints = heatmapPoints.map { HUDHeatmapPoint(it.x, it.y, it.intensity) }
        c.drawHeatmap(w, h, heatPoints, heatmapPaint)

        // 4. 危险物矩形
        for (hazard in hazards) {
            drawNormalizedRect(c, hazard, hazardPaint, "危险", w, h)
        }

        // 5. 玩家矩形
        playerBounds?.let { bounds ->
            drawNormalizedRect(c, bounds, playerPaint, "玩家", w, h)
        }

        // 6. 危险区高亮
        dangerZone?.let { zone ->
            flashPhase = c.drawDangerZone(w, h, zone, dangerPaint, textPaint, flashPhase)
        }

        // 7. 运动轨迹
        c.drawTrajectory(w, h, trajectoryPoints, trajectoryPaint)

        // 8. 预测路径
        c.drawPredictedPath(w, h, predictedPath, pathPaint)

        // 9. 分析结果标签
        analysisResult?.let { result ->
            val labelPaint = Paint(textPaint).apply { textSize = 9f }
            c.drawAnalysisLabel(c, result, 12f, labelPaint)
        }

        // 10. 置信度指示器
        c.drawConfidenceIndicator(confidenceLevel, Paint(), Paint(), textPaint)

        // 11. 动作倒计时
        if (actionTimerMs > 0f) {
            c.drawActionTimer(w, h, actionTimerMs, 2000f, Paint(), textPaint)
        }
    }

    /**
     * 绘制归一化坐标矩形。
     *
     * 绘制步骤：
     * 1. 半透明填充
     * 2. 实线描边
     * 3. 左上角标签
     *
     * @param c 目标 Canvas
     * @param bounds 归一化矩形坐标
     * @param paint 画笔
     * @param label 标签文字
     * @param viewW 视图宽度
     * @param viewH 视图高度
     */
    private fun drawNormalizedRect(
        c: Canvas,
        bounds: RectF,
        paint: Paint,
        label: String,
        viewW: Float,
        viewH: Float,
    ) {
        c.drawNormalizedRect(
            c = c,
            bounds = bounds,
            paint = paint,
            label = label,
            viewW = viewW,
            viewH = viewH,
            textPaint = textPaint,
        )
    }
}
