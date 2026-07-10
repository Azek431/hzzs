// 火崽崽助手（HZZS）悬浮窗缩放控制器。
//
// 职责：
// - 处理右下角缩放手柄（overlayResizeHandle）的自由宽高缩放事件
// - 根据手指拖动方向决定缩放维度：横向→宽度，纵向→高度，对角线→两者同时
// - 设置最小尺寸限制（100x100px），防止缩到看不见
// - 缩放后不持久化，每次关闭再打开都恢复默认大小
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理透明度/自动操作滑块绑定（OverlaySettingsBinder 已移除，逻辑内聚到 OverlayPreviewManager）
//
// 设计原因：
// - 自由宽高缩放让用户可以精确控制悬浮窗尺寸
// - 不持久化避免保存错误尺寸导致布局崩溃
// - 最小尺寸限制保证内容始终可读

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 缩放事件回调接口。
 *
 * 由 OverlayPreviewManager 实现，接收缩放过程中的宽度和高度更新。
 * 调用方负责将新尺寸应用到 WindowManager.LayoutParams 并调用 updateViewLayout。
 */
interface OnResizeUpdateListener {
    /**
     * 缩放尺寸更新回调。
     *
     * @param newWidth 新的悬浮窗宽度（px）
     * @param newHeight 新的悬浮窗高度（px）
     */
    fun onResized(newWidth: Int, newHeight: Int)
}

/**
 * 悬浮窗缩放控制器。
 *
 * 构造函数接收缩放手柄 View 和回调接口，
 * 在 attach() 调用后自动处理缩放事件。
 *
 * 缩放策略：
 * - 按住缩放手柄拖动时，根据手指移动方向决定缩放维度
 * - 横向移动（|dx| > |dy|）→ 只缩放宽度
 * - 纵向移动（|dy| > |dx|）→ 只缩放高度
 * - 对角线移动（|dx| ≈ |dy|）→ 同时缩放宽度和高度
 * - 最小尺寸：100x100px，防止缩到看不见
 * - 不持久化：每次关闭再打开悬浮窗都恢复默认大小
 *
 * @param resizeHandle 缩放手柄 View（拖动源），可为 null（attach 时静默跳过）
 * @param context 上下文（用于 dp/px 转换）
 * @param listener 缩放更新回调，接收新宽度和新高度
 */
class OverlayResizeController(
    private val resizeHandle: View?,
    private val context: Context,
    private val listener: OnResizeUpdateListener,
) {

    companion object {
        /** 悬浮窗最小宽度（px），防止缩到看不见 */
        private const val MIN_SIZE_PX = 100

        /** 悬浮窗基础宽度（dp），用于计算初始宽度 fallback */
        private const val BASE_WIDTH_DP = 228

        /** 悬浮窗基础高度（dp），用于计算初始高度 fallback */
        private const val BASE_HEIGHT_DP = 300
    }

    private val displayMetrics get() = context.resources.displayMetrics

    /** 手指按下时的屏幕 X 坐标 */
    private var downRawX = 0f

    /** 手指按下时的屏幕 Y 坐标 */
    private var downRawY = 0f

    /** 缩放起始时的悬浮窗宽度（px） */
    private var initialWidth = 0

    /** 缩放起始时的悬浮窗高度（px） */
    private var initialHeight = 0

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
     * 初始化缩放基准尺寸。
     *
     * 在手指按下时调用，记录当前悬浮窗的实际尺寸。
     * 优先使用 view 的 measuredWidth/measuredHeight，
     * 如果尚未测量（值为 0），fallback 到基础尺寸转换后的 px 值。
     *
     * @param view 悬浮窗根 View
     */
    fun initializeDimensions(view: View) {
        initialWidth = view.measuredWidth.takeIf { it > 0 } ?: dp(BASE_WIDTH_DP)
        initialHeight = view.measuredHeight.takeIf { it > 0 } ?: dp(BASE_HEIGHT_DP)
    }

    /**
     * 处理触摸事件。
     *
     * 缩放逻辑：
     * - ACTION_DOWN：记录手指按下位置和当前悬浮窗尺寸
     * - ACTION_MOVE：根据手指移动方向判断缩放维度
     *   - 横向为主：只改变宽度
     *   - 纵向为主：只改变高度
     *   - 对角线：同时改变宽度和高度
     * - ACTION_UP/CANCEL：不保存任何数据（每次重新打开恢复默认）
     *
     * @param event 触摸事件
     * @return true 表示事件已处理
     */
    private fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()

                // 判断缩放维度：横向为主 / 纵向为主 / 对角线
                val newWidth = when {
                    abs(dx) > abs(dy) -> {
                        // 横向拖动为主 → 只缩放宽度
                        (initialWidth + dx).coerceIn(MIN_SIZE_PX, Integer.MAX_VALUE)
                    }
                    abs(dy) > abs(dx) -> {
                        // 纵向拖动为主 → 只缩放高度
                        initialWidth
                    }
                    else -> {
                        // 对角线拖动 → 同时缩放宽度和高度
                        ((initialWidth + dx + dy) / 2).coerceIn(
                            MIN_SIZE_PX - initialWidth,
                            Integer.MAX_VALUE - initialWidth
                        ).let { initialWidth + it }
                    }
                }.coerceAtLeast(MIN_SIZE_PX)

                val newHeight = when {
                    abs(dy) > abs(dx) -> {
                        // 纵向拖动为主 → 只缩放高度
                        (initialHeight + dy).coerceIn(MIN_SIZE_PX, Integer.MAX_VALUE)
                    }
                    abs(dx) > abs(dy) -> {
                        // 横向拖动为主 → 只缩放宽度
                        initialHeight
                    }
                    else -> {
                        // 对角线拖动 → 同时缩放宽度和高度
                        ((initialHeight + dx + dy) / 2).coerceIn(
                            MIN_SIZE_PX - initialHeight,
                            Integer.MAX_VALUE - initialHeight
                        ).let { initialHeight + it }
                    }
                }.coerceAtLeast(MIN_SIZE_PX)

                listener.onResized(newWidth, newHeight)
                return true
            }

            else -> return false
        }
    }

    /**
     * dp 转 px 工具方法。
     *
     * @param value dp 值
     * @return 对应的 px 值（四舍五入）
     */
    private fun dp(value: Int): Int {
        return (value * displayMetrics.density + 0.5f).roundToInt()
    }
}
