// 火崽崽助手（HZZS）悬浮窗缩放控制器。
//
// 职责：
// - 处理右下角缩放手柄（overlayResizeHandle）的缩放事件
// - 计算缩放距离，限制最小/最大宽度范围
// - 更新 WindowManager.LayoutParams 的 width
// - 缩放系数持久化到 SharedPreferences，下次打开悬浮窗时恢复
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理透明度/自动操作滑块绑定（由 OverlaySettingsBinder 处理）
//
// 设计原因：
// - 缩放逻辑独立封装，便于将来修改缩放策略（如保持纵横比、添加缩放手势等）
// - 使用回调接口将布局参数更新通知给调用方，保持控制器无状态
// - 缩放系数通过 SharedPreferences 持久化，用户调整后下次打开自动恢复
//
// 缩放策略：
// - 基础宽度：228dp（与 OverlayWindowController 保持一致）
// - 宽度范围：0.5 倍 ~ 2.0 倍基础宽度（即 114dp ~ 456dp）
// - 缩放增量：取 dx 和 dy 变化的平均值，使缩放更平滑
// - 持久化：仅当缩放系数不为 1.0 时写入 SharedPreferences

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 缩放事件回调接口。
 *
 * 由 OverlayPreviewManager 实现，接收缩放过程中的宽度更新。
 * 调用方负责将新宽度应用到 WindowManager.LayoutParams 并调用 updateViewLayout。
 */
interface OnResizeUpdateListener {
    /**
     * 缩放宽度更新回调。
     *
     * @param newWidth 新的悬浮窗宽度（px）
     * @param scaleRatio 当前缩放系数（相对初始宽度的倍数）
     */
    fun onResized(newWidth: Int, scaleRatio: Float)
}

/**
 * 悬浮窗缩放控制器。
 *
 * 构造函数接收缩放手柄 View 和回调接口，
 * 在 attach() 调用后自动处理缩放事件。
 *
 * 缩放策略：
 * - 仅在右下角 resizeHandle 内响应缩放
 * - 宽度范围：0.5 倍 ~ 2.0 倍基础宽度
 * - 缩放系数保存到 SharedPreferences，下次打开悬浮窗时恢复
 * - 缩放增量取 dx/dy 平均值，使对角线拖动也能平滑缩放
 *
 * @param resizeHandle 缩放手柄 View（拖动源），可为 null（attach 时静默跳过）
 * @param context 上下文（用于 SharedPreferences 读写和 dp 转换）
 * @param listener 缩放更新回调，接收新宽度和缩放系数
 */
class OverlayResizeController(
    private val resizeHandle: View?,  // 允许 null，attach() 时会静默跳过
    private val context: Context,
    private val listener: OnResizeUpdateListener,
) {

    companion object {
        private const val TAG = "HZZS-ResizeCtrl"

        /** 悬浮窗缩放系数 SharedPreferences 文件名 */
        private const val PREFS_NAME = "hzzs_overlay_prefs"

        /** 缩放系数参数键，与 OverlaySettingsBinder.KEY_SCALE_RATIO 共用同一份存储 */
        private const val KEY_SCALE_RATIO = "overlay_scale_ratio"

        /** 基础宽度（dp），与 OverlayWindowController.BASE_WIDTH_DP 保持一致 */
        private const val BASE_WIDTH_DP = 228

        /** 最小宽度倍数，缩放下限为初始宽度的 0.7 倍（防止缩得太小无法辨认） */
        private const val MIN_WIDTH_MULTIPLIER = 0.7f

        /** 最大宽度倍数，缩放上限为初始宽度的 2.0 倍 */
        private const val MAX_WIDTH_MULTIPLIER = 2.0f
    }

    /** 应用上下文，用于避免内存泄漏 */
    private val appContext get() = context.applicationContext

    /** 屏幕显示度量信息，用于 dp/px 转换 */
    private val displayMetrics get() = appContext.resources.displayMetrics

    /** 缩放起始时的窗口宽度（px），在 ACTION_DOWN 时记录 */
    private var initialWidth = 0

    /** 当前缩放系数（相对初始宽度的倍数），用于计算新宽度和持久化 */
    private var currentScaleRatio = 1f

    /** 手指按下时的屏幕 X 坐标，用于计算 dx */
    private var downRawX = 0f

    /** 手指按下时的屏幕 Y 坐标，用于计算 dy */
    private var downRawY = 0f

    /**
     * 绑定缩放事件监听器。
     *
     * 此方法应在 View inflate 完成后调用。
     * 如果 resizeHandle 为 null，attach() 不做任何操作（静默跳过）。
     */
    fun attach() {
        // resizeHandle 可能为 null（布局中没有 resizeHandle 控件时）
        if (resizeHandle == null) {
            return
        }

        resizeHandle.setOnTouchListener { _, event ->
            handleMotionEvent(event)
        }
    }

    /**
     * 处理触摸事件。
     *
     * 缩放逻辑：
     * - ACTION_DOWN：记录初始宽度和手指位置，初始宽度取 measuredWidth 或 fallback 到 BASE_WIDTH_DP
     * - ACTION_MOVE：计算 dx/dy 的平均值作为缩放增量，限制在 [0.5x, 2.0x] 范围内，
     *   通过 listener 回调通知调用方更新布局参数
     * - ACTION_UP/CANCEL：将当前缩放系数保存到 SharedPreferences（仅当系数 != 1.0 时）
     *
     * @param event 触摸事件
     * @return true 表示事件已处理（ACTION_MOVE/UP 返回 true，ACTION_DOWN 返回 false 让其他监听器处理）
     */
    private fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                // 初始宽度：优先使用缩放手柄的实际测量宽度，
                // 如果尚未测量（measuredWidth == 0），fallback 到 BASE_WIDTH_DP 转换后的 px 值
                initialWidth = resizeHandle?.measuredWidth.takeIf { it != null && it > 0 }
                    ?: dp(BASE_WIDTH_DP)
                return true  // 拥有手势所有权，确保后续 ACTION_MOVE 能正确传递
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                // 取 x 和 y 变化的平均值作为缩放增量，
                // 使对角线拖动也能产生平滑的缩放效果，避免单向拖动过猛
                val delta = (dx + dy) / 2

                // 计算新宽度并限制在 [0.5x, 2.0x] 范围内
                val newWidth = (initialWidth + delta).coerceIn(
                    (initialWidth * MIN_WIDTH_MULTIPLIER).roundToInt(),
                    (initialWidth * MAX_WIDTH_MULTIPLIER).roundToInt()
                )
                // 更新当前缩放系数
                currentScaleRatio = newWidth.toFloat() / initialWidth

                // 通知调用方更新布局参数
                listener.onResized(newWidth, currentScaleRatio)
                return true  // 已处理，不再传递给其他监听器
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 保存缩放系数到 SharedPreferences（仅当系数不为 1 时，避免无意义的写入）
                if (currentScaleRatio != 1f) {
                    saveScaleRatio(currentScaleRatio)
                }
                return true
            }

            else -> return false
        }
    }

    /**
     * 保存缩放系数到 SharedPreferences。
     *
     * 使用 apply() 异步写入，不阻塞 UI 线程。
     *
     * @param ratio 缩放系数（如 1.5 表示 1.5 倍宽度）
     */
    private fun saveScaleRatio(ratio: Float) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SCALE_RATIO, ratio).apply()
    }

    /**
     * 从 SharedPreferences 加载上次保存的缩放系数。
     *
     * 供 OverlayPreviewManager 在 show() 时调用，
     * 恢复用户上次调整的悬浮窗宽度。
     *
     * @return 缩放系数，默认为 1.0f（无缩放）
     */
    fun loadSavedScaleRatio(): Float {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SCALE_RATIO, 1f)
    }

    /**
     * dp 转 px 工具方法。
     *
     * 使用四舍五入确保像素值为整数，避免半像素导致的绘制模糊。
     *
     * @param value dp 值
     * @return 对应的 px 值（四舍五入）
     */
    private fun dp(value: Int): Int {
        return (value * displayMetrics.density + 0.5f).toInt()
    }
}
