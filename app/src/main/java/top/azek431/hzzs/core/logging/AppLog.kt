/**
 * 应用日志门面：Logcat + 内存 ring buffer。
 *
 * 职责：为诊断导出与应用内日志查看器提供最近日志；不写文件、不上传、不记录 Bearer Token。
 * 线程：任意线程可写；快照拷贝出只读列表。
 * 边界：纯进程内；不依赖 Hilt；级别由 [configure] 同步。
 */
package top.azek431.hzzs.core.logging

import android.util.Log
import top.azek431.hzzs.core.model.AppLogLevel
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 单条缓冲日志（导出用纯文本，不含敏感密钥）。 */
data class AppLogEntry(
    val epochMs: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
    val throwableMessage: String? = null,
) {
    /** 单行展示：时间 级别/标签: 消息 [| ex=…] */
    fun formatLine(timeFormat: SimpleDateFormat = defaultTimeFormat()): String = buildString {
        append(timeFormat.format(Date(epochMs)))
        append(' ')
        append(level.name)
        append('/')
        append(tag)
        append(": ")
        append(message)
        throwableMessage?.let {
            append(" | ex=")
            append(it)
        }
    }

    companion object {
        fun defaultTimeFormat(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
    }
}

/**
 * 进程级日志门面。
 *
 * - 始终写 Logcat（受 [minLevel] 过滤）
 * - ring buffer 容量 [CAPACITY]；关闭开发者时 DEBUG/VERBOSE 不入 buffer
 * - [revision] 在写入/清空时递增，供 UI 轮询刷新
 */
object AppLog {
    private const val CAPACITY = 800
    private const val TAG_PREFIX = "HZZS"

    private val lock = Any()
    private val buffer = ArrayDeque<AppLogEntry>(CAPACITY)
    private val minLevel = AtomicReference(AppLogLevel.INFO)
    private val developerEnabled = AtomicBoolean(false)
    private val revisionCounter = AtomicLong(0L)

    /** 缓冲变更代数；UI 可用其轮询是否需要刷新。 */
    fun revision(): Long = revisionCounter.get()

    /** 由配置保存路径同步：开发者开关与最低级别。 */
    fun configure(enabled: Boolean, level: AppLogLevel) {
        developerEnabled.set(enabled)
        minLevel.set(level)
    }

    fun isDeveloperEnabled(): Boolean = developerEnabled.get()

    fun minLevel(): AppLogLevel = minLevel.get()

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

    /**
     * 筛选快照（旧→新）。
     *
     * @param minLevel 最低级别（含）
     * @param tagEquals 精确 tag；null 表示不限
     * @param query 对 message/tag/throwable 的子串匹配（忽略大小写）；blank 不限
     * @param limit 最大条数
     * @param newestFirst true 时新→旧
     */
    fun query(
        minLevel: AppLogLevel = AppLogLevel.VERBOSE,
        tagEquals: String? = null,
        query: String? = null,
        limit: Int = CAPACITY,
        newestFirst: Boolean = false,
    ): List<AppLogEntry> {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
        val tag = tagEquals?.trim()?.takeIf { it.isNotEmpty() }
        // snapshot 为旧→新；先筛再按方向截断
        val filtered = snapshot(CAPACITY).asSequence()
            .filter { it.level.ordinal >= minLevel.ordinal }
            .filter { tag == null || it.tag.equals(tag, ignoreCase = true) }
            .filter { entry ->
                if (q == null) {
                    true
                } else {
                    entry.message.lowercase(Locale.US).contains(q) ||
                        entry.tag.lowercase(Locale.US).contains(q) ||
                        (entry.throwableMessage?.lowercase(Locale.US)?.contains(q) == true)
                }
            }
            .toList()
        val capped = if (filtered.size <= limit) {
            filtered
        } else {
            // 保留最近 limit 条（列表末尾）
            filtered.takeLast(limit)
        }
        return if (newestFirst) capped.asReversed() else capped
    }

    /** 当前缓冲中出现过的 tag（字典序）。 */
    fun knownTags(): List<String> = synchronized(lock) {
        buffer.map { it.tag }.toSet().sorted()
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        revisionCounter.incrementAndGet()
    }

    fun size(): Int = synchronized(lock) { buffer.size }

    /** 将筛选结果格式化为可分享纯文本。 */
    fun formatText(
        minLevel: AppLogLevel = AppLogLevel.VERBOSE,
        tagEquals: String? = null,
        query: String? = null,
        limit: Int = CAPACITY,
        newestFirst: Boolean = false,
    ): String {
        val entries = query(
            minLevel = minLevel,
            tagEquals = tagEquals,
            query = query,
            limit = limit,
            newestFirst = newestFirst,
        )
        if (entries.isEmpty()) return "(empty)"
        val fmt = AppLogEntry.defaultTimeFormat()
        return entries.joinToString(separator = "\n") { it.formatLine(fmt) }
    }

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
            revisionCounter.incrementAndGet()
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
