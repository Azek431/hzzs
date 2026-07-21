package top.azek431.hzzs.core.algorithm

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmConfig
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.update.UpdateSourceId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 算法目录与下载任务的 [StateFlow] 唯一所有者。
 *
 * 当前实现提供完整 UI 契约 + 内置/模拟远端目录：
 * - 自动选择：按场景取兼容最新官方算法
 * - 手动选择：钉选已安装包；分析运行中仅标记 pending
 * - 网络失败：保留已安装/内置，进入 OfflineWithCache / Error
 * - 镜像回退：记录 [AlgorithmCatalogState.activeSource] 与 lastMirrorReason
 *
 * 线程：状态更新在 Main；网络/模拟 IO 切 [Dispatchers.IO]。
 *
 * 限制：真正的 `.hzzsalg` 签名校验与安装激活尚未接入；
 * 下载进度为可演示的模拟流程，**不修改 C++ / 不加载可执行代码**。
 */
@Singleton
class AlgorithmCatalogController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
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
    private var selectedScene: SceneId = SceneId.BAMBOO_BOOKSTORE

    /**
     * 绑定设置页草稿上下文，重算 active / pending。
     * 下载与检查不写入 [AlgorithmConfig]；钉选 ID 仍由 ViewModel 写回草稿。
     */
    fun bindSettings(
        algorithm: AlgorithmConfig,
        sourcePreference: UpdateSourcePreference,
        selectedScene: SceneId,
        analysisRunning: Boolean = this.analysisRunning,
    ) {
        this.draftConfig = algorithm
        this.sourcePreference = sourcePreference
        this.selectedScene = selectedScene
        this.analysisRunning = analysisRunning
        mutableState.update { current ->
            current.copy(
                selectionMode = algorithm.selectionMode,
                channel = algorithm.channel,
                sourcePreference = sourcePreference,
                analysisRunning = analysisRunning,
                active = resolveActive(current.installed, algorithm, selectedScene),
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
        mutableState.update { it.copy(analysisRunning = running) }
    }

    /**
     * 刷新远端目录（模拟）。
     *
     * @param force 预留给手动刷新标记；当前实现与非 force 相同
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
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchCatalog(draftConfig.channel, sourcePreference) }
            }
            result.onSuccess { catalog ->
                mutableState.update { current ->
                    val mergedInstalled = mergeInstalled(current.installed, catalog.remote)
                    val active = resolveActive(mergedInstalled, draftConfig, selectedScene)
                    current.copy(
                        phase = when {
                            catalog.remote.isEmpty() && mergedInstalled.isEmpty() ->
                                AlgorithmCatalogPhase.Empty
                            catalog.usedFallback -> AlgorithmCatalogPhase.MirrorFallback(
                                reason = catalog.fallbackReason.orEmpty(),
                                activeSource = catalog.activeSource,
                            )
                            else -> AlgorithmCatalogPhase.Idle
                        },
                        remote = sortRemote(catalog.remote, selectedScene),
                        installed = sortInstalled(mergedInstalled, active?.id),
                        active = active,
                        previousRollback = previousOf(mergedInstalled, active?.id),
                        activeSource = catalog.activeSource,
                        lastMirrorReason = catalog.fallbackReason,
                        lastCheckedAtEpochMs = System.currentTimeMillis(),
                        message = catalog.message,
                    ).recomputePhase()
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
            if (force) {
                // no-op marker for callers that distinguish manual refresh
            }
        }
    }

    /**
     * 下载并“校验”算法包（当前为进度模拟）。
     *
     * fail-closed：不兼容 / 不可信签名直接进入对应相位并中止。
     * 分析运行中或手动模式下载完成后进入 [AlgorithmCatalogPhase.PendingActivation]。
     */
    fun download(algorithmId: String) {
        val target = mutableState.value.remote.find { it.id == algorithmId }
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
        if (target.signature == AlgorithmSignatureState.UNTRUSTED) {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.SecurityWarning("签名不可信，已阻止下载"),
                    message = "安全警告：${target.name}",
                )
            }
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.Downloading(algorithmId, 0f),
                    downloads = it.downloads + (algorithmId to AlgorithmDownloadTask(algorithmId, 0f)),
                    message = "正在下载 ${target.name}",
                )
            }
            for (step in 1..10) {
                delay(90)
                val progress = step / 10f
                mutableState.update {
                    it.copy(
                        phase = AlgorithmCatalogPhase.Downloading(algorithmId, progress),
                        downloads = it.downloads + (
                            algorithmId to AlgorithmDownloadTask(algorithmId, progress)
                            ),
                    )
                }
            }
            mutableState.update {
                it.copy(
                    phase = AlgorithmCatalogPhase.Verifying(algorithmId),
                    downloads = it.downloads + (
                        algorithmId to AlgorithmDownloadTask(algorithmId, 1f, verifying = true)
                        ),
                    message = "正在校验 ${target.name}",
                )
            }
            delay(180)
            val installed = target.copy(
                origin = AlgorithmOrigin.INSTALLED,
                isInstalled = true,
                downloadSource = when (mutableState.value.activeSource) {
                    UpdateSourceId.GITHUB -> AlgorithmDownloadSource.GITHUB
                    UpdateSourceId.GITEE, null -> AlgorithmDownloadSource.GITEE
                },
            )
            mutableState.update { current ->
                val nextInstalled = sortInstalled(
                    mergeInstalled(current.installed, listOf(installed)),
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
                current.copy(
                    installed = nextInstalled,
                    remote = current.remote.map {
                        if (it.id == installed.id) installed.copy(origin = AlgorithmOrigin.REMOTE) else it
                    },
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
                        "已下载 ${installed.name}，待启用"
                    } else {
                        "已下载 ${installed.name}"
                    },
                )
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

    private data class CatalogFetch(
        val remote: List<AlgorithmPackageInfo>,
        val activeSource: UpdateSourceId,
        val usedFallback: Boolean,
        val fallbackReason: String?,
        val message: String?,
    )

    /**
     * 拉取目录（当前为本地样本 + 源可达性模拟）。
     * 目录检查体积极小，不受“仅 Wi-Fi 下载大文件”限制。
     */
    private fun fetchCatalog(
        channel: AlgorithmChannel,
        preference: UpdateSourcePreference,
    ): CatalogFetch {
        val online = isNetworkAvailable()
        if (!online) {
            error("网络不可用")
        }
        val order = sourceOrder(preference)
        var active: UpdateSourceId? = null
        var fallbackReason: String? = null
        var usedFallback = false
        for ((index, source) in order.withIndex()) {
            val ok = simulateSourceReachable(source)
            if (ok) {
                active = source
                if (index > 0) {
                    usedFallback = true
                    fallbackReason = "首选源不可达，已切换到 ${source.name}"
                }
                break
            }
        }
        val source = active ?: error("Gitee 与 GitHub 算法目录均不可用")
        val remote = sampleRemotePackages(channel, source)
        return CatalogFetch(
            remote = remote,
            activeSource = source,
            usedFallback = usedFallback,
            fallbackReason = fallbackReason,
            message = if (usedFallback) fallbackReason else "已从 ${source.name} 刷新算法目录",
        )
    }

    private fun sourceOrder(preference: UpdateSourcePreference): List<UpdateSourceId> =
        when (preference) {
            UpdateSourcePreference.AUTO, UpdateSourcePreference.PREFER_GITEE ->
                listOf(UpdateSourceId.GITEE, UpdateSourceId.GITHUB)
            UpdateSourcePreference.PREFER_GITHUB ->
                listOf(UpdateSourceId.GITHUB, UpdateSourceId.GITEE)
        }

    private fun simulateSourceReachable(source: UpdateSourceId): Boolean {
        // 默认双源都可达；若需演示回退，可在后续接入真实探测。
        return source == UpdateSourceId.GITEE || source == UpdateSourceId.GITHUB
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return true // JVM 单测环境无网络栈时视为可达
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sampleRemotePackages(
        channel: AlgorithmChannel,
        source: UpdateSourceId,
    ): List<AlgorithmPackageInfo> {
        val downloadSource = when (source) {
            UpdateSourceId.GITEE -> AlgorithmDownloadSource.GITEE
            UpdateSourceId.GITHUB -> AlgorithmDownloadSource.GITHUB
        }
        val appCode = installedVersionCode()
        val base = listOf(
            AlgorithmPackageInfo(
                id = "official-bamboo-1.1.0",
                name = "竹影书屋官方算法",
                versionName = "1.1.0",
                versionCode = 1_100,
                channel = AlgorithmChannel.STABLE,
                summary = "增强竹隙与悬挂毛笔边界，降低误检。",
                supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
                minAppVersionCode = 1,
                publishedAtEpochMs = 1_752_000_000_000L,
                sizeBytes = 420_000,
                origin = AlgorithmOrigin.REMOTE,
                signature = AlgorithmSignatureState.OFFICIAL,
                downloadSource = downloadSource,
                releaseNotes = "修复宽竹隙双写；优化低端机耗时。",
                isCompatible = appCode >= 1,
            ),
            AlgorithmPackageInfo(
                id = "official-sweet-1.0.2",
                name = "甜甜圈官方算法",
                versionName = "1.0.2",
                versionCode = 1_002,
                channel = AlgorithmChannel.STABLE,
                summary = "毒药瓶与蛋糕结构召回小幅提升。",
                supportedScenes = setOf(SceneId.SWEET_FACTORY),
                minAppVersionCode = 1,
                publishedAtEpochMs = 1_751_500_000_000L,
                sizeBytes = 390_000,
                origin = AlgorithmOrigin.REMOTE,
                signature = AlgorithmSignatureState.OFFICIAL,
                downloadSource = downloadSource,
                releaseNotes = "稳定通道热修。",
                isCompatible = appCode >= 1,
            ),
            AlgorithmPackageInfo(
                id = "official-dual-2.0.0-beta",
                name = "双赛季实验算法",
                versionName = "2.0.0-beta",
                versionCode = 2_000,
                channel = AlgorithmChannel.BETA,
                summary = "统一双赛季特征管线的测试通道构建。",
                supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE, SceneId.SWEET_FACTORY),
                minAppVersionCode = 2,
                publishedAtEpochMs = 1_752_200_000_000L,
                sizeBytes = 510_000,
                origin = AlgorithmOrigin.REMOTE,
                signature = AlgorithmSignatureState.OFFICIAL,
                downloadSource = downloadSource,
                releaseNotes = "测试通道，可能不稳定。",
                isCompatible = appCode >= 2,
            ),
        )
        return base.filter {
            channel == AlgorithmChannel.BETA || it.channel == AlgorithmChannel.STABLE
        }
    }

    private fun builtinPackages(): List<AlgorithmPackageInfo> {
        val now = 1_750_000_000_000L
        return listOf(
            AlgorithmPackageInfo(
                id = "builtin-bamboo-1.0.0",
                name = "竹影书屋内置算法",
                versionName = "1.0.0",
                versionCode = 1_000,
                channel = AlgorithmChannel.STABLE,
                summary = "随应用分发的默认竹影识别引擎。",
                supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
                minAppVersionCode = 1,
                publishedAtEpochMs = now,
                sizeBytes = 0,
                origin = AlgorithmOrigin.BUILTIN,
                signature = AlgorithmSignatureState.OFFICIAL,
                downloadSource = AlgorithmDownloadSource.BUILTIN,
                isBuiltin = true,
                isInstalled = true,
                isCompatible = true,
            ),
            AlgorithmPackageInfo(
                id = "builtin-sweet-1.0.0",
                name = "甜甜圈内置算法",
                versionName = "1.0.0",
                versionCode = 1_000,
                channel = AlgorithmChannel.STABLE,
                summary = "随应用分发的默认甜甜圈识别引擎。",
                supportedScenes = setOf(SceneId.SWEET_FACTORY),
                minAppVersionCode = 1,
                publishedAtEpochMs = now - 86_400_000L,
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
