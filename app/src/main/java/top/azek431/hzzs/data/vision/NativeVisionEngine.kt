package top.azek431.hzzs.data.vision

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * JNI 适配层：帧分析只读当前算法 generation 对应的不可变快照。
 * 配置切换经 [configureAlgorithm] 在安全点完成，并与 native analyze 串行。
 */
@Singleton
class NativeVisionEngine @Inject constructor(
    private val algorithmProvider: ActiveAlgorithmProvider,
) : VisionEngine {
    private val lastActivation = AtomicReference(algorithmProvider.current())

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

    override fun configureAlgorithm(profile: AlgorithmRuntimeProfile): Result<AlgorithmActivation> {
        val validated = AlgorithmProfileValidator.validate(profile)
        if (validated.isFailure) {
            // 校验失败：默认回退内置，保证 NativeVision 仍可用。
            val fallback = algorithmProvider.activate(
                profile = AlgorithmRuntimeProfile.builtin(),
                fallbackToBuiltinOnError = true,
            ).getOrElse {
                algorithmProvider.activateBuiltin(validated.exceptionOrNull()?.message)
            }
            if (NativeVision.isAvailable) {
                runCatching { NativeVision.configureAlgorithm(fallback.profile) }
            }
            lastActivation.set(fallback)
            return Result.failure(
                IllegalArgumentException(
                    validated.exceptionOrNull()?.message ?: "算法配置校验失败",
                ),
            )
        }
        val activation = algorithmProvider.activate(validated.getOrThrow(), fallbackToBuiltinOnError = false)
            .getOrElse { error ->
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
                return Result.failure(IllegalStateException(fallback.loadError ?: native.error))
            }
        }
        lastActivation.set(activation)
        return Result.success(activation)
    }

    override fun activeAlgorithmGeneration(): Long {
        val kotlinGen = algorithmProvider.current().generation
        if (!NativeVision.isAvailable) return kotlinGen
        return runCatching { NativeVision.activeAlgorithmGeneration() }.getOrDefault(kotlinGen)
    }

    override fun currentActivation(): AlgorithmActivation = algorithmProvider.current()

    override fun reset() {
        // 仅重置 native 分析侧状态，不强制回退内置算法。
        // 算法回退请调用 configureAlgorithm(AlgorithmRuntimeProfile.builtin())。
        if (NativeVision.isAvailable) {
            runCatching { NativeVision.reset() }
        }
    }

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

    private fun NormalizedRect.toFullScreen(viewport: ViewportConfig): NormalizedRect? =
        NormalizedRect.fromUnchecked(
            left = viewport.left + left * viewport.width,
            top = viewport.top + top * viewport.height,
            right = viewport.left + right * viewport.width,
            bottom = viewport.top + bottom * viewport.height,
        )

    private fun enabledKindMask(config: SceneConfig): Int {
        var mask = ALL_NATIVE_KINDS_MASK
        config.disabledObstacles.forEach { obstacle ->
            // Native Kind reserves ordinal 0 for PLAYER; obstacle ordinals start at bit 1.
            mask = mask and (1 shl (obstacle.ordinal + 1)).inv()
        }
        return mask
    }

    private companion object {
        const val MAX_NATIVE_DETECTIONS = 128
        const val ALL_NATIVE_KINDS_MASK = 0xFF
    }
}

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
