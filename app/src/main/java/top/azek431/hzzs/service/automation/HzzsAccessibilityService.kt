package top.azek431.hzzs.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureDispatcher
import top.azek431.hzzs.domain.automation.GestureSpec
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
 * - 前台包名/类名快照超过约 [FOREGROUND_STALE_MS] 视为过期，派发前会主动 [refreshForegroundLocked]；
 * - 仅当 [AutomationAction.allowedPackages] 非空时做包名门控；空集表示用户未开启限制；
 * - 调用方须经 GestureArbiter，避免并发手势互相取消；
 * - 服务未连接时 companion 入口 fail-closed。
 *
 * 线程：Accessibility 回调与 dispatch 使用主线程；截图回调在专用守护线程，结果回主线程续体。
 */
class HzzsAccessibilityService : AccessibilityService(), GestureDispatcher {
    private val foreground = AtomicReference<ForegroundWindow?>(null)

    override fun onServiceConnected() {
        current.set(this)
        // 连接后立刻采一次前台，避免「已连接但尚无 WINDOW 事件」导致 skip:no_foreground。
        refreshForegroundLocked()
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

    /**
     * 刷新前台包名/类名快照。
     * 订阅 WINDOW_STATE / WINDOW_CONTENT / WINDOWS_CHANGED，游戏内长时间无切窗时仍可更新。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> {
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString().orEmpty()
                if (!pkg.isNullOrBlank()) {
                    foreground.set(ForegroundWindow(pkg, cls, SystemClock.elapsedRealtime()))
                } else {
                    refreshForegroundLocked()
                }
            }
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    /**
     * 主线程分发手势：校验前台快照与允许包，路径坐标 clamp 到 [0,1] 后映射像素。
     * 系统拒绝、取消或前台不匹配均返回明确 [DispatchOutcome]，不抛异常。
     */
    override suspend fun dispatch(action: AutomationAction): DispatchReceipt =
        withContext(Dispatchers.Main.immediate) {
            val window = ensureFreshForeground()
                ?: return@withContext DispatchReceipt(action, DispatchOutcome.REJECTED, "前台窗口状态不可用")
            if (!action.matchesPackage(window.packageName) || !action.matchesWindow(window.className)) {
                return@withContext DispatchReceipt(action, DispatchOutcome.REJECTED, "当前页面不在允许范围")
            }
            val metrics = resources.displayMetrics
            val first = dispatchStroke(action, action.gesture, metrics.widthPixels, metrics.heightPixels)
            if (first.outcome != DispatchOutcome.COMPLETED) return@withContext first
            // 点击类手势可携带 doublePressDelayMs：完成第一次后延迟再发第二次。
            val doubleDelay = action.gesture.doublePressDelayMs
            val isClick = action.gesture.endX == null && action.gesture.endY == null
            if (isClick && doubleDelay > 0L) {
                delay(doubleDelay.coerceIn(1L, 2_000L))
                val second = dispatchStroke(action, action.gesture, metrics.widthPixels, metrics.heightPixels)
                if (second.outcome != DispatchOutcome.COMPLETED) return@withContext second
            }
            DispatchReceipt(action, DispatchOutcome.COMPLETED, null)
        }

    /**
     * 将单次 [GestureSpec] 映射为系统 [GestureDescription] 并等待回执。
     * 坐标 clamp 到 [0,1] 后按屏幕像素换算。
     */
    private suspend fun dispatchStroke(
        action: AutomationAction,
        gesture: GestureSpec,
        widthPixels: Int,
        heightPixels: Int,
    ): DispatchReceipt {
        val endX = gesture.endX
        val endY = gesture.endY
        val path = Path().apply {
            moveTo(
                gesture.startX.coerceIn(0f, 1f) * (widthPixels - 1),
                gesture.startY.coerceIn(0f, 1f) * (heightPixels - 1),
            )
            if (endX != null && endY != null) {
                lineTo(
                    endX.coerceIn(0f, 1f) * (widthPixels - 1),
                    endY.coerceIn(0f, 1f) * (heightPixels - 1),
                )
            }
        }
        val description = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    gesture.durationMs.coerceIn(10, 600),
                ),
            )
            .build()
        val result = CompletableDeferred<DispatchReceipt>()
        val accepted = dispatchGesture(description, object : GestureResultCallback() {
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

    /**
     * 若缓存过期则从 windows / active window 刷新；仍失败返回 null。
     * 必须在主线程调用。
     */
    private fun ensureFreshForeground(): ForegroundWindow? {
        val cached = foreground.get()
        val now = SystemClock.elapsedRealtime()
        if (cached != null && now - cached.observedAtMs <= FOREGROUND_STALE_MS) {
            return cached
        }
        return refreshForegroundLocked()
    }

    /**
     * 主动探测当前活动窗口的包名/类名并写入缓存。
     * 优先 active window，再扫 TYPE_APPLICATION 窗口。
     */
    private fun refreshForegroundLocked(): ForegroundWindow? {
        val now = SystemClock.elapsedRealtime()
        try {
            val active = rootInActiveWindow
            val activePkg = active?.packageName?.toString()
            val activeCls = active?.className?.toString().orEmpty()
            if (!activePkg.isNullOrBlank()) {
                val snap = ForegroundWindow(activePkg, activeCls, now)
                foreground.set(snap)
                runCatching { active.recycle() }
                return snap
            }
            runCatching { active?.recycle() }
        } catch (_: Throwable) {
            // 某些 ROM 在无 content 权限或窗口切换瞬间会抛；继续试 windows。
        }
        try {
            val windows = windows ?: return foreground.get()
            var best: ForegroundWindow? = null
            for (window in windows) {
                try {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    if (!window.isActive && !window.isFocused) continue
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString()
                    val cls = root.className?.toString().orEmpty()
                    runCatching { root.recycle() }
                    if (!pkg.isNullOrBlank()) {
                        best = ForegroundWindow(pkg, cls, now)
                        if (window.isActive) break
                    }
                } catch (_: Throwable) {
                    // 单窗口失败不中断扫描
                } finally {
                    runCatching { window.recycle() }
                }
            }
            if (best != null) {
                foreground.set(best)
                return best
            }
        } catch (_: Throwable) {
            // ignore
        }
        return foreground.get()
    }

    /** 最近观察到的前台窗口；[observedAtMs] 用于过期门禁。 */
    data class ForegroundWindow(val packageName: String, val className: String, val observedAtMs: Long)

    companion object {
        /** 缓存超过该毫秒视为陈旧，派发/快照时主动刷新。 */
        const val FOREGROUND_STALE_MS = 1_500L

        private val current = AtomicReference<HzzsAccessibilityService?>(null)
        private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hzzs-accessibility-screenshot").apply { isDaemon = true }
        }

        fun isConnected(): Boolean = current.get() != null

        /**
         * 只读前台快照；服务未连接返回 null。
         *
         * @param refreshIfStale true 时若缓存过期则主动探测（规划/派发路径应传 true）。
         * 主动探测可能触达 `rootInActiveWindow`/`windows`，须在主线程执行。
         */
        fun foregroundSnapshot(refreshIfStale: Boolean = false): ForegroundWindow? {
            val service = current.get() ?: return null
            val cached = service.foreground.get()
            if (!refreshIfStale) return cached
            val now = SystemClock.elapsedRealtime()
            if (cached != null && now - cached.observedAtMs <= FOREGROUND_STALE_MS) {
                return cached
            }
            return try {
                // Accessibility 窗口 API 要求主线程；帧循环可能在 Default 调用。
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    service.refreshForegroundLocked()
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val box = arrayOfNulls<ForegroundWindow>(1)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            box[0] = service.refreshForegroundLocked()
                        } catch (_: Throwable) {
                            box[0] = service.foreground.get()
                        } finally {
                            latch.countDown()
                        }
                    }
                    // 短等：超时则退回缓存，避免卡住帧循环。
                    latch.await(80, java.util.concurrent.TimeUnit.MILLISECONDS)
                    box[0] ?: service.foreground.get()
                }
            } catch (_: Throwable) {
                service.foreground.get()
            }
        }

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
