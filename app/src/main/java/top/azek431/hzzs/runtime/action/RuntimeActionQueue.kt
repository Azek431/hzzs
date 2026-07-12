package top.azek431.hzzs.runtime.action

import android.os.SystemClock
import java.util.PriorityQueue

enum class RuntimeActionType { JUMP, SLIDE }

data class RuntimeAction(
    val type: RuntimeActionType,
    val dueAtMs: Long,
    val expiresAtMs: Long = dueAtMs + 800L,
    val dedupeKey: String,
    val priority: Int = 0,
) {
    companion object {
        fun jump(dueAtMs: Long, key: String, priority: Int = 0) = RuntimeAction(
            type = RuntimeActionType.JUMP,
            dueAtMs = dueAtMs,
            expiresAtMs = dueAtMs + 800L,
            dedupeKey = key,
            priority = priority,
        )
    }
}

/**
 * C++ 实时视觉专用的绝对到期动作队列。
 *
 * 组动作采用原子入队：大瓶双跳或宽结构补跳要么全部进入队列，要么全部拒绝，
 * 防止只接受第一跳却提前把 Track 标记为已触发。
 */
object RuntimeActionQueue {
    private val queue = PriorityQueue<RuntimeAction>(
        compareBy<RuntimeAction> { it.dueAtMs }.thenByDescending { it.priority },
    )
    private val keys = HashSet<String>()

    @Volatile private var enabled = false
    @Volatile private var paused = false
    private const val MAX_SIZE = 16

    @Synchronized
    fun setEnabled(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed && !value) clear()
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        paused = value
    }

    /** 返回接受数量；组内任意冲突或容量不足时返回 0，不产生部分入队。 */
    @Synchronized
    fun enqueueAll(actions: Collection<RuntimeAction>): Int {
        if (!enabled || paused || actions.isEmpty()) return 0
        val now = SystemClock.uptimeMillis()
        val candidates = actions
            .filter { it.expiresAtMs > now }
            .sortedWith(compareBy<RuntimeAction> { it.dueAtMs }.thenByDescending { it.priority })
        if (candidates.isEmpty()) return 0

        val candidateKeys = candidates.map { it.dedupeKey }
        if (candidateKeys.toSet().size != candidateKeys.size) return 0
        if (candidateKeys.any(keys::contains)) return 0
        if (queue.size + candidates.size > MAX_SIZE) return 0

        candidates.forEach { action ->
            keys.add(action.dedupeKey)
            queue.offer(action)
        }
        return candidates.size
    }

    @Synchronized
    fun pollDue(now: Long = SystemClock.uptimeMillis()): RuntimeAction? {
        if (!enabled || paused) return null
        while (queue.isNotEmpty() && queue.peek().expiresAtMs < now) {
            queue.poll()?.let { keys.remove(it.dedupeKey) }
        }
        val next = queue.peek() ?: return null
        if (next.dueAtMs > now) return null
        queue.poll()
        keys.remove(next.dedupeKey)
        return next
    }

    /** dispatchGesture 被拒绝或稍后取消时，在原过期时间内短延迟重试。 */
    @Synchronized
    fun retry(action: RuntimeAction, delayMs: Long = 40L): Boolean {
        if (!enabled || paused) return false
        val now = SystemClock.uptimeMillis()
        val due = now + delayMs.coerceIn(16L, 120L)
        if (due >= action.expiresAtMs || !keys.add(action.dedupeKey)) return false
        queue.offer(action.copy(dueAtMs = due))
        return true
    }

    @Synchronized
    fun nextDelay(now: Long = SystemClock.uptimeMillis()): Long =
        queue.peek()?.let { (it.dueAtMs - now).coerceIn(8L, 250L) } ?: 120L

    @Synchronized
    fun clear() {
        queue.clear()
        keys.clear()
    }

    @Synchronized
    fun size(): Int = queue.size
}
