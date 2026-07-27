package top.azek431.hzzs.mcp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogController
import top.azek431.hzzs.core.algorithm.AlgorithmPipelineTrace
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.McpToolPolicy
import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.OverlayOrientation
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.OverlayTheme
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ThemePreset
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.mcp.executor.ToolExecutor
import top.azek431.hzzs.mcp.McpEventBus
import top.azek431.hzzs.mcp.McpToolCatalog
import top.azek431.hzzs.mcp.McpToolLabels
import top.azek431.hzzs.mcp.McpToolPolicySupport
import top.azek431.hzzs.mcp.McpAccessLog
import top.azek431.hzzs.mcp.McpSessionManager
import top.azek431.hzzs.mcp.McpUiBridge
import top.azek431.hzzs.mcp.McpToolRisk
import javax.inject.Inject
import javax.inject.Singleton

/** 协议层可调用的动作面（便于 JVM 单测注入假实现）。 */
interface McpActionSurface {
    suspend fun readResource(uri: String): JSONObject
    suspend fun call(
        tool: String,
        arguments: JSONObject,
        session: McpSessionManager.Session?,
    ): JSONObject
}

/**
 * 应用内动作面：MCP 与后续自动化测试共用。
 *
 * 安全：写操作经四级权限；HIGH_RISK 在 TRUSTED_SESSION 下拒绝；
 * 完整访问仍不能绕过系统权限对话框。
 */
@Singleton
class McpActionRegistry @Inject constructor(

    @param:ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val runtime: VisionRuntimeController,
    private val uiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
    private val algorithmCatalog: AlgorithmCatalogController,
    private val visionEngine: VisionEngine,
    private val executors: @JvmSuppressWildcards Set<ToolExecutor>,
) : McpActionSurface {
    private val executorIndex: Map<String, ToolExecutor> = buildMap {
        executors.forEach { executor ->
            require(executor.toolNames.isNotEmpty()) {
                "执行器 ${executor::class.simpleName} 未声明任何工具"
            }
            executor.toolNames.forEach { name ->
                require(put(name, executor) == null) { "工具名重复注册：$name" }
            }
        }
    }

    override suspend fun readResource(uri: String): JSONObject = when (uri) {
        "app://status" -> runtime.status.value.toJson()
        "app://runtime/snapshot" -> runtimeSnapshot()
        "app://settings/current" -> JSONObject(settings.exportJsonRedacted(settings.current()))
        "app://settings/schema" -> settingsSchema()
        "app://vision/latest" -> runtime.latestResult.value?.toJson() ?: JSONObject.NULL.asObject()
        "app://vision/metrics" -> runtime.status.value.toJson()
        "app://debug/frames" -> debugFrameMetadata()
        "app://algorithm/active" -> activeAlgorithmJson()
        "app://algorithm/catalog" -> algorithmCatalogJson()
        "app://algorithm/pipeline" -> algorithmPipelineJson()
        "app://automation/gates" -> automationGatesJson()
        "app://permissions" -> permissionsJson()
        "app://logs/recent" -> logsJson(limit = 80, newestFirst = true)
        "app://mcp/status" -> mcpStatusJson()
        "app://events" -> eventsJson()
        else -> throw IllegalArgumentException("未知资源：$uri")
    }

    override suspend fun call(
        tool: String,
        arguments: JSONObject,
        session: McpSessionManager.Session?,
    ): JSONObject {
        val descriptor = McpToolCatalog.tool(tool)
            ?: throw IllegalArgumentException("未知工具：$tool")
        validateArguments(descriptor, arguments)
        // 必须读 current（含设置页 preview），否则草稿里改的权限/策略要等保存才生效
        val mcp = settings.current().mcp
        authorize(descriptor, arguments, mcp, session)
        val executor = executorIndex[descriptor.name]
            ?: throw IllegalStateException("工具 ${descriptor.name} 无对应执行器")
        return executor.execute(descriptor.name, arguments)
    }

    private fun validateArguments(descriptor: McpToolDescriptor, arguments: JSONObject) {
        descriptor.required.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) {
                throw IllegalArgumentException("缺少参数：$key")
            }
            val value = arguments.opt(key)
            if (value == JSONObject.NULL) throw IllegalArgumentException("参数 $key 不能为 null")
        }
    }

    private suspend fun authorize(
        descriptor: McpToolDescriptor,
        arguments: JSONObject,
        mcp: top.azek431.hzzs.core.model.McpConfig,
        session: McpSessionManager.Session?,
    ) {
        val policy = mcp.policyFor(descriptor.name)
        val level = mcp.permissionLevel
        McpToolPolicySupport.hardRejectReason(
            risk = descriptor.risk,
            global = level,
            toolPolicy = policy,
            hasTrustedSession = session != null,
        )?.let { error(it) }

        if (descriptor.risk == McpToolRisk.READ) return

        // HIGH_RISK + ALWAYS_ASK：在 TRUSTED 下 hardReject 已放行到审批
        val needApproval = McpToolPolicySupport.requiresPhoneApproval(
            risk = descriptor.risk,
            global = level,
            toolPolicy = policy,
        )
        if (needApproval) {
            val approved = uiBridge.requestApproval(
                McpToolLabels.approvalLabel(descriptor.name),
                McpToolLabels.summaryZh(descriptor.name, arguments),
            )
            check(approved) { "用户未批准操作" }
        }
    }

    private suspend fun mcpStatusJson(): JSONObject {
        val snap = settings.current()
        val mcp = snap.mcp
        val state = uiBridge.serverState.value
        val port = if (state.running && state.port > 0) state.port else mcp.port
        val bindLocalhostOnly = if (state.running) state.bindLocalhostOnly else mcp.bindLocalhostOnly
        return JSONObject().apply {
            put("serverName", "hzzs")
            put("configEnabled", mcp.enabled)
            put("running", state.running)
            put("port", port)
            put("bind", if (bindLocalhostOnly) "127.0.0.1" else "0.0.0.0")
            put("bindLocalhostOnly", bindLocalhostOnly)
            put("endpointLoopback", "http://127.0.0.1:$port/mcp")
            put("permissionLevel", mcp.permissionLevel.name)
            put("requireAuth", mcp.requireAuth)
            put("tokenConfigured", mcp.authToken.isNotBlank())
            put("allowDebugFrames", mcp.allowDebugFrames)
            put("accessLogEnabled", mcp.accessLogEnabled)
            put("accessLogCount", McpAccessLog.size())
            put("eventCount", McpEventBus.size())
            put("activeSessions", state.activeSessions)
            put("lastError", state.lastError)
            put(
                "toolPolicies",
                JSONObject().apply {
                    mcp.toolPolicies.forEach { (name, policy) -> put(name, policy.name) }
                },
            )
            put("enabledToolCount", McpToolPolicySupport.effectiveTools(mcp).size)
            put("totalToolCount", McpToolCatalog.tools.size)
            put(
                "toolPolicyValues",
                JSONArray(McpToolPolicy.entries.map { it.name }),
            )
            put(
                "permissionLevels",
                JSONArray(McpPermissionLevel.entries.map { it.name }),
            )
        }
    }

    private suspend fun debugFrameMetadata(): JSONObject {
        val config = settings.current()
        check(config.developer.enabled && config.mcp.allowDebugFrames) {
            "请先在开发者设置中允许 MCP 读取调试帧元数据"
        }
        return JSONObject().put(
            "frames",
            JSONArray().apply {
                debugFrames.list().forEach { frame ->
                    put(
                        JSONObject()
                            .put("name", frame.name)
                            .put("sizeBytes", frame.sizeBytes)
                            .put("modifiedAtEpochMs", frame.modifiedAtEpochMs),
                    )
                }
            },
        )
    }

    private fun settingsSchema(): JSONObject = JSONObject().apply {
        put("schemaVersion", AppConfig.CURRENT_SCHEMA)
        put("captureBackends", JSONArray(CaptureBackend.entries.map { it.name }))
        put("gestureBackends", JSONArray(GestureBackend.entries.map { it.name }))
        put("permissionLevels", JSONArray(McpPermissionLevel.entries.map { it.name }))
        put("mcpToolPolicies", JSONArray(McpToolPolicy.entries.map { it.name }))
        put("mcpTools", JSONArray(McpToolCatalog.tools.map { it.name }))
        put("scenes", JSONArray(SceneId.entries.map { it.name }))
        put("themeModes", JSONArray(AppThemeMode.entries.map { it.name }))
        put("themePresets", JSONArray(ThemePreset.entries.map { it.name }))
        put("overlayStyles", JSONArray(OverlayStyle.entries.map { it.name }))
        put("overlayThemes", JSONArray(OverlayTheme.entries.map { it.name }))
        put("overlayOrientations", JSONArray(OverlayOrientation.entries.map { it.name }))
        put("playerReferenceModes", JSONArray(PlayerReferenceMode.entries.map { it.name }))
        put("obstacleKinds", JSONArray(ObstacleKind.entries.map { it.name }))
    }

    private suspend fun runtimeSnapshot(): JSONObject {
        val status = runtime.status.value
        val latest = runtime.latestResult.value
        val cfg = settings.current()
        val activation = visionEngine.currentActivation()
        return JSONObject().apply {
            put("status", status.toJson())
            put("latest", latest?.toJson() ?: JSONObject.NULL)
            put(
                "latestSummary",
                latest?.let { r ->
                    JSONObject().apply {
                        put("scene", r.scene.name)
                        put("sceneConfidence", r.sceneConfidence.toDouble())
                        put("detectionCount", r.detections.size)
                        put(
                            "kindHistogram",
                            JSONObject().apply {
                                r.detections.groupingBy { it.kind.name }.eachCount().forEach { (k, v) ->
                                    put(k, v)
                                }
                            },
                        )
                        put(
                            "hasPlayer",
                            r.detections.any {
                                it.kind == top.azek431.hzzs.domain.vision.ObjectKind.PLAYER
                            },
                        )
                    }
                } ?: JSONObject.NULL,
            )
            put("algorithm", activeAlgorithmJson())
            put("automationGates", automationGatesJson())
            put("selectedScene", cfg.selectedScene.name)
            put("captureBackend", cfg.captureBackend.name)
            put("overlayEnabled", cfg.overlay.enabled)
            put("developerEnabled", cfg.developer.enabled)
            put("pipelineRevision", AlgorithmPipelineTrace.revision())
            put("activationGeneration", activation.generation)
        }
    }

    private fun activeAlgorithmJson(): JSONObject {
        val activation = visionEngine.currentActivation()
        val catalog = algorithmCatalog.state.value
        return JSONObject().apply {
            put("id", activation.profile.algorithmId)
            put("version", activation.profile.version)
            put("generation", activation.generation)
            put("usingBuiltinFallback", activation.usingBuiltinFallback)
            put("loadError", activation.loadError ?: JSONObject.NULL)
            put("catalogActiveId", catalog.active?.id ?: JSONObject.NULL)
            put("pendingActivationId", catalog.pendingActivation?.id ?: JSONObject.NULL)
            put("selectionMode", catalog.selectionMode.name)
            put("channel", catalog.channel.name)
            put("analysisRunning", catalog.analysisRunning)
        }
    }

    private fun algorithmCatalogJson(): JSONObject {
        val state = algorithmCatalog.state.value
        return JSONObject().apply {
            put("phase", phaseName(state.phase))
            put("message", state.message ?: JSONObject.NULL)
            put("selectionMode", state.selectionMode.name)
            put("channel", state.channel.name)
            put("analysisRunning", state.analysisRunning)
            put("active", state.active?.let { AlgorithmPackageInfoToJson(it) } ?: JSONObject.NULL)
            put("pendingActivation", state.pendingActivation?.let { AlgorithmPackageInfoToJson(it) } ?: JSONObject.NULL)
            put("installed", JSONArray(state.installed.map { AlgorithmPackageInfoToJson(it) }))
            put("remote", JSONArray(state.remote.map { AlgorithmPackageInfoToJson(it) }))
            put(
                "downloads",
                JSONObject().apply {
                    state.downloads.forEach { (id, task) ->
                        put(
                            id,
                            JSONObject()
                                .put("progress", task.progress.toDouble())
                                .put("verifying", task.verifying)
                                .put("error", task.error ?: JSONObject.NULL),
                        )
                    }
                },
            )
            put("lastCheckedAtEpochMs", state.lastCheckedAtEpochMs ?: JSONObject.NULL)
        }
    }

    private fun AlgorithmPackageInfoToJson(info: top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo): JSONObject =
        JSONObject()
            .put("id", info.id)
            .put("name", info.name)
            .put("versionName", info.versionName)
            .put("versionCode", info.versionCode)
            .put("channel", info.channel.name)
            .put("origin", info.origin.name)
            .put("isCompatible", info.isCompatible)
            .put("isInstalled", info.isInstalled)
            .put("isBuiltin", info.isBuiltin)
            .put("summary", info.summary)
            .put("author", info.author ?: JSONObject.NULL)
            .put("supportedScenes", JSONArray(info.supportedScenes.map { it.name }))

    private fun algorithmPipelineJson(): JSONObject {
        val snap = AlgorithmPipelineTrace.snapshot()
        return JSONObject().apply {
            put("revision", snap.revision)
            put("catalogId", snap.catalogId ?: JSONObject.NULL)
            put("selectionMode", snap.selectionMode ?: JSONObject.NULL)
            put("selectedScene", snap.selectedScene ?: JSONObject.NULL)
            put(
                "stages",
                JSONArray().apply {
                    snap.stages.forEach { stage ->
                        put(
                            JSONObject()
                                .put("id", stage.id)
                                .put("title", stage.title)
                                .put("status", stage.status.name)
                                .put("detail", stage.detail ?: JSONObject.NULL)
                                .put("updatedAtEpochMs", stage.updatedAtEpochMs),
                        )
                    }
                },
            )
            put(
                "lastFrame",
                snap.lastFrame?.let { f ->
                    JSONObject()
                        .put("epochMs", f.epochMs)
                        .put("scene", f.scene)
                        .put("sceneConfidence", f.sceneConfidence.toDouble())
                        .put("hasPlayer", f.hasPlayer)
                        .put("obstacleCount", f.obstacleCount)
                        .put("actionableCount", f.actionableCount)
                        .put("kindHistogram", f.kindHistogram)
                        .put("processingMs", f.processingMs.toDouble())
                        .put("algorithmId", f.algorithmId)
                        .put("algorithmVersion", f.algorithmVersion)
                        .put("generation", f.generation)
                        .put("usingBuiltinFallback", f.usingBuiltinFallback)
                        .put("loadError", f.loadError ?: JSONObject.NULL)
                        .put("frameError", f.frameError ?: JSONObject.NULL)
                } ?: JSONObject.NULL,
            )
        }
    }

    private suspend fun automationGatesJson(): JSONObject {
        // automation / 手势后端以 saved 为准（与 VisionRuntimeController 一致）；草稿不派发。
        val saved = settings.snapshot()
        val status = runtime.status.value
        val latest = runtime.latestResult.value
        val a11y = HzzsAccessibilityService.isConnected()
        val auto = saved.automation
        val disclaimerOk = auto.disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION
        val sceneConf = latest?.sceneConfidence
        val sceneOk = sceneConf == null || sceneConf >= auto.minimumSceneConfidence
        val gestureRequested = auto.gestureBackend
        val gestureEffective = when {
            status.running -> status.activeGestureBackend
            else -> resolveEffectiveGestureBackend(
                gestureBackend = gestureRequested,
                accessibilityConnected = a11y,
                shizukuReady = runCatching {
                    top.azek431.hzzs.service.automation.ShellProcessSupport.isShizukuAuthorized()
                }.getOrDefault(false),
            ).effective
        }
        val needsA11y = gestureEffective == GestureBackend.ACCESSIBILITY ||
            gestureRequested == GestureBackend.ACCESSIBILITY ||
            (gestureRequested == GestureBackend.AUTO &&
                gestureEffective != GestureBackend.SHIZUKU &&
                gestureEffective != GestureBackend.ROOT)
        // 前台：无障碍路径用 a11y 快照；Shell 路径无法在此同步 dumpsys（避免卡 MCP），
        // 包门控仅在有 a11y 快照或非 restrict 时可信。
        val fg = if (needsA11y || auto.restrictPackages) {
            HzzsAccessibilityService.foregroundSnapshot(refreshIfStale = true)
        } else {
            null
        }
        val packageBlocked = auto.restrictPackages &&
            (fg == null || fg.packageName !in auto.allowedPackages)
        val blockers = buildList {
            if (!auto.enabled) add("automation.enabled=false")
            if (!disclaimerOk) {
                add("disclaimerAcceptedVersion=${auto.disclaimerAcceptedVersion}<${AppConfig.DISCLAIMER_VERSION}")
            }
            if (!status.running) add("analysis.not_running")
            if (!a11y && needsA11y) {
                add("accessibility.not_connected")
            }
            if (!sceneOk) {
                add("sceneConfidence=${sceneConf ?: "n/a"}<minimum=${auto.minimumSceneConfidence}")
            }
            if (packageBlocked) {
                add(
                    "package_gate restrict=true pkg=${fg?.packageName ?: "n/a"} " +
                        "allowed=${auto.allowedPackages.sorted().joinToString(",")}" +
                        if (!needsA11y) " (foreground via a11y probe; shell may differ)" else "",
                )
            }
        }
        return JSONObject().apply {
            put("source", "saved")
            put("automationEnabled", auto.enabled)
            put("gestureBackend", gestureRequested.name)
            put("activeGestureBackend", gestureEffective.name)
            put("disclaimerAcceptedVersion", auto.disclaimerAcceptedVersion)
            put("disclaimerRequired", AppConfig.DISCLAIMER_VERSION)
            put("disclaimerOk", disclaimerOk)
            put("analysisRunning", status.running)
            put("accessibilityConnected", a11y)
            put("selectedScene", saved.selectedScene.name)
            put("sceneConfidence", sceneConf?.toDouble() ?: JSONObject.NULL)
            put("minimumSceneConfidence", auto.minimumSceneConfidence.toDouble())
            put("sceneConfidenceOk", sceneOk)
            // legacy 字段：运行时不再硬锁竹影；保留只读兼容。
            put("bambooExperimentalAutoAction", auto.bambooExperimentalAutoAction)
            put("bambooLockActive", false)
            put("restrictPackages", auto.restrictPackages)
            put("allowedPackages", JSONArray(auto.allowedPackages.sorted()))
            put("foregroundPackage", fg?.packageName ?: JSONObject.NULL)
            put(
                "foregroundSource",
                if (needsA11y) "accessibility" else "accessibility_probe_for_package_gate",
            )
            put("lastAutomationDecision", status.lastAutomationDecision ?: JSONObject.NULL)
            put("maxActionsPerSecond", auto.maxActionsPerSecond)
            put("canDispatchLikely", blockers.isEmpty())
            put("blockers", JSONArray(blockers))
        }
    }

    private fun permissionsJson(): JSONObject = JSONObject().apply {
        put("canDrawOverlays", SystemCapabilityAccess.canDrawOverlays(appContext))
        put("accessibilityConnected", SystemCapabilityAccess.isAccessibilityServiceConnected())
        put("packageName", appContext.packageName)
    }

    private suspend fun logsJson(
        minLevel: AppLogLevel = AppLogLevel.INFO,
        tag: String? = null,
        query: String? = null,
        limit: Int = 100,
        newestFirst: Boolean = true,
    ): JSONObject {
        if (!settings.current().developer.enabled) {
            return JSONObject().put("error", "需要开发者选项").put("entries", JSONArray())
        }
        val entries = AppLog.query(
            minLevel = minLevel,
            tagEquals = tag,
            query = query,
            limit = limit,
            newestFirst = newestFirst,
        )
        return JSONObject().apply {
            put("revision", AppLog.revision())
            put("count", entries.size)
            put(
                "entries",
                JSONArray().apply {
                    entries.forEach { e ->
                        put(
                            JSONObject()
                                .put("id", e.id)
                                .put("epochMs", e.epochMs)
                                .put("level", e.level.name)
                                .put("tag", e.tag)
                                .put("message", e.message)
                                .put("throwable", e.throwableMessage ?: JSONObject.NULL),
                        )
                    }
                },
            )
            put("text", AppLog.formatText(minLevel, tag, query, limit, newestFirst))
        }
    }

    private fun eventsJson(since: Long = 0L, limit: Int = 50): JSONObject {
        val snap = McpEventBus.snapshot(since, limit)
        return JSONObject()
            .put("events", JSONArray(snap.events.map { it.toJson() }))
            .put("nextSince", snap.nextSince)
            .put("dropped", snap.dropped)
            .put("oldestSeq", snap.oldestSeq)
            .put("latestSeq", snap.latestSeq)
            .put("buffered", snap.buffered)
    }

    private suspend fun listMcpToolsJson(includeDisabled: Boolean): JSONObject {
        val mcp = settings.current().mcp
        val arr = JSONArray()
        McpToolCatalog.tools.forEach { tool ->
            val policy = mcp.policyFor(tool.name)
            if (!includeDisabled && policy == McpToolPolicy.DISABLED) return@forEach
            arr.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("titleZh", McpToolLabels.titleZh(tool.name))
                    .put("description", tool.description)
                    .put("risk", tool.risk.name)
                    .put("policy", policy.name)
                    .put("enabled", policy != McpToolPolicy.DISABLED)
                    .put("clientDescription", McpToolLabels.clientDescription(tool)),
            )
        }
        return JSONObject().put("tools", arr).put("count", arr.length())
    }

    private fun phaseName(phase: top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase): String = when (phase) {
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Idle -> "Idle"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Loading -> "Loading"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Empty -> "Empty"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.OfflineWithCache -> "OfflineWithCache"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.MirrorFallback -> "MirrorFallback"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Downloading -> "Downloading"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Verifying -> "Verifying"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.PendingActivation -> "PendingActivation"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Error -> "Error"
        is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Incompatible -> "Incompatible"
    }
}

private fun Any?.asObject(): JSONObject = JSONObject().put("value", this)

internal fun ok(message: String) = JSONObject().put("ok", true).put("message", message)

internal fun top.azek431.hzzs.core.model.RuntimeStatus.toJson() = JSONObject().apply {
    put("running", running)
    put("captureReady", captureReady)
    put("overlayVisible", overlayVisible)
    put("overlayBlockReason", overlayBlockReason?.name ?: JSONObject.NULL)
    put("activeScene", activeScene.name)
    put("activeBackend", activeBackend.name)
    put("activeGestureBackend", activeGestureBackend.name)
    put("fps", fps.toDouble())
    put("processingMs", processingMs.toDouble())
    put("obstacleCount", obstacleCount)
    lastError?.let { put("lastError", it) }
    lastAutomationDecision?.let { put("lastAutomationDecision", it) }
}

internal fun top.azek431.hzzs.domain.vision.VisionResult.toJson() = JSONObject().apply {
    put("scene", scene.name)
    put("sceneConfidence", sceneConfidence.toDouble())
    put("processingNanos", processingNanos)
    if (timing.totalNs > 0) {
        put(
            "timing",
            JSONObject().apply {
                put("jniPrepMs", (timing.jniPrepNs / 1_000_000.0))
                put("detectMs", (timing.detectNs / 1_000_000.0))
                put("postfilterMs", (timing.postfilterNs / 1_000_000.0))
                put("finalizeMs", (timing.finalizeNs / 1_000_000.0))
            },
        )
    }
    put(
        "detections",
        JSONArray().apply {
            detections.forEach { detection ->
                put(
                    JSONObject().apply {
                        put("kind", detection.kind.name)
                        put("confidence", detection.confidence.toDouble())
                        put("left", detection.bounds.left.toDouble())
                        put("top", detection.bounds.top.toDouble())
                        put("right", detection.bounds.right.toDouble())
                        put("bottom", detection.bounds.bottom.toDouble())
                    },
                )
            }
        },
    )
}
