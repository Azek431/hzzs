package top.azek431.hzzs.nativevision

/**
 * Thin, failure-contained JNI boundary for the C++ vision engine.
 *
 * Loading a native library may fail on unsupported ABIs, damaged installs, or
 * devices whose linker rejects a dependency. The process must still be able to
 * open Settings and export diagnostics, so library loading is recorded instead
 * of crashing the application during class initialization.
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

    external fun reset()
}
