// 火崽崽助手（HZZS）无障碍服务 — 自动操作核心。
//
// 工作原理：
// 1. 用户授权无障碍权限后，系统会回调此服务
// 2. 当悬浮窗检测到 ActionPrompt 时，通过 AutoActionQueue 将操作加入队列
// 3. 服务按配置的延迟执行触摸事件注入
//
// 关键设计：
// - AutoActionQueue — 队列管理（入队/出队/清空/暂停/开关/延迟）
// - GestureInjector — 手势注入（坐标转换 + dispatchGesture）
// - 本类只负责生命周期管理和调度
//
// 注意：
// - Android 12+ 使用 dispatchGesture() API
// - 由于 minSdk=24，不再需要 deprecated 的 injectMotionEvent
// - 需要在系统设置中手动开启此服务的无障碍权限

package top.azek431.hzzs.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 无障碍自动操作服务。
 *
 * 负责生命周期管理和操作调度。
 * 队列管理委托给 AutoActionQueue，手势注入委托给 GestureInjector。
 */
class AutoOperationService : AccessibilityService() {

    companion object {
        private const val TAG = "HZZS-AutoOp"
    }

    // ==================== 生命周期 ====================

    /**
     * Handler 运行在主线程，用于定时处理队列中的下一个操作。
     * processRunnable 每 operationDelayMs 毫秒触发一次，调用 processNextAction()。
     */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 定时处理任务：每次执行后重新 postDelayed，形成循环调度。
     * 注意：此 Runnable 在 onUnbind 时必须 remove，防止服务销毁后继续执行。
     */
    private val processRunnable = object : Runnable {
        override fun run() {
            processNextAction()
            handler.postDelayed(this, AutoActionQueue.getDelay().toLong())
        }
    }

    /**
     * 服务连接时启动定时处理循环。
     *
     * 系统回调此方法，表示无障碍权限已授予，服务已就绪。
     * 立即启动 processRunnable，开始处理队列中的操作。
     */
    override fun onServiceConnected() {
        Log.i(TAG, "[Service] connected, starting processing loop.")
        handler.post(processRunnable)
    }

    /**
     * 服务取消绑定时清理资源。
     *
     * 移除所有 pending 的 Runnable，防止服务销毁后继续执行导致崩溃。
     *
     * @return true 表示允许重新绑定
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(processRunnable)
        Log.d(TAG, "[Service] disconnected, processing loop stopped.")
        return super.onUnbind(intent)
    }

    // ==================== 操作调度 ====================

    /**
     * 处理队列中的下一个操作。
     *
     * 流程：
     * 1. 从 AutoActionQueue 中取出下一个操作
     * 2. 如果队列为空或已暂停/禁用，直接返回
     * 3. 通过 GestureInjector 执行触摸事件注入
     */
    private fun processNextAction() {
        val action = AutoActionQueue.dequeue() ?: return

        // 双重检查：防止在 dequeue 和 execute 之间状态发生变化
        if (AutoActionQueue.getDelay() < 0) return

        GestureInjector.inject(this, action, resources.displayMetrics)
        Log.d(TAG, "[Service] executed action: ${action.type} at (${action.targetX}, ${action.targetY})")
    }

    // ==================== 无障碍回调（当前不使用） ====================

    /**
     * 无障碍事件回调。
     *
     * 当前未使用，留作未来扩展（如监听屏幕内容变化）。
     */
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    /**
     * 无障碍中断回调。
     *
     * 当系统中断无障碍服务时调用（如切换到其他应用的无障碍服务）。
     * 当前未使用特殊处理，留作未来扩展。
     */
    override fun onInterrupt() {}
}
