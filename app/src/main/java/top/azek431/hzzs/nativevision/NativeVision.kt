package top.azek431.hzzs.nativevision

import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile

/**
 * C++ 视觉引擎的薄 JNI 边界；加载失败不得拖垮进程。
 *
 * 加载与可用性：
 * - 不支持 ABI、安装损坏或 linker 拒依赖时 [System.loadLibrary] 可能失败；
 * - 失败记录在 [loadFailure]，类初始化不抛出，应用仍可打开设置与导出诊断；
 * - 调用方必须先检查 [isAvailable] 再调 native 方法。
 *
 * 所有权与线程：
 * - [analyze] 仅在 JNI 调用期间借用 Java 像素数组，Native 不得持有数组地址；
 * - 返回边界相对配置视口归一化；字段视为不可信 JNI 输入，上层需再校验。
 *
 * 算法配置：
 * - [configureAlgorithm] 仅在安全切换点解析一次 profile；
 * - [analyze] 只读当前 generation 对应的不可变快照；
 * - 不在每帧解析 JSON；配置失败保留旧配置，调用方应回退内置。
 * - JNI 与 analyze 串行，禁止分析过程中半热切换。
 */
object NativeVision {
    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary("hzzs_vision")
    }.exceptionOrNull()

    /** 仅当打包的 native 库被 linker 接受时为 true。 */
    val isAvailable: Boolean
        get() = loadFailure == null

    /** 净化后的诊断文案，不暴露完整 native 堆栈。 */
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
     * 将配置视口内的像素拷入 native 并返回相对该裁剪区的归一化边界。
     * 调用前必须检查 [isAvailable]；返回字段一律按不可信 JNI 输入处理。
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

    /**
     * 重置 native 分析侧瞬时状态（若有）。
     * **不**回退算法 profile，也**不**递增 generation；回退请 [configureAlgorithm] 传入 builtin。
     */
    external fun reset()
}
