// 火崽崽助手（HZZS）自动操作服务 — 手势注入器。
//
// 职责：
// - 将归一化坐标转换为屏幕像素坐标
// - 通过 dispatchGesture() 注入触摸事件
//
// 设计原因：
// - 由于 minSdk=24，统一使用 dispatchGesture API
// - 不再需要 deprecated 的 injectMotionEvent
// - 所有操作类型（JUMP/SLIDE/DOUBLE_JUMP/TAP）最终都注入为短触摸事件
// - 不同操作类型可通过 targetX/targetY 区分注入位置
//
// 触摸事件说明：
// - dispatchGesture 是唯一官方推荐的无障碍触摸注入方式
// - 所有操作统一为 10ms 短触摸，区别仅在注入位置
// - 归一化坐标 (0~1) × 屏幕尺寸 = 像素坐标

package top.azek431.hzzs.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log

/**
 * 手势注入器。
 *
 * 将 QueuedAction 转换为实际的触摸事件，通过 dispatchGesture 注入。
 * 所有操作类型统一为短触摸（10ms），区别仅在注入位置。
 *
 * 使用流程：
 * 1. AutoOperationService.processNextAction() 从队列取出操作
 * 2. 调用 GestureInjector.inject(service, action, displayMetrics)
 * 3. 内部将归一化坐标转为像素坐标，构造 GestureDescription
 * 4. 通过 service.dispatchGesture() 提交给系统处理
 */
object GestureInjector {

    private const val TAG = "HZZS-Gesture"

    /** 触摸持续时间（毫秒），10ms 足够模拟一次短触摸 */
    private const val TOUCH_DURATION_MS = 10L

    /**
     * 执行触摸操作。
     *
     * 将归一化坐标 (targetX, targetY) 转换为屏幕像素坐标，
     * 然后通过 dispatchGesture 注入一次短触摸事件。
     *
     * @param service 无障碍服务实例（提供 dispatchGesture 能力）
     * @param action 要执行的操作（包含操作类型和目标坐标）
     * @param metrics 屏幕显示度量（用于获取屏幕尺寸）
     */
    fun inject(
        service: AccessibilityService,
        action: QueuedAction,
        metrics: DisplayMetrics,
    ) {
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        // 将归一化坐标转换为屏幕像素坐标
        val x = action.targetX * screenW
        val y = action.targetY * screenH

        // 所有操作类型统一为短触摸，区别仅在注入位置
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x, y) },  // 触摸起点
                    0L,                               // 延迟 0ms 开始
                    TOUCH_DURATION_MS,                // 触摸持续 10ms
                )
            )
            .build()

        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "[Inject] ${action.type} at ($x, $y)")
    }
}
