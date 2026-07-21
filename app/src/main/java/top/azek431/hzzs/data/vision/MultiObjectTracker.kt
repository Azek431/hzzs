package top.azek431.hzzs.data.vision

import top.azek431.hzzs.domain.vision.Detection
import top.azek431.hzzs.domain.vision.NormalizedRect
import top.azek431.hzzs.domain.vision.ObjectKind
import kotlin.math.hypot

/**
 * 场景内多目标追踪器：为检测框分配跨帧稳定 [trackId]。
 *
 * 职责：
 * - 在同 [ObjectKind] 内按中心距离 + IoU 代价关联检测与既有轨道；
 * - 输出带稳定帧计数的 [TrackedDetection]，供动作门控（stableFrames）使用；
 * - 不按 kind 建全局唯一槽位，同 kind 多个坑/雕像/毛笔各自独立。
 *
 * 坐标：输入输出均为视口/全屏归一化矩形 `[0,1]`，本类不做像素换算。
 * 所有权：由 [VisionRuntimeController] 帧循环独占；场景或算法切换时必须 [reset]。
 * 线程：非线程安全，仅应在单帧循环线程上调用。
 */
class MultiObjectTracker(
    private val maxCenterDistance: Float = 0.18f,
    private val maxMissedFrames: Int = 4,
    private val maxTracks: Int = 32,
) {
    /**
     * 一帧关联结果：稳定 [trackId]、写回 id/平滑框后的 [detection]、连续命中帧数。
     */
    data class TrackedDetection(
        val trackId: Long,
        val detection: Detection,
        val stableFrames: Int,
    )

    private data class Track(
        val id: Long,
        val kind: ObjectKind,
        var bounds: NormalizedRect,
        var stableFrames: Int,
        var lastSequence: Long,
        var missed: Int,
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1L

    /**
     * 用当前帧检测更新轨道。
     *
     * @param sequence 单调帧序号；连续序号才累加 [TrackedDetection.stableFrames]
     * @param detections 本帧检测（归一化坐标）
     * @return 与输入一一对应的追踪结果；未匹配则分配新 id
     *
     * 未匹配旧轨 [missed] 递增，超过 [maxMissedFrames] 删除；轨道数超限时保留最近活跃者。
     */
    fun update(sequence: Long, detections: List<Detection>): List<TrackedDetection> {
        require(sequence >= 0)
        val available = tracks.toMutableSet()
        val output = ArrayList<TrackedDetection>(detections.size)
        detections.forEach { detection ->
            val match = available
                .asSequence()
                .filter { it.kind == detection.kind }
                .map { it to associationCost(it.bounds, detection.bounds) }
                .filter { it.second <= maxCenterDistance }
                .minByOrNull { it.second }
                ?.first
            val track = if (match == null) {
                Track(nextId++, detection.kind, detection.bounds, 1, sequence, 0).also(tracks::add)
            } else {
                available.remove(match)
                match.bounds = smooth(match.bounds, detection.bounds)
                match.stableFrames = if (sequence == match.lastSequence + 1) match.stableFrames + 1 else 1
                match.lastSequence = sequence
                match.missed = 0
                match
            }
            output += TrackedDetection(track.id, detection.copy(id = track.id, bounds = track.bounds), track.stableFrames)
        }
        available.forEach { it.missed++ }
        tracks.removeAll { it.missed > maxMissedFrames }
        // 防止极端场景轨道无限增长。
        if (tracks.size > maxTracks) {
            tracks.sortByDescending { it.lastSequence }
            while (tracks.size > maxTracks) tracks.removeAt(tracks.lastIndex)
        }
        return output
    }

    /** 清空全部轨道与 id 计数；场景/算法切换或会话停止时调用。 */
    fun reset() {
        tracks.clear()
        nextId = 1L
    }

    /** 关联代价：中心欧氏距离，并以 IoU 略微降低重叠框代价。 */
    private fun associationCost(a: NormalizedRect, b: NormalizedRect): Float {
        val acx = (a.left + a.right) * .5f
        val acy = (a.top + a.bottom) * .5f
        val bcx = (b.left + b.right) * .5f
        val bcy = (b.top + b.bottom) * .5f
        val distance = hypot(acx - bcx, acy - bcy)
        return distance * (1f - intersectionOverUnion(a, b) * .55f)
    }

    private fun intersectionOverUnion(a: NormalizedRect, b: NormalizedRect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /** 指数平滑框边界，减轻单帧抖动对关联与动作触发的影响。 */
    private fun smooth(a: NormalizedRect, b: NormalizedRect): NormalizedRect {
        val alpha = .68f
        fun blend(old: Float, fresh: Float) = old * (1f - alpha) + fresh * alpha
        return NormalizedRect(
            blend(a.left, b.left), blend(a.top, b.top),
            blend(a.right, b.right), blend(a.bottom, b.bottom),
        )
    }
}
