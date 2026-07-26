package top.azek431.hzzs.core.algorithm

/**
 * 算法追踪层的注入适配接口（薄抽象）。
 *
 * 背景：[AlgorithmPipelineTrace] 与 [AlgorithmRuntimeTrace] 是进程内 ring buffer 的 `object`
 *（无 Android 依赖、本就无需 Context），直接作为全局单例使用没问题；
 * 但让 ViewModel / 测试直接绑定 `object` 会导致：
 * - 单测之间共享可变状态（需手动 reset）
 * - 无法替换为 fake 验证调用
 *
 * 因此本文件定义两个纯接口，并各提供一个默认实现**委托给现有 object**。
 *
 * 使用方：
 * - [AlgorithmPipelineScreen] 与「算法流程」相关 composable 仍可直接读 [AlgorithmPipelineTrace]（UI 层）
 * - ViewModel 与业务逻辑通过 [PipelineTraceSink] / [RuntimeTraceSink] 注入（默认值 = default impl）
 * 单测可替换为 fake 验证"激活尝试曾被调用 / 决策曾被记录"等交互。
 */
interface PipelineTraceSink {
    fun snapshot(): AlgorithmPipelineSnapshot
    fun setContext(catalogId: String?, selectionMode: String?, selectedScene: String?)
    fun markRunning(id: String, detail: String?)
    fun markSuccess(id: String, detail: String?)
    fun markWarning(id: String, detail: String?)
    fun markFailed(id: String, detail: String?)
    fun markSkipped(id: String, detail: String?)
    fun beginActivationAttempt()
    fun updateLastFrame(summary: AlgorithmLastFrameSummary)
    fun formatText(): String
}

interface RuntimeTraceSink {
    fun record(entry: AlgorithmFrameTraceEntry, writeAppLog: Boolean = true)
    fun logDecision(message: String)
    fun logCalc(message: String)
    fun nextAnalysisSequence(): Long
    fun resetSession(): Long
    fun recentFrames(limit: Int = AlgorithmRuntimeTrace.CAPACITY): List<AlgorithmFrameTraceEntry>
    fun recentDecisions(limit: Int = AlgorithmRuntimeTrace.DECISION_CAPACITY): List<DecisionTraceEntry>
}

/**
 * 默认实现：委托给 [AlgorithmPipelineTrace] object。
 *
 * 进程内唯一真相源仍是 [AlgorithmPipelineTrace] 的 LinkedHashMap + AtomicReference；
 * 本适配器只是调用入口，不持有状态。
 */
object DefaultPipelineTraceSink : PipelineTraceSink {
    override fun snapshot(): AlgorithmPipelineSnapshot = AlgorithmPipelineTrace.snapshot()
    override fun setContext(catalogId: String?, selectionMode: String?, selectedScene: String?) =
        AlgorithmPipelineTrace.setContext(catalogId, selectionMode, selectedScene)
    override fun markRunning(id: String, detail: String?) = AlgorithmPipelineTrace.markRunning(id, detail)
    override fun markSuccess(id: String, detail: String?) = AlgorithmPipelineTrace.markSuccess(id, detail)
    override fun markWarning(id: String, detail: String?) = AlgorithmPipelineTrace.markWarning(id, detail)
    override fun markFailed(id: String, detail: String?) = AlgorithmPipelineTrace.markFailed(id, detail)
    override fun markSkipped(id: String, detail: String?) = AlgorithmPipelineTrace.markSkipped(id, detail)
    override fun beginActivationAttempt() = AlgorithmPipelineTrace.beginActivationAttempt()
    override fun updateLastFrame(summary: AlgorithmLastFrameSummary) = AlgorithmPipelineTrace.updateLastFrame(summary)
    override fun formatText(): String = AlgorithmPipelineTrace.formatText()
}

/**
 * 默认实现：委托给 [AlgorithmRuntimeTrace] object。
 *
 * 进程内唯一真相源仍是 [AlgorithmRuntimeTrace] 的 ring buffer；
 * 本适配器只是调用入口，不持有状态。
 */
object DefaultRuntimeTraceSink : RuntimeTraceSink {
    override fun record(entry: AlgorithmFrameTraceEntry, writeAppLog: Boolean) =
        AlgorithmRuntimeTrace.record(entry, writeAppLog)
    override fun logDecision(message: String) = AlgorithmRuntimeTrace.logDecision(message)
    override fun logCalc(message: String) = AlgorithmRuntimeTrace.logCalc(message)
    override fun nextAnalysisSequence(): Long = AlgorithmRuntimeTrace.nextAnalysisSequence()
    override fun resetSession(): Long = AlgorithmRuntimeTrace.resetSession()
    override fun recentFrames(limit: Int): List<AlgorithmFrameTraceEntry> = AlgorithmRuntimeTrace.recentFrames(limit)
    override fun recentDecisions(limit: Int): List<DecisionTraceEntry> = AlgorithmRuntimeTrace.recentDecisions(limit)
}
