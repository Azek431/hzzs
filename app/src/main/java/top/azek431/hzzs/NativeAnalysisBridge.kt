package top.azek431.hzzs

/**
 * Kotlin 与 C++ 算法核心之间的最小 JNI 桥接层。
 *
 * 此对象负责：
 * 1. 加载 C++ 共享库（libhzzs_native.so）
 * 2. 暴露库加载状态（isAvailable），供上层判断是否可用
 * 3. 提供 engineInfo() 和 runSelfCheck() 两个诊断接口
 *
 * 架构定位：
 * - 当前阶段不接入首页、悬浮窗、屏幕采集或真实帧分析
 * - 仅作为 JNI 入口，验证 C++ 库能否正常加载和执行自检
 * - 后续视觉模块准备好后，由上层（如悬浮窗面板）调用 C++ 算法引擎
 *
 * 设计决策：
 * - 使用 object（单例）而非 class，因为 JNI 库只需加载一次
 * - 库加载在静态初始化阶段完成，失败时记录异常但不崩溃
 * - 所有公共方法在库加载失败时返回描述性错误字符串，而非抛异常
 *
 * 对应的 C++ 共享库：
 * - 库文件名：libhzzs_native.so
 * - 构建方式：CMakeLists.txt 定义的 SHARED 库
 * - 编译标准：C++17
 */
object NativeAnalysisBridge {

    /** C++ 共享库名称（不含 lib 前缀和 .so 后缀） */
    private const val LIBRARY_NAME = "hzzs_native"

    /** 库不可用时的统一错误消息模板 */
    private const val UNAVAILABLE_TEMPLATE = "Native library unavailable: %s"

    /**
     * 尝试加载 C++ 共享库，捕获任何加载异常。
     *
     * System.loadLibrary() 可能在以下情况失败：
     * - 库文件不存在于设备的 ABI 目录中（arm64-v8a、armeabi-v7a 等）
     * - 库依赖的其他符号缺失
     * - 进程权限不足
     * - ABI 不匹配（如在 x86 模拟器上运行 arm64 库）
     *
     * 使用 runCatching 将可能的异常转换为 Result，
     * 这样即使加载失败也不会导致应用崩溃。
     */
    private val libraryLoadError: Throwable? = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    /**
     * 指示 C++ 原生库是否已成功加载并可用的只读属性。
     *
     * @return true 如果库加载成功，false 如果加载失败或库文件不存在
     *
     * 上层组件（如首页 UI）可在使用分析功能前检查此属性，
     * 以决定是显示"功能开发中"还是实际调用分析接口。
     */
    val isAvailable: Boolean
        get() = libraryLoadError == null

    /**
     * 获取引擎版本信息字符串。
     *
     * 如果 C++ 库已成功加载，调用 nativeGetEngineInfo() 返回引擎信息；
     * 如果加载失败，返回描述性的错误字符串。
     *
     * @return 引擎信息字符串或错误描述
     */
    fun engineInfo(): String {
        val error = libraryLoadError

        return if (error == null) {
            // 库加载成功，调用 C++ 方法获取引擎信息
            nativeGetEngineInfo()
        } else {
            // 库加载失败，返回错误描述
            String.format(UNAVAILABLE_TEMPLATE, error.javaClass.simpleName)
        }
    }

    /**
     * 执行 C++ 引擎的自检程序。
     *
     * 自检流程（在 C++ 端）：
     * 1. 创建 NativeAnalysisEngine 实例
     * 2. 注入模拟的地面跑酷帧数据（含玩家矩形、障碍物等）
     * 3. 分析两帧数据，验证场景识别、角色姿态、危险检测和跳跃提示是否正确
     *
     * 如果 C++ 库加载失败，返回描述性的错误字符串。
     *
     * @return 自检结果字符串（"PASS: ..." 或 "FAIL: ..."）
     */
    fun runSelfCheck(): String {
        val error = libraryLoadError

        return if (error == null) {
            // 库加载成功，执行 C++ 自检
            nativeRunSelfCheck()
        } else {
            // 库加载失败，返回错误描述
            String.format(UNAVAILABLE_TEMPLATE, error.javaClass.simpleName)
        }
    }

    /**
     * C++ 原生方法声明：获取引擎信息。
     *
     * 对应 C++ 实现：
     * Java_top_azek431_hzzs_NativeAnalysisBridge_nativeGetEngineInfo()
     *
     * 返回值是一个描述字符串，包含引擎名称、C++ 标准和功能列表。
     */
    private external fun nativeGetEngineInfo(): String

    /**
     * C++ 原生方法声明：执行自检程序。
     *
     * 对应 C++ 实现：
     * Java_top_azek431_hzzs_NativeAnalysisBridge_nativeRunSelfCheck()
     *
     * 返回值是一个描述字符串，包含自检通过/失败的信息。
     */
    private external fun nativeRunSelfCheck(): String
}
