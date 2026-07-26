package top.azek431.hzzs.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.BuildConfig
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogController
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.AlgorithmPipelineTrace
import top.azek431.hzzs.core.algorithm.logic.AlgorithmCatalogPure
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
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
import top.azek431.hzzs.core.preferences.hardenedForExternalIngest
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
import top.azek431.hzzs.mcp.McpProfileStore
import top.azek431.hzzs.mcp.McpSettingsPatch
import top.azek431.hzzs.mcp.McpToolRisk
import top.azek431.hzzs.mcp.generateMcpAuthToken
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.requireString
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
    private val profileStore: McpProfileStore,
    private val updateRepository: top.azek431.hzzs.core.update.UpdateRepository,
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
    /** 进程存活起点（elapsedRealtime），用于 get_metrics.uptimeMs。 */
    private val processStartedElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()

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

    private suspend fun executePatchSettings(arguments: JSONObject): JSONObject {
        val persist = arguments.optBoolean("persist", true)
        val patchesObj = arguments.optJSONObject("patches")
        val opsArr = arguments.optJSONArray("operations")
        val hasPatches = patchesObj != null && patchesObj.length() > 0
        val hasOps = opsArr != null && opsArr.length() > 0
        require(hasPatches || hasOps) { "patch_settings 需要非空 patches 或 operations" }
        var patched = settings.current()
        if (hasPatches) {
            patched = McpSettingsPatch.applyFromJson(patched, patchesObj!!)
        }
        if (hasOps) {
            val list = ArrayList<McpSettingsPatch.Op>(opsArr!!.length())
            for (i in 0 until opsArr.length()) {
                val op = opsArr.getJSONObject(i)
                val path = op.requireString("path")
                val opRaw = op.requireString("op").trim().uppercase(java.util.Locale.ROOT)
                val opType = try {
                    McpSettingsPatch.OpType.valueOf(opRaw)
                } catch (_: IllegalArgumentException) {
                    error("未知 op：$opRaw（支持 set/add/remove/toggle）")
                }
                list.add(
                    McpSettingsPatch.Op(
                        path = path,
                        value = if (op.isNull("value")) null else op.opt("value"),
                        operation = opType,
                    ),
                )
            }
            patched = McpSettingsPatch.applyOperations(patched, list)
        }
        if (persist) settings.save(patched) else settings.preview(patched)
        runCatching {
            McpEventBus.append(
                McpEventBus.Type.CONFIG_CHANGE,
                JSONObject().put("source", "patch_settings").put("persist", persist),
            )
        }
        return ok(if (persist) "局部设置已保存" else "局部设置已预览")
    }

    private fun executeNavigate(arguments: JSONObject): JSONObject {
        val route = arguments.requireString("route")
        val allowedTops = setOf("home", "runtime", "settings", "about")
        val normalized = route.trim().trimStart('/').lowercase()
        val top = when {
            normalized in allowedTops -> normalized
            normalized.startsWith("settings/") ||
                normalized in setOf(
                    "appearance", "overlay", "capture", "algorithm", "detection",
                    "automation", "network", "mcp", "developer",
                    "settings_home", "log_viewer", "algorithm_pipeline",
                    "logs", "pipeline",
                ) -> "settings"
            else -> error(
                "未知页面：$route（可用 home/runtime/settings/about 或 settings/mcp、developer、log_viewer）",
            )
        }
        uiBridge.requestNavigation(route)
        return ok("已请求打开 $route（一级=$top）")
    }

    private suspend fun executeSetCaptureBackend(arguments: JSONObject): JSONObject {
        val backend = enumValueOf<CaptureBackend>(arguments.requireString("backend"))
        val persist = arguments.optBoolean("persist", true)
        applyConfig({ it.copy(captureBackend = backend) }, persist)
        runCatching {
            McpEventBus.append(
                McpEventBus.Type.CAPTURE_BACKEND_CHANGE,
                JSONObject().put("backend", backend.name).put("persist", persist),
            )
        }
        return ok("截图后端已设为 ${backend.name}（建议 restart_analysis 生效）")
    }

    private suspend fun executeSetGestureBackend(arguments: JSONObject): JSONObject {
        val backend = enumValueOf<GestureBackend>(arguments.requireString("backend"))
        val persist = arguments.optBoolean("persist", true)
        if (!persist) {
            error(
                "手势后端仅随已保存配置生效：请 set_gesture_backend(persist=true) 或设置页「保存并应用」",
            )
        }
        applyConfig(
            { it.copy(automation = it.automation.copy(gestureBackend = backend)) },
            persist = true,
        )
        runtime.cancelPendingActions()
        return ok("手势后端已保存为 ${backend.name}（运行时立即按 saved 解析）")
    }

    private suspend fun executeGetDebugFrame(arguments: JSONObject): JSONObject {
        ensureDeveloper()
        val mcp = settings.current().mcp
        check(mcp.allowDebugFrames) { "请先开启 MCP 允许读取调试帧（mcp.allowDebugFrames）" }
        val name = arguments.requireString("name")
        val maxWidth = arguments.optInt("maxWidth", 480).coerceIn(64, 1080)
        val quality = arguments.optInt("quality", 70).coerceIn(10, 100)
        val bytes = debugFrames.getBytes(name, maxWidth, quality)
        check(bytes != null) { "调试帧不存在或文件名非法：$name" }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return JSONObject()
            .put("name", name)
            .put("width", bounds.outWidth.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("height", bounds.outHeight.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("sizeBytes", bytes.size)
            .put("maxWidth", maxWidth)
            .put("quality", quality)
            .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }

    private suspend fun executeSetScene(arguments: JSONObject): JSONObject {
        val scene = enumValueOf<SceneId>(arguments.requireString("scene"))
        val persist = arguments.optBoolean("persist", true)
        applyConfig({ it.copy(selectedScene = scene) }, persist)
        return ok("赛季已切换为 ${scene.name}")
    }

    private suspend fun executeSetObstacleEnabled(arguments: JSONObject): JSONObject {
        val cfg = settings.current()
        val scene = arguments.optString("scene").takeIf { it.isNotBlank() }?.let {
            enumValueOf<SceneId>(it)
        } ?: cfg.selectedScene
        val kind = enumValueOf<ObstacleKind>(arguments.requireString("kind"))
        val enabled = arguments.getBoolean("enabled")
        val persist = arguments.optBoolean("persist", true)
        val sceneCfg = cfg.scenes[scene] ?: error("未知场景")
        val disabled = sceneCfg.disabledObstacles.toMutableSet()
        if (enabled) disabled.remove(kind) else disabled.add(kind)
        applyConfig(
            { it.copy(scenes = it.scenes + (scene to sceneCfg.copy(disabledObstacles = disabled))) },
            persist,
        )
        return ok("${kind.name} 已${if (enabled) "启用" else "禁用"} @ ${scene.name}")
    }

    private suspend fun executeSetThreshold(arguments: JSONObject): JSONObject {
        val cfg = settings.current()
        val scene = arguments.optString("scene").takeIf { it.isNotBlank() }?.let {
            enumValueOf<SceneId>(it)
        } ?: cfg.selectedScene
        val key = arguments.requireString("key")
        val persist = arguments.optBoolean("persist", true)
        val path = "scenes.${scene.name}.thresholds.$key"
        val patched = McpSettingsPatch.apply(cfg, mapOf(path to arguments.get("value")))
        if (persist) settings.save(patched) else settings.preview(patched)
        return ok("已更新 $path")
    }

    private suspend fun executeSetTheme(arguments: JSONObject): JSONObject {
        val persist = arguments.optBoolean("persist", true)
        val patches = JSONObject()
        arguments.optString("mode").takeIf { it.isNotBlank() }?.let { patches.put("theme.mode", it) }
        arguments.optString("preset").takeIf { it.isNotBlank() }?.let { patches.put("theme.preset", it) }
        if (arguments.has("dynamicColorEnabled")) {
            patches.put("theme.dynamicColorEnabled", arguments.getBoolean("dynamicColorEnabled"))
        }
        if (arguments.has("reduceMotion")) {
            patches.put("theme.reduceMotion", arguments.getBoolean("reduceMotion"))
        }
        if (arguments.has("highContrast")) {
            patches.put("theme.highContrast", arguments.getBoolean("highContrast"))
        }
        if (arguments.has("fontScale")) patches.put("theme.fontScale", arguments.getDouble("fontScale"))
        if (arguments.has("animationScale")) {
            patches.put("theme.animationScale", arguments.getDouble("animationScale"))
        }
        arguments.optString("customSeed").takeIf { it.isNotBlank() }?.let {
            patches.put("theme.customSeed", it)
        }
        require(patches.length() > 0) { "未提供任何主题字段" }
        val patched = McpSettingsPatch.applyFromJson(settings.current(), patches)
        if (persist) settings.save(patched) else settings.preview(patched)
        return ok("主题已更新")
    }

    private suspend fun executeSetOverlay(arguments: JSONObject): JSONObject {
        val persist = arguments.optBoolean("persist", true)
        val patches = JSONObject()
        if (arguments.has("enabled")) patches.put("overlay.enabled", arguments.getBoolean("enabled"))
        arguments.optString("style").takeIf { it.isNotBlank() }?.let { patches.put("overlay.style", it) }
        arguments.optString("theme").takeIf { it.isNotBlank() }?.let { patches.put("overlay.theme", it) }
        listOf(
            "showBoxes",
            "persistBoxes",
            "showText",
            "showFps",
            "showConfidence",
            "showDiagnostics",
        ).forEach { key ->
            if (arguments.has(key)) patches.put("overlay.$key", arguments.getBoolean(key))
        }
        if (arguments.has("backgroundAlpha")) {
            patches.put("overlay.backgroundAlpha", arguments.getDouble("backgroundAlpha"))
        }
        if (arguments.has("scale")) patches.put("overlay.scale", arguments.getDouble("scale"))
        require(patches.length() > 0) { "未提供任何悬浮窗字段" }
        val patched = McpSettingsPatch.applyFromJson(settings.current(), patches)
        if (persist) settings.save(patched) else settings.preview(patched)
        return ok("悬浮窗已更新")
    }

    private suspend fun executeSetDeveloperEnabled(arguments: JSONObject): JSONObject {
        val enabled = arguments.getBoolean("enabled")
        val base = settings.current()
        settings.save(base.copy(developer = base.developer.copy(enabled = enabled)))
        AppLog.configure(enabled, base.developer.logLevel, base.developer.logRingCapacity)
        return ok(if (enabled) "开发者选项已开启" else "开发者选项已关闭")
    }

    private suspend fun executeSetDeveloperOptions(arguments: JSONObject): JSONObject {
        val base = settings.current()
        check(base.developer.enabled) { "请先开启开发者选项" }
        val persist = arguments.optBoolean("persist", true)
        val patches = JSONObject()
        arguments.optString("logLevel").takeIf { it.isNotBlank() }?.let {
            patches.put("developer.logLevel", it)
        }
        if (arguments.has("saveDebugFrames")) {
            patches.put("developer.saveDebugFrames", arguments.getBoolean("saveDebugFrames"))
        }
        if (arguments.has("showCoordinateGrid")) {
            patches.put("developer.showCoordinateGrid", arguments.getBoolean("showCoordinateGrid"))
        }
        if (arguments.has("frameRateLimit")) {
            patches.put("developer.frameRateLimit", arguments.getInt("frameRateLimit"))
        }
        if (arguments.has("logRingCapacity")) {
            patches.put("developer.logRingCapacity", arguments.getInt("logRingCapacity"))
        }
        if (arguments.has("enableStageTiming")) {
            patches.put("developer.enableStageTiming", arguments.getBoolean("enableStageTiming"))
        }
        if (arguments.has("enableMulticolorDiagnostic")) {
            patches.put(
                "developer.enableMulticolorDiagnostic",
                arguments.getBoolean("enableMulticolorDiagnostic"),
            )
        }
        if (arguments.has("enableFilterTrace")) {
            patches.put("developer.enableFilterTrace", arguments.getBoolean("enableFilterTrace"))
        }
        if (arguments.has("forceCaptureBackend")) {
            val raw = arguments.optString("forceCaptureBackend")
            patches.put(
                "developer.forceCaptureBackend",
                if (raw.isBlank()) JSONObject.NULL else raw,
            )
        }
        require(patches.length() > 0) { "未提供任何开发者字段" }
        val patched = McpSettingsPatch.applyFromJson(base, patches)
        if (persist) {
            settings.save(patched)
            AppLog.configure(
                patched.developer.enabled,
                patched.developer.logLevel,
                patched.developer.logRingCapacity,
            )
        } else {
            settings.preview(patched)
        }
        return ok("开发者选项已更新")
    }

    private suspend fun executeSetAutomationEnabled(arguments: JSONObject): JSONObject {
        val enabled = arguments.getBoolean("enabled")
        val accept = arguments.optBoolean("acceptDisclaimer", false)
        val base = settings.current()
        var auto = base.automation.copy(enabled = enabled)
        if (enabled && auto.disclaimerAcceptedVersion < AppConfig.DISCLAIMER_VERSION) {
            check(accept) {
                "开启自动操作须 acceptDisclaimer=true（当前免责版本 ${auto.disclaimerAcceptedVersion} < ${AppConfig.DISCLAIMER_VERSION}）"
            }
            auto = auto.copy(disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION)
        }
        settings.save(base.copy(automation = auto))
        return ok(if (enabled) "自动操作已开启" else "自动操作已关闭")
    }

    private suspend fun executeSetActiveAlgorithm(arguments: JSONObject): JSONObject {
        val id = arguments.requireString("algorithmId")
        val mode = enumValueOf<AlgorithmSelectionMode>(
            arguments.optString("mode").ifBlank { "MANUAL" },
        )
        val persist = arguments.optBoolean("persist", true)
        bindCatalog()
        if (mode == AlgorithmSelectionMode.MANUAL) {
            val selected = algorithmCatalog.selectInstalled(id)
            check(selected != null || id.startsWith("builtin")) {
                "无法选择算法 $id（未安装或不兼容）"
            }
        }
        applyConfig(
            {
                it.copy(
                    algorithm = it.algorithm.copy(
                        selectionMode = mode,
                        pinnedAlgorithmId = if (mode == AlgorithmSelectionMode.MANUAL) id else null,
                    ),
                )
            },
            persist,
        )
        return ok("算法选择已更新：$mode / $id")
    }

    private suspend fun executeExportDiagnostics(arguments: JSONObject): JSONObject {
        val logLimit = arguments.optInt("logLimit", 200).coerceIn(0, 800)
        val snap = settings.current()
        val mcpState = uiBridge.serverState.value
        val activation = visionEngine.currentActivation()
        val text = DiagnosticsExporter.buildReport(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            config = snap,
            mcp = McpDiagnosticsSnapshot(
                running = mcpState.running,
                port = mcpState.port.takeIf { it > 0 },
                lastError = mcpState.lastError,
            ),
            debugFrameCount = runCatching { debugFrames.list().size }.getOrDefault(0),
            algorithm = top.azek431.hzzs.core.logging.AlgorithmDiagnosticsSnapshot(
                algorithmId = activation.profile.algorithmId,
                version = activation.profile.version,
                generation = activation.generation,
                usingBuiltinFallback = activation.usingBuiltinFallback,
                loadError = activation.loadError,
                nativeAvailable = top.azek431.hzzs.nativevision.NativeVision.isAvailable,
                pendingCatalogId = null,
                analysisRunning = runtime.status.value.running,
            ),
            runtime = runtime.status.value,
            appContext = appContext,
            logLimit = logLimit,
        )
        return JSONObject().put("text", text)
    }

    private fun executeOpenSystemSettings(arguments: JSONObject): JSONObject {
        when (arguments.requireString("target")) {
            "overlay" -> SystemCapabilityAccess.openOverlayPermissionSettings(appContext)
            "accessibility" -> SystemCapabilityAccess.openAccessibilitySettings(appContext)
            "app_details" -> {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> error("未知 target")
        }
        return ok("已请求打开系统设置")
    }

    private suspend fun executeSaveProfile(arguments: JSONObject): JSONObject {
        val name = arguments.requireString("name")
        val description = arguments.optString("description")
        val meta = profileStore.save(name, description, settings.current())
        return ok("profile 已保存：${meta.name}").put("profile", meta.toJson())
    }

    private suspend fun executeLoadProfile(arguments: JSONObject): JSONObject {
        val name = arguments.requireString("name")
        val persist = arguments.optBoolean("persist", false)
        val loaded = profileStore.load(name)
        val config = loaded.hardenedForExternalIngest(settings.current())
        if (persist) settings.save(config) else settings.preview(config)
        runCatching {
            McpEventBus.append(
                McpEventBus.Type.CONFIG_CHANGE,
                JSONObject()
                    .put("source", "load_profile")
                    .put("name", name)
                    .put("persist", persist),
            )
        }
        return ok(if (persist) "profile 已永久应用：$name" else "profile 已预览：$name")
            .put("name", name)
    }

    private suspend fun executeListProfiles(): JSONObject {
        val metas = profileStore.list()
        return JSONObject()
            .put("profiles", JSONArray(metas.map { it.toJson() }))
            .put("count", metas.size)
    }

    private suspend fun executeDeleteProfile(arguments: JSONObject): JSONObject {
        val name = arguments.requireString("name")
        val deleted = profileStore.delete(name)
        return ok(if (deleted) "profile 已删除：$name" else "profile 不存在：$name")
            .put("deleted", deleted)
    }

    private suspend fun executeUpgradeAlgorithms(arguments: JSONObject): JSONObject {
        bindCatalog()
        val dryRun = arguments.optBoolean("dryRun", false)
        return if (dryRun) {
            val plan = algorithmCatalog.planUpgrades()
            ok(
                "dryRun：可升级 ${plan.candidates.size} / 跳过 ${plan.skipped.size} / 失败 ${plan.failed.size}",
            ).put("result", upgradePlanToJson(plan))
        } else {
            val result = algorithmCatalog.upgradeAll()
            ok(
                buildString {
                    append("已触发升级 ${result.upgraded.size} 个")
                    if (result.queued.isNotEmpty()) {
                        append("，队列 ${result.queued.size} 个（请 list_algorithms 跟踪后再次 upgrade）")
                    }
                    append("（异步下载/验签/安装）")
                },
            ).put("result", upgradeResultToJson(result))
        }
    }

    private suspend fun executeSetMcpEnabled(arguments: JSONObject): JSONObject {
        val enabled = arguments.getBoolean("enabled")
        applyConfig({ it.copy(mcp = it.mcp.copy(enabled = enabled)) }, persist = true)
        return ok(
            if (enabled) {
                "MCP 已请求启用（保存后前台服务将启动）"
            } else {
                "MCP 已请求关闭（当前连接可能即将断开）"
            },
        ).put("enabled", enabled)
    }

    private suspend fun executeSetMcpPermissionLevel(arguments: JSONObject): JSONObject {
        val level = enumValueOf<McpPermissionLevel>(arguments.requireString("permissionLevel"))
        applyConfig({ it.copy(mcp = it.mcp.copy(permissionLevel = level)) }, persist = true)
        return ok("MCP 权限级已设为 ${level.name}").put("permissionLevel", level.name)
    }

    private suspend fun executeSetMcpAuth(arguments: JSONObject): JSONObject {
        val requireAuth = arguments.getBoolean("requireAuth")
        val rotate = arguments.optBoolean("rotateToken", false)
        val snap = settings.current()
        var token = snap.mcp.authToken
        if (requireAuth && (token.isBlank() || rotate)) {
            token = generateMcpAuthToken()
        }
        applyConfig(
            {
                it.copy(
                    mcp = it.mcp.copy(
                        requireAuth = requireAuth,
                        authToken = if (requireAuth) token else it.mcp.authToken,
                    ),
                )
            },
            persist = true,
        )
        return ok(
            buildString {
                append(if (requireAuth) "已开启 Bearer 鉴权" else "已关闭 Bearer 鉴权")
                if (requireAuth && rotate) append("；令牌已轮换，请重新复制导入 JSON")
            },
        )
            .put("requireAuth", requireAuth)
            .put("tokenConfigured", requireAuth && token.isNotBlank())
            .put("tokenRotated", requireAuth && rotate)
    }

    private suspend fun executeSetMcpToolPolicy(arguments: JSONObject): JSONObject {
        val toolName = arguments.requireString("tool")
        val policy = enumValueOf<McpToolPolicy>(arguments.requireString("policy"))
        check(McpToolCatalog.tool(toolName) != null) {
            "未知工具：$toolName（可用 list_mcp_tools 查看准确名）"
        }
        applyConfig(
            { cfg ->
                val nextPolicies = cfg.mcp.toolPolicies.toMutableMap()
                if (policy == McpToolPolicy.DEFAULT) {
                    nextPolicies.remove(toolName)
                } else {
                    nextPolicies[toolName] = policy
                }
                cfg.copy(mcp = cfg.mcp.copy(toolPolicies = nextPolicies))
            },
            persist = true,
        )
        return ok("工具 $toolName 策略已设为 ${policy.name}")
            .put("tool", toolName)
            .put("policy", policy.name)
            .put("titleZh", McpToolLabels.titleZh(toolName))
    }

    private suspend fun bindCatalog() {
        val snap = settings.current()
        algorithmCatalog.bindSettings(
            algorithm = snap.algorithm,
            sourcePreference = snap.update.sourcePreference,
            selectedScene = snap.selectedScene,
            analysisRunning = runtime.status.value.running,
            wifiOnly = snap.update.wifiOnly,
        )
    }

    private suspend fun applyConfig(transform: (AppConfig) -> AppConfig, persist: Boolean) {
        val next = transform(settings.current())
        if (persist) settings.save(next) else settings.preview(next)
    }

    private suspend fun ingestMcpConfig(rawJson: String): AppConfig {
        val baseline = settings.current()
        return settings.importJson(rawJson).hardenedForExternalIngest(baseline)
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
                summarize(descriptor.name, arguments),
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

    private suspend fun ensureDeveloper() {
        check(settings.current().developer.enabled) {
            "请先开启开发者选项（set_developer_enabled 或关于页连点版本号）"
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

    /**
     * 一键诊断聚合：默认返回 status/latest/algorithm/gates/permissions/mcp/version。
     * [includeCsv] 为空时返回全部；否则按逗号分隔子集裁剪。
     */
    private suspend fun inspectJson(includeCsv: String?): JSONObject {
        val include = includeCsv?.split(',', ' ', ';')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        fun want(name: String) = include.isEmpty() || name in include
        val status = runtime.status.value
        val latest = runtime.latestResult.value
        val cfg = settings.current()
        return JSONObject().apply {
            if (want("status")) put("status", status.toJson())
            if (want("latest")) {
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
            }
            if (want("algorithm")) put("algorithm", activeAlgorithmJson())
            if (want("gates")) put("automationGates", automationGatesJson())
            if (want("permissions")) put("permissions", permissionsJson())
            if (want("mcp")) {
                val mcp = cfg.mcp
                val server = uiBridge.serverState.value
                put(
                    "mcp",
                    JSONObject().apply {
                        put("enabled", mcp.enabled)
                        put("running", server.running)
                        put("port", if (server.running && server.port > 0) server.port else mcp.port)
                        put("configuredPort", mcp.port)
                        put("permissionLevel", mcp.permissionLevel.name)
                        put("requireAuth", mcp.requireAuth)
                        put("tokenConfigured", mcp.authToken.isNotBlank())
                        put("toolPolicyOverrides", mcp.toolPolicies.size)
                        put("allowDebugFrames", mcp.allowDebugFrames)
                        put("accessLogEnabled", mcp.accessLogEnabled)
                    },
                )
            }
            if (want("version")) {
                val pkg = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                }.getOrNull()
                put("version", versionJson(cfg, pkg))
            }
        }
    }

    private suspend fun versionJson(): JSONObject {
        val pkg = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        return versionJson(settings.current(), pkg)
    }

    /** 构建 version 字段：应用版本 + 配置 schema + 设备信息。 */
    private fun versionJson(
        cfg: top.azek431.hzzs.core.model.AppConfig,
        pkg: android.content.pm.PackageInfo?,
    ): JSONObject = JSONObject().apply {
        put("versionName", pkg?.versionName ?: BuildConfig.VERSION_NAME)
        put(
            "versionCode",
            if (Build.VERSION.SDK_INT >= 28) {
                pkg?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()
            } else {
                @Suppress("DEPRECATION")
                (pkg?.versionCode?.toLong() ?: BuildConfig.VERSION_CODE.toLong())
            },
        )
        put("schema", cfg.schemaVersion)
        put("buildType", if (BuildConfig.DEBUG) "debug" else "release")
        put("manufacturer", Build.MANUFACTURER ?: "")
        put("sdkInt", Build.VERSION.SDK_INT)
        put("abi", runCatching { Build.SUPPORTED_ABIS.joinToString() }.getOrDefault(""))
    }

    private suspend fun checkUpdateJson(): JSONObject {
        val cfg = settings.current()
        val update = cfg.update
        // 严格遵循用户 wifiOnly，不提供 force 绕过
        if (update.wifiOnly && !isOnUnmeteredNetwork()) {
            return JSONObject().put("error", "当前设置要求仅在 Wi‑Fi 下检查更新").put("skipped", true)
        }
        val repo = updateRepository
        return runCatching {
            val result = repo.check(
                beta = update.channel == top.azek431.hzzs.core.model.UpdateChannel.BETA,
                sourcePreference = update.sourcePreference,
            )
            val installed = installedVersionCode()
            JSONObject()
                .put("hasUpdate", result.manifest.versionCode > installed)
                .put("versionName", result.manifest.versionName)
                .put("versionCode", result.manifest.versionCode)
                .put("source", result.source.name)
                .put("releaseNotes", result.manifest.releaseNotes)
                .put("isIgnored", update.ignoredVersionCode == result.manifest.versionCode)
                .put("installedVersionCode", installed)
        }.getOrElse { error ->
            JSONObject().put("error", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun metricsJson(): JSONObject {
        val jvmRuntime = Runtime.getRuntime()
        val status = this.runtime.status.value
        val uptime = android.os.SystemClock.elapsedRealtime() - processStartedElapsedRealtimeMs
        return JSONObject().apply {
            put(
                "memory",
                JSONObject()
                    .put("totalBytes", jvmRuntime.totalMemory())
                    .put("freeBytes", jvmRuntime.freeMemory())
                    .put("usedBytes", jvmRuntime.totalMemory() - jvmRuntime.freeMemory())
                    .put("maxBytes", jvmRuntime.maxMemory()),
            )
            put(
                "frame",
                JSONObject()
                    .put("fps", status.fps.toDouble())
                    .put("processingMs", status.processingMs.toDouble())
                    .put("obstacleCount", status.obstacleCount)
                    // 滑动窗口尚未独立记录器；先暴露即时值占位，避免客户端假定字段缺失
                    .put("last30Fps", JSONArray().put(status.fps.toDouble()))
                    .put("last30ProcessingMs", JSONArray().put(status.processingMs.toDouble())),
            )
            put("uptimeMs", uptime.coerceAtLeast(0L))
            put("processStartedElapsedRealtimeMs", processStartedElapsedRealtimeMs)
        }
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
            put("trustAnchorsConfigured", catalog.trustAnchorsConfigured)
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
            put("trustAnchorsConfigured", state.trustAnchorsConfigured)
            put("analysisRunning", state.analysisRunning)
            put("active", state.active?.toJson() ?: JSONObject.NULL)
            put("pendingActivation", state.pendingActivation?.toJson() ?: JSONObject.NULL)
            put("installed", JSONArray(state.installed.map { it.toJson() }))
            put("remote", JSONArray(state.remote.map { it.toJson() }))
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

    private fun upgradePlanToJson(plan: AlgorithmCatalogPure.UpgradePlan): JSONObject =
        JSONObject()
            .put("candidates", JSONArray(plan.candidates))
            .put("upgraded", JSONArray()) // dryRun 兼容字段
            .put("queued", JSONArray())
            .put("skipped", JSONArray(plan.skipped))
            .put(
                "failed",
                JSONArray().apply {
                    plan.failed.forEach { (id, error) ->
                        put(JSONObject().put("id", id).put("error", error))
                    }
                },
            )

    private fun upgradeResultToJson(result: AlgorithmCatalogPure.UpgradeResult): JSONObject =
        JSONObject()
            .put("upgraded", JSONArray(result.upgraded))
            .put("queued", JSONArray(result.queued))
            .put("skipped", JSONArray(result.skipped))
            .put(
                "failed",
                JSONArray().apply {
                    result.failed.forEach { (id, error) ->
                        put(JSONObject().put("id", id).put("error", error))
                    }
                },
            )

    private fun installedVersionCode(): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun McpProfileStore.Meta.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("sizeBytes", sizeBytes)
        .put("schemaVersion", schemaVersion)

    private fun phaseName(phase: AlgorithmCatalogPhase): String = when (phase) {
        is AlgorithmCatalogPhase.Idle -> "Idle"
        is AlgorithmCatalogPhase.Loading -> "Loading"
        is AlgorithmCatalogPhase.Empty -> "Empty"
        is AlgorithmCatalogPhase.OfflineWithCache -> "OfflineWithCache"
        is AlgorithmCatalogPhase.MirrorFallback -> "MirrorFallback"
        is AlgorithmCatalogPhase.SecurityWarning -> "SecurityWarning"
        is AlgorithmCatalogPhase.Downloading -> "Downloading"
        is AlgorithmCatalogPhase.Verifying -> "Verifying"
        is AlgorithmCatalogPhase.PendingActivation -> "PendingActivation"
        is AlgorithmCatalogPhase.Error -> "Error"
        is AlgorithmCatalogPhase.Incompatible -> "Incompatible"
    }

    private fun AlgorithmPackageInfo.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("versionName", versionName)
        .put("versionCode", versionCode)
        .put("channel", channel.name)
        .put("origin", origin.name)
        .put("signature", signature.name)
        .put("isCompatible", isCompatible)
        .put("isInstalled", isInstalled)
        .put("isBuiltin", isBuiltin)
        .put("summary", summary)
        .put("author", author ?: JSONObject.NULL)
        .put("supportedScenes", JSONArray(supportedScenes.map { it.name }))

    private fun summarize(tool: String, arguments: JSONObject): String = when (tool) {
        "save_settings" -> "AI 请求永久保存应用设置"
        "preview_settings" -> "AI 请求临时预览应用设置"
        "patch_settings" -> "AI 请求局部修改设置"
        "start_analysis" -> "AI 请求启动屏幕分析"
        "stop_analysis" -> "AI 请求停止屏幕分析"
        "restart_analysis" -> "AI 请求重启屏幕分析"
        "cancel_actions" -> "AI 请求取消在飞自动操作"
        "navigate" -> "AI 请求打开应用页面：${arguments.optString("route")}"
        "set_overlay_visible" -> "AI 请求更改悬浮窗显示状态"
        "set_capture_backend" -> "AI 请求切换截图后端：${arguments.optString("backend")}"
        "set_gesture_backend" -> "AI 请求切换手势后端：${arguments.optString("backend")}"
        "clear_debug_frames" -> "AI 请求清除本机调试帧"
        "get_debug_frame" -> "AI 请求读取调试帧图像：${arguments.optString("name")}"
        "capture_debug_frame" -> "AI 请求强制保存下一帧调试截图"
        "save_profile" -> "AI 请求保存命名配置：${arguments.optString("name")}"
        "load_profile" ->
            "AI 请求${if (arguments.optBoolean("persist")) "永久应用" else "预览"}配置 profile：${arguments.optString("name")}"
        "delete_profile" -> "AI 请求删除配置 profile：${arguments.optString("name")}"
        "upgrade_algorithms" ->
            "AI 请求${if (arguments.optBoolean("dryRun")) "预览" else "执行"}一键升级算法包"
        "set_mcp_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "启用" else "关闭"} MCP 服务"
        "set_mcp_permission_level" ->
            "AI 请求修改 MCP 权限级：${arguments.optString("permissionLevel")}"
        "set_mcp_auth" -> "AI 请求修改 MCP Bearer 鉴权"
        "set_mcp_tool_policy" ->
            "AI 请求设置工具策略：${arguments.optString("tool")}=${arguments.optString("policy")}"
        "clear_mcp_access_log" -> "AI 请求清空 MCP 访问日志"
        "set_scene" -> "AI 请求切换赛季：${arguments.optString("scene")}"
        "set_obstacle_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "启用" else "禁用"}障碍 ${arguments.optString("kind")}"
        "set_threshold" -> "AI 请求修改阈值 ${arguments.optString("key")}"
        "set_theme" -> "AI 请求修改主题"
        "set_overlay" -> "AI 请求修改悬浮窗"
        "set_developer_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "开启" else "关闭"}开发者选项"
        "set_developer_options" -> "AI 请求修改开发者选项"
        "set_automation_enabled" ->
            "AI 请求${if (arguments.optBoolean("enabled")) "开启" else "关闭"}自动操作"
        "set_active_algorithm" -> "AI 请求切换算法：${arguments.optString("algorithmId")}"
        "refresh_algorithm_catalog" -> "AI 请求刷新算法目录"
        "download_algorithm" -> "AI 请求下载算法：${arguments.optString("algorithmId")}"
        "clear_logs" -> "AI 请求清空内存日志"
        "open_system_settings" -> "AI 请求打开系统设置：${arguments.optString("target")}"
        else -> "AI 请求执行：$tool（${arguments.length()} 个参数）"
    }
}

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

private fun Any?.asObject(): JSONObject = JSONObject().put("value", this)
