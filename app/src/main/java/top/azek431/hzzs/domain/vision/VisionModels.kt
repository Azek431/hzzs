package top.azek431.hzzs.domain.vision

import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ViewportConfig

/** A non-empty rectangle in normalized full-screen coordinates. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    companion object {
        fun fromUnchecked(left: Float, top: Float, right: Float, bottom: Float): NormalizedRect? {
            if (!listOf(left, top, right, bottom).all(Float::isFinite)) return null
            val l = left.coerceIn(0f, 1f)
            val t = top.coerceIn(0f, 1f)
            val r = right.coerceIn(0f, 1f)
            val b = bottom.coerceIn(0f, 1f)
            if (r - l < 0.0001f || b - t < 0.0001f) return null
            return NormalizedRect(l, t, r, b)
        }
    }
}

enum class ObjectKind {
    PLAYER,
    POISON_BOTTLE,
    CAKE_STRUCTURE,
    HANGING_SPIKE,
    PIT,
    PANDA_STATUE,
    BAMBOO_GAP,
    HANGING_BRUSH,
}

fun ObjectKind.asObstacleKind(): ObstacleKind? = when (this) {
    ObjectKind.PLAYER -> null
    ObjectKind.POISON_BOTTLE -> ObstacleKind.POISON_BOTTLE
    ObjectKind.CAKE_STRUCTURE -> ObstacleKind.CAKE_STRUCTURE
    ObjectKind.HANGING_SPIKE -> ObstacleKind.HANGING_SPIKE
    ObjectKind.PIT -> ObstacleKind.PIT
    ObjectKind.PANDA_STATUE -> ObstacleKind.PANDA_STATUE
    ObjectKind.BAMBOO_GAP -> ObstacleKind.BAMBOO_GAP
    ObjectKind.HANGING_BRUSH -> ObstacleKind.HANGING_BRUSH
}

enum class Avoidance { NONE, JUMP, DOUBLE_JUMP, SLIDE }

data class Detection(
    val id: Long,
    val kind: ObjectKind,
    val bounds: NormalizedRect,
    val confidence: Float,
    val actionable: Boolean,
    val diagnosticOnly: Boolean = false,
    val avoidance: Avoidance = Avoidance.NONE,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
        require(!(actionable && diagnosticOnly))
        require(!actionable || avoidance != Avoidance.NONE)
    }
}

data class FrameMeta(
    val sequence: Long,
    val timestampNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    init {
        require(sequence >= 0)
        require(timestampNanos >= 0)
        require(sourceWidth > 0 && sourceHeight > 0)
        require(sourceWidth <= MAX_FRAME_DIMENSION && sourceHeight <= MAX_FRAME_DIMENSION)
        require(sourceWidth.toLong() * sourceHeight.toLong() <= MAX_FRAME_PIXELS)
    }
}

data class VisionFrame(
    val meta: FrameMeta,
    val argb: IntArray,
) {
    init {
        require(meta.sourceWidth.toLong() * meta.sourceHeight.toLong() == argb.size.toLong())
    }
}

data class VisionResult(
    val scene: SceneId,
    val sceneConfidence: Float,
    val player: Detection?,
    val detections: List<Detection>,
    val processingNanos: Long,
    val error: String? = null,
    /** 当前激活算法诊断字段；切换算法后 generation 递增。 */
    val activeAlgorithmId: String = AlgorithmRuntimeProfile.BUILTIN_ID,
    val activeAlgorithmVersion: String = AlgorithmRuntimeProfile.BUILTIN_VERSION,
    val algorithmGeneration: Long = 0L,
    val usingBuiltinFallback: Boolean = true,
    val algorithmLoadError: String? = null,
) {
    val actionableDetections: List<Detection>
        get() = detections.filter {
            it.actionable && !it.diagnosticOnly && it.avoidance != Avoidance.NONE
        }
}

interface VisionEngine {
    suspend fun analyze(
        frame: VisionFrame,
        config: SceneConfig,
        viewport: ViewportConfig,
    ): VisionResult

    /**
     * 在安全切换点应用算法 profile。失败时实现应保留旧配置或回退内置。
     * 不得在帧循环中半热切换。
     */
    fun configureAlgorithm(profile: AlgorithmRuntimeProfile): Result<AlgorithmActivation>

    fun currentActivation(): AlgorithmActivation

    fun activeAlgorithmGeneration(): Long

    fun reset()
}

/**
 * Sanitizes every JNI result before it enters tracking or automation. The same
 * filter also enforces the user's per-season obstacle exclusions.
 */
object VisionResultValidator {
    fun sanitize(result: VisionResult, config: SceneConfig): VisionResult {
        val sceneConfidence = result.sceneConfidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val player = result.player?.takeIf {
            it.kind == ObjectKind.PLAYER &&
                !it.diagnosticOnly &&
                it.confidence.isFinite() &&
                it.confidence in 0f..1f
        }
        val playerBounds = player?.bounds
        val clean = result.detections.asSequence().mapNotNull { detection ->
            if (detection.kind == ObjectKind.PLAYER) return@mapNotNull null
            if (!detection.confidence.isFinite() || detection.confidence !in 0f..1f) return@mapNotNull null
            val obstacleKind = detection.kind.asObstacleKind() ?: return@mapNotNull null
            if (obstacleKind in config.disabledObstacles) return@mapNotNull null
            val behind = playerBounds != null && detection.bounds.right <= playerBounds.left
            val canAct = detection.actionable &&
                !behind &&
                !detection.diagnosticOnly &&
                detection.avoidance != Avoidance.NONE
            detection.copy(actionable = canAct)
        }.take(MAX_DETECTIONS).toList()
        return result.copy(
            sceneConfidence = sceneConfidence,
            player = player,
            detections = clean,
            processingNanos = result.processingNanos.coerceAtLeast(0),
        )
    }

    private const val MAX_DETECTIONS = 128
}

private const val MAX_FRAME_DIMENSION = 4_096
private const val MAX_FRAME_PIXELS = 8_388_608L
