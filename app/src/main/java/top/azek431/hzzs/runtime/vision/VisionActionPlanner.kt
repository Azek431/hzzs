package top.azek431.hzzs.runtime.vision

import android.os.SystemClock
import top.azek431.hzzs.features.service.RuntimeAction
import top.azek431.hzzs.features.service.RuntimeActionType
import kotlin.random.Random

/**
 * 只负责提出动作，不提前修改 Track 状态。
 * 竹影书屋参数仍属于实验配置，必须由用户单独确认后才允许进入本规划器。
 */
class VisionActionPlanner(
    private val tracker: VisionTracker,
) {
    fun plan(
        result: VisionFrameResult,
        bambooExperimentalEnabled: Boolean = false,
    ): List<RuntimeAction> {
        if (result.sceneState != VisionSceneState.RUNNING) return emptyList()
        if (result.algorithm == VisionAlgorithm.BAMBOO_STUDY && !bambooExperimentalEnabled) return emptyList()

        val detection = result.primary ?: return emptyList()
        if (!detection.actionable || detection.trackId <= 0L) return emptyList()

        val triggerDistanceP = when (result.algorithm) {
            VisionAlgorithm.SWEET_FACTORY_LEGACY -> SWEET_TRIGGER_DISTANCE_P
            VisionAlgorithm.BAMBOO_STUDY -> BAMBOO_TRIGGER_DISTANCE_P
        }
        if (detection.distanceP > triggerDistanceP || tracker.wasTriggered(detection)) return emptyList()

        val now = SystemClock.uptimeMillis()
        val baseKey = "${result.algorithm}:${detection.type}:${detection.trackId}"
        return when (detection.type) {
            VisionObjectType.GROUND_OBSTACLE -> {
                val large = tracker.maxHeightP(detection) >= 1.585f ||
                    detection.sizeClass == VisionSizeClass.LARGE
                if (large) {
                    val secondDelay = when (result.algorithm) {
                        VisionAlgorithm.SWEET_FACTORY_LEGACY -> Random.nextLong(50L, 101L)
                        VisionAlgorithm.BAMBOO_STUDY -> 80L
                    }
                    listOf(
                        RuntimeAction.jump(now, "$baseKey:1", priority = 10),
                        RuntimeAction.jump(now + secondDelay, "$baseKey:2", priority = 9),
                    )
                } else {
                    listOf(RuntimeAction.jump(now, baseKey, priority = 10))
                }
            }

            VisionObjectType.GAP -> {
                val wide = tracker.maxWidthP(detection) >= 3.60f ||
                    detection.sizeClass == VisionSizeClass.WIDE
                if (wide) {
                    val secondDelay = when (result.algorithm) {
                        VisionAlgorithm.SWEET_FACTORY_LEGACY -> 300L
                        VisionAlgorithm.BAMBOO_STUDY -> 250L
                    }
                    listOf(
                        RuntimeAction.jump(now, "$baseKey:1", priority = 10),
                        RuntimeAction.jump(now + secondDelay, "$baseKey:2", priority = 8),
                    )
                } else {
                    listOf(RuntimeAction.jump(now, baseKey, priority = 10))
                }
            }

            VisionObjectType.OVERHEAD_OBSTACLE -> listOf(
                RuntimeAction(
                    type = RuntimeActionType.SLIDE,
                    dueAtMs = now,
                    expiresAtMs = now + when (result.algorithm) {
                        VisionAlgorithm.SWEET_FACTORY_LEGACY -> 650L
                        VisionAlgorithm.BAMBOO_STUDY -> 600L
                    },
                    dedupeKey = baseKey,
                    priority = 10,
                ),
            )

            VisionObjectType.NONE -> emptyList()
        }
    }

    fun commit(detection: VisionDetection) {
        tracker.markTriggered(detection)
    }

    private companion object {
        const val SWEET_TRIGGER_DISTANCE_P = 1.50f
        const val BAMBOO_TRIGGER_DISTANCE_P = 1.35f
    }
}
