package top.azek431.hzzs.core.algorithm

import org.json.JSONObject

import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AlgorithmConfig
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.domain.vision.AlgorithmActivation
import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile
import top.azek431.hzzs.domain.vision.VisionEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按 [AlgorithmConfig] 解析并激活算法 profile。
 *
 * 线程：configure 须在分析安全点（start 前 / 未运行时 save 后）。
 * 分析运行中仅记录 pending，不半热切换。
 * 同步写入 [AlgorithmPipelineTrace] 供开发者流程页展示。
 */
@Singleton
class AlgorithmActivationCoordinator @Inject constructor(
    private val engine: VisionEngine,
    private val store: InstalledAlgorithmStore,
) {
    private val analysisRunning = AtomicBoolean(false)
    private val pendingCatalogId = AtomicReference<String?>(null)

    fun setAnalysisRunning(running: Boolean) {
        analysisRunning.set(running)
        if (!running) {
            // stop 后不自动 activate；下次 start / onConfigCommitted 再 resolve
        }
    }

    fun isAnalysisRunning(): Boolean = analysisRunning.get()

    fun pendingCatalogId(): String? = pendingCatalogId.get()

    /**
     * 配置已提交（保存）后调用。运行中只 pending。
     */
    fun onConfigCommitted(config: AlgorithmConfig, selectedScene: SceneId): Result<AlgorithmActivation> {
        val target = resolveCatalogId(config, selectedScene)
        AlgorithmPipelineTrace.setContext(
            catalogId = target,
            selectionMode = config.selectionMode.name,
            selectedScene = selectedScene.name,
        )
        if (analysisRunning.get()) {
            pendingCatalogId.set(target)
            AlgorithmPipelineTrace.markWarning(
                "resolve",
                "分析中，待下次 start 激活 catalog=$target mode=${config.selectionMode.name}",
            )
            AppLog.i(
                "algorithm",
                "config committed while analyzing; pending=$target mode=${config.selectionMode.name} " +
                    "scene=${selectedScene.name} pinned=${config.pinnedAlgorithmId ?: "-"}",
            )
            return Result.success(engine.currentActivation())
        }
        AppLog.i(
            "algorithm",
            "config committed; activating=$target mode=${config.selectionMode.name} " +
                "scene=${selectedScene.name} pinned=${config.pinnedAlgorithmId ?: "-"}",
        )
        return activateCatalog(target, selectionMode = config.selectionMode.name, scene = selectedScene.name)
    }

    /**
     * 启动分析前强制 resolve：消费 pending 或按 config 解析。
     */
    fun ensureConfigured(config: AlgorithmConfig, selectedScene: SceneId): Result<AlgorithmActivation> {
        val pending = pendingCatalogId.getAndSet(null)
        val target = pending ?: resolveCatalogId(config, selectedScene)
        AlgorithmPipelineTrace.setContext(
            catalogId = target,
            selectionMode = config.selectionMode.name,
            selectedScene = selectedScene.name,
        )
        AppLog.i(
            "algorithm",
            "ensureConfigured target=$target pendingWas=${pending ?: "-"} " +
                "mode=${config.selectionMode.name} scene=${selectedScene.name} " +
                "pinned=${config.pinnedAlgorithmId ?: "-"}",
        )
        return activateCatalog(target, selectionMode = config.selectionMode.name, scene = selectedScene.name)
    }

    fun activateCatalog(catalogId: String): Result<AlgorithmActivation> =
        activateCatalog(catalogId, selectionMode = null, scene = null)

    private fun activateCatalog(
        catalogId: String,
        selectionMode: String?,
        scene: String?,
    ): Result<AlgorithmActivation> {
        AlgorithmPipelineTrace.beginActivationAttempt()
        val runtimeId = AlgorithmIds.runtimeIdForCatalog(catalogId)
        AlgorithmPipelineTrace.setContext(
            catalogId = catalogId,
            selectionMode = selectionMode,
            selectedScene = scene,
        )
        AlgorithmPipelineTrace.markRunning("resolve", "catalogId=$catalogId runtimeId=$runtimeId")
        AppLog.i("algorithm", "resolve catalogId=$catalogId runtimeId=$runtimeId")

        val profile = AlgorithmRuntimeProfile.forRuntimeId(runtimeId)
        val profileSource = when {
            AlgorithmIds.isBuiltinCatalog(catalogId) -> when (runtimeId) {
                AlgorithmIds.NATIVE_VISION_RUNTIME_ID -> "builtin-native-vision"
                else -> "builtin"
            }
            else -> {
                val installed = store.get(catalogId)
                if (installed != null) "installed" else "missing→builtin"
            }
        }

        val finalProfile = if (profileSource == "installed") {
            store.get(catalogId)?.profile ?: profile
        } else {
            profile
        }

        AlgorithmPipelineTrace.markRunning(
            "validate",
            "id=${finalProfile.algorithmId} ver=${finalProfile.version} backend=${finalProfile.backendId}",
        )
        AlgorithmPipelineTrace.markRunning("activate", "source=$profileSource")
        AlgorithmPipelineTrace.markRunning(
            "native",
            if (top.azek431.hzzs.nativevision.NativeVision.isAvailable) {
                "JNI available"
            } else {
                "JNI unavailable"
            },
        )

        return engine.configureAlgorithm(finalProfile).also { result ->
            result.onSuccess { activation ->
                pendingCatalogId.set(null)
                val sceneHint = scene?.let { " scene=$it" }.orEmpty()
                AppLog.i(
                    "algorithm",
                    "activated id=${activation.profile.algorithmId} ver=${activation.profile.version} " +
                        "gen=${activation.generation} builtinFallback=${activation.usingBuiltinFallback} " +
                        "source=$profileSource schema=${activation.profile.schemaVersion}$sceneHint",
                )
                activation.loadError?.let { AppLog.w("algorithm", "activation loadError=$it") }
                AlgorithmPipelineTrace.markSuccess(
                    "validate",
                    "ok id=${activation.profile.algorithmId} schema=${activation.profile.schemaVersion}",
                )
                AlgorithmPipelineTrace.markSuccess(
                    "activate",
                    "gen=${activation.generation} fallback=${activation.usingBuiltinFallback}",
                )
                runCatching {
                    top.azek431.hzzs.mcp.McpEventBus.append(
                        top.azek431.hzzs.mcp.McpEventBus.Type.ALGORITHM_SWITCH,
                        JSONObject()
                            .put("id", activation.profile.algorithmId)
                            .put("version", activation.profile.version)
                            .put("generation", activation.generation)
                            .put("source", profileSource),
                    )
                }
                if (top.azek431.hzzs.nativevision.NativeVision.isAvailable) {
                    AlgorithmPipelineTrace.markSuccess(
                        "native",
                        "configured gen=${activation.generation}",
                    )
                } else {
                    AlgorithmPipelineTrace.markWarning(
                        "native",
                        "库不可用，仅 Kotlin 激活",
                    )
                }
                AlgorithmPipelineTrace.markSuccess(
                    "ready",
                    "可分析 id=${activation.profile.algorithmId} v${activation.profile.version}",
                )
            }.onFailure { error ->
                AppLog.e(
                    "algorithm",
                    "activate failed catalogId=$catalogId source=$profileSource: " +
                        "${error.message ?: error.javaClass.simpleName}",
                    error,
                )
                AlgorithmPipelineTrace.markFailed(
                    "ready",
                    error.message?.take(200) ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun resolveCatalogId(config: AlgorithmConfig, selectedScene: SceneId): String {
        return when (config.selectionMode) {
            AlgorithmSelectionMode.MANUAL -> {
                val pinned = config.pinnedAlgorithmId
                if (pinned != null &&
                    (AlgorithmIds.isBuiltinCatalog(pinned) || store.get(pinned) != null)
                ) {
                    pinned // 保留原始 catalogId（可能是 builtin-hzzs-native-vision-*）
                } else {
                    AlgorithmIds.BUILTIN_CATALOG_ID
                }
            }
            AlgorithmSelectionMode.AUTO -> {
                val installed = store.listInstalled()
                    .filter { selectedScene in it.supportedScenes }
                    .maxWithOrNull(
                        compareBy<InstalledAlgorithmStore.InstalledAlgorithmRecord> { it.versionCode }
                            .thenBy { it.installedAtEpochMs },
                    )
                when {
                    installed != null -> installed.catalogId
                    // AUTO 默认基础引擎；Native Vision 仅 MANUAL 钉选用。
                    else -> AlgorithmIds.BUILTIN_CATALOG_ID
                }
            }
        }
    }
}
