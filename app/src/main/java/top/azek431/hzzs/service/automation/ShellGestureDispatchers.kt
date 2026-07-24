package top.azek431.hzzs.service.automation

import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
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
 *
 * 性能：记住本会话首次成功的 input 前缀，后续优先该条；非首选候选短超时
 * fail-fast，避免三条命令各等 1s+ 把 DOUBLE_JUMP 拖进「手势回调超时」。
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

    /** 成功过的 input 可执行前缀键（与坐标无关）。 */
    private val preferredInputPrefix = AtomicReference<String?>(null)

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
        val preferred = preferredInputPrefix.get()
        val ordered = if (preferred == null) {
            candidates
        } else {
            val hit = candidates.filter { inputPrefixKey(it) == preferred }
            val rest = candidates.filter { inputPrefixKey(it) != preferred }
            if (hit.isEmpty()) candidates else hit + rest
        }
        var lastDetail = "input_fail"
        val strokeStarted = clock()
        val tried = mutableListOf<String>()
        for ((index, command) in ordered.withIndex()) {
            val isPreferred = preferred != null && inputPrefixKey(command) == preferred
            val isLast = index == ordered.lastIndex
            // 非首选候选短超时快速失败；首选/最后一条给足单次 input 时间。
            val timeoutMs = when {
                isPreferred || isLast -> (duration + 900L).coerceIn(700L, 2_500L)
                else -> FAIL_FAST_CANDIDATE_TIMEOUT_MS
            }
            val prefix = inputPrefixKey(command)
            val attemptStarted = clock()
            val result = runCatching { runResult(command, timeoutMs) }
                .getOrElse { ShellProcessSupport.ShellCommandResult(false, it.javaClass.simpleName) }
            val attemptMs = clock() - attemptStarted
            tried += "$prefix${if (isPreferred) "*" else ""}:${if (result.ok) "ok" else "fail"}/${attemptMs}ms"
            if (result.ok) {
                preferredInputPrefix.set(prefix)
                return@withContext DispatchReceipt(
                    action,
                    DispatchOutcome.COMPLETED,
                    "px=${x1},${y1}" +
                        (if (isSwipe) "→${x2},${y2}" else "") +
                        " via=$prefix strokeMs=${clock() - strokeStarted} " +
                        "tries=${tried.joinToString("|")}",
                )
            }
            lastDetail = result.detail ?: "input_fail"
        }
        DispatchReceipt(
            action,
            DispatchOutcome.REJECTED,
            "input 命令失败或超时 px=${x1},${y1} last=$lastDetail " +
                "strokeMs=${clock() - strokeStarted} tries=${tried.joinToString("|")}",
        )
    }

    private companion object {
        /** 非首选 input 变体的 fail-fast 超时（毫秒）。 */
        const val FAIL_FAST_CANDIDATE_TIMEOUT_MS = 380L

        /** 与具体坐标无关的命令前缀键，用于缓存可用 input 入口。 */
        fun inputPrefixKey(command: Array<String>): String = when {
            command.size >= 2 && command[0] == "cmd" && command[1] == "input" -> "cmd+input"
            command.isNotEmpty() -> command[0]
            else -> ""
        }
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
