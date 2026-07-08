// 火崽崽助手（HZZS）JNI 库加载器。
//
// 职责：
// - 加载 C++ 共享库（libhzzs_native.so）
// - 暴露库加载状态（isAvailable），供上层判断是否可用
// - 提供加载失败时的错误消息（调试用）
//
// 不负责：
// - 不处理 JNI 调用（由 NativeEngineFacade 处理）
// - 不处理 JSON 解析（由 NativeJsonParser 处理）
//
// 设计原因：
// - 库加载是独立的生命周期操作，单独封装便于将来支持动态加载/热更新
// - 使用 object 单例，因为 JNI 库只需加载一次
// - 库加载在静态初始化阶段完成，失败时记录异常但不崩溃
// - 与重构前的 NativeAnalysisBridge 相比，职责更单一、更易测试

package top.azek431.hzzs.data.native

/**
 * JNI 库加载器。
 *
 * 负责加载 C++ 共享库（libhzzs_native.so），并在加载失败时记录异常但不崩溃。
 * 上层通过 isAvailable 属性判断库是否可用。
 *
 * 加载时机：
 * - 在第一次访问 NativeLibraryLoader 时触发静态初始化
 * - 使用 runCatching 包裹 System.loadLibrary，捕获 UnsatisfiedLinkError 等异常
 * - 加载失败后 isAvailable 返回 false，上层调用会优雅降级
 *
 * 线程安全：
 * - Kotlin object 的静态初始化由 JVM 保证线程安全
 * - libraryLoadError 在初始化后即为只读，无需额外同步
 */
object NativeLibraryLoader {

    /** C++ 共享库名称（不含 lib 前缀和 .so 后缀） */
    private const val LIBRARY_NAME = "hzzs_native"

    /**
     * 尝试加载 C++ 共享库，捕获任何加载异常。
     *
     * 使用 runCatching 包裹 System.loadLibrary：
     * - 成功 → libraryLoadError 为 null
     * - 失败 → libraryLoadError 为捕获的 Throwable（如 UnsatisfiedLinkError）
     *
     * 注意：加载失败不会导致应用崩溃，上层会通过 isAvailable 判断并降级处理。
     */
    private val libraryLoadError: Throwable? = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    /**
     * 指示 C++ 原生库是否已成功加载并可用的只读属性。
     *
     * @return true 如果库加载成功，false 如果加载失败
     */
    val isAvailable: Boolean
        get() = libraryLoadError == null

    /**
     * 获取库加载失败的异常消息（调试用）。
     *
     * 在日志中记录失败原因，便于开发阶段排查 ABI 不匹配、SO 文件缺失等问题。
     *
     * @return 异常消息，如果库加载成功则返回 null
     */
    fun getLoadErrorMessage(): String? {
        return libraryLoadError?.message
    }
}
