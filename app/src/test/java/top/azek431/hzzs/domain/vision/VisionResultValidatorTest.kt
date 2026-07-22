package top.azek431.hzzs.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId

class VisionResultValidatorTest {
    @Test
    fun disabledObstacleNeverLeavesValidator() {
        val config = SceneConfig(
            sceneId = SceneId.SWEET_FACTORY,
            disabledObstacles = setOf(ObstacleKind.GREEN_BOTTLE),
        )
        val result = result(
            detection(ObjectKind.GREEN_BOTTLE, 0.30f, Avoidance.JUMP),
            detection(ObjectKind.CAKE_STRUCTURE, 0.50f, Avoidance.DOUBLE_JUMP),
        )

        val clean = VisionResultValidator.sanitize(result, config)

        assertEquals(listOf(ObjectKind.CAKE_STRUCTURE), clean.detections.map { it.kind })
    }

    @Test
    fun obstacleBehindPlayerCannotTriggerAutomation() {
        val player = Detection(
            id = 1,
            kind = ObjectKind.PLAYER,
            bounds = NormalizedRect(0.18f, 0.65f, 0.24f, 0.92f),
            confidence = 0.9f,
            actionable = false,
        )
        val obstacle = detection(ObjectKind.PIT, 0.05f, Avoidance.JUMP)
        val clean = VisionResultValidator.sanitize(
            result(obstacle).copy(player = player),
            SceneConfig(SceneId.SWEET_FACTORY),
        )

        assertFalse(clean.detections.single().actionable)
        assertTrue(clean.actionableDetections.isEmpty())
    }

    private fun result(vararg detections: Detection) = VisionResult(
        scene = SceneId.SWEET_FACTORY,
        sceneConfidence = 0.9f,
        player = null,
        detections = detections.toList(),
        processingNanos = 1,
    )

    private fun detection(kind: ObjectKind, left: Float, avoidance: Avoidance) = Detection(
        id = left.toBits().toLong(),
        kind = kind,
        bounds = NormalizedRect(left, 0.55f, left + 0.08f, 0.85f),
        confidence = 0.9f,
        actionable = true,
        avoidance = avoidance,
    )
}
