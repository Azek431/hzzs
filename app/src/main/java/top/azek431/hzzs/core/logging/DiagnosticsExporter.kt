/**
 * 诊断摘要构建：版本 / 机型 / 配置摘要 / 算法激活 / 运行态 / 最近日志。
 *
 * 安全：不包含 MCP Bearer、签名密钥、调试帧像素；配置仅摘要字段。
 */
package top.azek431.hzzs.core.logging

import android.os.Build
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** MCP 运行态摘要（不含 token）。 */
data class McpDiagnosticsSnapshot(
    val running: Boolean,
    val port: Int?,
    val lastError: String?,
)

/** 算法激活快照摘要（供诊断导出，不含 profile 大字段）。 */
data class AlgorithmDiagnosticsSnapshot(
    val algorithmId: String,
    val version: String,
    val generation: Long,
    val usingBuiltinFallback: Boolean,
    val loadError: String?,
    val nativeAvailable: Boolean,
    val pendingCatalogId: String?,
    val analysisRunning: Boolean,
)

object DiagnosticsExporter {
    /**
     * 设备本地时区 + 真实偏移（如 `+08:00`），避免再把本地时间标成假 `Z`。
     * 每次格式化时取 [TimeZone.getDefault]，跟随系统时区切换。
     */
    private fun localTimeFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    /**
     * 构建可分享的纯文本诊断包。
     *
     * @param versionName 应用 versionName
     * @param versionCode 应用 versionCode
     * @param config 当前已保存（或草稿）配置
     * @param mcp MCP 状态；可为 null
     * @param algorithm 当前算法激活摘要；可为 null
     * @param runtime 视觉运行时状态；可为 null
     * @param debugFrameCount 私有目录调试帧张数
     * @param logLimit 附带最近日志条数
     */
    fun buildReport(
        versionName: String,
        versionCode: Long,
        config: AppConfig,
        mcp: McpDiagnosticsSnapshot?,
        debugFrameCount: Int,
        algorithm: AlgorithmDiagnosticsSnapshot? = null,
        runtime: RuntimeStatus? = null,
        logLimit: Int = 200,
    ): String {
        val timeFormat = localTimeFormat()
        return buildString {
            appendLine("HZZS diagnostics")
            appendLine("generatedAt=${timeFormat.format(Date())}")
            appendLine()
            appendLine("== App ==")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("schema=${config.schemaVersion}")
            appendLine()
            appendLine("== Device ==")
            // JVM 单测中 Build 字段可能为 null，全部用默认值兜底。
            appendLine("manufacturer=${Build.MANUFACTURER ?: "unknown"}")
            appendLine("model=${Build.MODEL ?: "unknown"}")
            appendLine("device=${Build.DEVICE ?: "unknown"}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE ?: "unknown"}")
            val abis = runCatching { Build.SUPPORTED_ABIS?.joinToString().orEmpty() }.getOrDefault("")
            appendLine("abi=${abis.ifBlank { "unknown" }}")
            appendLine()
            appendLine("== Config summary ==")
            appendLine("scene=${config.selectedScene.name}")
            appendLine("captureBackend=${config.captureBackend.name}")
            appendLine("overlay.enabled=${config.overlay.enabled}")
            appendLine("overlay.style=${config.overlay.style.name}")
            appendLine("automation.enabled=${config.automation.enabled}")
            appendLine("mcp.enabled=${config.mcp.enabled}")
            appendLine("mcp.permission=${config.mcp.permissionLevel.name}")
            appendLine("mcp.allowDebugFrames=${config.mcp.allowDebugFrames}")
            appendLine("developer.enabled=${config.developer.enabled}")
            appendLine(
                "developer.forceCapture=${config.developer.forceCaptureBackend?.name ?: "FOLLOW"}",
            )
            val captureResolution = resolveEffectiveCaptureBackend(
                captureBackend = config.captureBackend,
                developerEnabled = config.developer.enabled,
                forceCaptureBackend = config.developer.forceCaptureBackend,
            )
            appendLine("capture.requested=${captureResolution.requested.name}")
            appendLine("capture.effective=${captureResolution.effective.name}")
            appendLine(
                "capture.fallbackReason=${captureResolution.fallbackReason?.let(AppLog::redact) ?: "-"}",
            )
            appendLine("developer.saveDebugFrames=${config.developer.saveDebugFrames}")
            appendLine("developer.showCoordinateGrid=${config.developer.showCoordinateGrid}")
            appendLine(
                "developer.frameRateLimit=${config.developer.frameRateLimit} (field retained; not consumed by completion-driven loop)",
            )
            appendLine("developer.nativeBenchmarkIterations=${config.developer.nativeBenchmarkIterations}")
            appendLine("developer.logLevel=${config.developer.logLevel.name}")
            appendLine("algorithm.mode=${config.algorithm.selectionMode.name}")
            appendLine("algorithm.pinned=${config.algorithm.pinnedAlgorithmId ?: "-"}")
            appendLine("algorithm.channel=${config.algorithm.channel.name}")
            appendLine("update.channel=${config.update.channel.name}")
            appendLine("update.source=${config.update.sourcePreference.name}")
            appendLine()
            appendLine("== Algorithm activation ==")
            if (algorithm != null) {
                appendLine("id=${algorithm.algorithmId}")
                appendLine("version=${algorithm.version}")
                appendLine("generation=${algorithm.generation}")
                appendLine("usingBuiltinFallback=${algorithm.usingBuiltinFallback}")
                appendLine("loadError=${algorithm.loadError?.let(AppLog::redact) ?: "-"}")
                appendLine("nativeAvailable=${algorithm.nativeAvailable}")
                appendLine("pendingCatalogId=${algorithm.pendingCatalogId ?: "-"}")
                appendLine("analysisRunning=${algorithm.analysisRunning}")
            } else {
                appendLine("(unavailable)")
            }
            appendLine()
            appendLine("== Runtime bits ==")
            appendLine("debugFrameCount=$debugFrameCount")
            if (runtime != null) {
                appendLine("vision.running=${runtime.running}")
                appendLine("vision.captureReady=${runtime.captureReady}")
                appendLine("vision.overlayVisible=${runtime.overlayVisible}")
                appendLine("vision.overlayBlockReason=${runtime.overlayBlockReason?.name ?: "-"}")
                appendLine("vision.automationArmed=-")
                appendLine("vision.activeScene=${runtime.activeScene.name}")
                appendLine("vision.activeBackend=${runtime.activeBackend.name}")
                appendLine("vision.fps=${"%.2f".format(runtime.fps)}")
                appendLine("vision.processingMs=${"%.2f".format(runtime.processingMs)}")
                appendLine("vision.obstacleCount=${runtime.obstacleCount}")
                appendLine("vision.lastError=${runtime.lastError?.let(AppLog::redact) ?: "-"}")
            } else {
                appendLine("vision.running=unknown")
            }
            if (mcp != null) {
                appendLine("mcp.running=${mcp.running}")
                appendLine("mcp.port=${mcp.port ?: "-"}")
                appendLine("mcp.lastError=${mcp.lastError?.let(AppLog::redact) ?: "-"}")
            } else {
                appendLine("mcp.running=unknown")
            }
            appendLine()
            appendLine("== Recent logs (oldest→newest, max $logLimit) ==")
            val logs = AppLog.snapshot(logLimit)
            if (logs.isEmpty()) {
                appendLine("(empty)")
            } else {
                logs.forEach { entry ->
                    val ts = timeFormat.format(Date(entry.epochMs))
                    append(ts)
                    append(' ')
                    append(entry.level.name)
                    append('/')
                    append(entry.tag)
                    append(": ")
                    append(entry.message)
                    entry.throwableMessage?.let {
                        append(" | ex=")
                        append(it)
                    }
                    appendLine()
                }
            }
            appendLine()
            appendLine("== Notes ==")
            appendLine("- Bearer tokens and secrets are redacted.")
            appendLine("- Debug frame pixels are not included.")
            appendLine("- Timestamps use the device local timezone with offset (not UTC Z).")
            appendLine("- Overlay DEBUG_HUD / FPS / diagnostics toggles live under Overlay settings.")
            appendLine("- External algorithm packs need release-index catalog + AlgorithmTrustAnchors public key.")
        }
    }
}
