package top.azek431.hzzs

/**
 * Kotlin 与 C++ 算法核心之间的最小 JNI 桥接。
 *
 * 当前不接入首页、悬浮窗、屏幕采集或真实帧分析。
 * 后续视觉模块准备好后，再由上层调用 C++ 算法引擎。
 */
object NativeAnalysisBridge {

    private const val LIBRARY_NAME = "hzzs_native"

    private val libraryLoadError: Throwable? = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    val isAvailable: Boolean
        get() = libraryLoadError == null

    fun engineInfo(): String {
        val error = libraryLoadError

        return if (error == null) {
            nativeGetEngineInfo()
        } else {
            "Native library unavailable: ${error.javaClass.simpleName}"
        }
    }

    fun runSelfCheck(): String {
        val error = libraryLoadError

        return if (error == null) {
            nativeRunSelfCheck()
        } else {
            "Native library unavailable: ${error.javaClass.simpleName}"
        }
    }

    private external fun nativeGetEngineInfo(): String

    private external fun nativeRunSelfCheck(): String
}
