package top.azek431.hzzs.runtime.vision

import android.os.SystemClock
import top.azek431.hzzs.runtime.action.RuntimeAction
import top.azek431.hzzs.runtime.action.RuntimeActionType
import kotlin.random.Random

/**
 * 只负责提出动作，不提前修改 Track 状态。
 * 只有动作队列真正接受至少一个动作后，调用 commit() 才标记已触发，避免关闭自动操作时
 * 目标被错误锁死、重新开启后也不再执行。
 */
class VisionActionPlanner(
    private val tracker: VisionTracker,
    private val triggerDistanceP: Float = 1.50f,
) {
    fun plan(result: VisionFrameResult): List<RuntimeAction> {
        val detection = result.primary ?: return emptyList()
        if (detection.distanceP > triggerDistanceP || tracker.wasTriggered(detection)) return emptyList()

        val now = SystemClock.uptimeMillis()
        val baseKey = "${detection.type}:${detection.trackId}"
        return when (detection.type) {
            VisionObjectType.POISON_BOTTLE -> {
                val large = tracker.maxHeightP(detection) >= 1.585f ||
                    detection.sizeClass == VisionSizeClass.LARGE
                if (large) {
                    listOf(
                        RuntimeAction.jump(now, "$baseKey:1", priority = 10),
                        RuntimeAction.jump(now + Random.nextLong(50L, 101L), "$baseKey:2", priority = 9),
                    )
                } else {
                    listOf(RuntimeAction.jump(now, baseKey, priority = 10))
                }
            }

            VisionObjectType.CAKE_STRUCTURE -> {
                val wide = tracker.maxWidthP(detection) >= 3.60f ||
                    detection.sizeClass == VisionSizeClass.WIDE
                if (wide) {
                    listOf(
                        RuntimeAction.jump(now, "$baseKey:1", priority = 10),
                        RuntimeAction.jump(now + 300L, "$baseKey:2", priority = 8),
                    )
                } else {
                    listOf(RuntimeAction.jump(now, baseKey, priority = 10))
                }
            }

            VisionObjectType.HANGING_SPIKE -> listOf(
                RuntimeAction(
                    type = RuntimeActionType.SLIDE,
                    dueAtMs = now,
                    expiresAtMs = now + 650L,
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
}
