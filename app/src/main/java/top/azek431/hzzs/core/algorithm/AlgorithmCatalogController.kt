package top.azek431.hzzs.core.algorithm

import android.content.Context
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
import top.azek431.hzzs.core.algorithm.logic.AlgorithmCatalogPure
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
 * - 下载：HTTPS 资产 + size/sha256 + ZIP 白名单 + [InstalledAlgorithmStore]
 * - **「待启用」**：仅 [AlgorithmCatalogPhase.PendingActivation]——分析运行中改钉选时；
 *   真正 Native configure 在 [AlgorithmActivationCoordinator]（save / start 安全点），见
 *   `docs/navigation/KOTLIN.md` 与 `docs/ALGORITHM_SYSTEM_V1.md`
 *
 * 本类的决策逻辑（解析激活包、合并列表、排序、计算 pending、推导相位、升级计划）
 * 全部委托给 [AlgorithmCatalogPure]（纯函数，可脱离 Android 单测）；
 * 本类只做 StateFlow 持有、Android 框架调用与网络 IO 编排。
 *
 * 线程：状态更新在 Main；网络 IO 切 [Dispatchers.IO]。
 */
@Singleton
class AlgorithmCatalogController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val network: AlgorithmNetworkClient,
    private val store: InstalledAlgorithmStore,
    private val activation: AlgorithmActivationCoordinator,
    private val bundledInstaller: BundledAlgorithmInstaller,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(seedState())
    /** UI 只读状态。 */
    val state: StateFlow<AlgorithmCatalogState> = mutableState.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var analysisRunning: Boolean = false
    private var bundledSeeded: Boolean = false
    /** 来自设置草稿的算法配置（未保存也会驱动列表解析）。 */
    private var draftConfig: AlgorithmConfig = AlgorithmConfig()
    private var sourcePreference: UpdateSourcePreference = UpdateSourcePreference.AUTO
    private var selectedScene: SceneId = AppConfig.DEFAULT_SELECTED_SCENE
    private var remoteEntries: List<CatalogRemoteEntry> = emptyList()
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
        ensureBundledSeeded()
        this.draftConfig = algorithm
        this.sourcePreference = sourcePreference
        this.selectedScene = selectedScene
        this.analysisRunning = analysisRunning
        this.wifiOnly = wifiOnly
        mutableState.update { current ->
            val installed = mergeDiskInstalled(current.installed)
            val active = AlgorithmCatalogPure.resolveActive(
                installed = installed,
                pinned = algorithm.pinnedAlgorithmId,
                mode = algorithm.selectionMode,
                scene = selectedScene,
            )
            // pending 仅在「草稿/配置已钉选，但引擎尚未切到该包」时保留。
            // 未分析且 resolveActive 已与钉选一致 → 清 pending（保存后不再假「待启用」）。
            // 分析中钉选变更 → 保留 pending（须 stop 或下次 start 才 ensureConfigured）。
            val pending = AlgorithmCatalogPure.computePending(
                pendingFromUi = current.pendingActivation,
                activeId = active?.id,
                pinnedId = algorithm.pinnedAlgorithmId,
                mode = algorithm.selectionMode,
                analysisRunning = analysisRunning,
            )
            current.copy(
                selectionMode = algorithm.selectionMode,
                channel = algorithm.channel,
                sourcePreference = sourcePreference,
                analysisRunning = analysisRunning,
                installed = installed.sortedWith(
                    AlgorithmCatalogPure.sortInstalled(installed, active?.id, selectedScene),
                ),
                active = active,
                pendingActivation = pending,
            ).recomputePhase()
        }
    }

    /** 预装 APK assets 中的声明式算法（幂等）；供 Application / 首次 bind 调用。 */
    fun ensureBundledSeeded() {
        if (bundledSeeded) return
        bundledSeeded = true
        runCatching { bundledInstaller.ensureBundledInstalled() }
            .onFailure { error ->
                // 预装失败不阻断内置算法
                mutableState.update {
                    it.copy(message = "捆绑算法预装部分失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        mutableState.update { current ->
            val installed = mergeDiskInstalled(current.installed)
            val active = AlgorithmCatalogPure.resolveActive(
                installed = installed,
                pinned = draftConfig.pinnedAlgorithmId,
                mode = draftConfig.selectionMode,
                scene = selectedScene,
            )
            current.copy(
                installed = installed.sortedWith(
                    AlgorithmCatalogPure.sortInstalled(installed, active?.id, selectedScene),
                ),
                active = active,
            )
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
            ensureBundledSeeded()
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
                    val active = AlgorithmCatalogPure.resolveActive(
                        installed = mergedInstalled,
                        pinned = draftConfig.pinnedAlgorithmId,
                        mode = draftConfig.selectionMode,
                        scene = selectedScene,
                    )
                    current.copy(
                        phase = AlgorithmCatalogPure.catalogPhaseAfter(
                            // 注意：必须传旧 phase（进入本 update 前），不能传 current.phase，
                            // 否则刚从 Loading 转出来的成功路径会被 catalogPhaseAfter 的
                            // 「Loading 原样保留」规则吞掉，UI 永远卡在「正在检查算法目录…」。
                            current = AlgorithmCatalogPhase.Idle,
                            remoteInfos = remoteInfos,
                            installed = mergedInstalled,
                            catalog = AlgorithmCatalogPure.RemoteCatalogMeta(
                                activeSource = catalog.activeSource,
                                usedFallback = catalog.usedFallback,
                                fallbackReason = catalog.fallbackReason,
                                message = catalog.message,
                            ),
                        ),
                        remote = remoteInfos.sortedWith(
                            AlgorithmCatalogPure.sortRemote(remoteInfos, selectedScene),
                        ),
                        installed = mergedInstalled.sortedWith(
                            AlgorithmCatalogPure.sortInstalled(mergedInstalled, active?.id, selectedScene),
                        ),
                        active = active,
                        previousRollback = AlgorithmCatalogPure.previousOf(mergedInstalled, active?.id),
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
                    val nextInstalled = AlgorithmCatalogPure.mergeInstalled(
                        current.installed,
                        listOf(installed),
                    ).sortedWith(
                        AlgorithmCatalogPure.sortInstalled(current.installed, current.active?.id, selectedScene),
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
                        AlgorithmCatalogPure.resolveActive(
                            installed = nextInstalled,
                            pinned = draftConfig.pinnedAlgorithmId,
                            mode = draftConfig.selectionMode,
                            scene = selectedScene,
                        )
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
                        // 下载成功且未产生新 pending 时，必须显式清除旧 pending，
                        // 否则「AUTO + 未分析」路径会继承历史 pending，与 selectInstalled
                        // 的「未分析即清 null」语义不一致；真正生效仍在下次 start 的
                        // AlgorithmActivationCoordinator.ensureConfigured。
                        pendingActivation = pending,
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
     * 计算可升级计划（纯只读，不触发下载）。
     *
     * 跳过 [AlgorithmOrigin.BUILTIN] / [AlgorithmOrigin.BUNDLED]；
     * 仅对远端同 id 更高 versionCode 且兼容、有信任锚的包给出可升级 id。
     */
    fun planUpgrades(): AlgorithmCatalogPure.UpgradePlan {
        val current = mutableState.value
        return AlgorithmCatalogPure.planUpgrades(
            installed = current.installed,
            remote = current.remote,
        )
    }

    /**
     * 一键升级：按 [planUpgrades] 结果**顺序**触发下载。
     *
     * [download] 同时只允许一个任务；因此这里只启动队列中的**第一个**可升级包，
     * 其余记入 [AlgorithmCatalogPure.UpgradeResult.queued]，避免「报告已升级但实际未启动」。
     * 实际下载/验签/安装异步；调用方以 [state] / `list_algorithms` 跟踪。
     */
    fun upgradeAll(): AlgorithmCatalogPure.UpgradeResult {
        val plan = planUpgrades()
        if (plan.candidates.isEmpty()) {
            return AlgorithmCatalogPure.UpgradeResult(
                upgraded = emptyList(),
                queued = emptyList(),
                skipped = plan.skipped,
                failed = plan.failed,
            )
        }
        val first = plan.candidates.first()
        val rest = plan.candidates.drop(1)
        download(first)
        // download 若因已有任务进行中而未启动，仍如实报告：首个记 upgraded（请求已发出），
        // 其余 queued；调用方用 list_algorithms 观察进度。
        return AlgorithmCatalogPure.UpgradeResult(
            upgraded = listOf(first),
            queued = rest,
            skipped = plan.skipped,
            failed = plan.failed,
        )
    }

    /**
     * 手动模式选择已安装算法。
     *
     * - 分析运行中：写入 [AlgorithmCatalogState.pendingActivation]（引擎不半热切换）。
     * - 未分析：不设 pending（草稿钉选经 [bindSettings]/[AlgorithmCatalogPure.resolveActive] 即可反映；
     *   真正 [VisionEngine.configureAlgorithm] 仍在 [AlgorithmActivationCoordinator.onConfigCommitted]）。
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
            when {
                analysisRunning -> current.copy(
                    pendingActivation = installed,
                    phase = AlgorithmCatalogPhase.PendingActivation(
                        algorithmId = installed.id,
                        message = "下次启动分析时应用",
                    ),
                    message = "已选择 ${installed.name}，下次启动分析时应用",
                )
                current.active?.id == installed.id -> current.copy(
                    pendingActivation = null,
                    phase = AlgorithmCatalogPhase.Idle,
                    message = "已是当前算法",
                )
                else -> current.copy(
                    // 未分析：不挂 pending 徽章；文案提示须保存才 configure Native
                    pendingActivation = null,
                    phase = AlgorithmCatalogPhase.Idle,
                    message = "已选择 ${installed.name}，保存后启用",
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
        val builtin = AlgorithmCatalogPure.builtinPackages()
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

    /** 合并磁盘已安装记录与当前列表（委托 [AlgorithmCatalogPure]）。 */
    private fun mergeDiskInstalled(current: List<AlgorithmPackageInfo>): List<AlgorithmPackageInfo> {
        val disk = AlgorithmCatalogPure.mergeDiskInstalled(store.listInstalled())
        return AlgorithmCatalogPure.mergeInstalled(current, disk)
    }

    private fun maybeAutoDownloadLatest() {
        if (!draftConfig.autoDownload) return
        val latest = mutableState.value.remote
            .filter { it.isCompatible && selectedScene in it.supportedScenes }
            .maxByOrNull { it.versionCode }
            ?: return
        if (mutableState.value.installed.any { it.id == latest.id }) return
        download(latest.id)
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
            phase is AlgorithmCatalogPhase.Error ||
            phase is AlgorithmCatalogPhase.Incompatible ||
            phase is AlgorithmCatalogPhase.Empty
        ) {
            return this
        }
        return copy(phase = AlgorithmCatalogPhase.Idle)
    }
}
