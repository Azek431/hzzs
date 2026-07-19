package top.azek431.hzzs.data.vision

import top.azek431.hzzs.domain.vision.Detection
import top.azek431.hzzs.domain.vision.NormalizedRect
import top.azek431.hzzs.domain.vision.ObjectKind
import kotlin.math.hypot

/**
 * Scene-local multi-object tracker. It never indexes detections by kind, so
 * several pits/statues/brushes of the same kind remain independent tracks.
 */
class MultiObjectTracker(
    private val maxCenterDistance: Float = 0.18f,
    private val maxMissedFrames: Int = 4,
) {
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
        return output
    }

    fun reset() {
        tracks.clear()
        nextId = 1L
    }

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

    private fun smooth(a: NormalizedRect, b: NormalizedRect): NormalizedRect {
        val alpha = .68f
        fun blend(old: Float, fresh: Float) = old * (1f - alpha) + fresh * alpha
        return NormalizedRect(
            blend(a.left, b.left), blend(a.top, b.top),
            blend(a.right, b.right), blend(a.bottom, b.bottom),
        )
    }
}
