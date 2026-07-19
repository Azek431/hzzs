package top.azek431.hzzs.runtime.vision

import kotlin.math.abs

class VisionTracker(
    private val releaseMissingFrames: Int = 3,
) {
    private data class Track(
        val id: Long,
        val type: VisionObjectType,
        var missing: Int = 0,
        var triggered: Boolean = false,
        var maxWidthP: Float = 0f,
        var maxHeightP: Float = 0f,
        var lastGap: Int = Int.MAX_VALUE,
        var centerX: Float = 0f,
    )

    private val tracks = mutableListOf<Track>()
    private var sequence = 0L

    fun update(result: VisionFrameResult): VisionFrameResult {
        if (result.sceneState != VisionSceneState.RUNNING || result.playerWidth <= 0) {
            reset()
            return result.copy(detections = emptyList(), primary = null)
        }

        val unmatchedTracks = tracks.toMutableSet()
        val updatedDetections = result.detections
            .sortedWith(compareBy<VisionDetection> { it.type.code }.thenBy { it.edgeGapPx })
            .map { detection ->
                val center = detection.bounds.centerX()
                val candidate = unmatchedTracks
                    .asSequence()
                    .filter { it.type == detection.type && it.missing < releaseMissingFrames }
                    .minByOrNull { track ->
                        val gapDelta = abs(track.lastGap - detection.edgeGapPx)
                        val centerDelta = abs(track.centerX - center).toInt()
                        gapDelta + centerDelta
                    }
                    ?.takeIf { track ->
                        val maxAssociationDistance = (result.playerWidth * 2.5f).toInt().coerceAtLeast(30)
                        abs(track.lastGap - detection.edgeGapPx) <= maxAssociationDistance
                    }

                val track = candidate ?: Track(
                    id = ++sequence,
                    type = detection.type,
                ).also(tracks::add)
                unmatchedTracks.remove(track)

                track.missing = 0
                track.maxWidthP = maxOf(track.maxWidthP, detection.widthP)
                track.maxHeightP = maxOf(track.maxHeightP, detection.heightP)
                track.lastGap = detection.edgeGapPx
                track.centerX = center

                detection.copy(
                    trackId = track.id,
                    distanceP = detection.edgeGapPx / result.playerWidth.coerceAtLeast(1).toFloat(),
                )
            }

        unmatchedTracks.forEach { it.missing++ }
        tracks.removeAll { it.missing >= releaseMissingFrames }

        val primary = updatedDetections
            .asSequence()
            .filter { it.actionable }
            .minByOrNull { it.distanceP }

        return result.copy(
            detections = updatedDetections,
            primary = primary,
        )
    }

    fun wasTriggered(detection: VisionDetection): Boolean =
        tracks.firstOrNull { it.id == detection.trackId }?.triggered == true

    fun markTriggered(detection: VisionDetection) {
        tracks.firstOrNull { it.id == detection.trackId }?.triggered = true
    }

    fun maxWidthP(detection: VisionDetection): Float =
        tracks.firstOrNull { it.id == detection.trackId }?.maxWidthP ?: detection.widthP

    fun maxHeightP(detection: VisionDetection): Float =
        tracks.firstOrNull { it.id == detection.trackId }?.maxHeightP ?: detection.heightP

    fun reset() {
        tracks.clear()
    }
}
