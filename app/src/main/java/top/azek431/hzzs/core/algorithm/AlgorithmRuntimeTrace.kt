/**
 * 算法运行时帧级追踪：识别摘要、检测明细、轨迹与决策。
 *
 * 供调参与诊断导出使用；进程内 ring buffer，不写文件、不上传、不含像素。
 * AppLog 仅在开发者开启且级别允许时输出，并按「状态变化 + 每 N 帧」节流。
 * 线程：任意线程可 [record]；快照为不可变拷贝。
 */
package top.azek431.hzzs.core.algorithm

import top.azek431.hzzs.core.logging.AppLog
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 单目标检测明细（归一化坐标，短格式）。 */
data class AlgorithmDetectionTrace(
    val kind: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val avoidance: String,
    val actionable: Boolean,
    val diagnosticOnly: Boolean,
    val trackId: Long? = null,
    val stableFrames: Int? = null,
) {
    fun formatShort(): String = buildString {
        append(kind)
        append('@')
        append("%.2f".format(confidence))
        append('[')
        append("%.2f".format(left))
        append(',')
        append("%.2f".format(top))
        append('-')
        append("%.2f".format(right))
        append(',')
        append("%.2f".format(bottom))
        append(']')
        append(' ')
        append(avoidance)
        if (actionable) append(" act")
        if (diagnosticOnly) append(" diag")
        trackId?.let {
            append(" t=")
            append(it)
        }
        stableFrames?.let {
            append(" s=")
            append(it)
        }
    }
}

/**
 * 一帧分析后的运行轨迹摘要。
 *
 * @property analysisSequence 帧循环内已分析序号（非截图 sequence）
 * @property decision 自动操作决策一行摘要；未评估时为 null
 */
data class AlgorithmFrameTraceEntry(
    val epochMs: Long,
    val analysisSequence: Long,
    val scene: String,
    val sceneConfidence: Float,
    val hasPlayer: Boolean,
    val playerConfidence: Float?,
    val playerBounds: String?,
    val obstacleCount: Int,
    val actionableCount: Int,
    val kindHistogram: String,
    val processingMs: Float,
    val algorithmId: String,
    val algorithmVersion: String,
    val generation: Long,
    val usingBuiltinFallback: Boolean,
    val frameError: String?,
    val disabledObstaclesDropped: Boolean,
    val detections: List<AlgorithmDetectionTrace>,
    val trackSummary: String?,
    val decision: String?,
) {
    /** 用于节流：检出结构 / 错误 / 置信度桶 / 决策前缀。 */
    fun changeSignature(): String = buildString {
        append(obstacleCount)
        append('|')
        append(kindHistogram)
        append('|')
        append(hasPlayer)
        append('|')
        append(frameError != null)
        append('|')
        append((sceneConfidence * 10f).toInt())
        append('|')
        append(decision?.substringBefore(' ') ?: "-")
        append('|')
        append(disabledObstaclesDropped)
    }

    fun formatL1(): String = buildString {
        append("seq=")
        append(analysisSequence)
        append(' ')
        append(scene)
        append(" conf=")
        append("%.2f".format(sceneConfidence))
        append(" player=")
        append(if (hasPlayer) "y" else "n")
        playerConfidence?.let {
            append('@')
            append("%.2f".format(it))
        }
        append(" obs=")
        append(obstacleCount)
        append(" act=")
        append(actionableCount)
        append(' ')
        append("%.1f".format(processingMs))
        append("ms")
        if (kindHistogram.isNotBlank()) {
            append(" [")
            append(kindHistogram)
            append(']')
        }
        if (usingBuiltinFallback) append(" builtin")
        if (disabledObstaclesDropped) append(" droppedDisabled")
        frameError?.let {
            append(" err=")
            append(it.take(120))
        }
    }

    fun formatL2(maxItems: Int = MAX_DET_LOG): String {
        if (detections.isEmpty()) return "dets=-"
        val body = detections.take(maxItems).joinToString("; ") { it.formatShort() }
        return if (detections.size > maxItems) {
            "dets(${detections.size})=$body; …+${detections.size - maxItems}"
        } else {
            "dets(${detections.size})=$body"
        }
    }

    fun formatTextBlock(): String = buildString {
        appendLine(formatL1())
        playerBounds?.let { appendLine("playerBounds=$it") }
        appendLine(formatL2())
        trackSummary?.let { appendLine("tracks=$it") }
        decision?.let { appendLine("decision=$it") }
        appendLine(
            "algo=$algorithmId@$algorithmVersion gen=$generation fallback=$usingBuiltinFallback",
        )
    }

    companion object {
        const val MAX_DET_LOG = 8
    }
}

/** 决策/计算时间线条目（无像素）。 */
data class DecisionTraceEntry(
    val epochMs: Long,
    val message: String,
)

/**
 * 进程级算法帧轨迹 ring buffer + 可选 AppLog。
 */
object AlgorithmRuntimeTrace {
    /** 诊断与 UI 保留的最近帧数。 */
    const val CAPACITY = 32

    /** 决策/计算时间线容量（dispatch/skip/calc）。 */
    const val DECISION_CAPACITY = 64

    /** 状态未变时，每 N 帧写一次 DEBUG 摘要。 */
    const val PERIODIC_FRAMES = 12

    private val lock = Any()
    private val buffer = ArrayDeque<AlgorithmFrameTraceEntry>(CAPACITY)
    private val decisionBuffer = ArrayDeque<DecisionTraceEntry>(DECISION_CAPACITY)
    private val revision = AtomicLong(0L)
    private val lastChangeSignature = AtomicReference<String?>(null)
    private var framesSinceLog = 0
    private var analysisSequenceCounter = 0L

    fun revision(): Long = revision.get()

    /** 下一分析序号（帧循环成功进入 analyze 后递增）。 */
    fun nextAnalysisSequence(): Long = synchronized(lock) {
        analysisSequenceCounter += 1
        analysisSequenceCounter
    }

    fun resetSession() = synchronized(lock) {
        buffer.clear()
        decisionBuffer.clear()
        lastChangeSignature.set(null)
        framesSinceLog = 0
        analysisSequenceCounter = 0L
        revision.incrementAndGet()
    }

    fun recentFrames(limit: Int = CAPACITY): List<AlgorithmFrameTraceEntry> = synchronized(lock) {
        val n = limit.coerceAtMost(buffer.size).coerceAtLeast(0)
        if (n == 0) emptyList() else buffer.toList().takeLast(n)
    }

    /**
     * 记录一帧完整轨迹。
     *
     * @param writeAppLog 为 true 时按节流策略写 DEBUG（仍受 AppLog 开发者/级别门控）
     */
    fun record(entry: AlgorithmFrameTraceEntry, writeAppLog: Boolean = true) {
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            revision.incrementAndGet()
            if (!writeAppLog) return
            val signature = entry.changeSignature()
            val changed = signature != lastChangeSignature.get()
            framesSinceLog++
            val shouldLog = changed || framesSinceLog >= PERIODIC_FRAMES || entry.frameError != null
            if (!shouldLog) return
            lastChangeSignature.set(signature)
            framesSinceLog = 0
            emitAppLog(entry, forcedChange = changed)
        }
    }

    /**
     * 决策类事件：不受帧节流限制，便于对照「为何动手/为何跳过」。
     *
     * skip / plan / dispatch_* / probe / shell 用 INFO（默认可见）；其它 DEBUG。
     * 调用方应对决策串变化再写，避免每帧刷屏。
     *
     * 同时写入进程内 ring，诊断导出「Algorithm decisions」节可独立阅读。
     */
    fun logDecision(message: String) {
        val clipped = message.take(720)
        synchronized(lock) {
            if (decisionBuffer.size >= DECISION_CAPACITY) decisionBuffer.removeFirst()
            decisionBuffer.addLast(
                DecisionTraceEntry(
                    epochMs = System.currentTimeMillis(),
                    message = clipped,
                ),
            )
            revision.incrementAndGet()
        }
        val levelUp = clipped.startsWith("skip:") ||
            clipped.startsWith("dispatch_") ||
            clipped.startsWith("plan ") ||
            clipped.startsWith("probe ") ||
            clipped.startsWith("shell ") ||
            clipped.startsWith("calc ")
        if (levelUp) {
            AppLog.i("algo.decision", clipped)
        } else {
            AppLog.d("algo.decision", clipped)
        }
    }

    /** 几何/触发距离等计算过程一行（INFO），写入决策 ring。 */
    fun logCalc(message: String) {
        logDecision("calc ${message.take(700)}")
    }

    fun recentDecisions(limit: Int = DECISION_CAPACITY): List<DecisionTraceEntry> = synchronized(lock) {
        val n = limit.coerceAtMost(decisionBuffer.size).coerceAtLeast(0)
        if (n == 0) emptyList() else decisionBuffer.toList().takeLast(n)
    }

    fun formatText(limit: Int = CAPACITY): String {
        val frames = recentFrames(limit)
        return buildString {
            appendLine("HZZS algorithm runtime frames")
            appendLine("count=${frames.size} capacity=$CAPACITY revision=${revision.get()}")
            appendLine()
            if (frames.isEmpty()) {
                appendLine("(none)")
            } else {
                frames.forEachIndexed { index, frame ->
                    appendLine("--- frame ${index + 1}/${frames.size} epochMs=${frame.epochMs} ---")
                    append(frame.formatTextBlock())
                    appendLine()
                }
            }
        }
    }

    /** 独立决策时间线（含 dispatch/skip/calc），供诊断导出。 */
    fun formatDecisionText(limit: Int = DECISION_CAPACITY): String {
        val items = recentDecisions(limit)
        return buildString {
            appendLine("HZZS algorithm decisions")
            appendLine("count=${items.size} capacity=$DECISION_CAPACITY revision=${revision.get()}")
            appendLine()
            if (items.isEmpty()) {
                appendLine("(none)")
            } else {
                items.forEachIndexed { index, item ->
                    appendLine("${index + 1}. epochMs=${item.epochMs} ${item.message}")
                }
            }
        }
    }

    private fun emitAppLog(entry: AlgorithmFrameTraceEntry, forcedChange: Boolean) {
        val prefix = if (forcedChange) "Δ " else "… "
        AppLog.d("algo.frame", prefix + entry.formatL1())
        if (entry.detections.isNotEmpty() || entry.frameError != null) {
            AppLog.d("algo.det", entry.formatL2())
        }
        entry.trackSummary?.takeIf { it.isNotBlank() }?.let {
            AppLog.d("algo.track", it.take(500))
        }
        entry.decision?.takeIf { it.isNotBlank() }?.let {
            // 帧内 decision 已可能经 logDecision 写过 INFO；此处 DEBUG 保留帧对齐副本。
            AppLog.d("algo.decision", "frame ${it.take(500)}")
        }
    }
}
