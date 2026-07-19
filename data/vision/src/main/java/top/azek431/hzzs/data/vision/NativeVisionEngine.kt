package top.azek431.hzzs.data.vision

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.ViewportConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
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

class NativeVisionEngine @Inject constructor(
    private val viewportProvider: VisionViewportProvider,
) : VisionEngine {
    override suspend fun analyze(frame: VisionFrame, config: SceneConfig): VisionResult = withContext(Dispatchers.Default) {
        val viewport = viewportProvider.current()
        var nativeResult: NativeVision.Result? = null
        val elapsed = measureNanoTime {
            nativeResult = NativeVision.analyze(
                scene = config.sceneId.ordinal,
                pixels = frame.argb,
                width = frame.meta.sourceWidth,
                height = frame.meta.sourceHeight,
                workWidth = config.thresholds.workWidth,
                viewportLeft = viewport.left,
                viewportTop = viewport.top,
                viewportRight = viewport.right,
                viewportBottom = viewport.bottom,
            )
        }
        val native = nativeResult ?: return@withContext VisionResult(
            scene = config.sceneId,
            sceneConfidence = 0f,
            player = null,
            detections = emptyList(),
            processingNanos = elapsed,
            error = "Native 视觉引擎未返回结果",
        )
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
            ),
        )
    }

    override fun reset() = NativeVision.reset()

    private fun NormalizedRect.toFullScreen(viewport: ViewportConfig): NormalizedRect? =
        NormalizedRect.fromUnchecked(
            left = viewport.left + left * viewport.width,
            top = viewport.top + top * viewport.height,
            right = viewport.left + right * viewport.width,
            bottom = viewport.top + bottom * viewport.height,
        )

    private companion object { const val MAX_NATIVE_DETECTIONS = 128 }
}

/** Keeps the hot JNI call independent from DataStore reads. */
interface VisionViewportProvider {
    fun current(): ViewportConfig
}

/**
 * Mirrors the effective settings flow, including non-persistent previews, into
 * an atomic snapshot so the per-frame JNI path never performs DataStore I/O.
 */
@Singleton
class SettingsVisionViewportProvider @Inject constructor(
    settingsRepository: SettingsRepository,
) : VisionViewportProvider {
    private val viewport = AtomicReference(ViewportConfig())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            settingsRepository.config
                .map { it.viewport }
                .distinctUntilChanged()
                .collect(viewport::set)
        }
    }

    override fun current(): ViewportConfig = viewport.get()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionEngineBindings {
    @Binds
    abstract fun bindVisionEngine(impl: NativeVisionEngine): VisionEngine

    @Binds
    abstract fun bindVisionViewportProvider(
        impl: SettingsVisionViewportProvider,
    ): VisionViewportProvider
}
