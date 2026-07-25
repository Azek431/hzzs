/**
 * 诊断摘要构建：版本 / 机型 / 配置摘要 / 算法激活 / 运行态 / 最近日志。
 *
 * 安全：不包含 MCP Bearer、签名密钥、调试帧像素；配置仅摘要字段。
 */
package top.azek431.hzzs.core.logging

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import top.azek431.hzzs.core.algorithm.AlgorithmPipelineTrace
import top.azek431.hzzs.core.algorithm.AlgorithmRuntimeTrace
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend
import top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend
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
     * @param appContext 可选；用于读系统指针位置 / Shizuku 就绪（JVM 单测可 null）
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
        appContext: Context? = null,
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
            appendLine(
                "automation.disclaimerAcceptedVersion=${config.automation.disclaimerAcceptedVersion}" +
                    "/${AppConfig.DISCLAIMER_VERSION}",
            )
            appendLine("automation.gestureBackend=${config.automation.gestureBackend.name}")
            appendLine("automation.restrictPackages=${config.automation.restrictPackages}")
            appendLine(
                "automation.allowedPackages=" +
                    config.automation.allowedPackages.sorted().joinToString(",").ifBlank { "-" },
            )
            appendLine(
                "automation.bambooExperimental=${config.automation.bambooExperimentalAutoAction} " +
                    "(legacy unused at runtime)",
            )
            appendLine(
                "automation.autoAdjustTriggerDistance=${config.automation.autoAdjustTriggerDistance}",
            )
            appendLine(
                "automation.triggerPlayerWidths=" +
                    "sweet=${"%.2f".format(config.automation.sweetTriggerDistancePlayerWidths)}" +
                    ",bamboo=${"%.2f".format(config.automation.bambooTriggerDistancePlayerWidths)}" +
                    ",sea=${"%.2f".format(config.automation.seaSaltTriggerDistancePlayerWidths)}",
            )
            appendLine(
                "automation.minimumSceneConfidence=" +
                    "%.2f".format(config.automation.minimumSceneConfidence),
            )
            appendLine("automation.maxActionsPerSecond=${config.automation.maxActionsPerSecond}")
            appendLine("automation.retryLimit=${config.automation.retryLimit}")
            appendLine("automation.autoReviveEnabled=${config.automation.autoReviveEnabled}")
            appendLine("mcp.enabled=${config.mcp.enabled}")
            appendLine("mcp.permission=${config.mcp.permissionLevel.name}")
            appendLine("mcp.requireAuth=${config.mcp.requireAuth}")
            // 只写是否有 token，不写明文。
            appendLine("mcp.authTokenConfigured=${config.mcp.authToken.isNotBlank()}")
            appendLine("mcp.allowDebugFrames=${config.mcp.allowDebugFrames}")
            appendLine("mcp.accessLogEnabled=${config.mcp.accessLogEnabled}")
            appendLine(
                "mcp.accessLogCount=" +
                    runCatching { top.azek431.hzzs.mcp.McpAccessLog.size() }.getOrDefault(0),
            )
            appendLine("mcp.toolPolicyOverrides=${config.mcp.toolPolicies.size}")
            if (config.mcp.toolPolicies.isNotEmpty()) {
                appendLine(
                    "mcp.toolPolicies=" +
                        config.mcp.toolPolicies.entries
                            .sortedBy { it.key }
                            .joinToString(",") { "${it.key}:${it.value.name}" },
                )
            }
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
            // 手势门控：无障碍连接 / 前台快照 / AUTO 解析结果（与 capture 正交）。
            // 经 FQCN + runCatching，避免 core.logging 硬依赖 service 在 JVM 单测炸。
            val a11yConnected = runCatching {
                top.azek431.hzzs.service.automation.HzzsAccessibilityService.isConnected()
            }.getOrDefault(false)
            val shizukuReady = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val gestureResolution = resolveEffectiveGestureBackend(
                gestureBackend = config.automation.gestureBackend,
                accessibilityConnected = a11yConnected,
                shizukuReady = shizukuReady,
            )
            appendLine("gesture.requested=${gestureResolution.requested.name}")
            appendLine("gesture.effective=${gestureResolution.effective.name}")
            appendLine(
                "gesture.fallbackReason=${gestureResolution.fallbackReason?.let(AppLog::redact) ?: "-"}",
            )
            appendLine("a11y.connected=$a11yConnected")
            appendLine("shizuku.ready=$shizukuReady")
            val fgLine = runCatching {
                val fg = top.azek431.hzzs.service.automation.HzzsAccessibilityService
                    .foregroundSnapshot(refreshIfStale = true)
                if (fg == null) {
                    "foreground.pkg=- cls=- ageMs=-"
                } else {
                    val age = SystemClock.elapsedRealtime() - fg.observedAtMs
                    "foreground.pkg=${fg.packageName.ifBlank { "-" }} " +
                        "cls=${fg.className.ifBlank { "-" }} " +
                        "ageMs=$age"
                }
            }.getOrDefault("foreground.pkg=- cls=- ageMs=- (probe_failed)")
            appendLine(fgLine)
            appendLine("developer.saveDebugFrames=${config.developer.saveDebugFrames}")
            appendLine("developer.showCoordinateGrid=${config.developer.showCoordinateGrid}")
            appendLine(
                "developer.frameRateLimit=${config.developer.frameRateLimit} (field retained; not consumed by completion-driven loop)",
            )
            appendLine("developer.nativeBenchmarkIterations=${config.developer.nativeBenchmarkIterations}")
            appendLine("developer.logLevel=${config.developer.logLevel.name}")
            appendLine("developer.logRingCapacity=${config.developer.logRingCapacity}")
            appendLine("developer.enableStageTiming=${config.developer.enableStageTiming}")
            appendLine("developer.enableMulticolorDiagnostic=${config.developer.enableMulticolorDiagnostic}")
            appendLine("developer.enableFilterTrace=${config.developer.enableFilterTrace}")
            // 系统指针位置不进 AppConfig；只读当前系统/Shizuku 状态便于真机对照。
            if (appContext != null) {
                appendLine(
                    "system." +
                        SystemCapabilityAccess.pointerLocationDiagnosticsLine(appContext),
                )
            } else {
                appendLine("system.pointerLocation=(no context)")
            }
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
                appendLine("vision.automationSessionArm=removed")
                appendLine("vision.activeScene=${runtime.activeScene.name}")
                appendLine("vision.activeBackend=${runtime.activeBackend.name}")
                appendLine("vision.activeGestureBackend=${runtime.activeGestureBackend.name}")
                appendLine("vision.fps=${"%.2f".format(runtime.fps)}")
                appendLine("vision.processingMs=${"%.2f".format(runtime.processingMs)}")
                appendLine("vision.obstacleCount=${runtime.obstacleCount}")
                appendLine("vision.lastError=${runtime.lastError?.let(AppLog::redact) ?: "-"}")
                appendLine(
                    "vision.lastAutomationDecision=" +
                        (runtime.lastAutomationDecision?.let(AppLog::redact) ?: "-"),
                )
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
            appendLine("== MCP access log (newest first, max 40) ==")
            val access = runCatching {
                top.azek431.hzzs.mcp.McpAccessLog.formatText(limit = 40, newestFirst = true)
            }.getOrDefault("")
            if (access.isBlank()) {
                appendLine("(none)")
            } else {
                appendLine(access)
            }
            appendLine()
            appendLine("== Algorithm pipeline ==")
            append(AlgorithmPipelineTrace.formatText().trimEnd())
            appendLine()
            appendLine()
            appendLine("== Algorithm runtime frames (oldest→newest, max ${AlgorithmRuntimeTrace.CAPACITY}) ==")
            append(AlgorithmRuntimeTrace.formatText().trimEnd())
            appendLine()
            appendLine()
            appendLine(
                "== Algorithm decisions (oldest→newest, max ${AlgorithmRuntimeTrace.DECISION_CAPACITY}) ==",
            )
            append(AlgorithmRuntimeTrace.formatDecisionText().trimEnd())
            appendLine()
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
            appendLine(
                "- Algorithm frame AppLog tags: algo.frame / algo.det / algo.track / algo.decision " +
                    "(developer on + logLevel≤DEBUG for frames; decisions INFO on skip/plan/dispatch/calc). " +
                    "Throttled on change or every ${AlgorithmRuntimeTrace.PERIODIC_FRAMES} frames.",
            )
            appendLine(
                "- Stage timing appears inside algo.frame lines (jni/detect/post/finalize ms). " +
                    "Gated by developer.enableStageTiming (off by default; ~5-10us/frame).",
            )
            appendLine(
                "- Multicolor per-template match/reject appears in algo.decision calc lines " +
                    "and DEBUG_HUD 找色/搜索区/命中点. " +
                    "Gated by developer.enableMulticolorDiagnostic (off by default).",
            )
            appendLine(
                "- Filtered-out detections with reason appear in algo.frame (filt=N) and DEBUG_HUD 虚线框. " +
                    "Gated by developer.enableFilterTrace (off by default).",
            )
            appendLine(
                "- Decision ring retains last ${AlgorithmRuntimeTrace.DECISION_CAPACITY} " +
                    "skip/plan/dispatch/calc lines for agent triage (no pixels).",
            )
            appendLine(
                "- Runtime frame ring retains last ${AlgorithmRuntimeTrace.CAPACITY} analyses after stop " +
                    "until next start; no pixels.",
            )
            appendLine(
                "- Automation gates: a11y.connected / foreground.* / gesture.effective / " +
                    "disclaimerAcceptedVersion / triggerPlayerWidths; decision ring explains skip:*.",
            )
            appendLine(
                "- Boxes on screen ≠ gestures: algorithm+Overlay draw Detection; " +
                    "actions need automation gates + gesture backend.",
            )
        }
    }
}