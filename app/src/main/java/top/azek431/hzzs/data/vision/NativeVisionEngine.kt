package top.azek431.hzzs.data.vision

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.ViewportConfig
import top.azek431.hzzs.domain.vision.ActiveAlgorithmProvider
import top.azek431.hzzs.domain.vision.AlgorithmActivation
import top.azek431.hzzs.domain.vision.AlgorithmProfileValidator
import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile
import top.azek431.hzzs.domain.vision.Avoidance
import top.azek431.hzzs.domain.vision.Detection
import top.azek431.hzzs.domain.vision.NormalizedRect
import top.azek431.hzzs.domain.vision.ObjectKind
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.domain.vision.VisionFrame
import top.azek431.hzzs.domain.vision.VisionResult
import top.azek431.hzzs.domain.vision.VisionResultValidator
import top.azek431.hzzs.nativevision.NativeVision
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureNanoTime

/**
 * Native 视觉引擎的 JNI 适配层，实现领域 [VisionEngine]。
 *
 * 职责：
 * - 将 [VisionFrame] / 场景与视口配置映射为 [NativeVision.analyze] 调用；
 * - 把 native 检测结果还原为全屏归一化坐标的 [Detection] / [VisionResult]；
 * - 经 [configureAlgorithm] 在安全点切换算法快照，并与 analyze 串行（由调用方帧循环保证）。
 *
 * 输入输出：
 * - analyze：只读当前 [ActiveAlgorithmProvider] 的不可变激活快照，输出经 [VisionResultValidator] 消毒的结果；
 * - 像素缓冲仅在 JNI 调用期间借用，本类不持有帧数组地址。
 *
 * fail-closed：库不可用、调用异常、非法框/置信度均丢弃为错误结果或跳过该检测项。
 */
@Singleton
class NativeVisionEngine @Inject constructor(
    private val algorithmProvider: ActiveAlgorithmProvider,
) : VisionEngine {
    private val lastActivation = AtomicReference(algorithmProvider.current())

    /**
     * 在 Default 调度器上分析一帧。
     *
     * 线程：挂起函数，实际工作在 [Dispatchers.Default]；调用方应保证与 [configureAlgorithm] 不并发半热切换。
     * 坐标：native 返回视口裁剪内归一化框，再经 [toFullScreen] 映射到全屏 `[0,1]`。
     */
    override suspend fun analyze(
        frame: VisionFrame,
        config: SceneConfig,
        viewport: ViewportConfig,
    ): VisionResult = withContext(Dispatchers.Default) {
        val activation = algorithmProvider.current().also(lastActivation::set)
        if (!NativeVision.isAvailable) {
            return@withContext errorResult(
                config = config,
                activation = activation,
                message = NativeVision.loadFailureMessage?.let { "Native 视觉库加载失败：$it" }
                    ?: "Native 视觉库不可用",
            )
        }

        var nativeResult: Result<NativeVision.Result>? = null
        val elapsed = measureNanoTime {
            nativeResult = runCatching {
                // JNI 仅借用 frame.argb，调用返回后不得再访问该数组地址。
                NativeVision.analyze(
                    scene = config.sceneId.ordinal,
                    pixels = frame.argb,
                    width = frame.meta.sourceWidth,
                    height = frame.meta.sourceHeight,
                    workWidth = config.thresholds.workWidth,
                    enabledKindMask = enabledKindMask(config),
                    detectPlayer = config.thresholds.playerReferenceMode != PlayerReferenceMode.FIXED_RATIO,
                    fixedPlayerXRatio = config.thresholds.fixedPlayerXRatio,
                    viewportLeft = viewport.left,
                    viewportTop = viewport.top,
                    viewportRight = viewport.right,
                    viewportBottom = viewport.bottom,
                )
            }
        }
        val nativeAttempt = nativeResult ?: return@withContext errorResult(
            config = config,
            activation = activation,
            message = "Native 视觉引擎未返回结果",
            elapsed = elapsed,
        )
        val native = nativeAttempt.getOrElse { error ->
            return@withContext errorResult(
                config = config,
                activation = activation,
                message = "Native 视觉调用失败：${error.message?.take(200) ?: error.javaClass.simpleName}",
                elapsed = elapsed,
            )
        }
        val detections = native.detections.asSequence().take(MAX_NATIVE_DETECTIONS).mapNotNull { raw ->
            val kind = ObjectKind.entries.getOrNull(raw.kind) ?: return@mapNotNull null
            val cropBounds = NormalizedRect.fromUnchecked(raw.left, raw.top, raw.right, raw.bottom)
                ?: return@mapNotNull null
            val bounds = cropBounds.toFullScreen(viewport) ?: return@mapNotNull null
            val confidence = raw.confidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
                ?: return@mapNotNull null
            val avoidance = Avoidance.entries.getOrNull(raw.avoidance) ?: Avoidance.NONE
            Detection(
                id = raw.trackHint.toLong().coerceAtLeast(0),
                kind = kind,
                bounds = bounds,
                confidence = confidence,
                actionable = raw.actionable && avoidance != Avoidance.NONE,
                diagnosticOnly = raw.diagnosticOnly,
                avoidance = avoidance,
            )
        }.toList()
        VisionResultValidator.sanitize(
            VisionResult(
                scene = config.sceneId,
                sceneConfidence = native.sceneConfidence,
                player = detections.firstOrNull { it.kind == ObjectKind.PLAYER },
                detections = detections.filterNot { it.kind == ObjectKind.PLAYER },
                processingNanos = elapsed,
                error = native.error.takeIf(String::isNotBlank),
                activeAlgorithmId = activation.profile.algorithmId,
                activeAlgorithmVersion = activation.profile.version,
                algorithmGeneration = activation.generation,
                usingBuiltinFallback = activation.usingBuiltinFallback,
                algorithmLoadError = activation.loadError,
            ),
            config,
        )
    }

    /**
     * 在安全点配置算法：先 Kotlin 侧校验与激活，再下推 native。
     *
     * 失败路径 fail-closed：校验失败或 native 拒绝时回退内置 profile，尽量保持引擎可用，
     * 并向调用方返回 failure；成功则推进 generation 并更新 [lastActivation]。
     * 应由帧循环外或帧循环安全点调用，避免与 analyze 半热竞态。
     */
    override fun configureAlgorithm(profile: AlgorithmRuntimeProfile): Result<AlgorithmActivation> {
        val validated = AlgorithmProfileValidator.validate(profile)
        if (validated.isFailure) {
            // 校验失败：默认回退内置，保证 NativeVision 仍可用。
            val reason = validated.exceptionOrNull()?.message ?: "算法配置校验失败"
            AppLog.w(
                "algorithm",
                "profile validate failed id=${profile.algorithmId}: $reason → builtin fallback",
            )
            val fallback = algorithmProvider.activate(
                profile = AlgorithmRuntimeProfile.builtin(),
                fallbackToBuiltinOnError = true,
            ).getOrElse {
                algorithmProvider.activateBuiltin(reason)
            }
            if (NativeVision.isAvailable) {
                runCatching { NativeVision.configureAlgorithm(fallback.profile) }
                    .onFailure { AppLog.e("algorithm", "native configure builtin failed", it) }
            }
            lastActivation.set(fallback)
            return Result.failure(IllegalArgumentException(reason))
        }
        val activation = algorithmProvider.activate(validated.getOrThrow(), fallbackToBuiltinOnError = false)
            .getOrElse { error ->
                AppLog.e("algorithm", "kotlin activate failed: ${error.message}", error)
                return Result.failure(error)
            }
        if (NativeVision.isAvailable) {
            val native = runCatching { NativeVision.configureAlgorithm(activation.profile) }
                .getOrElse { error ->
                    val fallback = algorithmProvider.activateBuiltin(
                        "Native 配置失败：${error.message?.take(160) ?: error.javaClass.simpleName}",
                    )
                    runCatching { NativeVision.configureAlgorithm(fallback.profile) }
                    lastActivation.set(fallback)
                    AppLog.e(
                        "algorithm",
                        "native configure threw; fallback builtin gen=${fallback.generation}",
                        error,
                    )
                    return Result.failure(
                        IllegalStateException(fallback.loadError ?: "Native 配置失败"),
                    )
                }
            if (!native.ok) {
                val fallback = algorithmProvider.activateBuiltin(
                    native.error.ifBlank { "Native 拒绝算法配置" }.take(240),
                )
                runCatching { NativeVision.configureAlgorithm(fallback.profile) }
                lastActivation.set(fallback)
                AppLog.e(
                    "algorithm",
                    "native rejected profile id=${activation.profile.algorithmId}: ${native.error}",
                )
                return Result.failure(IllegalStateException(fallback.loadError ?: native.error))
            }
            AppLog.d(
                "algorithm",
                "native configure ok id=${activation.profile.algorithmId} gen=${activation.generation}",
            )
        } else {
            AppLog.w(
                "algorithm",
                "native unavailable; kotlin-only activation id=${activation.profile.algorithmId}",
            )
        }
        lastActivation.set(activation)
        return Result.success(activation)
    }

    /**
     * 读取当前算法 generation：优先 native 侧，库不可用时回落 Kotlin 激活快照。
     * 帧循环用该值检测算法切换并进入安全点。
     */
    override fun activeAlgorithmGeneration(): Long {
        val kotlinGen = algorithmProvider.current().generation
        if (!NativeVision.isAvailable) return kotlinGen
        return runCatching { NativeVision.activeAlgorithmGeneration() }.getOrDefault(kotlinGen)
    }

    override fun currentActivation(): AlgorithmActivation = algorithmProvider.current()

    /**
     * 仅重置 native 分析侧瞬时状态，不强制回退内置算法。
     * 算法回退请调用 [configureAlgorithm]（[AlgorithmRuntimeProfile.builtin]）。
     */
    override fun reset() {
        if (NativeVision.isAvailable) {
            runCatching { NativeVision.reset() }
        }
    }

    /** 构造无检测的错误结果，仍带上当前算法元数据便于 UI/诊断。 */
    private fun errorResult(
        config: SceneConfig,
        activation: AlgorithmActivation,
        message: String,
        elapsed: Long = 0L,
    ) = VisionResult(
        scene = config.sceneId,
        sceneConfidence = 0f,
        player = null,
        detections = emptyList(),
        processingNanos = elapsed,
        error = message,
        activeAlgorithmId = activation.profile.algorithmId,
        activeAlgorithmVersion = activation.profile.version,
        algorithmGeneration = activation.generation,
        usingBuiltinFallback = activation.usingBuiltinFallback,
        algorithmLoadError = activation.loadError,
    )

    /** 视口内归一化框 → 全屏归一化框；非法结果返回 null（fail-closed 丢弃）。 */
    private fun NormalizedRect.toFullScreen(viewport: ViewportConfig): NormalizedRect? =
        NormalizedRect.fromUnchecked(
            left = viewport.left + left * viewport.width,
            top = viewport.top + top * viewport.height,
            right = viewport.left + right * viewport.width,
            bottom = viewport.top + bottom * viewport.height,
        )

    /**
     * 根据场景禁用的障碍构造 native kind 位掩码。
     * Native Kind 保留 ordinal 0 为 PLAYER；障碍 ordinal 从 bit 1 起。
     */
    private fun enabledKindMask(config: SceneConfig): Int {
        var mask = ALL_NATIVE_KINDS_MASK
        config.disabledObstacles.forEach { obstacle ->
            mask = mask and (1 shl (obstacle.ordinal + 1)).inv()
        }
        return mask
    }

    private companion object {
        const val MAX_NATIVE_DETECTIONS = 128
        /** PLAYER(0) + 10 障碍 Kind(1..10) → 低 11 位。 */
        const val ALL_NATIVE_KINDS_MASK = 0x7FF
    }
}

/** Hilt 绑定：将 [NativeVisionEngine] / [DefaultActiveAlgorithmProvider] 接到领域接口。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisionEngineBindings {
    @Binds
    abstract fun bindVisionEngine(impl: NativeVisionEngine): VisionEngine

    @Binds
    abstract fun bindActiveAlgorithmProvider(
        impl: DefaultActiveAlgorithmProvider,
    ): ActiveAlgorithmProvider
}
