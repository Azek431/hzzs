package top.azek431.hzzs.service.automation

import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.azek431.hzzs.domain.automation.AutomationAction
import top.azek431.hzzs.domain.automation.DispatchOutcome
import top.azek431.hzzs.domain.automation.DispatchReceipt
import top.azek431.hzzs.domain.automation.GestureDispatcher
import top.azek431.hzzs.domain.automation.GestureSpec

/**
 * 无障碍手势注入：委托 [HzzsAccessibilityService]（生产唯一 dispatchGesture 持有者）。
 */
@Singleton
class AccessibilityGestureDispatcher @Inject constructor() : GestureDispatcher {
    override suspend fun dispatch(action: AutomationAction): DispatchReceipt =
        HzzsAccessibilityService.dispatchCurrent(action)
}

/**
 * 通过 shell `input tap/swipe` 注入手势（Shizuku 或 Root）。
 *
 * 完成语义：命令 exit 0 = COMPLETED，**弱于**无障碍 GestureResultCallback。
 * 前台门控使用注入的 [ForegroundWindowProbe]（dumpsys）。
 *
 * 兼容：Shizuku 子进程 PATH 常为空，优先绝对路径 `/system/bin/input`，
 * 失败再试 `cmd input`；失败 detail 带 exit/stderr 便于诊断。
 */
/**
 * 仅同模块内由 Shizuku/Root 分发器持有；不直接注入到 feature。
 */
class ShellInputGestureDispatcher(
    private val probe: ForegroundWindowProbe,
    private val screenSize: () -> Pair<Int, Int>,
    private val runResult: suspend (command: Array<String>, timeoutMs: Long) -> ShellProcessSupport.ShellCommandResult,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val maxForegroundAgeMs: Long = 1_500L,
) : GestureDispatcher {

    override suspend fun dispatch(action: AutomationAction): DispatchReceipt {
        val window = probe.snapshot()
            ?: return DispatchReceipt(action, DispatchOutcome.REJECTED, "前台窗口状态不可用")
        if (clock() - window.observedAtMs > maxForegroundAgeMs) {
            return DispatchReceipt(action, DispatchOutcome.REJECTED, "前台窗口状态已过期")
        }
        if (!action.matchesPackage(window.packageName) || !action.matchesWindow(window.className)) {
            return DispatchReceipt(
                action,
                DispatchOutcome.REJECTED,
                "当前页面不在允许范围 pkg=${window.packageName}",
            )
        }
        val (width, height) = screenSize()
        if (width <= 1 || height <= 1) {
            return DispatchReceipt(action, DispatchOutcome.REJECTED, "屏幕尺寸无效")
        }
        val first = dispatchStroke(action, action.gesture, width, height)
        if (first.outcome != DispatchOutcome.COMPLETED) return first
        val doubleDelay = action.gesture.doublePressDelayMs
        val isClick = action.gesture.endX == null && action.gesture.endY == null
        if (isClick && doubleDelay > 0L) {
            delay(doubleDelay.coerceIn(1L, 2_000L))
            // 双击间隔内前台可能已变；再检一次。
            val recheck = probe.snapshot()
            if (recheck == null ||
                clock() - recheck.observedAtMs > maxForegroundAgeMs ||
                !action.matchesPackage(recheck.packageName) ||
                !action.matchesWindow(recheck.className)
            ) {
                return DispatchReceipt(action, DispatchOutcome.REJECTED, "双击间隔前台已变化")
            }
            val second = dispatchStroke(action, action.gesture, width, height)
            if (second.outcome != DispatchOutcome.COMPLETED) return second
        }
        return DispatchReceipt(action, DispatchOutcome.COMPLETED, null)
    }

    private suspend fun dispatchStroke(
        action: AutomationAction,
        gesture: GestureSpec,
        widthPixels: Int,
        heightPixels: Int,
    ): DispatchReceipt = withContext(Dispatchers.IO) {
        val x1 = (gesture.startX.coerceIn(0f, 1f) * (widthPixels - 1)).toInt()
        val y1 = (gesture.startY.coerceIn(0f, 1f) * (heightPixels - 1)).toInt()
        val endX = gesture.endX
        val endY = gesture.endY
        val duration = gesture.durationMs.coerceIn(10L, 1_000L)
        val isSwipe = endX != null && endY != null
        val x2 = if (isSwipe) (endX!!.coerceIn(0f, 1f) * (widthPixels - 1)).toInt() else x1
        val y2 = if (isSwipe) (endY!!.coerceIn(0f, 1f) * (heightPixels - 1)).toInt() else y1
        val timeoutMs = (duration + 1_200L).coerceIn(1_000L, 4_000L)
        // 候选命令：绝对路径优先（PATH 空）；再 cmd input；最后裸 input。
        val candidates: List<Array<String>> = if (isSwipe) {
            listOf(
                arrayOf("/system/bin/input", "swipe", "$x1", "$y1", "$x2", "$y2", "$duration"),
                arrayOf("cmd", "input", "swipe", "$x1", "$y1", "$x2", "$y2", "$duration"),
                arrayOf("input", "swipe", "$x1", "$y1", "$x2", "$y2", "$duration"),
            )
        } else {
            listOf(
                arrayOf("/system/bin/input", "tap", "$x1", "$y1"),
                arrayOf("cmd", "input", "tap", "$x1", "$y1"),
                arrayOf("input", "tap", "$x1", "$y1"),
            )
        }
        var lastDetail = "input_fail"
        for (command in candidates) {
            val result = runCatching { runResult(command, timeoutMs) }
                .getOrElse { ShellProcessSupport.ShellCommandResult(false, it.javaClass.simpleName) }
            if (result.ok) {
                return@withContext DispatchReceipt(
                    action,
                    DispatchOutcome.COMPLETED,
                    "px=${x1},${y1}" + if (isSwipe) "→${x2},${y2}" else "",
                )
            }
            lastDetail = result.detail ?: "input_fail"
        }
        DispatchReceipt(
            action,
            DispatchOutcome.REJECTED,
            "input 失败 px=${x1},${y1} $lastDetail",
        )
    }
}

/** Shizuku input 分发器持有者（含前台 probe 缓存可重置）。 */
@Singleton
class ShizukuGestureDispatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : GestureDispatcher {
    private val probe = ShellForegroundProbe(
        runner = ShellCommandRunner { command, maxBytes, timeout ->
            ShellProcessSupport.runShizuku(command, maxBytes, timeout)
        },
    )

    fun clearForegroundCache() = probe.clearCache()

    /** 供运行时规划/派发前 dumpsys 前台探测（与 input 同源缓存）。 */
    suspend fun snapshotForeground(): ForegroundWindowSnapshot? = probe.snapshot()

    private val inner = ShellInputGestureDispatcher(
        probe = probe,
        screenSize = { screenSize(context) },
        runResult = { command, timeout -> ShellProcessSupport.runShizukuResult(command, timeout) },
    )

    override suspend fun dispatch(action: AutomationAction): DispatchReceipt = inner.dispatch(action)
}

/** Root input 分发器。 */
@Singleton
class RootGestureDispatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : GestureDispatcher {
    private val probe = ShellForegroundProbe(
        runner = ShellCommandRunner { command, maxBytes, timeout ->
            val joined = command.joinToString(" ") { arg ->
                if (arg.any { it.isWhitespace() }) "\"$arg\"" else arg
            }
            ShellProcessSupport.runRoot(joined, maxBytes, timeout)
        },
    )

    fun clearForegroundCache() = probe.clearCache()

    /** 供运行时规划/派发前 dumpsys 前台探测（与 input 同源缓存）。 */
    suspend fun snapshotForeground(): ForegroundWindowSnapshot? = probe.snapshot()

    private val inner = ShellInputGestureDispatcher(
        probe = probe,
        screenSize = { screenSize(context) },
        runResult = { command, timeout ->
            val joined = command.joinToString(" ") { arg ->
                if (arg.any { it.isWhitespace() }) "\"$arg\"" else arg
            }
            ShellProcessSupport.runRootResult(joined, timeout)
        },
    )

    override suspend fun dispatch(action: AutomationAction): DispatchReceipt = inner.dispatch(action)
}

private fun screenSize(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // 与 VirtualDisplay / 多数游戏全屏坐标对齐：优先真实显示尺寸，避免 app metrics 与
    // currentWindowMetrics 在刘海/导航条机型上分叉导致 Shell 点偏。
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val bounds = wm.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }
}
