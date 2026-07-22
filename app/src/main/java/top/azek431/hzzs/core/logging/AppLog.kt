/**
 * 应用日志门面：Logcat + 内存 ring buffer。
 *
 * 职责：为诊断导出提供最近日志；不写文件、不上传、不记录 Bearer Token。
 * 线程：任意线程可写；快照拷贝出只读列表。
 * 边界：纯进程内；不依赖 Hilt；级别由 [configure] 同步。
 */
package top.azek431.hzzs.core.logging

import android.util.Log
import top.azek431.hzzs.core.model.AppLogLevel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 单条缓冲日志（导出用纯文本，不含敏感密钥）。 */
data class AppLogEntry(
    val epochMs: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
    val throwableMessage: String? = null,
)

/**
 * 进程级日志门面。
 *
 * - 始终写 Logcat（受 [minLevel] 过滤）
 * - ring buffer 容量 [CAPACITY]；关闭开发者时 DEBUG/VERBOSE 不入 buffer
 */
object AppLog {
    private const val CAPACITY = 800
    private const val TAG_PREFIX = "HZZS"

    private val lock = Any()
    private val buffer = ArrayDeque<AppLogEntry>(CAPACITY)
    private val minLevel = AtomicReference(AppLogLevel.INFO)
    private val developerEnabled = AtomicBoolean(false)

    /** 由配置保存路径同步：开发者开关与最低级别。 */
    fun configure(enabled: Boolean, level: AppLogLevel) {
        developerEnabled.set(enabled)
        minLevel.set(level)
    }

    fun v(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.VERBOSE, tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(AppLogLevel.ERROR, tag, message, throwable)

    /** 按时间从旧到新返回快照。 */
    fun snapshot(limit: Int = CAPACITY): List<AppLogEntry> = synchronized(lock) {
        val n = limit.coerceAtMost(buffer.size).coerceAtLeast(0)
        if (n == 0) emptyList() else buffer.toList().takeLast(n)
    }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun size(): Int = synchronized(lock) { buffer.size }

    private fun log(level: AppLogLevel, tag: String, message: String, throwable: Throwable?) {
        val min = minLevel.get()
        if (level.ordinal < min.ordinal) return
        // 关闭开发者时压制 DEBUG/VERBOSE，避免常态噪音与诊断膨胀。
        if (!developerEnabled.get() && level.ordinal < AppLogLevel.INFO.ordinal) return

        val safeTag = sanitizeTag(tag)
        val safeMessage = redact(message)
        val thrMsg = throwable?.let { redact(it.message ?: it.javaClass.simpleName) }
        writeLogcat(level, safeTag, safeMessage, throwable)
        val entry = AppLogEntry(
            epochMs = System.currentTimeMillis(),
            level = level,
            tag = safeTag,
            message = safeMessage.take(2_000),
            throwableMessage = thrMsg?.take(500),
        )
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    private fun writeLogcat(level: AppLogLevel, tag: String, message: String, throwable: Throwable?) {
        // JVM 单测无完整 android.util.Log 实现时 fail-open，只保留 ring buffer。
        runCatching {
            val fullTag = "$TAG_PREFIX/$tag"
            when (level) {
                AppLogLevel.VERBOSE -> if (throwable != null) Log.v(fullTag, message, throwable) else Log.v(fullTag, message)
                AppLogLevel.DEBUG -> if (throwable != null) Log.d(fullTag, message, throwable) else Log.d(fullTag, message)
                AppLogLevel.INFO -> if (throwable != null) Log.i(fullTag, message, throwable) else Log.i(fullTag, message)
                AppLogLevel.WARN -> if (throwable != null) Log.w(fullTag, message, throwable) else Log.w(fullTag, message)
                AppLogLevel.ERROR -> if (throwable != null) Log.e(fullTag, message, throwable) else Log.e(fullTag, message)
            }
        }
    }

    private fun sanitizeTag(tag: String): String =
        tag.trim().take(32).ifBlank { "app" }

    /**
     * 粗粒度脱敏：去掉 Bearer token、常见密钥片段。
     * 不保证穷尽所有密钥形态；调用方仍应避免传入明文令牌。
     */
    internal fun redact(raw: String): String {
        var s = raw
        s = BEARER_REGEX.replace(s) { "Bearer <redacted>" }
        s = TOKEN_KV_REGEX.replace(s) { m -> "${m.groupValues[1]}=<redacted>" }
        return s
    }

    private val BEARER_REGEX =
        Regex("""Bearer\s+[A-Za-z0-9\-._~+/]+=*""", RegexOption.IGNORE_CASE)
    private val TOKEN_KV_REGEX =
        Regex(
            """(?i)\b(token|access_token|refresh_token|password|secret|api[_-]?key)\s*[=:]\s*\S+""",
        )
}
