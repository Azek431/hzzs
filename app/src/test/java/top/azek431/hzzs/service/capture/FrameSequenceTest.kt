package top.azek431.hzzs.service.capture

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameSequenceTest {
    @Test
    fun sequenceAndTimestampAreStrictlyIncreasing() {
        var now = 1_000L
        val sequence = FrameSequencer(clockNanos = { ++now })

        val first = sequence.next()
        val second = sequence.next()

        assertTrue(second.first > first.first)
        assertTrue(second.second > first.second)
    }

    @Test
    fun poolNeverAcceptsLeaseFromOldResolutionGeneration() {
        val pool = IntFramePool(capacity = 2)
        val old = requireNotNull(pool.tryAcquire(4))
        val currentA = requireNotNull(pool.tryAcquire(9))
        val currentB = requireNotNull(pool.tryAcquire(9))
        assertNull(pool.tryAcquire(9))

        old.close()
        assertNull("old-generation lease must not re-enter the active pool", pool.tryAcquire(9))

        currentA.close()
        assertNotNull(pool.tryAcquire(9)?.also(AutoCloseable::close))
        currentB.close()
    }

    @Test
    fun poolRejectsFrameLargerThanMemoryBudget() {
        val pool = IntFramePool(capacity = 2)
        assertThrows(IllegalArgumentException::class.java) {
            pool.tryAcquire(8_388_609)
        }
    }

    @Test
    fun capturedFrameReleasesLeaseOnlyOnce() {
        var releases = 0
        val frame = CapturedFrame(1, 1, 1, 1, intArrayOf(0)) { releases++ }
        frame.close()
        frame.close()
        assertTrue(releases == 1)
    }
}
