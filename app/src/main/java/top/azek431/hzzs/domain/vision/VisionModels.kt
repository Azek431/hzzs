package top.azek431.hzzs.domain.vision

import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ViewportConfig

/**
 * 视觉领域模型（纯 Kotlin，可 JVM 单测）。
 *
 * 职责：
 * - 定义归一化坐标、检测结果、帧输入与引擎契约
 * - 在进入 Tracker / 自动操作前清洗 JNI 输出
 *
 * 坐标约定：除特别说明外，矩形均为**全屏归一化** `[0, 1]`。
 * 像素换算只允许发生在绘制层与手势分发层。
 *
 * 本包不依赖 Android Framework。
 */

/**
 * 非空矩形，使用全屏归一化坐标。
 *
 * 不变量：
 * - 四边均为 finite，且落在 `[0, 1]`
 * - `left < right` 且 `top < bottom`
 *
 * 构造失败应走 [fromUnchecked] 得到 `null`，避免把脏数据推进业务。
 */
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
        /**
         * 宽松构造：裁剪到 `[0,1]`，过小或非 finite 返回 `null`。
         * 用于 JNI / 外部输入边界清洗。
         */
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

/**
 * 视觉目标类别。
 *
 * 含玩家与全部障碍；与设置侧 [ObstacleKind] 通过 [asObstacleKind] 映射。
 * `PLAYER` 不是障碍，不会进入类别过滤列表。
 */
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

/** 将检测类别映射为设置/场景可关闭的障碍 ID；玩家返回 `null`。 */
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

/**
 * 建议规避动作。
 *
 * - [NONE]：不可动作或仅诊断
 * - [JUMP] / [DOUBLE_JUMP] / [SLIDE]：由规划器映射为具体手势
 */
enum class Avoidance { NONE, JUMP, DOUBLE_JUMP, SLIDE }

/**
 * 单次检测结果。
 *
 * @property id 引擎侧临时 ID；跨帧稳定 ID 由 Tracker 分配
 * @property bounds 全屏归一化包围盒
 * @property confidence 置信度，必须在 `[0, 1]`
 * @property actionable 是否允许进入自动操作规划
 * @property diagnosticOnly 仅调试展示，不可与 actionable 同时为 true
 * @property avoidance 建议规避；actionable 为 true 时不得为 [Avoidance.NONE]
 */
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

/**
 * 帧元数据。
 *
 * 尺寸上限与 Native 侧一致，防止超大缓冲进入 JNI。
 */
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

/**
 * 引擎输入帧：元数据 + ARGB 像素。
 *
 * 所有权：调用方保证 `argb` 在 [VisionEngine.analyze] 返回前有效；
 * Native 不得缓存数组地址跨调用。
 */
data class VisionFrame(
    val meta: FrameMeta,
    val argb: IntArray,
) {
    init {
        require(meta.sourceWidth.toLong() * meta.sourceHeight.toLong() == argb.size.toLong())
    }
}

/**
 * 单帧视觉输出。
 *
 * @property scene 当前分析场景（与配置一致，不由引擎擅自切换赛季）
 * @property sceneConfidence 场景置信度，门控自动操作时会读取
 * @property player 玩家检测（可空）
 * @property detections 障碍列表（通常不含玩家；清洗后保证）
 * @property processingNanos 引擎耗时
 * @property error 非空表示本帧失败；调用方应 fail-closed
 * @property activeAlgorithmId 诊断：当前算法 ID
 * @property activeAlgorithmVersion 诊断：当前算法版本
 * @property algorithmGeneration 诊断：算法切换代数
 * @property usingBuiltinFallback 是否使用内置回退
 * @property algorithmLoadError 最近一次算法加载错误（可空）
 */
data class VisionResult(
    val scene: SceneId,
    val sceneConfidence: Float,
    val player: Detection?,
    val detections: List<Detection>,
    val processingNanos: Long,
    val error: String? = null,
    val activeAlgorithmId: String = AlgorithmRuntimeProfile.BUILTIN_ID,
    val activeAlgorithmVersion: String = AlgorithmRuntimeProfile.BUILTIN_VERSION,
    val algorithmGeneration: Long = 0L,
    val usingBuiltinFallback: Boolean = true,
    val algorithmLoadError: String? = null,
) {
    /** 可进入自动操作规划的检测子集。 */
    val actionableDetections: List<Detection>
        get() = detections.filter {
            it.actionable && !it.diagnosticOnly && it.avoidance != Avoidance.NONE
        }
}

/**
 * 视觉引擎契约。
 *
 * 实现通常为 JNI 适配器。算法切换必须在帧循环外的安全点完成，
 * 不得与 [analyze] 半热交错。
 */
interface VisionEngine {
    /**
     * 分析一帧。
     *
     * 线程：由运行时在后台调度；实现应自行串行化 JNI 调用。
     */
    suspend fun analyze(
        frame: VisionFrame,
        config: SceneConfig,
        viewport: ViewportConfig,
    ): VisionResult

    /**
     * 在安全切换点应用算法 profile。
     *
     * 失败时实现应保留旧配置或回退内置；不得在帧循环中半热切换。
     */
    fun configureAlgorithm(profile: AlgorithmRuntimeProfile): Result<AlgorithmActivation>

    /** 当前激活视图（含 generation 与回退诊断）。 */
    fun currentActivation(): AlgorithmActivation

    /** 当前算法代数；切换成功后递增。 */
    fun activeAlgorithmGeneration(): Long

    /** 清空引擎侧瞬时状态（不含算法 profile）。 */
    fun reset()
}

/**
 * 将 JNI / 引擎原始结果清洗为领域安全结果。
 *
 * 同时强制：
 * - 置信度与类别合法
 * - 用户按赛季关闭的障碍被剔除
 * - 玩家身后障碍不可动作
 * - 检测数量上限，防止异常膨胀
 *
 * 调用点：进入 Tracker 与自动操作规划之前。
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
            // 玩家只走 player 字段，不进入障碍列表。
            if (detection.kind == ObjectKind.PLAYER) return@mapNotNull null
            if (!detection.confidence.isFinite() || detection.confidence !in 0f..1f) return@mapNotNull null
            val obstacleKind = detection.kind.asObstacleKind() ?: return@mapNotNull null
            // 尊重用户在设置中关闭的障碍类别。
            if (obstacleKind in config.disabledObstacles) return@mapNotNull null
            // 已越过玩家身后的障碍不再规划动作，避免回点。
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

/** 与 Native 一致的单边最大像素。 */
private const val MAX_FRAME_DIMENSION = 4_096

/** 与 Native 一致的总像素上限（约 8MP）。 */
private const val MAX_FRAME_PIXELS = 8_388_608L
