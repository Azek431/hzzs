package top.azek431.hzzs.runtime.vision

import java.util.EnumMap

class VisionTracker(private val releaseMissingFrames: Int = 3) {
    private data class Track(var id: Long, var missing: Int = 0, var triggered: Boolean = false, var maxWidthP: Float = 0f, var maxHeightP: Float = 0f, var lastGap: Int = Int.MAX_VALUE)
    private val tracks = EnumMap<VisionObjectType, Track>(VisionObjectType::class.java)
    private var sequence = 0L

    fun update(result: VisionFrameResult): VisionFrameResult {
        val present = result.detections.associateBy { it.type }
        for (type in listOf(VisionObjectType.POISON_BOTTLE, VisionObjectType.CAKE_STRUCTURE, VisionObjectType.HANGING_SPIKE)) {
            val d = present[type]
            if (d == null) {
                tracks[type]?.let { if (++it.missing >= releaseMissingFrames) tracks.remove(type) }
                continue
            }
            var track = tracks[type]
            val isNew = track == null || track.missing >= releaseMissingFrames || (track.lastGap < 20 && d.edgeGapPx > result.playerWidth * 2)
            if (isNew) {
                track = Track(++sequence)
                tracks[type] = track
            }
            track!!.missing = 0
            track.maxWidthP = maxOf(track.maxWidthP, d.widthP)
            track.maxHeightP = maxOf(track.maxHeightP, d.heightP)
            track.lastGap = d.edgeGapPx
            d.trackId = track.id
            d.distanceP = d.edgeGapPx / result.playerWidth.coerceAtLeast(1).toFloat()
        }
        return result
    }

    fun wasTriggered(detection: VisionDetection): Boolean = tracks[detection.type]?.takeIf { it.id == detection.trackId }?.triggered == true
    fun markTriggered(detection: VisionDetection) { tracks[detection.type]?.takeIf { it.id == detection.trackId }?.triggered = true }
    fun maxWidthP(detection: VisionDetection) = tracks[detection.type]?.takeIf { it.id == detection.trackId }?.maxWidthP ?: detection.widthP
    fun maxHeightP(detection: VisionDetection) = tracks[detection.type]?.takeIf { it.id == detection.trackId }?.maxHeightP ?: detection.heightP
    fun reset() = tracks.clear()
}
