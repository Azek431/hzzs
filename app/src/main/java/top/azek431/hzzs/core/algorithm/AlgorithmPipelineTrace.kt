/**
 * 算法执行管线追踪：会话级阶段状态 + 最近一帧分析摘要。
 *
 * 供开发者「算法流程」页直观展示；不写文件、不上传。
 * 阶段更新可在任意线程；快照为不可变拷贝。
 */
package top.azek431.hzzs.core.algorithm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 管线阶段状态。 */
enum class AlgorithmStageStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    WARNING,
    FAILED,
    SKIPPED,
}

/**
 * 单个管线阶段。
 *
 * @property id 稳定键，如 resolve / profile / validate / native / ready / lastFrame
 * @property title 中文标题
 * @property status 状态
 * @property detail 一行摘要（已脱敏、无密钥）
 * @property updatedAtEpochMs 更新时间
 */
data class AlgorithmPipelineStage(
    val id: String,
    val title: String,
    val status: AlgorithmStageStatus = AlgorithmStageStatus.IDLE,
    val detail: String? = null,
    val updatedAtEpochMs: Long = 0L,
)

/** 最近一次成功/失败分析的摘要（UI 用，默认不刷 AppLog）。 */
data class AlgorithmLastFrameSummary(
    val epochMs: Long,
    val scene: String,
    val sceneConfidence: Float,
    val hasPlayer: Boolean,
    val obstacleCount: Int,
    val actionableCount: Int,
    val kindHistogram: String,
    val processingMs: Float,
    val algorithmId: String,
    val algorithmVersion: String,
    val generation: Long,
    val usingBuiltinFallback: Boolean,
    val loadError: String?,
    val frameError: String?,
    val disabledObstaclesDropped: Boolean,
)

/** 完整管线快照。 */
data class AlgorithmPipelineSnapshot(
    val stages: List<AlgorithmPipelineStage>,
    val lastFrame: AlgorithmLastFrameSummary?,
    val revision: Long,
    val catalogId: String? = null,
    val selectionMode: String? = null,
    val selectedScene: String? = null,
)

/**
 * 进程级算法管线追踪器。
 *
 * 阶段顺序固定，便于 UI 画流程；[mark] 只更新对应 id。
 */
object AlgorithmPipelineTrace {
    private val lock = Any()
    private val revision = AtomicLong(0L)
    private val lastFrame = AtomicReference<AlgorithmLastFrameSummary?>(null)
    private val catalogId = AtomicReference<String?>(null)
    private val selectionMode = AtomicReference<String?>(null)
    private val selectedScene = AtomicReference<String?>(null)

    private val stageOrder = listOf(
        "resolve" to "解析算法选择",
        "profile" to "加载运行时 Profile",
        "validate" to "校验 Profile",
        "activate" to "Kotlin 侧激活",
        "native" to "Native configureAlgorithm",
        "ready" to "分析就绪",
        "lastFrame" to "最近一帧结果",
    )

    private val stages = LinkedHashMap<String, AlgorithmPipelineStage>().apply {
        stageOrder.forEach { (id, title) ->
            put(id, AlgorithmPipelineStage(id = id, title = title))
        }
    }

    fun revision(): Long = revision.get()

    fun snapshot(): AlgorithmPipelineSnapshot = synchronized(lock) {
        AlgorithmPipelineSnapshot(
            stages = stages.values.toList(),
            lastFrame = lastFrame.get(),
            revision = revision.get(),
            catalogId = catalogId.get(),
            selectionMode = selectionMode.get(),
            selectedScene = selectedScene.get(),
        )
    }

    fun setContext(catalogId: String?, selectionMode: String?, selectedScene: String?) {
        this.catalogId.set(catalogId)
        this.selectionMode.set(selectionMode)
        this.selectedScene.set(selectedScene)
        bump()
    }

    fun mark(
        id: String,
        status: AlgorithmStageStatus,
        detail: String? = null,
    ) {
        synchronized(lock) {
            val current = stages[id] ?: return
            stages[id] = current.copy(
                status = status,
                detail = detail?.take(500),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            bumpLocked()
        }
    }

    fun markRunning(id: String, detail: String? = null) =
        mark(id, AlgorithmStageStatus.RUNNING, detail)

    fun markSuccess(id: String, detail: String? = null) =
        mark(id, AlgorithmStageStatus.SUCCESS, detail)

    fun markWarning(id: String, detail: String? = null) =
        mark(id, AlgorithmStageStatus.WARNING, detail)

    fun markFailed(id: String, detail: String? = null) =
        mark(id, AlgorithmStageStatus.FAILED, detail)

    fun markSkipped(id: String, detail: String? = null) =
        mark(id, AlgorithmStageStatus.SKIPPED, detail)

    fun updateLastFrame(summary: AlgorithmLastFrameSummary) {
        lastFrame.set(summary)
        mark(
            id = "lastFrame",
            status = if (summary.frameError != null) {
                AlgorithmStageStatus.FAILED
            } else {
                AlgorithmStageStatus.SUCCESS
            },
            detail = buildString {
                append(summary.scene)
                append(" conf=")
                append("%.2f".format(summary.sceneConfidence))
                append(" obs=")
                append(summary.obstacleCount)
                append(" act=")
                append(summary.actionableCount)
                append(" ")
                append("%.1f".format(summary.processingMs))
                append("ms")
                if (summary.kindHistogram.isNotBlank()) {
                    append(" [")
                    append(summary.kindHistogram)
                    append(']')
                }
                summary.frameError?.let {
                    append(" err=")
                    append(it.take(120))
                }
            },
        )
    }

    /** 开始一次新的激活尝试时重置执行阶段（保留 lastFrame）。 */
    fun beginActivationAttempt() {
        synchronized(lock) {
            listOf("resolve", "profile", "validate", "activate", "native", "ready").forEach { id ->
                val current = stages[id] ?: return@forEach
                stages[id] = current.copy(
                    status = AlgorithmStageStatus.IDLE,
                    detail = null,
                    updatedAtEpochMs = 0L,
                )
            }
            bumpLocked()
        }
    }

    fun formatText(): String {
        val snap = snapshot()
        return buildString {
            appendLine("HZZS algorithm pipeline")
            appendLine("revision=${snap.revision}")
            appendLine("catalogId=${snap.catalogId ?: "-"}")
            appendLine("selectionMode=${snap.selectionMode ?: "-"}")
            appendLine("selectedScene=${snap.selectedScene ?: "-"}")
            appendLine()
            appendLine("== Stages ==")
            snap.stages.forEach { stage ->
                append(stage.status.name.padEnd(8))
                append(' ')
                append(stage.title)
                stage.detail?.let {
                    append(" — ")
                    append(it)
                }
                appendLine()
            }
            appendLine()
            appendLine("== Last frame ==")
            val frame = snap.lastFrame
            if (frame == null) {
                appendLine("(none)")
            } else {
                appendLine("at=${frame.epochMs}")
                appendLine("scene=${frame.scene}")
                appendLine("sceneConfidence=${frame.sceneConfidence}")
                appendLine("hasPlayer=${frame.hasPlayer}")
                appendLine("obstacleCount=${frame.obstacleCount}")
                appendLine("actionableCount=${frame.actionableCount}")
                appendLine("kinds=${frame.kindHistogram.ifBlank { "-" }}")
                appendLine("processingMs=${frame.processingMs}")
                appendLine("algorithmId=${frame.algorithmId}")
                appendLine("algorithmVersion=${frame.algorithmVersion}")
                appendLine("generation=${frame.generation}")
                appendLine("usingBuiltinFallback=${frame.usingBuiltinFallback}")
                appendLine("loadError=${frame.loadError ?: "-"}")
                appendLine("frameError=${frame.frameError ?: "-"}")
            }
        }
    }

    private fun bump() {
        synchronized(lock) { bumpLocked() }
    }

    private fun bumpLocked() {
        revision.incrementAndGet()
    }
}
