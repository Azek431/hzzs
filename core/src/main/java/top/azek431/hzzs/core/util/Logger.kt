// 火崽崽助手（HZZS）日志缓冲区。
//
// 职责：
// - 提供线程安全的内存环形缓冲区，用于收集 app 各层日志
// - 支持按级别/标签筛选
// - 主线程可批量拉取新日志用于 UI 展示
// - 条数上限可配置，超出时丢弃最旧日志
//
// 日志级别：
// - VERBOSE (v)、DEBUG (d)、INFO (i)、WARN (w)、ERROR (e)
//
// 日志来源：
// - HUD 渲染器：模拟帧生成、引擎调用、视觉识别结果
// - 悬浮窗管理：显示/隐藏、按钮点击、状态切换
// - 按钮绑定器：循环执行、单次执行、状态变化
// - 截图采集器：截图成功/失败、API 兼容性
// - 通用：任意模块通过 Logger.log() 写入
//
// 线程模型：
// - 多生产者（各后台线程 + 主线程均可写入）
// - 单消费者（UI 线程批量拉取）
// - 使用 synchronized 保护环形缓冲区（非高并发场景，锁开销可忽略）

package top.azek431.hzzs.core.util

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 内存日志缓冲区。
 *
 * 线程安全的有界环形缓冲区，用于收集应用运行日志。
 * 日志条目按时间顺序排列，最新一条在最后。
 *
 * @param capacity 最大容量，默认 5000 条
 */
object Logger {

    private const val TAG = "HZZS-Logger"

    // ==================== 日志条目 ====================

    /** 日志级别常量 */
    const val LEVEL_VERBOSE = 0
    const val LEVEL_DEBUG = 1
    const val LEVEL_INFO = 2
    const val LEVEL_WARN = 3
    const val LEVEL_ERROR = 4

    /**
     * 单条日志数据。
     *
     * @property timestamp 时间戳（SystemClock.uptimeMillis）
     * @property timeText 格式化时间文本（HH:mm:ss.SSS，创建时计算一次）
     * @property level 日志级别（0=VERBOSE, 1=DEBUG, 2=INFO, 3=WARN, 4=ERROR）
     * @property tag 日志标签（模块名）
     * @property message 日志消息
     */
    data class LogEntry(
        val timestamp: Long,
        val timeText: String,
        val level: Int,
        val tag: String,
        val message: String,
    ) {
        /** 级别文本 */
        val levelText: String
            get() = when (level) {
                LEVEL_VERBOSE -> "V"
                LEVEL_DEBUG -> "D"
                LEVEL_INFO -> "I"
                LEVEL_WARN -> "W"
                LEVEL_ERROR -> "E"
                else -> "?"
            }

        /** 级别颜色（Android 标准日志颜色） */
        val levelColor: Int
            get() = when (level) {
                LEVEL_VERBOSE -> android.graphics.Color.rgb(150, 150, 150)
                LEVEL_DEBUG -> android.graphics.Color.rgb(150, 200, 255)
                LEVEL_INFO -> android.graphics.Color.rgb(150, 255, 150)
                LEVEL_WARN -> android.graphics.Color.rgb(255, 200, 50)
                LEVEL_ERROR -> android.graphics.Color.rgb(255, 80, 80)
                else -> android.graphics.Color.WHITE
            }
    }

    // ==================== 环形缓冲区 ====================

    private var buffer: Array<LogEntry?> = arrayOfNulls(DEFAULT_CAPACITY)
    private var head = 0
    private var tail = 0
    private var count = 0
    private var capacity = DEFAULT_CAPACITY

    private const val DEFAULT_CAPACITY = 5000

    /** 时间格式器（线程安全：每个线程独立实例） */
    private val timeFormatter: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    // ==================== 写入 ====================

    /**
     * 写入一条日志到缓冲区。
     *
     * 缓冲区满时丢弃最旧的日志，确保新数据优先。
     * 此方法线程安全，可在任意线程调用。
     *
     * @param level 日志级别
     * @param tag 日志标签
     * @param message 日志消息
     */
    @Synchronized
    fun log(level: Int, tag: String, message: String) {
        if (capacity <= 0) return

        val now = SystemClock.uptimeMillis()
        val entry = LogEntry(
            timestamp = now,
            timeText = timeFormatter.get()?.format(now) ?: "?",
            level = level,
            tag = tag,
            message = message,
        )

        if (count < capacity) {
            // 缓冲区未满，直接写入
            buffer[tail] = entry
            tail = (tail + 1) % capacity
            count++
        } else {
            // 缓冲区已满，丢弃最旧日志（head 前进一位）
            head = (head + 1) % capacity
            buffer[tail] = entry
            tail = (tail + 1) % capacity
            // count 不变（进一出一）
        }
    }

    /**
     * 便捷方法：写入 DEBUG 级别日志。
     */
    fun d(tag: String, message: String) = log(LEVEL_DEBUG, tag, message)

    /**
     * 便捷方法：写入 INFO 级别日志。
     */
    fun i(tag: String, message: String) = log(LEVEL_INFO, tag, message)

    /**
     * 便捷方法：写入 WARN 级别日志。
     */
    fun w(tag: String, message: String) = log(LEVEL_WARN, tag, message)

    /**
     * 便捷方法：写入 ERROR 级别日志。
     */
    fun e(tag: String, message: String) = log(LEVEL_ERROR, tag, message)

    // ==================== 读取接口 ====================

    /**
     * 批量拉取所有日志条目到目标列表。
     *
     * 此操作会清空缓冲区中的待拉取数据（类似 drainTo）。
     * 适合 UI 线程定期调用以获取最新日志。
     *
     * @param target 目标列表，拉取的条目将追加到此列表
     * @return 实际拉取的条目数量
     */
    @Synchronized
    fun drainTo(target: MutableList<LogEntry>): Int {
        var extracted = 0
        while (extracted < 100) {
            val entry = pollEntry() ?: break
            target.add(entry)
            extracted++
        }
        return extracted
    }

    /**
     * 获取指定数量的最新日志（不清空缓冲区）。
     *
     * @param limit 最多返回多少条
     * @return 日志列表（按时间正序）
     */
    @Synchronized
    fun peekLatest(limit: Int = 500): List<LogEntry> {
        val result = mutableListOf<LogEntry>()
        if (count == 0) return result

        // 从 head 开始读取（最旧的），最多读取 limit 条
        var idx = head
        val toRead = minOf(limit, count)
        for (i in 0 until toRead) {
            val entry = buffer[idx]
            if (entry != null) result.add(entry)
            idx = (idx + 1) % capacity
        }
        return result
    }

    /**
     * 获取缓冲区中所有日志（按时间正序，不清空）。
     */
    @Synchronized
    fun getAll(): List<LogEntry> = peekLatest(count)

    /**
     * 按条件筛选日志。
     *
     * @param minLevel 最低级别（包含）
     * @param tags 标签过滤器（null 表示不过滤）
     * @param search 搜索关键词（null 表示不搜索）
     * @return 筛选后的日志列表
     */
    @Synchronized
    fun filter(
        minLevel: Int = LEVEL_DEBUG,
        tags: List<String>? = null,
        search: String? = null,
    ): List<LogEntry> {
        return getAll().filter { entry ->
            entry.level >= minLevel &&
                (tags == null || tags.contains(entry.tag)) &&
                (search == null || entry.message.contains(search, ignoreCase = true) ||
                    entry.tag.contains(search, ignoreCase = true))
        }
    }

    // ==================== 状态管理 ====================

    /** 当前缓冲区中的日志条数 */
    val size: Int get() = count

    /** 缓冲区是否已满 */
    val isFull: Boolean get() = count >= capacity

    /**
     * 清空缓冲区。
     */
    @Synchronized
    fun clear() {
        for (i in buffer.indices) {
            buffer[i] = null
        }
        head = 0
        tail = 0
        count = 0
    }

    /**
     * 设置缓冲区容量。
     *
     * 注意：更改容量会导致现有日志丢失。
     *
     * @param newCapacity 新容量
     */
    @Synchronized
    fun setCapacity(newCapacity: Int) {
        if (newCapacity <= 0) return
        val oldEntries = getAll()
        buffer = arrayOfNulls<LogEntry?>(newCapacity)
        head = 0
        tail = 0
        count = 0
        capacity = newCapacity

        // 重新写入旧日志（可能因容量变小而被截断）
        for (entry in oldEntries) {
            if (count < capacity) {
                buffer[tail] = entry
                tail = (tail + 1) % capacity
                count++
            }
            // 满了就丢弃
        }
    }

    // ==================== 内部方法 ====================

    /** 从队头弹出一条日志（内部方法，必须在 synchronized 块内调用） */
    @Synchronized
    private fun pollEntry(): LogEntry? {
        if (count == 0) return null
        val entry = buffer[head]
        head = (head + 1) % capacity
        count--
        return entry
    }
}
