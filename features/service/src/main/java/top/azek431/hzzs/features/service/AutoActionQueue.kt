// 火崽崽助手（HZZS）旧版坐标动作队列。
//
// 此队列仅用于兼容现有检测器；所有动作仍由唯一的 AutoOperationService
// 在目标应用校验通过后执行。后续算法统一阶段会迁移到 RuntimeActionQueue。

package top.azek431.hzzs.features.service

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

/** 有界、可过期的线程安全坐标动作队列。 */
object AutoActionQueue {
    private const val TAG = "HZZS-AutoOp"
    private const val MAX_SIZE = 24
    private const val MAX_ACTION_AGE_MS = 1_000L

    private val queue = ArrayDeque<QueuedAction>()

    @Volatile private var paused = false
    @Volatile private var enabled = false
    @Volatile private var delayMs = 100

    @Synchronized
    fun enqueue(action: QueuedAction) {
        if (!enabled || paused) return
        if (!action.targetX.isFinite() || !action.targetY.isFinite()) return
        if (action.targetX !in 0f..1f || action.targetY !in 0f..1f) return

        discardExpired(SystemClock.uptimeMillis())
        if (queue.size >= MAX_SIZE) {
            Log.w(TAG, "[LegacyQueue] capacity reached, dropping ${action.type}")
            return
        }
        queue.addLast(action)
    }

    @Synchronized
    fun dequeue(now: Long = SystemClock.uptimeMillis()): QueuedAction? {
        if (!enabled || paused) return null
        discardExpired(now)
        return queue.pollFirst()
    }

    @Synchronized
    fun clear() {
        queue.clear()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) queue.clear()
        Log.i(TAG, "[LegacyQueue] enabled=$value")
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        paused = value
    }

    fun setDelay(value: Int) {
        delayMs = value.coerceIn(0, 500)
    }

    @Synchronized
    fun size(): Int = queue.size

    fun getDelay(): Int = delayMs

    @Synchronized
    fun getStatus(): String =
        "enabled=$enabled, paused=$paused, delay=${delayMs}ms, queue=${queue.size}"

    private fun discardExpired(now: Long) {
        while (queue.isNotEmpty() && now - queue.peekFirst().timestamp > MAX_ACTION_AGE_MS) {
            queue.removeFirst()
        }
    }
}

/** 旧版检测器输出的归一化坐标动作。 */
data class QueuedAction(
    val type: ActionType,
    val targetX: Float,
    val targetY: Float,
    val timestamp: Long = SystemClock.uptimeMillis(),
) {
    enum class ActionType {
        JUMP,
        SLIDE,
        DOUBLE_JUMP,
        TAP,
    }
}
