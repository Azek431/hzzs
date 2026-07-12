package top.azek431.hzzs.runtime.vision

import android.os.SystemClock
import android.util.Log
import top.azek431.hzzs.core.data.native.NativeLibraryLoader

object HzzsVisionBridge {
    private const val TAG = "HZZS-VisionV2"
    private const val RESULT_VERSION = 3
    private const val RESULT_SIZE = 64

    private external fun nativeAnalyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        coarseStep: Int,
        spikeStep: Int,
    ): IntArray?

    fun analyze(frame: NormalizedFrame, coarseStep: Int = 3, spikeStep: Int = 2): VisionFrameResult? {
        if (!NativeLibraryLoader.isAvailable) return null
        if (frame.width <= 0 || frame.height <= 0 || frame.pixels.size < frame.width * frame.height) return null
        val started = SystemClock.elapsedRealtimeNanos()
        val raw = runCatching { nativeAnalyze(frame.pixels, frame.width, frame.height, coarseStep, spikeStep) }
            .onFailure { Log.e(TAG, "nativeAnalyze failed", it) }.getOrNull() ?: return null
        if (raw.size < RESULT_SIZE || raw[0] != RESULT_VERSION) {
            Log.e(TAG, "native result mismatch: size=${raw.size}, version=${raw.getOrNull(0)}")
            return null
        }
        fun detection(offset: Int): VisionDetection {
            val type = VisionObjectType.fromCode(raw[offset + 1])
            val size = when (type) {
                VisionObjectType.POISON_BOTTLE -> if (raw[offset + 13] == 2) VisionSizeClass.LARGE else VisionSizeClass.SMALL
                VisionObjectType.CAKE_STRUCTURE -> if (raw[offset + 13] == 2) VisionSizeClass.WIDE else VisionSizeClass.NARROW
                VisionObjectType.HANGING_SPIKE -> VisionSizeClass.HANGING
                else -> VisionSizeClass.UNKNOWN
            }
            return VisionDetection(
                found = raw[offset] == 1,
                type = type,
                bounds = android.graphics.RectF(raw[offset + 2].toFloat(), raw[offset + 3].toFloat(), raw[offset + 4].toFloat(), raw[offset + 5].toFloat()),
                edgeGapPx = raw[offset + 8], widthPx = raw[offset + 9], heightPx = raw[offset + 10],
                widthP = raw[offset + 11] / 1000f, heightP = raw[offset + 12] / 1000f,
                sizeClass = size, confidence = raw[offset + 14] / 1000f, samples = raw[offset + 15],
            )
        }
        val all = listOf(detection(12), detection(28), detection(44)).filter { it.found }.sortedBy { it.edgeGapPx }
        return VisionFrameResult(
            width = raw[1], height = raw[2], playerLeft = raw[3], playerRight = raw[4],
            playerCenterX = raw[5], playerCenterY = raw[6], playerWidth = raw[7],
            detections = all, primary = all.firstOrNull(), totalSamples = raw[9],
            nativeCostMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f,
        )
    }
}
