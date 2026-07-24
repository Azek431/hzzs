package top.azek431.hzzs.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import java.util.concurrent.atomic.AtomicLong
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
    /**
     * 手势 generation：超时/取消后递增，使迟到的 GestureResultCallback 无法 complete 旧 deferred，
     * 避免与下一单叠点（配合 GestureArbiter 超时后持锁排空）。
     */
    private val gestureGeneration = AtomicLong(0)

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
     * 仅 WINDOW_STATE / WINDOWS_CHANGED 写入包名类名；
     * CONTENT_CHANGED 易带 widget 包/类，只刷新时间戳且要求包名与缓存一致。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString().orEmpty()
                if (!pkg.isNullOrBlank() && !looksLikeWidgetClass(cls)) {
                    foreground.set(ForegroundWindow(pkg, cls, SystemClock.elapsedRealtime()))
                } else {
                    refreshForegroundLocked()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()
                val cached = foreground.get()
                if (cached != null && !pkg.isNullOrBlank() && pkg == cached.packageName) {
                    // 同包 UI 刷新：仅续期，不覆盖可能更准的 Activity class。
                    foreground.set(cached.copy(observedAtMs = SystemClock.elapsedRealtime()))
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
            // 与 Shell/截图一致：优先真实显示尺寸，减少点偏。
            val (widthPx, heightPx) = realDisplaySize()
            val first = dispatchStroke(action, action.gesture, widthPx, heightPx)
            if (first.outcome != DispatchOutcome.COMPLETED) return@withContext first
            // 点击类手势可携带 doublePressDelayMs：完成第一次后延迟再发第二次。
            val doubleDelay = action.gesture.doublePressDelayMs
            val isClick = action.gesture.endX == null && action.gesture.endY == null
            if (isClick && doubleDelay > 0L) {
                delay(doubleDelay.coerceIn(1L, 2_000L))
                val recheck = ensureFreshForeground()
                if (recheck == null ||
                    !action.matchesPackage(recheck.packageName) ||
                    !action.matchesWindow(recheck.className)
                ) {
                    return@withContext DispatchReceipt(
                        action,
                        DispatchOutcome.REJECTED,
                        "双击间隔前台已变化",
                    )
                }
                val second = dispatchStroke(action, action.gesture, widthPx, heightPx)
                if (second.outcome != DispatchOutcome.COMPLETED) return@withContext second
            }
            DispatchReceipt(action, DispatchOutcome.COMPLETED, null)
        }

    private fun realDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = resources.displayMetrics
            // API 24–29：displayMetrics 在多数全屏游戏上已是真实尺寸；无 getRealMetrics 扩展时退回此值。
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            @Suppress("DEPRECATION")
            val real = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(real)
            real.widthPixels to real.heightPixels
        }
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
            val sx = gesture.startX.coerceIn(0f, 1f) * (widthPixels - 1)
            val sy = gesture.startY.coerceIn(0f, 1f) * (heightPixels - 1)
            moveTo(sx, sy)
            if (endX != null && endY != null) {
                lineTo(
                    endX.coerceIn(0f, 1f) * (widthPixels - 1),
                    endY.coerceIn(0f, 1f) * (heightPixels - 1),
                )
            } else {
                // 部分 OEM 拒绝零长度 Path；点击补 1px 位移。
                lineTo((sx + 1f).coerceAtMost((widthPixels - 1).toFloat()), sy)
            }
        }
        val description = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    gesture.durationMs.coerceIn(10, 1_000),
                ),
            )
            .build()
        val result = CompletableDeferred<DispatchReceipt>()
        val generation = gestureGeneration.get()
        val accepted = dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // 仅当前 generation 可 complete，防止超时后迟到回调污染下一单。
                if (gestureGeneration.get() == generation) {
                    result.complete(DispatchReceipt(action, DispatchOutcome.COMPLETED, null))
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (gestureGeneration.get() == generation) {
                    result.complete(DispatchReceipt(action, DispatchOutcome.CANCELLED, "系统取消手势"))
                }
            }
        }, null)
        if (!accepted) return DispatchReceipt(action, DispatchOutcome.REJECTED, "系统拒绝手势")
        return try {
            result.await()
        } finally {
            // 无论完成/取消/协程取消：抬 generation，使迟到 callback 失效。
            gestureGeneration.incrementAndGet()
        }
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

    /** android.widget.* 等控件类名不能当作前台 Activity。 */
    private fun looksLikeWidgetClass(className: String): Boolean {
        if (className.isBlank()) return false
        return className.startsWith("android.widget.") ||
            className.startsWith("android.view.") ||
            className.startsWith("androidx.")
    }

    /**
     * 主线程：按精确文案在活动根节点中查找，返回可点区域屏幕归一化中心。
     * 多标签按 [labels] 顺序优先（调用方先列「原地复活」再「重新冒险」）。
     */
    private fun findClickableCenterByExactTextsLocked(
        labels: Collection<String>,
        preferVisible: Boolean,
    ): Pair<Float, Float>? {
        val root = rootInActiveWindow ?: return null
        val wanted = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) {
            runCatching { root.recycle() }
            return null
        }
        try {
            val (widthPx, heightPx) = realDisplaySize()
            if (widthPx <= 1 || heightPx <= 1) return null
            for (label in wanted) {
                val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
                try {
                    for (node in nodes) {
                        val nodeText = node.text?.toString()?.trim().orEmpty()
                        val nodeDesc = node.contentDescription?.toString()?.trim().orEmpty()
                        if (nodeText != label && nodeDesc != label) continue
                        val target = nearestClickable(node) ?: continue
                        if (!target.isEnabled) continue
                        if (preferVisible && !target.isVisibleToUser) continue
                        val rect = Rect()
                        target.getBoundsInScreen(rect)
                        if (rect.width() < 8 || rect.height() < 8) continue
                        val cx = ((rect.left + rect.right) * 0.5f) / widthPx.toFloat()
                        val cy = ((rect.top + rect.bottom) * 0.5f) / heightPx.toFloat()
                        if (!cx.isFinite() || !cy.isFinite()) continue
                        return cx.coerceIn(0f, 1f) to cy.coerceIn(0f, 1f)
                    }
                } finally {
                    nodes.forEach { runCatching { it.recycle() } }
                }
            }
            return null
        } finally {
            runCatching { root.recycle() }
        }
    }

    /** 自身可点则用自身，否则向上找可点击祖先（最多 12 层）。 */
    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur.isClickable && cur.isEnabled) return cur
            val parent = cur.parent
            if (cur !== node) {
                runCatching { cur.recycle() }
            }
            cur = parent
            depth++
        }
        return null
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
         * 在活动窗口中按**精确文案**查找可点目标，返回屏幕归一化中心点。
         *
         * 布局 dump 中「原地复活 / 重新冒险」TextView 本身常不可点且无 viewId；
         * 取最近可点击祖先的 [AccessibilityNodeInfo.getBoundsInScreen] 中心。
         * 当前 dump 亦无稳定 resource-id 绑在按钮上，故以文案为主、可选 id 为辅。
         *
         * @return 归一化 [0,1] 中心；未找到或无障碍未连接时 null
         */
        fun findClickableCenterByExactTexts(
            labels: Collection<String>,
            preferVisible: Boolean = true,
        ): Pair<Float, Float>? {
            val service = current.get() ?: return null
            if (labels.isEmpty()) return null
            return try {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    service.findClickableCenterByExactTextsLocked(labels, preferVisible)
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val box = arrayOfNulls<Pair<Float, Float>>(1)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            box[0] = service.findClickableCenterByExactTextsLocked(labels, preferVisible)
                        } catch (_: Throwable) {
                            box[0] = null
                        } finally {
                            latch.countDown()
                        }
                    }
                    latch.await(120, java.util.concurrent.TimeUnit.MILLISECONDS)
                    box[0]
                }
            } catch (_: Throwable) {
                null
            }
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
