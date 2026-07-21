package top.azek431.hzzs.nativevision

import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile

/**
 * Thin, failure-contained JNI boundary for the C++ vision engine.
 *
 * Loading a native library may fail on unsupported ABIs, damaged installs, or
 * devices whose linker rejects a dependency. The process must still be able to
 * open Settings and export diagnostics, so library loading is recorded instead
 * of crashing the application during class initialization.
 *
 * 算法配置：
 * - [configureAlgorithm] 在安全切换点解析一次 profile
 * - [analyze] 只读当前 generation 对应的不可变快照
 * - 不在每帧解析 JSON
 */
object NativeVision {
    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary("hzzs_vision")
    }.exceptionOrNull()

    /** True only when the linker accepted the packaged native library. */
    val isAvailable: Boolean
        get() = loadFailure == null

    /** Sanitized diagnostic text. It never exposes a full native stack trace. */
    val loadFailureMessage: String?
        get() = loadFailure?.let { error ->
            error.message?.take(240) ?: error.javaClass.simpleName
        }

    data class Detection(
        val trackHint: Int,
        val kind: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
        val actionable: Boolean,
        val diagnosticOnly: Boolean,
        val avoidance: Int,
    )

    data class Result(
        val sceneConfidence: Float,
        val detections: Array<Detection>,
        val error: String,
    )

    data class ConfigResult(
        val ok: Boolean,
        val generation: Long,
        val usingBuiltinFallback: Boolean,
        val error: String,
    )

    /**
     * Copies only the configured viewport into native memory and returns bounds
     * normalized to that crop. Callers must check [isAvailable] first and must
     * treat every returned field as untrusted JNI input.
     */
    external fun analyze(
        scene: Int,
        pixels: IntArray,
        width: Int,
        height: Int,
        workWidth: Int,
        enabledKindMask: Int,
        detectPlayer: Boolean,
        fixedPlayerXRatio: Float,
        viewportLeft: Float,
        viewportTop: Float,
        viewportRight: Float,
        viewportBottom: Float,
    ): Result

    /**
     * 在安全切换点配置算法。失败时保留旧配置；调用方应回退内置。
     * JNI 与 analyze 串行，禁止分析过程中半热切换。
     */
    external fun configureAlgorithm(profile: AlgorithmRuntimeProfile): ConfigResult

    external fun activeAlgorithmGeneration(): Long

    external fun reset()
}
