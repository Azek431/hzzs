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
 * 线程：synchronized 写；[snapshot] 返回深拷贝。
 *
 * 游标语义：`snapshot(since, limit)` 返回 **seq > since 的最早 limit 条**（增量游标不丢中间事件）。
 * [clear] 清缓冲并把序列号重置为 0，使新事件从 seq=1 重新开始；重置后旧游标会落后于
 * 新 oldest，客户端应以 [Snapshot.dropped]=true 作为「需丢弃旧游标、从 0 重新追赶」的信号。
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
            // 再拷贝一次，避免调用方 mutate 返回对象
            .put("data", JSONObject(data.toString()))
    }

    data class Snapshot(
        val events: List<Entry>,
        val nextSince: Long,
        val oldestSeq: Long,
        val latestSeq: Long,
        val dropped: Boolean,
        val buffered: Int,
    )

    fun append(type: String, data: JSONObject) {
        // 深拷贝：JSONObject 可变，禁止 ring 持有调用方引用
        val copy = JSONObject(data.toString())
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(
                Entry(
                    seq = sequenceCounter.incrementAndGet(),
                    type = type,
                    epochMs = System.currentTimeMillis(),
                    data = copy,
                ),
            )
        }
    }

    /**
     * 返回 seq > since 的最早 [limit] 条事件及游标元数据。
     *
     * [Snapshot.dropped]：客户端 since 已落后于 ring 中最旧 seq（中间事件被覆盖）。
     */
    fun snapshot(since: Long = 0L, limit: Int = CAPACITY): Snapshot = synchronized(lock) {
        val matched = buffer.filter { it.seq > since }
        val n = limit.coerceAtLeast(0).coerceAtMost(matched.size)
        val events = matched.take(n)
        val oldest = buffer.firstOrNull()?.seq ?: 0L
        val latest = sequenceCounter.get()
        val dropped = oldest > 0L && oldest > since + 1
        val nextSince = events.lastOrNull()?.seq ?: since.coerceAtLeast(0L)
        Snapshot(
            events = events,
            nextSince = nextSince,
            oldestSeq = oldest,
            latestSeq = latest,
            dropped = dropped,
            buffered = buffer.size,
        )
    }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        sequenceCounter.set(0)
    }

    fun toJsonArray(since: Long = 0L, limit: Int = CAPACITY): JSONArray =
        JSONArray().apply {
            snapshot(since, limit).events.forEach { put(it.toJson()) }
        }
}
