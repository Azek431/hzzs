package top.azek431.hzzs.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * The only production owner of dispatchGesture(). Every caller goes through
 * GestureArbiter, so gestures cannot cancel each other by racing.
 */
class HzzsAccessibilityService : AccessibilityService(), GestureDispatcher {
    private val foreground = AtomicReference<ForegroundWindow?>(null)

    override fun onServiceConnected() {
        current.set(this)
        super.onServiceConnected()
    }

    override fun onDestroy() {
        current.compareAndSet(this, null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        foreground.set(ForegroundWindow(pkg, cls, SystemClock.elapsedRealtime()))
    }

    override fun onInterrupt() = Unit

    override suspend fun dispatch(action: AutomationAction): DispatchReceipt {
        val window = foreground.get()
        if (window == null || SystemClock.elapsedRealtime() - window.observedAtMs > 1_500L) {
            return DispatchReceipt(action, DispatchOutcome.REJECTED, "前台窗口状态已过期")
        }
        if (window.packageName !in action.allowedPackages || !action.matchesWindow(window.className)) {
            return DispatchReceipt(action, DispatchOutcome.REJECTED, "当前页面不在允许范围")
        }
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(action.gesture.startX.coerceIn(0f, 1f) * (metrics.widthPixels - 1), action.gesture.startY.coerceIn(0f, 1f) * (metrics.heightPixels - 1))
            if (action.gesture.endX != null && action.gesture.endY != null) {
                lineTo(action.gesture.endX.coerceIn(0f, 1f) * (metrics.widthPixels - 1), action.gesture.endY.coerceIn(0f, 1f) * (metrics.heightPixels - 1))
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, action.gesture.durationMs.coerceIn(10, 600)))
            .build()
        val result = CompletableDeferred<DispatchReceipt>()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.complete(DispatchReceipt(action, DispatchOutcome.COMPLETED, null))
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(DispatchReceipt(action, DispatchOutcome.CANCELLED, "系统取消手势"))
            }
        }, null)
        if (!accepted) return DispatchReceipt(action, DispatchOutcome.REJECTED, "系统拒绝手势")
        return result.await()
    }

    data class ForegroundWindow(val packageName: String, val className: String, val observedAtMs: Long)

    companion object {
        private val current = AtomicReference<HzzsAccessibilityService?>(null)
        private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hzzs-accessibility-screenshot").apply { isDaemon = true }
        }

        fun isConnected(): Boolean = current.get() != null

        fun foregroundSnapshot(): ForegroundWindow? = current.get()?.foreground?.get()

        suspend fun dispatchCurrent(action: AutomationAction): DispatchReceipt {
            val service = current.get()
                ?: return DispatchReceipt(action, DispatchOutcome.REJECTED, "无障碍服务未连接")
            return service.dispatch(action)
        }

        suspend fun captureBitmap(): Bitmap? {
            if (Build.VERSION.SDK_INT < 30) return null
            val service = current.get() ?: return null
            return suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardware = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                            val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hardware.close()
                            if (continuation.isActive) continuation.resume(software)
                            else software?.recycle()
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }
    }

}
