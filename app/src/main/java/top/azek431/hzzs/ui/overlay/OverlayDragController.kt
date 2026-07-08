// 火崽崽助手（HZZS）悬浮窗拖动控制器。
//
// 职责：
// - 处理顶部标题栏（overlayDragHandle）的拖动事件
// - 计算拖动距离，判断是否超过最小阈值（避免手指抖动误触）
// - 更新 WindowManager.LayoutParams 的 x/y 坐标
//
// 不负责：
// - 不处理缩放逻辑（由 OverlayResizeController 处理）
// - 不处理滑块绑定（由 OverlaySettingsBinder 处理）
//
// 设计原因：
// - 拖动逻辑独立封装，便于将来修改拖动策略（如添加惯性、吸附边缘等）
// - 使用回调接口将布局参数更新通知给调用方，保持控制器无状态
//
// 拖动策略：
// - 最小拖动距离：10px，低于此值不触发拖动（避免手指抖动误触）
// - 边界限制：X/Y 坐标由调用方通过 coerceIn 限制，控制器不直接处理
// - 标题栏区域：仅在 overlayDragHandle 内响应拖动，不影响其他控件

package top.azek431.hzzs.ui.overlay

import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * 拖动事件回调接口。
 *
 * 由 OverlayPreviewManager 实现，接收拖动过程中的位置更新。
 * 调用方负责将新坐标应用到 layoutParams 并调用 updateViewLayout。
 */
interface OnDragUpdateListener {
    /**
     * 拖动位置更新回调。
     *
     * @param x 新的窗口 X 坐标（绝对坐标，由 startX + dx 计算得出）
     * @param y 新的窗口 Y 坐标（绝对坐标，由 startY + dy 计算得出）
     */
    fun onDragUpdated(x: Int, y: Int)
}

/**
 * 悬浮窗拖动控制器。
 *
 * 构造函数接收需要监听的 View 和回调接口，
 * 在 attach() 调用后自动处理拖动事件。
 *
 * 拖动策略：
 * - 最小拖动距离：10px，低于此值不触发拖动（避免手指抖动误触）
 * - 边界限制：X/Y 坐标由调用方通过 coerceIn 限制，控制器不直接处理
 * - 标题栏区域：仅在 overlayDragHandle 内响应拖动，不影响其他控件
 *
 * @param dragHandle 标题栏 View（拖动源）
 * @param listener 拖动更新回调
 */
class OverlayDragController(
    private val dragHandle: View,
    private val listener: OnDragUpdateListener,
) {

    companion object {
        /** 最小拖动距离（像素），约 10dp，避免手指抖动误触 */
        private const val MIN_DRAG_DISTANCE_PX = 10
    }

    /** 拖动起始时的窗口 X 坐标，由 recordStartPosition() 设置 */
    private var startX = 0

    /** 拖动起始时的窗口 Y 坐标，由 recordStartPosition() 设置 */
    private var startY = 0

    /** 是否已确认启动拖动（超过阈值后才为 true） */
    private var isDragging = false

    /** 手指按下时的屏幕 X 坐标（rawX），用于计算 dx */
    private var downRawX = 0f

    /** 手指按下时的屏幕 Y 坐标（rawY），用于计算 dy */
    private var downRawY = 0f

    /**
     * 绑定拖动事件监听器。
     *
     * 此方法应在 View inflate 完成后调用。
     * 将 OnTouchListener 设置到 dragHandle 上。
     */
    fun attach() {
        dragHandle.setOnTouchListener { _, event ->
            handleMotionEvent(event)
        }
    }

    /**
     * 处理触摸事件。
     *
     * 拖动逻辑：
     * - ACTION_DOWN：记录手指按下位置，设置 isDragging = false
     *   返回 false 让标题栏自身 clickable 事件正常响应
     * - ACTION_MOVE：计算 dx/dy 的欧几里得距离
     *   - 距离 < 10px：不启动拖动，返回 false
     *   - 距离 >= 10px 且首次超过：设置 isDragging = true
     *   - 已确认拖动：更新 layoutParams.x/y 并通过 listener 通知调用方
     * - ACTION_UP/CANCEL：重置 isDragging = false
     *
     * @param event 触摸事件
     * @return true 表示已确认拖动中（事件已处理），false 表示未启动拖动
     */
    private fun handleMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                isDragging = false
                // 让标题栏自身 clickable 事件正常响应
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                // 计算欧几里得距离，避免单向微小移动误触发拖动
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toInt()

                // 位移未达到最小阈值前，不启动拖动——让点击事件正常传递
                if (!isDragging && distance < MIN_DRAG_DISTANCE_PX) {
                    return false
                }

                // 首次超过阈值：确认启动拖动
                if (!isDragging) {
                    isDragging = true
                }

                // 通知调用方更新布局参数（传入绝对坐标）
                listener.onDragUpdated(startX + dx, startY + dy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }

            else -> return false
        }
    }

    /**
     * 记录拖动起始位置。
     *
     * 在 ACTION_DOWN 时调用，保存当前的窗口坐标（layoutParams.x/y）。
     * 后续 ACTION_MOVE 计算出的 dx/dy 会叠加到此起始位置上，
     * 得到新的窗口绝对坐标。
     *
     * @param x 当前窗口 X 坐标
     * @param y 当前窗口 Y 坐标
     */
    fun recordStartPosition(x: Int, y: Int) {
        startX = x
        startY = y
    }
}
