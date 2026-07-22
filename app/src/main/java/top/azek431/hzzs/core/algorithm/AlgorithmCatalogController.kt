package top.azek431.hzzs.core.algorithm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmConfig
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.update.UpdateSourceId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 算法目录与下载任务的 [StateFlow] 唯一所有者。
 *
 * - 目录：HTTPS 拉 `algorithms/{channel}.json`（Gitee/GitHub，尊重 sourcePreference）
 * - 下载：HTTPS 资产 + size/sha256 + [AlgorithmPackVerifier] + [InstalledAlgorithmStore]
 * - 官方公钥未配置时：目录可展示，但下载安装 fail-closed
 *
 * 线程：状态更新在 Main；网络 IO 切 [Dispatchers.IO]。
 */
@Singleton
class AlgorithmCatalogController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val network: AlgorithmNetworkClient,
    private val store: InstalledAlgorithmStore,
    private val activation: AlgorithmActivationCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(seedState())
    /** UI 只读状态。 */
    val state: StateFlow<AlgorithmCatalogState> = mutableState.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var analysisRunning: Boolean = false
    /** 来自设置草稿的算法配置（未保存也会驱动列表解析）。 */
    private var draftConfig: AlgorithmConfig = AlgorithmConfig()
    private var sourcePreference: UpdateSourcePreference = UpdateSourcePreference.AUTO
    private var selectedScene: SceneId = AppConfig.DEFAULT_SELECTED_SCENE
    private var remoteEntries: List<AlgorithmNetworkClient.CatalogRemoteEntry> = emptyList()
    private var wifiOnly: Boolean = true

    /**
     * 绑定设置页草稿上下文，重算 active / pending。
     * 下载与检查不写入 [AlgorithmConfig]；钉选 ID 仍由 ViewModel 写回草稿。
     */
    fun bindSettings(
        algorithm: AlgorithmConfig,
        sourcePreference: UpdateSourcePreference,
        selectedScene: SceneId,
        analysisRunning: Boolean = this.analysisRunning,
        wifiOnly: Boolean = this.wifiOnly,
    ) {
        this.draftConfig = algorithm
        this.sourcePreference = sourcePreference
        this.selectedScene = selectedScene
        this.analysisRunning = analysisRunning
        this.wifiOnly = wifiOnly
        mutableState.update { current ->
            val installed = mergeDiskInstalled(current.installed)
            current.copy(
                selectionMode = algorithm.selectionMode,
                channel = algorithm.channel,
                sourcePreference = sourcePreference,
                analysisRunning = analysisRunning,
                installed = sortInstalled(installed, resolveActive(installed, algorithm, selectedScene)?.id),
                active = resolveActive(installed, algorithm, selectedScene),
                pendingActivation = current.pendingActivation?.takeIf {
                    algorithm.selectionMode == AlgorithmSelectionMode.MANUAL &&
                        algorithm.pinnedAlgorithmId == it.id
                },
            ).recomputePhase()
        }
    }

    /** 同步视觉分析是否在跑；影响“选择后立即启用 vs pending”。 */
    fun setAnalysisRunning(running: Boolean) {
        analysisRunning = running
        activation.setAnalysisRunning(running)
        mutableState.update { it.copy(analysisRunning = running) }
    }

    /**
     * 刷新远端目录。
     *
     * @param force 预留给手动刷新标记
     */
    fun refreshCatalog(force: Boolean = false) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.Loading,
                    message = "正在检查算法目录…",
                )
            }
            val result = runCatching {
                network.fetchCatalog(draftConfig.channel, sourcePreference)
            }
            result.onSuccess { catalog ->
                remoteEntries = catalog.remote
                val remoteInfos = catalog.remote.map { it.info }
                mutableState.update { current ->
                    val mergedInstalled = mergeDiskInstalled(current.installed)
                    val active = resolveActive(mergedInstalled, draftConfig, selectedScene)
                    current.copy(
                        phase = when {
                            remoteInfos.isEmpty() && mergedInstalled.isEmpty() ->
                                AlgorithmCatalogPhase.Empty
                            catalog.usedFallback -> AlgorithmCatalogPhase.MirrorFallback(
                                reason = catalog.fallbackReason.orEmpty(),
                                activeSource = catalog.activeSource,
                            )
                            else -> AlgorithmCatalogPhase.Idle
                        },
                        remote = sortRemote(remoteInfos, selectedScene),
                        installed = sortInstalled(mergedInstalled, active?.id),
                        active = active,
                        previousRollback = previousOf(mergedInstalled, active?.id),
                        activeSource = catalog.activeSource,
                        lastMirrorReason = catalog.fallbackReason,
                        lastCheckedAtEpochMs = System.currentTimeMillis(),
                        message = catalog.message,
                    ).recomputePhase()
                }
                if (force && draftConfig.autoDownload && draftConfig.selectionMode == AlgorithmSelectionMode.AUTO) {
                    maybeAutoDownloadLatest()
                }
            }.onFailure { error ->
                mutableState.update { current ->
                    val hasCache = current.installed.isNotEmpty() || current.remote.isNotEmpty()
                    current.copy(
                        phase = if (hasCache) {
                            AlgorithmCatalogPhase.OfflineWithCache(
                                message = error.message ?: "网络不可用，已显示本地算法",
                            )
                        } else {
                            AlgorithmCatalogPhase.Error(
                                message = error.message ?: "检查算法目录失败",
                            )
                        },
                        message = error.message,
                        lastCheckedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /**
     * 下载并校验安装算法包。
     *
     * fail-closed：不兼容 / 无信任锚 / 验签失败直接进入对应相位。
     */
    fun download(algorithmId: String) {
        val entry = remoteEntries.find { it.info.id == algorithmId }
        val target = entry?.info
            ?: mutableState.value.remote.find { it.id == algorithmId }
            ?: mutableState.value.installed.find { it.id == algorithmId }
            ?: return
        if (!target.isCompatible) {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.Incompatible("该算法与当前应用版本不兼容"),
                    message = "不兼容：${target.name}",
                )
            }
            return
        }
        if (entry == null) {
            // 已安装项：仅选择，不重复下载
            selectInstalled(algorithmId)
            return
        }
        if (!AlgorithmTrustAnchors.hasOfficialAnchors()) {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.SecurityWarning(
                        "客户端尚未配置官方算法公钥，拒绝下载安装",
                    ),
                    message = "安全警告：未配置信任锚",
                )
            }
            return
        }
        if (downloadJob?.isActive == true) {
            mutableState.update { it.copy(message = "已有下载任务进行中") }
            return
        }
        val source = mutableState.value.activeSource ?: UpdateSourceId.GITEE
        downloadJob = scope.launch {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.Downloading(algorithmId, 0f),
                    downloads = it.downloads + (algorithmId to AlgorithmDownloadTask(algorithmId, 0f)),
                    message = "正在下载 ${target.name}",
                )
            }
            val installResult = runCatching {
                network.downloadAndInstall(
                    entry = entry,
                    source = source,
                    wifiOnly = wifiOnly,
                    onProgress = { progress ->
                        mutableState.update {
                            it.copy(
                                phase = AlgorithmCatalogPhase.Downloading(algorithmId, progress),
                                downloads = it.downloads + (
                                    algorithmId to AlgorithmDownloadTask(algorithmId, progress)
                                    ),
                            )
                        }
                    },
                )
            }
            installResult.onSuccess { record ->
                val installed = target.copy(
                    origin = AlgorithmOrigin.INSTALLED,
                    isInstalled = true,
                    downloadSource = when (source) {
                        UpdateSourceId.GITHUB -> AlgorithmDownloadSource.GITHUB
                        UpdateSourceId.GITEE -> AlgorithmDownloadSource.GITEE
                    },
                    versionName = record.version,
                    versionCode = record.versionCode,
                )
                mutableState.update { current ->
                    val nextInstalled = sortInstalled(
                        mergeDiskInstalled(listOf(installed)),
                        current.active?.id,
                    )
                    val pending = if (
                        draftConfig.selectionMode == AlgorithmSelectionMode.MANUAL ||
                        analysisRunning
                    ) {
                        installed
                    } else {
                        null
                    }
                    val active = if (pending == null) {
                        resolveActive(nextInstalled, draftConfig, selectedScene)
                    } else {
                        current.active
                    }
                    current.copy(
                        installed = nextInstalled,
                        remote = current.remote.map {
                            if (it.id == installed.id) {
                                installed.copy(origin = AlgorithmOrigin.REMOTE)
                            } else {
                                it
                            }
                        },
                        active = active,
                        pendingActivation = pending ?: current.pendingActivation,
                        downloads = current.downloads - algorithmId,
                        phase = if (pending != null) {
                            AlgorithmCatalogPhase.PendingActivation(
                                algorithmId = installed.id,
                                message = if (analysisRunning) {
                                    "下次启动分析时应用"
                                } else {
                                    "已下载，保存选择后启用"
                                },
                            )
                        } else {
                            AlgorithmCatalogPhase.Idle
                        },
                        message = if (pending != null) {
                            "已安装 ${installed.name}，待启用"
                        } else {
                            "已安装 ${installed.name}"
                        },
                    )
                }
                if (!analysisRunning && draftConfig.selectionMode == AlgorithmSelectionMode.AUTO) {
                    activation.activateCatalog(record.catalogId)
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        downloads = it.downloads - algorithmId,
                        phase = AlgorithmCatalogPhase.Error(
                            message = error.message ?: "下载/安装失败",
                        ),
                        message = error.message,
                    )
                }
            }
        }
    }

    /** 取消进行中的模拟下载任务。 */
    fun cancelDownload(algorithmId: String) {
        downloadJob?.cancel()
        downloadJob = null
        mutableState.update {
            it.copy(
                downloads = it.downloads - algorithmId,
                phase = AlgorithmCatalogPhase.Idle,
                message = "已取消下载",
            )
        }
    }

    /**
     * 手动模式选择已安装算法。
     *
     * 分析运行中或与当前 active 不同 → 仅 pending；
     * 真正写入 [AlgorithmConfig.pinnedAlgorithmId] 由设置 ViewModel 负责。
     */
    fun selectInstalled(algorithmId: String): AlgorithmPackageInfo? {
        val installed = mutableState.value.installed.find { it.id == algorithmId } ?: return null
        if (!installed.isCompatible) {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.Incompatible("无法选择不兼容算法"),
                    message = "不兼容：${installed.name}",
                )
            }
            return null
        }
        mutableState.update { current ->
            if (analysisRunning || current.active?.id != installed.id) {
                current.copy(
                    pendingActivation = installed,
                    phase = AlgorithmCatalogPhase.PendingActivation(
                        algorithmId = installed.id,
                        message = if (analysisRunning) "下次启动分析时应用" else "保存后启用",
                    ),
                    message = if (analysisRunning) {
                        "已选择 ${installed.name}，下次启动分析时应用"
                    } else {
                        "已选择 ${installed.name}，保存后启用"
                    },
                )
            } else {
                current.copy(
                    pendingActivation = null,
                    phase = AlgorithmCatalogPhase.Idle,
                    message = "已是当前算法",
                )
            }
        }
        return installed
    }

    /** 清除一次性提示文案。 */
    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    /** 种子状态：仅内置包，无远端列表。 */
    private fun seedState(): AlgorithmCatalogState {
        val builtin = builtinPackages()
        val active = builtin.first()
        return AlgorithmCatalogState(
            phase = AlgorithmCatalogPhase.Idle,
            active = active,
            previousRollback = builtin.getOrNull(1),
            installed = builtin,
            remote = emptyList(),
            lastCheckedAtEpochMs = null,
        )
    }

    /**
     * 按选择模式解析当前应展示的 active 包。
     * MANUAL：钉选优先，否则内置/任意兼容；AUTO：场景兼容中 versionCode 最新。
     */
    private fun resolveActive(
        installed: List<AlgorithmPackageInfo>,
        config: AlgorithmConfig,
        scene: SceneId,
    ): AlgorithmPackageInfo? {
        return when (config.selectionMode) {
            AlgorithmSelectionMode.MANUAL -> {
                val pinned = config.pinnedAlgorithmId
                    ?.let { id -> installed.find { it.id == id && it.isCompatible } }
                pinned
                    ?: installed.firstOrNull { it.isBuiltin && scene in it.supportedScenes }
                    ?: installed.firstOrNull { it.isCompatible }
            }
            AlgorithmSelectionMode.AUTO -> {
                installed
                    .filter { it.isCompatible && scene in it.supportedScenes }
                    .sortedWith(
                        compareByDescending<AlgorithmPackageInfo> { it.versionCode }
                            .thenByDescending { it.publishedAtEpochMs },
                    )
                    .firstOrNull()
                    ?: installed.firstOrNull { it.isBuiltin }
            }
        }
    }

    private fun previousOf(
        installed: List<AlgorithmPackageInfo>,
        activeId: String?,
    ): AlgorithmPackageInfo? {
        val ordered = installed
            .filter { it.isCompatible && !it.isBuiltin }
            .sortedByDescending { it.versionCode }
        if (ordered.isEmpty()) {
            return installed.firstOrNull { it.isBuiltin && it.id != activeId }
        }
        return ordered.firstOrNull { it.id != activeId }
            ?: installed.firstOrNull { it.isBuiltin && it.id != activeId }
    }

    private fun mergeInstalled(
        current: List<AlgorithmPackageInfo>,
        extras: List<AlgorithmPackageInfo>,
    ): List<AlgorithmPackageInfo> {
        val map = linkedMapOf<String, AlgorithmPackageInfo>()
        builtinPackages().forEach { map[it.id] = it }
        current.forEach { map[it.id] = it }
        extras.filter { it.isInstalled || it.origin == AlgorithmOrigin.INSTALLED }.forEach {
            map[it.id] = it.copy(isInstalled = true, origin = AlgorithmOrigin.INSTALLED)
        }
        return map.values.toList()
    }

    /** 合并磁盘已安装记录与当前列表。 */
    private fun mergeDiskInstalled(current: List<AlgorithmPackageInfo>): List<AlgorithmPackageInfo> {
        val disk = store.listInstalled().map { record ->
            AlgorithmPackageInfo(
                id = record.catalogId,
                name = record.displayName,
                versionName = record.version,
                versionCode = record.versionCode,
                channel = AlgorithmChannel.STABLE,
                summary = "已安装算法包",
                supportedScenes = record.supportedScenes,
                minAppVersionCode = 1,
                publishedAtEpochMs = record.installedAtEpochMs,
                sizeBytes = 0,
                origin = AlgorithmOrigin.INSTALLED,
                signature = AlgorithmSignatureState.OFFICIAL,
                downloadSource = AlgorithmDownloadSource.CACHE,
                isBuiltin = false,
                isInstalled = true,
                isCompatible = true,
            )
        }
        return mergeInstalled(current, disk)
    }

    private fun maybeAutoDownloadLatest() {
        if (!draftConfig.autoDownload) return
        if (!AlgorithmTrustAnchors.hasOfficialAnchors()) return
        val latest = mutableState.value.remote
            .filter { it.isCompatible && selectedScene in it.supportedScenes }
            .maxByOrNull { it.versionCode }
            ?: return
        if (mutableState.value.installed.any { it.id == latest.id }) return
        download(latest.id)
    }

    private fun sortRemote(
        remote: List<AlgorithmPackageInfo>,
        scene: SceneId,
    ): List<AlgorithmPackageInfo> {
        return remote.sortedWith(
            compareByDescending<AlgorithmPackageInfo> { it.isCompatible }
                .thenByDescending { scene in it.supportedScenes }
                .thenByDescending { it.versionCode }
                .thenByDescending { it.publishedAtEpochMs },
        )
    }

    private fun sortInstalled(
        installed: List<AlgorithmPackageInfo>,
        activeId: String?,
    ): List<AlgorithmPackageInfo> {
        return installed.sortedWith(
            compareByDescending<AlgorithmPackageInfo> { it.id == activeId }
                .thenByDescending { it.isBuiltin }
                .thenByDescending { it.versionCode },
        )
    }

    private fun AlgorithmCatalogState.recomputePhase(): AlgorithmCatalogState {
        if (phase is AlgorithmCatalogPhase.Downloading ||
            phase is AlgorithmCatalogPhase.Verifying ||
            phase is AlgorithmCatalogPhase.Loading
        ) {
            return this
        }
        val pending = pendingActivation
        if (pending != null) {
            return copy(
                phase = AlgorithmCatalogPhase.PendingActivation(
                    algorithmId = pending.id,
                    message = if (analysisRunning) "下次启动分析时应用" else "保存后启用",
                ),
            )
        }
        if (phase is AlgorithmCatalogPhase.OfflineWithCache ||
            phase is AlgorithmCatalogPhase.MirrorFallback ||
            phase is AlgorithmCatalogPhase.SecurityWarning ||
            phase is AlgorithmCatalogPhase.Error ||
            phase is AlgorithmCatalogPhase.Incompatible ||
            phase is AlgorithmCatalogPhase.Empty
        ) {
            return this
        }
        return copy(phase = AlgorithmCatalogPhase.Idle)
    }

    private fun builtinPackages(): List<AlgorithmPackageInfo> {
        // versionCode 与语义化 0.1.0 对齐：major*1e6 + minor*1e3 + patch
        val versionCode = 100L
        return listOf(
            AlgorithmPackageInfo(
                id = AlgorithmIds.BUILTIN_CATALOG_ID,
                name = "内置算法",
                versionName = AlgorithmIds.BUILTIN_VERSION,
                versionCode = versionCode,
                channel = AlgorithmChannel.STABLE,
                summary = "随应用分发的三赛季默认识别引擎（runtime ${AlgorithmIds.BUILTIN_RUNTIME_ID} v${AlgorithmIds.BUILTIN_VERSION}）。",
                supportedScenes = SceneId.entries.toSet(),
                minAppVersionCode = 1,
                publishedAtEpochMs = 0L,
                sizeBytes = 0,
                origin = AlgorithmOrigin.BUILTIN,
                signature = AlgorithmSignatureState.OFFICIAL,
                downloadSource = AlgorithmDownloadSource.BUILTIN,
                isBuiltin = true,
                isInstalled = true,
                isCompatible = true,
            ),
        )
    }

    private fun installedVersionCode(): Long {
        return runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.GET_META_DATA,
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        }.getOrDefault(1L)
    }

    companion object {
        /** 供单测注入固定时钟差。 */
        fun uptime(): Long = SystemClock.uptimeMillis()
    }
}
