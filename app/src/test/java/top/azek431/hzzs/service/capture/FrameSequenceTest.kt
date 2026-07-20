package top.azek431.hzzs.service.capture

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameSequenceTest {
    @Test
    fun sequenceIsStrictlyIncreasing() {
        val sequence = FrameSequencer()
        val a = sequence.next().first
        val b = sequence.next().first
        assertTrue(b > a)
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
