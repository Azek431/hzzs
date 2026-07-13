package top.azek431.hzzs.features.service

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

/** 视觉运行时使用的绝对到期动作队列。 */
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

    /** 组内任意冲突或容量不足时整体拒绝，不产生部分入队。 */
    @Synchronized
    fun enqueueAll(actions: Collection<RuntimeAction>): Int {
        if (!enabled || paused || actions.isEmpty()) return 0
        val now = SystemClock.uptimeMillis()
        val candidates = actions
            .filter { it.expiresAtMs > now && it.dedupeKey.isNotBlank() }
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
        while (queue.isNotEmpty() && queue.peek().expiresAtMs <= now) {
            queue.poll()?.let { keys.remove(it.dedupeKey) }
        }
        val next = queue.peek() ?: return null
        if (next.dueAtMs > now) return null
        queue.poll()
        keys.remove(next.dedupeKey)
        return next
    }

    @Synchronized
    fun retry(action: RuntimeAction, delayMs: Long = 40L): Boolean {
        if (!enabled || paused) return false
        val due = SystemClock.uptimeMillis() + delayMs.coerceIn(16L, 120L)
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
