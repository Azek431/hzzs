package top.azek431.hzzs.service.automation

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 前台窗口快照（归一化字段，供各手势后端门控）。
 *
 * [className] 在 shell 路径可能为空串；此时仅当动作未声明窗口前缀约束才放行。
 */
data class ForegroundWindowSnapshot(
    val packageName: String,
    val className: String,
    val observedAtMs: Long,
)

/** 平台前台探测；null = fail-closed。 */
fun interface ForegroundWindowProbe {
    suspend fun snapshot(): ForegroundWindowSnapshot?
}

/** 无障碍事件前台快照。 */
object AccessibilityForegroundProbe : ForegroundWindowProbe {
    override suspend fun snapshot(): ForegroundWindowSnapshot? = blockingSnapshot()

    /** 同步路径（帧循环规划期）使用；会在过期时主动刷新。 */
    fun blockingSnapshot(): ForegroundWindowSnapshot? {
        val window = HzzsAccessibilityService.foregroundSnapshot(refreshIfStale = true) ?: return null
        return ForegroundWindowSnapshot(
            packageName = window.packageName,
            className = window.className,
            observedAtMs = window.observedAtMs,
        )
    }
}

/**
 * 将 dumpsys 文本解析为 package/class。
 *
 * 纯函数，便于 JVM 单测；不执行 shell。
 */
object ShellForegroundParser {
    private val patterns = listOf(
        Regex(
            """(?:topResumedActivity|mResumedActivity|mFocusedApp|mCurrentFocus)\s*[=:]\s*.*?\s+([\w.]+)/([^\s}]+)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """ActivityRecord\{[^}]*\s+u\d+\s+([\w.]+)/([^\s}]+)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """Window\{[^}]*\s+([\w.]+)/([^\s}]+)""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun parse(dump: String): ForegroundWindowSnapshot? {
        if (dump.isBlank()) return null
        for (pattern in patterns) {
            val match = pattern.find(dump) ?: continue
            val pkg = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val cls = match.groupValues.getOrNull(2)?.trim().orEmpty()
            if (pkg.isBlank() || !pkg.contains('.')) continue
            return ForegroundWindowSnapshot(
                packageName = pkg,
                className = cls.removePrefix("."),
                observedAtMs = 0L,
            )
        }
        return null
    }
}

/**
 * Shell 通道执行器：返回命令 stdout（已限长）或 null。
 */
fun interface ShellCommandRunner {
    suspend fun run(command: Array<String>, maxStdoutBytes: Int, timeoutMs: Long): String?
}

/**
 * 基于 dumpsys 的前台探测（Shizuku / Root 共用解析，执行通道注入）。
 *
 * 缓存 TTL 降低 dumpsys 频率；失败短缓存防风暴。
 */
class ShellForegroundProbe(
    private val runner: ShellCommandRunner,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val successTtlMs: Long = 450L,
    private val failureTtlMs: Long = 200L,
) : ForegroundWindowProbe {
    private data class Cache(
        val value: ForegroundWindowSnapshot?,
        val storedAtMs: Long,
        val success: Boolean,
    )

    private val cache = AtomicReference<Cache?>(null)

    fun clearCache() {
        cache.set(null)
    }

    override suspend fun snapshot(): ForegroundWindowSnapshot? {
        val now = clock()
        cache.get()?.let { hit ->
            val ttl = if (hit.success) successTtlMs else failureTtlMs
            if (now - hit.storedAtMs < ttl) return hit.value
        }
        val parsed = queryOnce()
        cache.set(Cache(parsed, now, success = parsed != null))
        return parsed
    }

    private suspend fun queryOnce(): ForegroundWindowSnapshot? {
        val primary = runner.run(
            arrayOf("dumpsys", "activity", "activities"),
            MAX_STDOUT_BYTES,
            TIMEOUT_MS,
        )
        ShellForegroundParser.parse(primary.orEmpty())?.let { snap ->
            return snap.copy(observedAtMs = clock())
        }
        val secondary = runner.run(
            arrayOf("dumpsys", "window", "windows"),
            MAX_STDOUT_BYTES,
            TIMEOUT_MS,
        )
        return ShellForegroundParser.parse(secondary.orEmpty())?.copy(observedAtMs = clock())
    }

    private companion object {
        const val MAX_STDOUT_BYTES = 512 * 1024
        const val TIMEOUT_MS = 1_800L
    }
}
