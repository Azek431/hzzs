/**
 * 设置模块 ViewModel：即时落盘（方案 C）。
 *
 * 职责：订阅/维护当前 [AppConfig]；普通改动防抖后直接 [SettingsRepository.save]；
 * 危险项（如开自动操作）由子页对话框确认后再调用 [update]。
 * 网络刷新与算法下载为即时任务，与配置字段无关。
 * 边界：不直接 JNI/Root/WindowManager；算法经 [AlgorithmCatalogController] /
 * [AlgorithmActivationCoordinator]，更新经 [UpdateRepository]。
 */
package top.azek431.hzzs.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.core.algorithm.AlgorithmActivationCoordinator
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogController
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.logging.AlgorithmDiagnosticsSnapshot
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.UpdateChannel
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.theme.HzzsThemePackage
import top.azek431.hzzs.core.theme.ThemePackageCodec
import top.azek431.hzzs.core.update.ApkInstaller
import top.azek431.hzzs.core.update.DeltaPatchApplier
import top.azek431.hzzs.core.update.SourceResult
import top.azek431.hzzs.core.update.UpdateFileVerifier
import top.azek431.hzzs.core.update.UpdateRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.NativeBenchmarkResult
import top.azek431.hzzs.data.vision.NativeBenchmarkRunner
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.mcp.McpServerState
import top.azek431.hzzs.mcp.McpUiBridge
import top.azek431.hzzs.nativevision.NativeVision
import top.azek431.hzzs.platform.compat.CaptureCapabilityResolver
import java.io.File
import javax.inject.Inject

/** 应用更新检查/下载/安装过程的界面态（即时任务，非草稿字段）。 */
data class UpdateUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val available: SourceResult? = null,
    val localApk: File? = null,
)

/**
 * 设置模块配置编辑入口。
 *
 * 子页面共享本 ViewModel；改动经 [update] 乐观更新 UI 并防抖落盘。
 * 导入/MCP 等外部写入通过 [SettingsRepository.config] 回流，本地无挂起写时同步。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val capabilityResolver: CaptureCapabilityResolver,
    private val updateRepository: UpdateRepository,
    private val algorithmCatalog: AlgorithmCatalogController,
    private val algorithmActivation: AlgorithmActivationCoordinator,
    private val visionEngine: VisionEngine,
    private val visionRuntime: VisionRuntimeController,
    private val debugFrames: DebugFrameRecorder,
    private val benchmarkRunner: NativeBenchmarkRunner,
    mcpUiBridge: McpUiBridge,
) : ViewModel() {
    private val mutableConfig = MutableStateFlow(AppConfig())
    /**
     * 当前设置页展示的配置（与磁盘一致或乐观领先一帧）。
     * 历史命名 [draft] 保留，避免子页签名大面积改动。
     */
    val draft: StateFlow<AppConfig> = mutableConfig.asStateFlow()

    val capabilities = capabilityResolver.all()
    private val mutableUpdate = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = mutableUpdate.asStateFlow()
    val algorithmState: StateFlow<AlgorithmCatalogState> = algorithmCatalog.state
    val mcpState: StateFlow<McpServerState> = mcpUiBridge.serverState
    private val mutableDebugFrameCount = MutableStateFlow(0)
    val debugFrameCount: StateFlow<Int> = mutableDebugFrameCount.asStateFlow()
    private val mutableBenchmark = MutableStateFlow<Result<NativeBenchmarkResult>?>(null)
    val benchmark: StateFlow<Result<NativeBenchmarkResult>?> = mutableBenchmark.asStateFlow()

    private var persistJob: Job? = null
    private var pendingWrite: AppConfig? = null
    private val writeMutex = Mutex()

    init {
        viewModelScope.launch {
            val snap = repository.snapshot()
            mutableConfig.value = snap
            bindAlgorithm(snap)
            algorithmCatalog.refreshCatalog()
            refreshDebugFrameCount()
        }
        viewModelScope.launch {
            repository.config.collectLatest { remote ->
                if (pendingWrite != null) return@collectLatest
                if (remote != mutableConfig.value) {
                    mutableConfig.value = remote
                    bindAlgorithm(remote)
                }
            }
        }
    }

    fun refreshDebugFrameCount() {
        viewModelScope.launch { mutableDebugFrameCount.value = debugFrames.list().size }
    }

    fun clearDebugFrames() {
        viewModelScope.launch {
            debugFrames.clear()
            mutableDebugFrameCount.value = 0
        }
    }

    fun runNativeBenchmark() {
        val iterations = mutableConfig.value.developer.nativeBenchmarkIterations
        viewModelScope.launch { mutableBenchmark.value = benchmarkRunner.run(iterations) }
    }

    /** 基于当前配置与运行态生成脱敏诊断文本（不含 Bearer）。 */
    fun buildDiagnosticsReport(): String {
        val config = mutableConfig.value
        val mcp = mcpState.value
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: 0L
        }
        val activation = runCatching { visionEngine.currentActivation() }.getOrNull()
        return DiagnosticsExporter.buildReport(
            versionName = versionName,
            versionCode = versionCode,
            config = config,
            mcp = McpDiagnosticsSnapshot(
                running = mcp.running,
                port = mcp.port.takeIf { mcp.running },
                lastError = mcp.lastError,
            ),
            debugFrameCount = mutableDebugFrameCount.value,
            algorithm = activation?.let {
                AlgorithmDiagnosticsSnapshot(
                    algorithmId = it.profile.algorithmId,
                    version = it.profile.version,
                    generation = it.generation,
                    usingBuiltinFallback = it.usingBuiltinFallback,
                    loadError = it.loadError,
                    nativeAvailable = NativeVision.isAvailable,
                    pendingCatalogId = algorithmActivation.pendingCatalogId(),
                    analysisRunning = algorithmActivation.isAnalysisRunning(),
                )
            },
            runtime = visionRuntime.status.value,
        )
    }

    private fun bindAlgorithm(config: AppConfig) {
        algorithmCatalog.bindSettings(
            algorithm = config.algorithm,
            sourcePreference = config.update.sourcePreference,
            selectedScene = config.selectedScene,
            wifiOnly = config.update.wifiOnly,
        )
    }

    /**
     * 乐观更新 UI 并防抖落盘。
     * 子页危险确认应在调用本方法前完成（如自动操作风险对话框）。
     */
    fun update(transform: (AppConfig) -> AppConfig) {
        val optimistic = transform(mutableConfig.value)
        mutableConfig.value = optimistic
        pendingWrite = optimistic
        bindAlgorithm(optimistic)
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            flushPending()
        }
    }

    /** 立即刷盘（离开设置、切走主导航时调用）。 */
    fun flushNow(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            persistJob?.cancel()
            flushPending()
            onDone()
        }
    }

    private suspend fun flushPending() {
        writeMutex.withLock {
            val toWrite = pendingWrite ?: return
            pendingWrite = null
            runCatching {
                repository.clearPreview()
                repository.save(toWrite)
                val saved = repository.snapshot()
                mutableConfig.value = saved
                bindAlgorithm(saved)
                algorithmActivation.onConfigCommitted(
                    config = saved.algorithm,
                    selectedScene = saved.selectedScene,
                )
                AppLog.i(
                    "settings",
                    "settings saved developer=${saved.developer.enabled} logLevel=${saved.developer.logLevel}",
                )
            }.onFailure { error ->
                pendingWrite = toWrite
                AppLog.w(
                    "settings",
                    "settings save failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    /** 将主题包解码后写入主题/悬浮窗（保留当前悬浮窗开关）并落盘。 */
    fun importTheme(raw: String) {
        val pack = ThemePackageCodec.decode(raw)
        update { it.copy(theme = pack.theme, overlay = pack.overlay.copy(enabled = it.overlay.enabled)) }
    }

    /** 从当前配置导出声明式主题包 JSON。 */
    fun exportTheme(): String {
        val config = mutableConfig.value
        return ThemePackageCodec.encode(
            HzzsThemePackage(
                name = "HZZS 自定义主题",
                theme = config.theme,
                overlay = config.overlay,
            ),
        )
    }

    /**
     * Composition 卸载时尽量刷盘；不再使用「预览层」语义。
     * 真正的离开导航应走 [flushNow]。
     */
    fun onLeaveComposition() {
        if (pendingWrite == null) return
        viewModelScope.launch {
            persistJob?.cancel()
            flushPending()
        }
    }

    fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            persistJob?.cancel()
            flushPending()
            val config = mutableConfig.value
            mutableUpdate.value = UpdateUiState(busy = true, message = "正在检查更新…")
            runCatching {
                if (config.update.wifiOnly && !isOnUnmeteredNetwork()) {
                    error("当前设置要求仅在 Wi‑Fi 下检查/下载更新")
                }
                val result = updateRepository.check(
                    beta = config.update.channel == UpdateChannel.BETA,
                    sourcePreference = config.update.sourcePreference,
                )
                val installed = installedVersionCode()
                if (result.manifest.versionCode <= installed) {
                    UpdateUiState(
                        busy = false,
                        message = "已是最新（远端 ${result.manifest.versionName} / ${result.manifest.versionCode}）",
                    )
                } else if (config.update.ignoredVersionCode == result.manifest.versionCode) {
                    UpdateUiState(
                        busy = false,
                        message = "已忽略版本 ${result.manifest.versionName}",
                        available = result,
                    )
                } else {
                    UpdateUiState(
                        busy = false,
                        message = "发现 ${result.manifest.versionName}（${result.source.name}）",
                        available = result,
                    )
                }
            }.onSuccess { mutableUpdate.value = it }
                .onFailure { error ->
                    mutableUpdate.value = UpdateUiState(
                        busy = false,
                        message = "检查失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
        }
    }

    private var updateJob: Job? = null

    fun downloadAvailableUpdate() {
        val available = mutableUpdate.value.available ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            persistJob?.cancel()
            flushPending()
            val config = mutableConfig.value
            mutableUpdate.value = mutableUpdate.value.copy(busy = true, message = "正在下载更新…")
            runCatching {
                if (config.update.wifiOnly && !isOnUnmeteredNetwork()) {
                    error("当前设置要求仅在 Wi‑Fi 下下载更新")
                }
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val fullApk = File(dir, available.manifest.fullApk.name)
                val patch = available.manifest.patches.firstOrNull {
                    it.fromVersionCode == installedVersionCode()
                }
                if (patch != null) {
                    val patchFile = File(dir, patch.patch.name)
                    updateRepository.download(available.source, available.manifest, patch.patch, patchFile)
                    val oldApk = File(appContext.applicationInfo.sourceDir)
                    DeltaPatchApplier.apply(oldApk, patchFile, fullApk)
                    patchFile.delete()
                } else {
                    updateRepository.download(
                        available.source,
                        available.manifest,
                        available.manifest.fullApk,
                        fullApk,
                    )
                }
                UpdateUiState(
                    busy = false,
                    message = "下载完成，可安装 ${available.manifest.versionName}",
                    available = available,
                    localApk = fullApk,
                )
            }.onSuccess { mutableUpdate.value = it }
                .onFailure { error ->
                    mutableUpdate.value = mutableUpdate.value.copy(
                        busy = false,
                        message = "下载失败：${error.message ?: error.javaClass.simpleName}",
                        localApk = null,
                    )
                }
        }
    }

    fun installDownloadedUpdate() {
        val apk = mutableUpdate.value.localApk ?: return
        val available = mutableUpdate.value.available
        if (available == null) {
            mutableUpdate.value = mutableUpdate.value.copy(message = "缺少已校验的更新清单，请重新检查更新")
            return
        }
        runCatching {
            UpdateFileVerifier.verifyPackage(appContext, apk, available.manifest)
            ApkInstaller.launch(appContext, apk)
        }.onFailure { error ->
            mutableUpdate.value = mutableUpdate.value.copy(
                message = "无法安装：${error.message ?: error.javaClass.simpleName}",
                localApk = null,
            )
            runCatching { apk.delete() }
        }
    }

    /** 将忽略版本号即时写入配置。 */
    fun ignoreAvailableUpdate() {
        val code = mutableUpdate.value.available?.manifest?.versionCode ?: return
        update { it.copy(update = it.update.copy(ignoredVersionCode = code)) }
        mutableUpdate.value = mutableUpdate.value.copy(message = "已忽略该版本")
    }

    fun refreshAlgorithms() = algorithmCatalog.refreshCatalog(force = true)

    fun downloadAlgorithm(id: String) = algorithmCatalog.download(id)

    fun cancelAlgorithmDownload(id: String) = algorithmCatalog.cancelDownload(id)

    /** 钉选手动算法并即时落盘；分析运行中由激活协调器 pending。 */
    fun selectAlgorithm(id: String) {
        val selected = algorithmCatalog.selectInstalled(id) ?: return
        update {
            it.copy(
                algorithm = it.algorithm.copy(
                    selectionMode = top.azek431.hzzs.core.model.AlgorithmSelectionMode.MANUAL,
                    pinnedAlgorithmId = selected.id,
                ),
            )
        }
    }

    private fun installedVersionCode(): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_META_DATA,
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
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 40L
    }
}
