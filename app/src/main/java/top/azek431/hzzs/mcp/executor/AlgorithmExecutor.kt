package top.azek431.hzzs.mcp.executor

import org.json.JSONObject
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogController
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.domain.vision.VisionEngine
import javax.inject.Inject
import top.azek431.hzzs.mcp.requireString

/**
 * 算法包管理执行器：目录 / 激活 / 下载 / 升级。
 *
 * 写操作（set_active / refresh / download / upgrade）统一先 [bindCatalog]，
 * 让 [AlgorithmCatalogController] 与当前配置绑定。
 */
class AlgorithmExecutor @Inject constructor(
    private val algorithmCatalog: AlgorithmCatalogController,
    private val visionEngine: VisionEngine,
    private val settings: SettingsRepository,
    private val runtime: VisionRuntimeController,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "list_algorithms",
        "get_active_algorithm",
        "get_algorithm_pipeline",
        "set_active_algorithm",
        "refresh_algorithm_catalog",
        "download_algorithm",
        "upgrade_algorithms",
    )

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "list_algorithms" -> algorithmCatalogJson()
        "get_active_algorithm" -> activeAlgorithmJson()
        "get_algorithm_pipeline" -> algorithmPipelineJson()
        "set_active_algorithm" -> executeSetActiveAlgorithm(arguments)
        "refresh_algorithm_catalog" -> {
            bindCatalog()
            algorithmCatalog.refreshCatalog(force = true)
            ok("已请求刷新算法目录")
        }
        "download_algorithm" -> {
            val id = arguments.requireString("algorithmId")
            bindCatalog()
            algorithmCatalog.download(id)
            ok("已请求下载/安装算法 $id（异步）")
        }
        "upgrade_algorithms" -> executeUpgradeAlgorithms(arguments)
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private suspend fun executeGetAlgorithmCatalogEntry(arguments: JSONObject): JSONObject {
        val id = arguments.requireString("algorithmId")
        bindCatalog()
        val state = algorithmCatalog.state.value
        val entry = state.installed.firstOrNull { it.id == id }
            ?: state.remote.firstOrNull { it.id == id }
        return JSONObject()
            .put("found", true)
            .put("location", if (state.installed.any { it.id == id }) "installed" else "remote")
            .put("entry", entry?.toJson() ?: JSONObject.NULL)
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

    private suspend fun applyConfig(transform: (top.azek431.hzzs.core.model.AppConfig) -> top.azek431.hzzs.core.model.AppConfig, persist: Boolean) {
        val next = transform(settings.current())
        if (persist) settings.save(next) else settings.preview(next)
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
            put("installed", org.json.JSONArray(state.installed.map { it.toJson() }))
            put("remote", org.json.JSONArray(state.remote.map { it.toJson() }))
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
        val snap = top.azek431.hzzs.core.algorithm.AlgorithmPipelineTrace.snapshot()
        return JSONObject().apply {
            put("revision", snap.revision)
            put("catalogId", snap.catalogId ?: JSONObject.NULL)
            put("selectionMode", snap.selectionMode ?: JSONObject.NULL)
            put("selectedScene", snap.selectedScene ?: JSONObject.NULL)
            put(
                "stages",
                org.json.JSONArray().apply {
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

    private fun phaseName(phase: top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase): String =
        when (phase) {
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Idle -> "Idle"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Loading -> "Loading"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Empty -> "Empty"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.OfflineWithCache -> "OfflineWithCache"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.MirrorFallback -> "MirrorFallback"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.SecurityWarning -> "SecurityWarning"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Downloading -> "Downloading"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Verifying -> "Verifying"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.PendingActivation -> "PendingActivation"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Error -> "Error"
            is top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase.Incompatible -> "Incompatible"
        }

    private fun upgradePlanToJson(plan: top.azek431.hzzs.core.algorithm.logic.AlgorithmCatalogPure.UpgradePlan): JSONObject =
        JSONObject()
            .put("candidates", org.json.JSONArray(plan.candidates))
            .put("upgraded", org.json.JSONArray())
            .put("queued", org.json.JSONArray())
            .put("skipped", org.json.JSONArray(plan.skipped))
            .put(
                "failed",
                org.json.JSONArray().apply {
                    plan.failed.forEach { (id, error) ->
                        put(JSONObject().put("id", id).put("error", error))
                    }
                },
            )

    private fun upgradeResultToJson(result: top.azek431.hzzs.core.algorithm.logic.AlgorithmCatalogPure.UpgradeResult): JSONObject =
        JSONObject()
            .put("upgraded", org.json.JSONArray(result.upgraded))
            .put("queued", org.json.JSONArray(result.queued))
            .put("skipped", org.json.JSONArray(result.skipped))
            .put(
                "failed",
                org.json.JSONArray().apply {
                    result.failed.forEach { (id, error) ->
                        put(JSONObject().put("id", id).put("error", error))
                    }
                },
            )
}

private fun top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo.toJson(): JSONObject =
    JSONObject()
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
        .put("supportedScenes", org.json.JSONArray(supportedScenes.map { it.name }))
