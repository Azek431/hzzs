package top.azek431.hzzs.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.SceneId

class TriggerDistanceAutoTunerTest {
    @Test
    fun noCandidateRaisesTowardNearGap() {
        val tuner = TriggerDistanceAutoTuner(minIntervalMs = 0L)
        val playerWidth = 0.05f
        val baseline = 1.4f
        val next = tuner.onNoCandidate(
            scene = SceneId.SEA_SALT_LIVING_ROOM,
            baseline = baseline,
            playerWidth = playerWidth,
            nearGap = 0.20f,
            nowMs = 1_000L,
        )
        assertNotNull(next)
        assertTrue(next!! > baseline)
        assertTrue(next <= baseline + 0.45f + 0.001f)
        assertEquals(next, tuner.effective(SceneId.SEA_SALT_LIVING_ROOM, baseline), 0.001f)
    }

    @Test
    fun noCandidateDoesNotRaiseWhenAlreadyInBand() {
        val tuner = TriggerDistanceAutoTuner(minIntervalMs = 0L)
        val next = tuner.onNoCandidate(
            scene = SceneId.SEA_SALT_LIVING_ROOM,
            baseline = 5.0f,
            playerWidth = 0.05f,
            nearGap = 0.10f,
            nowMs = 1_000L,
        )
        assertNull(next)
    }

    @Test
    fun planSuccessLowersTowardBaseline() {
        val tuner = TriggerDistanceAutoTuner(minIntervalMs = 0L)
        // force raise beyond single step by repeated calls with zero cooldown
        var t = 1_000L
        repeat(8) {
            tuner.onNoCandidate(
                scene = SceneId.SEA_SALT_LIVING_ROOM,
                baseline = 1.4f,
                playerWidth = 0.05f,
                nearGap = 0.40f,
                nowMs = t,
            )
            t += 2_000L
        }
        val raised = tuner.effective(SceneId.SEA_SALT_LIVING_ROOM, 1.4f)
        assertTrue(raised > 1.4f + 0.4f)
        val lowered = tuner.onPlanSuccess(
            scene = SceneId.SEA_SALT_LIVING_ROOM,
            baseline = 1.4f,
            playerWidth = 0.05f,
            gap = 0.02f,
            nowMs = t,
        )
        assertNotNull(lowered)
        assertTrue(lowered!! < raised)
        assertTrue(lowered >= 1.4f - 0.001f)
    }

    @Test
    fun respectsCooldown() {
        val tuner = TriggerDistanceAutoTuner(minIntervalMs = 5_000L)
        val first = tuner.onNoCandidate(
            scene = SceneId.SWEET_FACTORY,
            baseline = 1.5f,
            playerWidth = 0.05f,
            nearGap = 0.25f,
            nowMs = 1_000L,
        )
        assertNotNull(first)
        val second = tuner.onNoCandidate(
            scene = SceneId.SWEET_FACTORY,
            baseline = 1.5f,
            playerWidth = 0.05f,
            nearGap = 0.40f,
            nowMs = 2_000L,
        )
        assertNull(second)
    }
}
