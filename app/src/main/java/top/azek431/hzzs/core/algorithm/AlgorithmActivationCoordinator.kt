package top.azek431.hzzs.core.algorithm

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
        if (analysisRunning.get()) {
            pendingCatalogId.set(target)
            return Result.success(engine.currentActivation())
        }
        return activateCatalog(target)
    }

    /**
     * 启动分析前强制 resolve：消费 pending 或按 config 解析。
     */
    fun ensureConfigured(config: AlgorithmConfig, selectedScene: SceneId): Result<AlgorithmActivation> {
        val pending = pendingCatalogId.getAndSet(null)
        val target = pending ?: resolveCatalogId(config, selectedScene)
        return activateCatalog(target)
    }

    fun activateCatalog(catalogId: String): Result<AlgorithmActivation> {
        val profile = store.getProfile(catalogId) ?: AlgorithmRuntimeProfile.builtin()
        return engine.configureAlgorithm(profile).also {
            if (it.isSuccess) pendingCatalogId.set(null)
        }
    }

    fun resolveCatalogId(config: AlgorithmConfig, selectedScene: SceneId): String {
        return when (config.selectionMode) {
            AlgorithmSelectionMode.MANUAL -> {
                val pinned = config.pinnedAlgorithmId
                if (pinned != null &&
                    (AlgorithmIds.isBuiltinCatalog(pinned) || store.get(pinned) != null)
                ) {
                    if (AlgorithmIds.isBuiltinCatalog(pinned)) AlgorithmIds.BUILTIN_CATALOG_ID else pinned
                } else {
                    AlgorithmIds.BUILTIN_CATALOG_ID
                }
            }
            AlgorithmSelectionMode.AUTO -> {
                store.listInstalled()
                    .filter { selectedScene in it.supportedScenes }
                    .maxWithOrNull(
                        compareBy<InstalledAlgorithmStore.InstalledAlgorithmRecord> { it.versionCode }
                            .thenBy { it.installedAtEpochMs },
                    )
                    ?.catalogId
                    ?: AlgorithmIds.BUILTIN_CATALOG_ID
            }
        }
    }
}
