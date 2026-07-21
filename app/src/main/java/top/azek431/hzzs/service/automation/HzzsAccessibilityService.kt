package top.azek431.hzzs.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 生产环境唯一的 [dispatchGesture] 持有者。
 *
 * 职责：
 * - 主线程协调手势分发与前台窗口快照；
 * - 将归一化手势坐标换算为屏幕像素后投递系统；
 * - 可选 Android 11+ 无障碍截图（硬件缓冲立即拷贝后关闭）。
 *
 * 安全不变量：
 * - 前台包名/类名快照超过约 1.5s 视为过期，拒绝分发；
 * - 仅允许 [AutomationAction.allowedPackages] 与窗口匹配的动作；
 * - 调用方须经 GestureArbiter，避免并发手势互相取消；
 * - 服务未连接时 companion 入口 fail-closed。
 *
 * 线程：Accessibility 回调与 dispatch 使用主线程；截图回调在专用守护线程，结果回主线程续体。
 */
class HzzsAccessibilityService : AccessibilityService(), GestureDispatcher {
    private val foreground = AtomicReference<ForegroundWindow?>(null)

    override fun onServiceConnected() {
        current.set(this)
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent): Boolean {
        current.compareAndSet(this, null)
        foreground.set(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        current.compareAndSet(this, null)
        foreground.set(null)
        super.onDestroy()
    }

    /** 刷新前台包名/类名快照，供手势门禁使用。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        foreground.set(ForegroundWindow(pkg, cls, SystemClock.elapsedRealtime()))
    }

    override fun onInterrupt() = Unit

    /**
     * 主线程分发手势：校验前台快照与允许包，路径坐标 clamp 到 [0,1] 后映射像素。
     * 系统拒绝、取消或前台不匹配均返回明确 [DispatchOutcome]，不抛异常。
     */
    override suspend fun dispatch(action: AutomationAction): DispatchReceipt =
        withContext(Dispatchers.Main.immediate) {
            val window = foreground.get()
            if (window == null || SystemClock.elapsedRealtime() - window.observedAtMs > 1_500L) {
                return@withContext DispatchReceipt(action, DispatchOutcome.REJECTED, "前台窗口状态已过期")
            }
            if (window.packageName !in action.allowedPackages || !action.matchesWindow(window.className)) {
                return@withContext DispatchReceipt(action, DispatchOutcome.REJECTED, "当前页面不在允许范围")
            }
            val metrics = resources.displayMetrics
            val endX = action.gesture.endX
            val endY = action.gesture.endY
            val path = Path().apply {
                moveTo(
                    action.gesture.startX.coerceIn(0f, 1f) * (metrics.widthPixels - 1),
                    action.gesture.startY.coerceIn(0f, 1f) * (metrics.heightPixels - 1),
                )
                if (endX != null && endY != null) {
                    lineTo(
                        endX.coerceIn(0f, 1f) * (metrics.widthPixels - 1),
                        endY.coerceIn(0f, 1f) * (metrics.heightPixels - 1),
                    )
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
            if (!accepted) return@withContext DispatchReceipt(action, DispatchOutcome.REJECTED, "系统拒绝手势")
            result.await()
        }

    /** 最近观察到的前台窗口；[observedAtMs] 用于过期门禁。 */
    data class ForegroundWindow(val packageName: String, val className: String, val observedAtMs: Long)

    companion object {
        private val current = AtomicReference<HzzsAccessibilityService?>(null)
        private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hzzs-accessibility-screenshot").apply { isDaemon = true }
        }

        fun isConnected(): Boolean = current.get() != null

        /** 只读前台快照；服务未连接返回 null。 */
        fun foregroundSnapshot(): ForegroundWindow? = current.get()?.foreground?.get()

        suspend fun dispatchCurrent(action: AutomationAction): DispatchReceipt {
            val service = current.get()
                ?: return DispatchReceipt(action, DispatchOutcome.REJECTED, "无障碍服务未连接")
            return service.dispatch(action)
        }

        /**
         * Android 11+ 无障碍截图。
         * 硬件缓冲包装后立即拷贝为软件 ARGB_8888 并 close 硬件缓冲；
         * 协程已取消时回收软件图，避免泄漏。
         */
        suspend fun captureBitmap(): Bitmap? {
            if (Build.VERSION.SDK_INT < 30) return null
            val service = current.get() ?: return null
            return withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        screenshotExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val hardware = screenshot.hardwareBuffer
                                var software: Bitmap? = null
                                try {
                                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                                    try {
                                        software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                    } finally {
                                        wrapped?.recycle()
                                    }
                                } catch (_: Throwable) {
                                    software?.recycle()
                                    software = null
                                } finally {
                                    hardware.close()
                                }
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

}
