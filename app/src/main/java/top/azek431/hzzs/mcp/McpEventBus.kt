package top.azek431.hzzs.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 进程内事件总线（ring）。
 *
 * 职责：记录运行时事件（启停 / 算法切换 / 配置变化 / 错误），供 [get_events] 拉取。
 * 不写文件、不上传；仅记事件类型 + 摘要 data，**永不**记 Token/参数体。
 * 线程：synchronized 写；[snapshot] 返回拷贝。
 */
object McpEventBus {
    const val CAPACITY = 200

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val sequenceCounter = AtomicLong(0)

    /** 事件类型枚举：analysis.start/stop、algorithm.switch、config.change、error 等。 */
    object Type {
        const val ANALYSIS_START = "analysis.start"
        const val ANALYSIS_STOP = "analysis.stop"
        const val ALGORITHM_SWITCH = "algorithm.switch"
        const val CONFIG_CHANGE = "config.change"
        const val ERROR = "error"
        const val AUTOMATION_DECISION = "automation.decision"
        const val CAPTURE_BACKEND_CHANGE = "capture.backend_change"
    }

    data class Entry(
        val seq: Long,
        val type: String,
        val epochMs: Long,
        val data: JSONObject,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("type", type)
            .put("epochMs", epochMs)
            .put("data", data)
    }

    fun append(type: String, data: JSONObject) {
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(
                Entry(
                    seq = sequenceCounter.incrementAndGet(),
                    type = type,
                    epochMs = System.currentTimeMillis(),
                    data = data,
                ),
            )
        }
    }

    /** 返回 seq > since 的事件（默认全部），最多 limit 条。 */
    fun snapshot(since: Long = 0L, limit: Int = CAPACITY): List<Entry> = synchronized(lock) {
        val seq = sequenceCounter.get()
        val matched = buffer.filter { it.seq > since }
        val n = limit.coerceAtLeast(0).coerceAtMost(matched.size)
        matched.takeLast(n)
    }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    fun toJsonArray(since: Long = 0L, limit: Int = CAPACITY): JSONArray =
        JSONArray().apply {
            snapshot(since, limit).forEach { put(it.toJson()) }
        }
}
