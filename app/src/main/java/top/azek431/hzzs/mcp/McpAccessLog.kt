package top.azek431.hzzs.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 访问日志（进程内 ring）。
 *
 * 职责：记录连接/请求摘要，供设置页与 [get_mcp_access_log] 查看。
 * 不写文件、不上传；**永不**记录 Bearer / authToken / 请求体参数。
 * 线程：任意线程可写；[snapshot] 返回拷贝。
 */
data class McpAccessLogEntry(
    val epochMs: Long,
    val remote: String,
    val httpMethod: String,
    val path: String,
    /** JSON-RPC method，如 tools/call；非 RPC 为空。 */
    val rpcMethod: String = "",
    /** tools/call 时的工具名。 */
    val tool: String = "",
    val httpStatus: Int = 0,
    val rpcErrorCode: Int? = null,
    val durationMs: Long = 0L,
    val sessionPrefix: String = "",
    val note: String = "",
) {
    fun formatLine(timeFormat: SimpleDateFormat = defaultTimeFormat()): String = buildString {
        append(timeFormat.format(Date(epochMs)))
        append(' ')
        append(httpMethod)
        append(' ')
        append(path)
        if (rpcMethod.isNotBlank()) {
            append(' ')
            append(rpcMethod)
        }
        if (tool.isNotBlank()) {
            append(' ')
            append(tool)
        }
        append(" → ")
        append(httpStatus)
        rpcErrorCode?.let {
            append(" rpc=")
            append(it)
        }
        append(" ")
        append(durationMs)
        append("ms")
        if (remote.isNotBlank()) {
            append(" from=")
            append(remote)
        }
        if (note.isNotBlank()) {
            append(" | ")
            append(note)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("epochMs", epochMs)
        .put("remote", remote)
        .put("httpMethod", httpMethod)
        .put("path", path)
        .put("rpcMethod", rpcMethod)
        .put("tool", tool)
        .put("httpStatus", httpStatus)
        .put("rpcErrorCode", rpcErrorCode ?: JSONObject.NULL)
        .put("durationMs", durationMs)
        .put("sessionPrefix", sessionPrefix)
        .put("note", note)
        .put("line", formatLine())

    companion object {
        fun defaultTimeFormat(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
    }
}

object McpAccessLog {
    const val CAPACITY = 200

    private val lock = Any()
    private val buffer = ArrayDeque<McpAccessLogEntry>(CAPACITY)
    private val enabled = AtomicBoolean(true)
    private val revisionCounter = AtomicLong(0L)

    fun revision(): Long = revisionCounter.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun append(entry: McpAccessLogEntry) {
        if (!enabled.get()) return
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            revisionCounter.incrementAndGet()
        }
    }

    fun record(
        remote: String,
        httpMethod: String,
        path: String,
        rpcMethod: String = "",
        tool: String = "",
        httpStatus: Int,
        rpcErrorCode: Int? = null,
        durationMs: Long,
        sessionId: String? = null,
        note: String = "",
    ) {
        append(
            McpAccessLogEntry(
                epochMs = System.currentTimeMillis(),
                remote = remote.ifBlank { "-" },
                httpMethod = httpMethod,
                path = path,
                rpcMethod = rpcMethod,
                tool = tool,
                httpStatus = httpStatus,
                rpcErrorCode = rpcErrorCode,
                durationMs = durationMs.coerceAtLeast(0L),
                sessionPrefix = sessionId?.take(8).orEmpty(),
                note = note.take(120),
            ),
        )
    }

    /** 默认新→旧，便于 UI。 */
    fun snapshot(limit: Int = CAPACITY, newestFirst: Boolean = true): List<McpAccessLogEntry> =
        synchronized(lock) {
            val n = limit.coerceIn(0, CAPACITY).coerceAtMost(buffer.size)
            if (n == 0) return emptyList()
            val list = buffer.toList().takeLast(n)
            if (newestFirst) list.asReversed() else list
        }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            revisionCounter.incrementAndGet()
        }
    }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun toJsonArray(limit: Int = CAPACITY, newestFirst: Boolean = true): JSONArray =
        JSONArray().apply {
            snapshot(limit, newestFirst).forEach { put(it.toJson()) }
        }

    fun formatText(limit: Int = CAPACITY, newestFirst: Boolean = true): String =
        snapshot(limit, newestFirst).joinToString("\n") { it.formatLine() }
}
