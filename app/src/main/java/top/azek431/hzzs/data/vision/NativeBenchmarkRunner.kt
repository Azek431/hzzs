package top.azek431.hzzs.data.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.azek431.hzzs.nativevision.NativeVision
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/**
 * 端上 JNI + C++ 冒烟基准的统计结果（毫秒）。
 *
 * @property iterations 计入统计的正式迭代次数（不含预热）
 * @property meanMs 平均耗时
 * @property p50Ms 中位数
 * @property p95Ms 95 分位
 */
data class NativeBenchmarkResult(
    val iterations: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
)

/**
 * 设备侧 Native 视觉冒烟基准运行器。
 *
 * 职责：在确定性合成帧上反复调用 [NativeVision.analyze]，输出 mean/p50/p95，
 * 供开发者诊断库是否可用与大致帧时延，不参与生产帧循环。
 *
 * 线程：挂起于 [Dispatchers.Default]；不持有外部帧缓冲。
 * 安全：库不可用时 Result.failure；迭代次数钳制在 [10, 1000]。
 * 注意：使用固定 synthetic 像素，不读取真实截图，也不修改活动算法配置。
 */
@Singleton
class NativeBenchmarkRunner @Inject constructor() {
    /**
     * 运行基准。
     *
     * @param requestedIterations 期望迭代次数，实际钳制到合法区间
     * @return 成功时为统计结果；Native 不可用或运行异常时 failure
     */
    suspend fun run(requestedIterations: Int): Result<NativeBenchmarkResult> = withContext(Dispatchers.Default) {
        runCatching {
            check(NativeVision.isAvailable) {
                NativeVision.loadFailureMessage?.let { "Native 视觉库不可用：$it" } ?: "Native 视觉库不可用"
            }
            val iterations = requestedIterations.coerceIn(10, 1_000)
            val width = 384
            val height = 216
            val pixels = IntArray(width * height) { 0xFFF1ECE3.toInt() }
            // 预热：不计入样本，降低首次 JNI/JIT 抖动。
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

    /**
     * 对合成帧做一次 analyze；像素数组仅在 JNI 调用期间借用。
     * 参数固定为全视口、全 kind 掩码，不检测玩家。
     */
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

    /** 在已排序样本上取分位；空数组返回 0。 */
    private fun percentile(sorted: DoubleArray, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }
}
