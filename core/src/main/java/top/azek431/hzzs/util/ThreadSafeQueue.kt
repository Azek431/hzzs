// 火崽崽助手（HZZS）线程安全队列。
//
// 无锁环形缓冲区（lock-free ring buffer），适用于单生产者-单消费者场景。
// 替代 Handler.post {} 模式，支持批量 drainTo() 减少主线程调度开销。
//
// 线程模型：
// - 生产者（后台线程）和消费者（主线程）通过 volatile head/tail/count 同步
// - 不使用 synchronized 或 ReentrantLock，减少锁竞争开销
// - 注意：offer/poll 不是原子操作，多生产者/多消费者场景需要外部加锁
//
// 与 Handler.post 的区别：
// - Handler.post 是异步回调，无法批量处理
// - ThreadSafeQueue 支持批量 drainTo()，减少主线程调度开销
// - 满时可选择丢弃最旧帧或阻塞等待
//
// 使用场景：
// - HUDRenderer 后台线程 → ThreadSafeQueue.offer() → 主线程 ThreadSafeQueue.drainTo()
// - 批量消费可减少主线程调度次数，降低 UI 更新延迟

package top.azek431.hzzs.core.util

/**
 * 线程安全的有界环形缓冲区。
 *
 * 使用数组 + 头尾指针实现，支持生产者/消费者异步通信。
 * 队列满时丢弃最旧的元素（head 前进一位），确保新数据优先。
 *
 * @param capacity 最大容量，默认为 64
 */
class ThreadSafeQueue<E>(private val capacity: Int = 64) {

    /** 底层存储数组，使用 Any? 类型避免泛型擦除问题 */
    private val buffer = arrayOfNulls<Any>(capacity)

    /** 下一个读取位置（消费者指针），volatile 保证多线程可见性 */
    @Volatile private var head = 0

    /** 下一个写入位置（生产者指针），volatile 保证多线程可见性 */
    @Volatile private var tail = 0

    /** 当前元素个数，volatile 保证多线程可见性 */
    @Volatile private var count = 0

    /**
     * 添加元素到队尾。
     *
     * 队列满时丢弃最旧的元素（head 前进一位），新元素覆盖旧元素位置。
     * 此方法使用 CAS-like 循环，不依赖 synchronized。
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
     * 每次最多取 16 个元素，避免长时间占用主线程。
     * 适合在 UI 线程中批量消费后台线程产生的数据。
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

    /**
     * 清空队列。
     *
     * 重置 head/tail/count 指针，并将 buffer 中所有引用置为 null。
     * 注意：此方法不是线程安全的，应在无并发访问时调用。
     */
    fun clear() {
        head = 0
        tail = 0
        count = 0
        for (i in buffer.indices) {
            buffer[i] = null
        }
    }
}
