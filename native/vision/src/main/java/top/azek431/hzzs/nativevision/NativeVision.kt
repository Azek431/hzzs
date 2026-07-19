package top.azek431.hzzs.nativevision

object NativeVision {
    init { System.loadLibrary("hzzs_vision") }

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
     * The JNI boundary copies only the configured viewport, row by row, and
     * returns crop-normalized bounds. Kotlin maps them back to full-screen
     * coordinates after validating every field.
     */
    external fun analyze(
        scene: Int,
        pixels: IntArray,
        width: Int,
        height: Int,
        workWidth: Int,
        viewportLeft: Float,
        viewportTop: Float,
        viewportRight: Float,
        viewportBottom: Float,
    ): Result

    external fun reset()
}
