package top.azek431.hzzs.data.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.azek431.hzzs.nativevision.NativeVision
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** A small on-device JNI + C++ smoke benchmark using a deterministic synthetic frame. */
data class NativeBenchmarkResult(
    val iterations: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
)

@Singleton
class NativeBenchmarkRunner @Inject constructor() {
    suspend fun run(requestedIterations: Int): Result<NativeBenchmarkResult> = withContext(Dispatchers.Default) {
        runCatching {
            check(NativeVision.isAvailable) {
                NativeVision.loadFailureMessage?.let { "Native 视觉库不可用：$it" } ?: "Native 视觉库不可用"
            }
            val iterations = requestedIterations.coerceIn(10, 1_000)
            val width = 384
            val height = 216
            val pixels = IntArray(width * height) { 0xFFF1ECE3.toInt() }
            repeat(3) { analyze(pixels, width, height) }
            val samples = DoubleArray(iterations)
            repeat(iterations) { index ->
                samples[index] = measureNanoTime { analyze(pixels, width, height) } / 1_000_000.0
            }
            samples.sort()
            NativeBenchmarkResult(
                iterations = iterations,
                meanMs = samples.average(),
                p50Ms = percentile(samples, 0.50),
                p95Ms = percentile(samples, 0.95),
            )
        }
    }

    private fun analyze(pixels: IntArray, width: Int, height: Int) {
        NativeVision.analyze(
            scene = 0,
            pixels = pixels,
            width = width,
            height = height,
            workWidth = 384,
            enabledKindMask = 0xFF,
            detectPlayer = false,
            fixedPlayerXRatio = 0.185f,
            viewportLeft = 0f,
            viewportTop = 0f,
            viewportRight = 1f,
            viewportBottom = 1f,
        )
    }

    private fun percentile(sorted: DoubleArray, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }
}
