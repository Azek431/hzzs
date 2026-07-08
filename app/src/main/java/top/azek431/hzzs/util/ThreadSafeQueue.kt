// 火崽崽助手（HZZS）线程安全队列。
//
// 无锁环形缓冲区，用于 C++ 分析结果 → 主线程 UI 更新。
// 替代 OverlayHUDRenderer 中的 mainHandler.post {} 模式。
//
// 与 Handler.post 的区别：
// - Handler.post 是异步回调，无法批量处理
// - ThreadSafeQueue 支持批量 drainTo()，减少主线程调度开销
// - 满时可选择丢弃最旧帧或阻塞等待

package top.azek431.hzzs.util

/**
 * 线程安全的有界环形缓冲区。
 *
 * @param capacity 最大容量，默认为 64
 * @param <E> 元素类型
 */
class ThreadSafeQueue<E>(private val capacity: Int = 64) {

    private val buffer = arrayOfNulls<Any>(capacity)
    @Volatile private var head = 0      // 下一个读取位置
    @Volatile private var tail = 0      // 下一个写入位置
    @Volatile private var count = 0     // 当前元素个数

    /**
     * 添加元素到队尾。
     * 队列满时丢弃最旧的元素（head 前进一位）。
     *
     * @param element 要添加的元素
     * @return true 如果成功添加（即使丢弃了旧元素也算成功）
     */
    fun offer(element: E): Boolean {
        while (true) {
            val currentHead = head
            val currentTail = tail
            val currentCount = count

            if (currentCount < capacity) {
                // 队列未满，直接写入
                @Suppress("UNCHECKED_CAST")
                buffer[currentTail] = element
                // 确保写入可见后再更新 tail
                tail = (currentTail + 1) % capacity
                count = currentCount + 1
                return true
            } else {
                // 队列已满，丢弃最旧元素后写入
                head = (currentHead + 1) % capacity
                @Suppress("UNCHECKED_CAST")
                buffer[tail] = element
                tail = (tail + 1) % capacity
                // count 不变（进一出一）
                return true
            }
        }
    }

    /**
     * 从队头取出一个元素。
     *
     * @return 队头元素，队列为空时返回 null
     */
    fun poll(): E? {
        while (true) {
            val currentCount = count
            if (currentCount == 0) return null

            val currentHead = head
            @Suppress("UNCHECKED_CAST")
            val element = buffer[currentHead] as E
            head = (currentHead + 1) % capacity
            count = currentCount - 1
            return element
        }
    }

    /**
     * 批量取出所有元素到目标集合。
     *
     * @param target 目标 MutableList，取出的元素将追加到此列表
     * @return 实际取出的元素数量
     */
    fun drainTo(target: MutableList<E>): Int {
        var extracted = 0
        while (extracted < 16) {  // 每次最多取 16 个，避免长时间持有锁
            val element = poll() ?: break
            target.add(element)
            extracted++
        }
        return extracted
    }

    /** 当前队列中的元素数量 */
    val size: Int get() = count

    /** 队列是否已满 */
    val isFull: Boolean get() = count >= capacity

    /** 队列是否为空 */
    val isEmpty: Boolean get() = count == 0

    /** 清空队列 */
    fun clear() {
        head = 0
        tail = 0
        count = 0
        for (i in buffer.indices) {
            buffer[i] = null
        }
    }
}
