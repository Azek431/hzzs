package top.azek431.hzzs.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HzzsMotion.resolve] 纯函数单测：倍率合成、减少动效与 clamp。 */
class HzzsMotionTest {
    @Test
    fun defaultPolicyIsEnabledWithBaselineDurations() {
        val policy = HzzsMotion.resolve(animationScale = 1f, reduceMotion = false)
        assertTrue(policy.enabled)
        assertEquals(1f, policy.effectiveScale, 0.001f)
        assertEquals(HzzsMotion.BASE_INTERACTION_MS, policy.interactionMs)
        assertEquals(HzzsMotion.BASE_TRANSITION_MS, policy.transitionMs)
        assertEquals(HzzsMotion.BASE_EXIT_MS, policy.exitMs)
        assertTrue(policy.exitMs < policy.transitionMs)
    }

    @Test
    fun reduceMotionDisablesTransitions() {
        val policy = HzzsMotion.resolve(animationScale = 1f, reduceMotion = true)
        assertFalse(policy.enabled)
        assertEquals(0, policy.transitionMs)
        assertEquals(0, policy.exitMs)
        assertEquals(0, policy.scaleDuration(200))
    }

    @Test
    fun zeroAppScaleDisablesTransitions() {
        val policy = HzzsMotion.resolve(animationScale = 0f, reduceMotion = false)
        assertFalse(policy.enabled)
        assertEquals(0f, policy.effectiveScale, 0.001f)
    }

    @Test
    fun systemAnimatorZeroDisablesEvenIfAppScalePositive() {
        val policy = HzzsMotion.resolve(
            animationScale = 1f,
            reduceMotion = false,
            systemAnimatorDurationScale = 0f,
        )
        assertFalse(policy.enabled)
    }

    @Test
    fun scalesMultiplyAndClampDurations() {
        val policy = HzzsMotion.resolve(
            animationScale = 2f,
            reduceMotion = false,
            systemAnimatorDurationScale = 0.5f,
        )
        assertTrue(policy.enabled)
        assertEquals(1f, policy.effectiveScale, 0.001f)
        assertEquals(HzzsMotion.BASE_TRANSITION_MS, policy.transitionMs)
        assertEquals(400, policy.scaleDuration(400))
    }

    @Test
    fun animationScaleAboveTwoIsClamped() {
        val policy = HzzsMotion.resolve(animationScale = 9f, reduceMotion = false)
        assertEquals(2f, policy.appScale, 0.001f)
        assertEquals(2f, policy.effectiveScale, 0.001f)
        assertEquals(HzzsMotion.BASE_TRANSITION_MS * 2, policy.transitionMs)
    }

    @Test
    fun negativeSystemScaleTreatedAsZero() {
        val policy = HzzsMotion.resolve(
            animationScale = 1f,
            reduceMotion = false,
            systemAnimatorDurationScale = -1f,
        )
        assertFalse(policy.enabled)
    }
}
