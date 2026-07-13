// 火崽崽助手（HZZS）无障碍服务。
//
// 本类是项目唯一注册的无障碍服务入口，统一承载：
// - Android 11+ 无障碍截图代理；
// - 旧版坐标动作队列与实时视觉动作队列调度；
// - 前台目标应用校验和服务生命周期清理。

package top.azek431.hzzs.features.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import top.azek431.hzzs.core.util.FeatureFlags

/** 项目唯一的无障碍截图与可选动作调度入口。 */
class AutoOperationService : AccessibilityService() {

    companion object {
        private const val TAG = "HZZS-AutoOp"

        /** 仅允许在已明确适配的宿主应用前台执行动作。 */
        private val allowedActionPackages = setOf(
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
        )

        @Volatile
        private var instance: AutoOperationService? = null

        /** 只由窗口状态事件更新，不再采用任意无障碍事件的来源包名。 */
        @Volatile
        private var foregroundPackage: String? = null

        /** 供截图模式和设置页判断服务是否已连接。 */
        fun isConnected(): Boolean = instance != null

        /** 最近一次可信窗口状态事件对应的包名。 */
        fun foregroundPackageName(): String? = foregroundPackage

        /** 动作调度采用失败关闭：服务未连接、目标未知或不在允许列表时均返回 false。 */
        fun isActionTargetAllowed(): Boolean =
            instance != null && foregroundPackage in allowedActionPackages

        /**
         * 通过当前服务请求系统截图。
         * 截图能力与动作开关解耦；动作目标校验不会阻止用户主动授权的截图。
         */
        fun proxyTakeScreenshot(
            displayId: Int,
            executor: java.util.concurrent.Executor,
            callback: AccessibilityService.TakeScreenshotCallback,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val service = instance ?: return false
            return try {
                service.takeScreenshot(displayId, executor, callback)
                true
            } catch (exception: Exception) {
                Log.w(TAG, "[Screenshot] request failed: ${exception.message}")
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val processRunnable = object : Runnable {
        override fun run() {
            if (isActionTargetAllowed()) {
                processRuntimeAction()
                processLegacyAction()
            } else {
                clearActionQueues()
            }

            val legacyDelay = AutoActionQueue.getDelay().toLong().coerceAtLeast(8L)
            handler.postDelayed(this, minOf(legacyDelay, RuntimeActionQueue.nextDelay()))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        foregroundPackage = null

        // 旧版队列只恢复用户明确保存的开关；默认始终关闭。
        AutoActionQueue.setDelay(FeatureFlags.getAutoOperationDelayMs(this))
        AutoActionQueue.setEnabled(FeatureFlags.isAutoOperationEnabled(this))
        RuntimeActionQueue.setEnabled(false)

        handler.removeCallbacks(processRunnable)
        handler.post(processRunnable)
        Log.i(TAG, "[Service] connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacks(processRunnable)
        clearActionQueues()
        RuntimeActionQueue.setEnabled(false)
        foregroundPackage = null
        if (instance === this) instance = null
        Log.i(TAG, "[Service] disconnected")
    }

    private fun processLegacyAction() {
        if (!isActionTargetAllowed()) return
        val action = AutoActionQueue.dequeue() ?: return
        GestureInjector.inject(this, action, resources.displayMetrics)
        Log.d(TAG, "[Legacy] executed ${action.type}")
    }

    /** 实时队列的手势被系统取消时，仅在原过期时间内短延迟重试。 */
    private fun processRuntimeAction() {
        if (!isActionTargetAllowed()) return
        val action = RuntimeActionQueue.pollDue() ?: return
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription) {
                if (isActionTargetAllowed()) RuntimeActionQueue.retry(action)
            }
        }
        val accepted = RuntimeGestureInjector.inject(
            service = this,
            action = action,
            metrics = resources.displayMetrics,
            callback = callback,
        )
        if (!accepted && isActionTargetAllowed()) {
            RuntimeActionQueue.retry(action)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 窗口状态事件代表前台界面切换；空包名同样按未知目标处理。
        foregroundPackage = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (!isActionTargetAllowed()) clearActionQueues()
    }

    override fun onInterrupt() {
        foregroundPackage = null
        clearActionQueues()
    }

    private fun clearActionQueues() {
        AutoActionQueue.clear()
        RuntimeActionQueue.clear()
    }
}
