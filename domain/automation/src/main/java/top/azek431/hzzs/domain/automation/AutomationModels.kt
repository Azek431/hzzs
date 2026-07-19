package top.azek431.hzzs.domain.automation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.domain.vision.Avoidance

data class GestureSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long = 30L,
) {
    init {
        require(startX in 0f..1f && startY in 0f..1f)
        require(endX == null || endX in 0f..1f)
        require(endY == null || endY in 0f..1f)
        require((endX == null) == (endY == null))
        require(durationMs in 10L..1_000L)
    }
}

data class AutomationAction(
    val id: Long,
    val trackId: Long,
    val avoidance: Avoidance,
    val gesture: GestureSpec,
    val createdAtUptimeMs: Long,
    val expiresAtUptimeMs: Long,
    val allowedPackages: Set<String>,
    val requiredWindowClassPrefixes: Set<String> = emptySet(),
    val retryCount: Int = 0,
) {
    init {
        require(id > 0)
        require(trackId > 0)
        require(createdAtUptimeMs <= expiresAtUptimeMs)
        require(allowedPackages.isNotEmpty())
        require(retryCount >= 0)
    }

    fun matchesWindow(className: String): Boolean =
        requiredWindowClassPrefixes.isEmpty() || requiredWindowClassPrefixes.any(className::startsWith)
}

enum class DispatchOutcome { COMPLETED, CANCELLED, REJECTED, EXPIRED }

data class DispatchReceipt(
    val action: AutomationAction,
    val outcome: DispatchOutcome,
    val detail: String? = null,
)

fun interface GestureDispatcher {
    suspend fun dispatch(action: AutomationAction): DispatchReceipt
}

/**
 * The single gate around every system gesture. The mutex remains locked until
 * Android reports completion/cancellation, preventing dispatchGesture races.
 */
class GestureArbiter(
    private val clock: () -> Long,
    private val dispatcher: GestureDispatcher,
) {
    private val mutex = Mutex()

    suspend fun dispatch(action: AutomationAction): DispatchReceipt = mutex.withLock {
        if (clock() >= action.expiresAtUptimeMs) {
            return@withLock DispatchReceipt(action, DispatchOutcome.EXPIRED, "动作已过期")
        }
        dispatcher.dispatch(action)
    }
}

class ActionCommitLedger {
    private val mutex = Mutex()
    private val completedTracks = mutableSetOf<Long>()

    suspend fun canPlan(trackId: Long): Boolean = mutex.withLock {
        trackId > 0 && trackId !in completedTracks
    }

    suspend fun commit(receipt: DispatchReceipt) = mutex.withLock {
        if (receipt.outcome == DispatchOutcome.COMPLETED) {
            completedTracks += receipt.action.trackId
        }
    }

    suspend fun reset() = mutex.withLock { completedTracks.clear() }
}
