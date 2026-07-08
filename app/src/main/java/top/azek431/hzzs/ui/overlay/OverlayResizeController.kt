// 火崽崽助手（HZZS）悬浮窗缩放控制器。
//
// 职责：
// - 处理右下角缩放手柄（overlayResizeHandle）的缩放事件
// - 计算缩放距离，限制最小/最大宽度
// - 更新 WindowManager.LayoutParams 的 width
// - 缩放系数持久化到 SharedPreferences
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理滑块绑定（由 OverlaySettingsBinder 处理）
//
// 设计原因：
// - 缩放逻辑独立封装，便于将来修改缩放策略（如保持纵横比、添加缩放手势等）
// - 使用回调接口将布局参数更新通知给调用方，保持控制器无状态

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
 */
interface OnResizeUpdateListener {
    /** 缩放宽度更新 */
    fun onResized(newWidth: Int, scaleRatio: Float)
}

/**
 * 悬浮窗缩放控制器。
 *
 * 构造函数接收缩放手柄 View 和回调接口，
 * 在 attach() 调用后自动处理缩放事件。
 *
 * 缩放策略：
//  - 仅在右下角 resizeHandle 内响应缩放
//  - 宽度范围：0.5 倍 ~ 2.0 倍基础宽度
//  - 缩放系数保存到 SharedPreferences，下次打开悬浮窗时恢复
//
// @param resizeHandle 缩放手柄 View（拖动源）
// @param context 上下文（用于 SharedPreferences）
// @param listener 缩放更新回调
 */
class OverlayResizeController(
    private val resizeHandle: View,
    private val context: Context,
    private val listener: OnResizeUpdateListener,
) {

    companion object {
        private const val TAG = "HZZS-ResizeCtrl"

        /** 悬浮窗缩放系数 SharedPreferences 文件名 */
        private const val PREFS_NAME = "hzzs_overlay_prefs"

        /** 缩放系数参数键 */
        private const val KEY_SCALE_RATIO = "overlay_scale_ratio"

        /** 基础宽度（dp） */
        private const val BASE_WIDTH_DP = 228

        /** 最小宽度倍数 */
        private const val MIN_WIDTH_MULTIPLIER = 0.5f

        /** 最大宽度倍数 */
        private const val MAX_WIDTH_MULTIPLIER = 2.0f
    }

    private val appContext get() = context.applicationContext
    private val displayMetrics get() = appContext.resources.displayMetrics

    /** 缩放起始时的窗口宽度（px） */
    private var initialWidth = 0

    /** 当前缩放系数（相对初始宽度的倍数） */
    private var currentScaleRatio = 1f

    /** 手指按下时的屏幕 X 坐标 */
    private var downRawX = 0f

    /** 手指按下时的屏幕 Y 坐标 */
    private var downRawY = 0f

    /**
     * 绑定缩放事件监听器。
     *
     * 此方法应在 View inflate 完成后调用。
     * 如果 resizeHandle 为 null，attach() 不做任何操作。
     */
    fun attach() {
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
     * - ACTION_DOWN：记录初始宽度和手指位置
     * - ACTION_MOVE：计算 x/y 变化的平均值作为缩放增量
     *   更新 layoutParams.width 并通过 listener 通知调用方
     * - ACTION_UP/CANCEL：保存缩放系数到 SharedPreferences
     *
     * @param event 触摸事件
     * @return true 表示事件已处理
     */
    private fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                initialWidth = resizeHandle.measuredWidth.takeIf { it > 0 }
                    ?: dp(BASE_WIDTH_DP)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                // 取 x 和 y 变化的平均值，使缩放更平滑
                val delta = (dx + dy) / 2

                val newWidth = (initialWidth + delta).coerceIn(
                    (initialWidth * MIN_WIDTH_MULTIPLIER).roundToInt(),
                    (initialWidth * MAX_WIDTH_MULTIPLIER).roundToInt()
                )
                currentScaleRatio = newWidth.toFloat() / initialWidth

                // 通知调用方更新布局参数
                listener.onResized(newWidth, currentScaleRatio)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 保存缩放系数到 SharedPreferences（仅当系数不为 1 时）
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
     * @param ratio 缩放系数
     */
    private fun saveScaleRatio(ratio: Float) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SCALE_RATIO, ratio).apply()
    }

    /**
     * 从 SharedPreferences 加载上次保存的缩放系数。
     *
     * @return 缩放系数，默认为 1.0f
     */
    fun loadSavedScaleRatio(): Float {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SCALE_RATIO, 1f)
    }

    /**
     * dp 转 px 工具方法。
     *
     * @param value dp 值
     * @return 对应的 px 值（四舍五入）
     */
    private fun dp(value: Int): Int {
        return (value * displayMetrics.density + 0.5f).toInt()
    }
}
