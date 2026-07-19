package top.azek431.hzzs.service.capture

import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSequenceTest {
    @Test fun sequenceIsStrictlyIncreasing() {
        val sequence = FrameSequencer()
        val a = sequence.next().first
        val b = sequence.next().first
        assertTrue(b > a)
    }
}
