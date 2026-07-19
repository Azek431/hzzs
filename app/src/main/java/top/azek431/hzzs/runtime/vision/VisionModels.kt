package top.azek431.hzzs.runtime.vision

import android.graphics.RectF

enum class VisionObjectType(val code: Int) {
    NONE(0),
    GROUND_OBSTACLE(1),
    GAP(2),
    OVERHEAD_OBSTACLE(3),
    ;

    companion object {
        fun fromCode(code: Int): VisionObjectType = entries.firstOrNull { it.code == code } ?: NONE

        // Source-compatible aliases for existing tests and callers. New code should use
        // the generic collision semantics above and VisionAppearance for season artwork.
        @Deprecated("Use GROUND_OBSTACLE")
        val POISON_BOTTLE: VisionObjectType = GROUND_OBSTACLE

        @Deprecated("Use GAP")
        val CAKE_STRUCTURE: VisionObjectType = GAP

        @Deprecated("Use OVERHEAD_OBSTACLE")
        val HANGING_SPIKE: VisionObjectType = OVERHEAD_OBSTACLE
    }
}

enum class VisionAppearance {
    UNKNOWN,
    SWEET_BOTTLE,
    SWEET_CAKE_GAP,
    SWEET_PIPING_BAG,
    BAMBOO_PANDA_STATUE,
    BAMBOO_PIT,
    BAMBOO_BRUSH,
}

enum class VisionSceneState(val code: Int) {
    UNSAFE(0),
    RUNNING(1),
    ;

    companion object {
        fun fromCode(code: Int): VisionSceneState = entries.firstOrNull { it.code == code } ?: UNSAFE
    }
}

enum class VisionSizeClass {
    UNKNOWN,
    SMALL,
    LARGE,
    NARROW,
    WIDE,
    HANGING,
}

data class VisionDetection(
    val found: Boolean,
    val type: VisionObjectType,
    val appearance: VisionAppearance = VisionAppearance.UNKNOWN,
    val bounds: RectF = RectF(),
    val edgeGapPx: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val widthP: Float = 0f,
    val heightP: Float = 0f,
    val sizeClass: VisionSizeClass = VisionSizeClass.UNKNOWN,
    val confidence: Float = 0f,
    val samples: Int = 0,
    val trackId: Long = 0,
    val distanceP: Float = Float.POSITIVE_INFINITY,
    val actionable: Boolean = false,
)

data class VisionFrameResult(
    val width: Int,
    val height: Int,
    val playerLeft: Int,
    val playerRight: Int,
    val playerCenterX: Int,
    val playerCenterY: Int,
    val playerWidth: Int,
    val detections: List<VisionDetection>,
    val primary: VisionDetection?,
    val totalSamples: Int,
    val nativeCostMs: Float,
    val sceneState: VisionSceneState = VisionSceneState.RUNNING,
    val playerConfidence: Float = 1f,
    val algorithm: VisionAlgorithm = VisionAlgorithm.SWEET_FACTORY_LEGACY,
)

/** HUD 只保存轻量坐标映射，不保留每帧约 2MB 的 ARGB IntArray。 */
data class FrameMapping(
    val viewport: RectF,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scaleToSourceX: Float,
    val scaleToSourceY: Float,
)

data class NormalizedFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val mapping: FrameMapping,
)
