// 火崽崽助手（HZZS）JNI 桥接层（兼容门面）。
//
// 重构说明：
// 此对象已从原来的"全功能桥接"重构为"纯委托门面"。
// 原有职责已拆分到 data/native/ 包下：
// - 库加载 → NativeLibraryLoader（单例 object，负责 System.loadLibrary）
// - JSON 解析 → NativeJsonParser（单例 object，负责正则提取字段）
// - JNI 调用 → NativeEngineFacade（单例 object，封装 external 函数声明）
//
// 保留此文件的原因：
// - 向后兼容：如有其他模块直接引用 NativeAnalysisBridge，无需修改
// - 门面模式：新代码可以直接使用 NativeAnalysisBridge，内部自动委托
//
// 使用建议：
// - 外部模块：直接使用 NativeAnalysisBridge（简单场景）
// - 需要测试/mock：使用 data/native/ 包下的三个类（更细粒度）

package top.azek431.hzzs.core

import top.azek431.hzzs.core.data.native.NativeEngineFacade
import top.azek431.hzzs.core.data.native.NativeLibraryLoader
import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.RectF

/**
 * JNI 桥接层（兼容门面）。
 *
 * 所有公共方法委托给 NativeEngineFacade，保持向后兼容。
 * 此对象本身不包含任何业务逻辑，仅作为对外暴露的统一入口。
 *
 * 委托关系：
 * - isAvailable → NativeLibraryLoader.isAvailable
 * - engineInfo() → NativeEngineFacade.engineInfo()
 * - runSelfCheck() → NativeEngineFacade.runSelfCheck()
 * - analyzeFrame() → NativeEngineFacade.analyzeFrame()
 * - resetEngine() → NativeEngineFacade.resetEngine()
 */
object NativeAnalysisBridge {

    /**
     * 指示 C++ 原生库是否已成功加载并可用的只读属性。
     *
     * 委托给 NativeLibraryLoader.isAvailable。
     * 在 HUD 渲染器中使用此属性判断是否调用 JNI。
     */
    val isAvailable: Boolean
        get() = NativeLibraryLoader.isAvailable

    /**
     * 获取引擎版本信息字符串。
     *
     * 委托给 NativeEngineFacade.engineInfo()。
     * 返回内容："HZZS native core ready | C++17 | scene + runner + double-jump + hazard ETA"
     *
     * @return 引擎信息字符串，库不可用时返回错误消息
     */
    fun engineInfo(): String = NativeEngineFacade.engineInfo()

    /**
     * 执行 C++ 引擎的自检程序。
     *
     * 委托给 NativeEngineFacade.runSelfCheck()。
     * 自检流程：注入两帧模拟数据 → 验证 scene_mode/runner_pose/prompt_action/jump_stage → 返回 PASS/FAIL
     *
     * @return 自检结果字符串，库不可用时返回错误消息
     */
    fun runSelfCheck(): String = NativeEngineFacade.runSelfCheck()

    /**
     * 分析单帧模拟数据并返回结构化结果。
     *
     * 委托给 NativeEngineFacade.analyzeFrame()，
     * 完整流程：检查库可用性 → 调用 JNI → 解析 JSON → 返回 FrameAnalysisResult
     *
     * @param timestampMs 帧时间戳（毫秒）
     * @param playerBounds 玩家矩形归一化坐标
     * @param playerConfidence 玩家检测可信度（0.0 ~ 1.0）
     * @param hazardType 危险物类型（1=蛋糕断层, 2=毒瓶, 3=裱花袋, 0=无）
     * @param hazardBounds 危险物矩形归一化坐标（可为 null）
     * @param hazardConfidence 危险物检测可信度
     * @param hazardVelocityX 危险物 X 方向速度
     * @param worldScrollSpeed 背景滚动速度
     * @return 结构化分析结果，库不可用时返回 null
     */
    fun analyzeFrame(
        timestampMs: Long,
        playerBounds: RectF,
        playerConfidence: Float,
        hazardType: Int,
        hazardBounds: RectF?,
        hazardConfidence: Float,
        hazardVelocityX: Float,
        worldScrollSpeed: Float,
    ): FrameAnalysisResult? = NativeEngineFacade.analyzeFrame(
        timestampMs, playerBounds, playerConfidence,
        hazardType, hazardBounds, hazardConfidence,
        hazardVelocityX, worldScrollSpeed,
    )

    /**
     * 重置 C++ 分析引擎状态机。
     *
     * 委托给 NativeEngineFacade.resetEngine()。
     * 在停止循环执行时调用，清除场景/姿态/跳跃阶段等所有子模块状态。
     */
    fun resetEngine() {
        NativeEngineFacade.resetEngine()
    }
}
