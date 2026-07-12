package top.azek431.hzzs.runtime.vision

import android.graphics.RectF

enum class VisionObjectType(val code: Int) {
    NONE(0), POISON_BOTTLE(1), CAKE_STRUCTURE(2), HANGING_SPIKE(3);
    companion object { fun fromCode(code: Int) = values().firstOrNull { it.code == code } ?: NONE }
}

enum class VisionSizeClass { UNKNOWN, SMALL, LARGE, NARROW, WIDE, HANGING }

data class VisionDetection(
    val found: Boolean,
    val type: VisionObjectType,
    val bounds: RectF = RectF(),
    val edgeGapPx: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val widthP: Float = 0f,
    val heightP: Float = 0f,
    val sizeClass: VisionSizeClass = VisionSizeClass.UNKNOWN,
    val confidence: Float = 0f,
    val samples: Int = 0,
    var trackId: Long = 0,
    var distanceP: Float = Float.POSITIVE_INFINITY,
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
