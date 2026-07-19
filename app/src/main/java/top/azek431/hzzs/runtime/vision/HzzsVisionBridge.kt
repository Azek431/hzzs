package top.azek431.hzzs.runtime.vision

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import top.azek431.hzzs.core.data.native.NativeLibraryLoader

object HzzsVisionBridge {
    private const val TAG = "HZZS-VisionV2"
    private const val LEGACY_RESULT_VERSION = 3
    private const val BAMBOO_RESULT_VERSION = 4
    private const val RESULT_SIZE = 64

    private external fun nativeAnalyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        coarseStep: Int,
        spikeStep: Int,
    ): IntArray?

    private external fun nativeAnalyzeBamboo(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): IntArray?

    fun analyze(
        frame: NormalizedFrame,
        algorithm: VisionAlgorithm,
        coarseStep: Int = 3,
        spikeStep: Int = 2,
    ): VisionFrameResult? {
        if (!NativeLibraryLoader.isAvailable) return null
        if (frame.width <= 0 || frame.height <= 0 || frame.pixels.size < frame.width * frame.height) return null

        val started = SystemClock.elapsedRealtimeNanos()
        val raw = runCatching {
            when (algorithm) {
                VisionAlgorithm.BAMBOO_STUDY ->
                    nativeAnalyzeBamboo(frame.pixels, frame.width, frame.height)

                VisionAlgorithm.SWEET_FACTORY_LEGACY ->
                    nativeAnalyze(frame.pixels, frame.width, frame.height, coarseStep, spikeStep)
            }
        }
            .onFailure { Log.e(TAG, "nativeAnalyze failed: ${algorithm.name}", it) }
            .getOrNull() ?: return null

        val expectedVersion = when (algorithm) {
            VisionAlgorithm.BAMBOO_STUDY -> BAMBOO_RESULT_VERSION
            VisionAlgorithm.SWEET_FACTORY_LEGACY -> LEGACY_RESULT_VERSION
        }
        if (raw.size < RESULT_SIZE || raw[0] != expectedVersion) {
            Log.e(
                TAG,
                "native result mismatch: algorithm=${algorithm.name}, size=${raw.size}, " +
                    "version=${raw.getOrNull(0)}, expected=$expectedVersion",
            )
            return null
        }

        val sceneState = when (algorithm) {
            VisionAlgorithm.BAMBOO_STUDY -> VisionSceneState.fromCode(raw[10])
            VisionAlgorithm.SWEET_FACTORY_LEGACY -> VisionSceneState.RUNNING
        }
        val playerConfidence = when (algorithm) {
            VisionAlgorithm.BAMBOO_STUDY -> (raw[11] / 1000f).coerceIn(0f, 1f)
            VisionAlgorithm.SWEET_FACTORY_LEGACY -> 1f
        }

        fun appearance(type: VisionObjectType): VisionAppearance = when (algorithm) {
            VisionAlgorithm.SWEET_FACTORY_LEGACY -> when (type) {
                VisionObjectType.GROUND_OBSTACLE -> VisionAppearance.SWEET_BOTTLE
                VisionObjectType.GAP -> VisionAppearance.SWEET_CAKE_GAP
                VisionObjectType.OVERHEAD_OBSTACLE -> VisionAppearance.SWEET_PIPING_BAG
                VisionObjectType.NONE -> VisionAppearance.UNKNOWN
            }

            VisionAlgorithm.BAMBOO_STUDY -> when (type) {
                VisionObjectType.GROUND_OBSTACLE -> VisionAppearance.BAMBOO_PANDA_STATUE
                VisionObjectType.GAP -> VisionAppearance.BAMBOO_PIT
                VisionObjectType.OVERHEAD_OBSTACLE -> VisionAppearance.BAMBOO_BRUSH
                VisionObjectType.NONE -> VisionAppearance.UNKNOWN
            }
        }

        fun detection(offset: Int): VisionDetection {
            val type = VisionObjectType.fromCode(raw[offset + 1])
            val size = when (type) {
                VisionObjectType.GROUND_OBSTACLE ->
                    if (raw[offset + 13] == 2) VisionSizeClass.LARGE else VisionSizeClass.SMALL

                VisionObjectType.GAP ->
                    if (raw[offset + 13] == 2) VisionSizeClass.WIDE else VisionSizeClass.NARROW

                VisionObjectType.OVERHEAD_OBSTACLE -> VisionSizeClass.HANGING
                VisionObjectType.NONE -> VisionSizeClass.UNKNOWN
            }
            val bounds = RectF(
                raw[offset + 2].toFloat(),
                raw[offset + 3].toFloat(),
                raw[offset + 4].toFloat(),
                raw[offset + 5].toFloat(),
            )
            val found = raw[offset] == 1
            val actionable = found &&
                sceneState == VisionSceneState.RUNNING &&
                playerConfidence >= MIN_PLAYER_CONFIDENCE &&
                bounds.right > raw[3]
            return VisionDetection(
                found = found,
                type = type,
                appearance = appearance(type),
                bounds = bounds,
                edgeGapPx = raw[offset + 8].coerceAtLeast(0),
                widthPx = raw[offset + 9],
                heightPx = raw[offset + 10],
                widthP = raw[offset + 11] / 1000f,
                heightP = raw[offset + 12] / 1000f,
                sizeClass = size,
                confidence = (raw[offset + 14] / 1000f).coerceIn(0f, 1f),
                samples = raw[offset + 15],
                actionable = actionable,
            )
        }

        val all = if (sceneState == VisionSceneState.RUNNING) {
            listOf(detection(12), detection(28), detection(44))
                .filter { it.found }
                .sortedBy { it.edgeGapPx }
        } else {
            emptyList()
        }
        val primary = all.firstOrNull { it.actionable }

        return VisionFrameResult(
            width = raw[1],
            height = raw[2],
            playerLeft = raw[3],
            playerRight = raw[4],
            playerCenterX = raw[5],
            playerCenterY = raw[6],
            playerWidth = raw[7],
            detections = all,
            primary = primary,
            totalSamples = raw[9],
            nativeCostMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f,
            sceneState = sceneState,
            playerConfidence = playerConfidence,
            algorithm = algorithm,
        )
    }

    /**
     * Compatibility overload for historical tests/tools. The runtime path always passes
     * an explicit algorithm; legacy callers are deliberately routed to Sweet Factory,
     * never silently to the new default Bamboo algorithm.
     */
    @Deprecated("Pass VisionAlgorithm explicitly")
    fun analyze(
        frame: NormalizedFrame,
        coarseStep: Int = 3,
        spikeStep: Int = 2,
    ): VisionFrameResult? = analyze(
        frame = frame,
        algorithm = VisionAlgorithm.SWEET_FACTORY_LEGACY,
        coarseStep = coarseStep,
        spikeStep = spikeStep,
    )

    private const val MIN_PLAYER_CONFIDENCE = 0.45f
}
