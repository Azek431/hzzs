package top.azek431.hzzs.domain.automation

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import top.azek431.hzzs.domain.vision.Avoidance

class GestureArbiterTest {
    @Test fun dispatchesStrictlySerially() = runTest {
        val order = mutableListOf<Long>()
        val arbiter = GestureArbiter(clock = { 10L }, dispatcher = GestureDispatcher { action ->
            order += action.id
            delay(20)
            DispatchReceipt(action, DispatchOutcome.COMPLETED)
        })
        val a = async { arbiter.dispatch(action(1)) }
        val b = async { arbiter.dispatch(action(2)) }
        a.await(); b.await()
        assertEquals(listOf(1L, 2L), order)
    }

    @Test fun expiredActionNeverDispatches() = runTest {
        var called = false
        val arbiter = GestureArbiter(clock = { 100L }, dispatcher = GestureDispatcher { action ->
            called = true
            DispatchReceipt(action, DispatchOutcome.COMPLETED)
        })
        val result = arbiter.dispatch(action(1).copy(expiresAtUptimeMs = 99L))
        assertEquals(DispatchOutcome.EXPIRED, result.outcome)
        assertFalse(called)
    }

    @Test fun onlyCompletedActionIsCommitted() = runTest {
        val ledger = ActionCommitLedger()
        val action = action(9)
        ledger.commit(DispatchReceipt(action, DispatchOutcome.CANCELLED))
        assertEquals(true, ledger.canPlan(9))
        ledger.commit(DispatchReceipt(action, DispatchOutcome.COMPLETED))
        assertEquals(false, ledger.canPlan(9))
    }

    private fun action(id: Long) = AutomationAction(
        id = id,
        trackId = id,
        avoidance = Avoidance.JUMP,
        gesture = GestureSpec(0.8f, 0.8f),
        createdAtUptimeMs = 0L,
        expiresAtUptimeMs = 1_000L,
        allowedPackages = setOf("com.smile.gifmaker"),
    )
}
