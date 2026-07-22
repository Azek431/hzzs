/**
 * 设置模块 ViewModel 与更新 UI 状态。
 *
 * 职责：维护唯一共享草稿会话；子页共用本 VM；离开模块才保存/丢弃。
 * 预览约束：视觉预览强制保留 baseline 的 capture/automation/mcp/developer/update/algorithm，
 * 权限型设置预览不生效；网络刷新与算法下载为即时任务，不属于视觉预览。
 * 边界：不直接 JNI/Root/WindowManager；算法经 [AlgorithmCatalogController]，更新经 [UpdateRepository]。
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
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.algorithm.AlgorithmActivationCoordinator
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogController
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.UpdateChannel
import top.azek431.hzzs.core.preferences.SettingsEditSession
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
import top.azek431.hzzs.mcp.McpServerState
import top.azek431.hzzs.mcp.McpUiBridge
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
 * 设置模块唯一编辑会话。
 *
 * 子页面共享本 ViewModel；返回首页不丢草稿；离开整个设置模块时才保存/丢弃。
 * [onPreview] 始终用 baseline 覆盖 capture/automation/mcp/developer/update/algorithm，
 * 保证权限型与算法/更新偏好在预览阶段不生效。
 * 网络刷新与算法下载是即时任务，不属于视觉预览。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val capabilityResolver: CaptureCapabilityResolver,
    private val updateRepository: UpdateRepository,
    private val algorithmCatalog: AlgorithmCatalogController,
    private val algorithmActivation: AlgorithmActivationCoordinator,
    private val debugFrames: DebugFrameRecorder,
    private val benchmarkRunner: NativeBenchmarkRunner,
    mcpUiBridge: McpUiBridge,
) : ViewModel() {
    private val mutableDraft = MutableStateFlow(AppConfig())
    val draft: StateFlow<AppConfig> = mutableDraft.asStateFlow()
    private val mutableBaseline = MutableStateFlow(AppConfig())
    val baseline: StateFlow<AppConfig> = mutableBaseline.asStateFlow()
    val capabilities = capabilityResolver.all()
    private val mutableUpdate = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = mutableUpdate.asStateFlow()
    val algorithmState: StateFlow<AlgorithmCatalogState> = algorithmCatalog.state
    val mcpState: StateFlow<McpServerState> = mcpUiBridge.serverState
    private val mutableDebugFrameCount = MutableStateFlow(0)
    val debugFrameCount: StateFlow<Int> = mutableDebugFrameCount.asStateFlow()
    private val mutableBenchmark = MutableStateFlow<Result<NativeBenchmarkResult>?>(null)
    val benchmark: StateFlow<Result<NativeBenchmarkResult>?> = mutableBenchmark.asStateFlow()

    private var session: SettingsEditSession? = null
    private var previewJob: Job? = null
    private var updateJob: Job? = null
    private var pendingDraft: AppConfig? = null

    init {
        viewModelScope.launch {
            openSession(repository.snapshot())
            algorithmCatalog.refreshCatalog()
            refreshDebugFrameCount()
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
        val iterations = mutableDraft.value.developer.nativeBenchmarkIterations
        viewModelScope.launch { mutableBenchmark.value = benchmarkRunner.run(iterations) }
    }

    /** 基于当前草稿配置与运行态生成脱敏诊断文本（不含 Bearer）。 */
    fun buildDiagnosticsReport(): String {
        val config = mutableDraft.value
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
        )
    }

    private fun openSession(original: AppConfig) {
        mutableBaseline.value = original
        mutableDraft.value = original
        pendingDraft = null
        bindAlgorithm(original)
        session = SettingsEditSession(
            original = original,
            onPreview = { candidate ->
                // 预览仅放行外观等安全字段；权限型与算法/更新保持 baseline
                val baseline = mutableBaseline.value
                repository.preview(
                    candidate.copy(
                        captureBackend = baseline.captureBackend,
                        automation = baseline.automation,
                        mcp = baseline.mcp,
                        developer = baseline.developer,
                        update = baseline.update,
                        algorithm = baseline.algorithm,
                    ),
                )
            },
            onPersist = { safe -> repository.save(safe) },
            onClearPreview = { repository.clearPreview() },
        )
    }

    /** 将草稿中的算法/赛季偏好同步给目录控制器（不触发权限型预览）。 */
    private fun bindAlgorithm(config: AppConfig) {
        algorithmCatalog.bindSettings(
            algorithm = config.algorithm,
            sourcePreference = config.update.sourcePreference,
            selectedScene = config.selectedScene,
        )
    }

    /** 更新共享草稿；防抖后写入编辑会话并触发受约束预览。 */
    fun update(transform: (AppConfig) -> AppConfig) {
        val optimistic = transform(mutableDraft.value)
        mutableDraft.value = optimistic
        pendingDraft = optimistic
        bindAlgorithm(optimistic)
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(40)
            flushPendingDraft()
        }
    }

    private suspend fun flushPendingDraft() {
        val draft = pendingDraft ?: return
        pendingDraft = null
        val active = session ?: return
        runCatching {
            val next = active.replace(draft)
            mutableDraft.value = next
            bindAlgorithm(next)
        }
    }

    /** 持久化草稿并重建会话，完成后执行离开动作。 */
    fun save(onDone: () -> Unit = {}) = viewModelScope.launch {
        previewJob?.cancel()
        flushPendingDraft()
        val active = session ?: return@launch
        val saved = active.save()
        openSession(saved)
        AppLog.i(
            "settings",
            "settings saved developer=${saved.developer.enabled} logLevel=${saved.developer.logLevel}",
        )
        runCatching {
            algorithmActivation.onConfigCommitted(
                config = saved.algorithm,
                selectedScene = saved.selectedScene,
            )
        }.onFailure { error ->
            AppLog.w(
                "settings",
                "algorithm activation after save failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        onDone()
    }

    /** 丢弃草稿、清除预览并回调离开。 */
    fun discard(onDone: () -> Unit = {}) = viewModelScope.launch {
        previewJob?.cancel()
        pendingDraft = null
        val active = session
        if (active != null) {
            val restored = active.discard()
            openSession(restored)
        } else {
            repository.clearPreview()
            mutableDraft.value = mutableBaseline.value
            bindAlgorithm(mutableBaseline.value)
        }
        onDone()
    }

    /** 将主题包解码后写入草稿主题/悬浮窗（保留当前悬浮窗开关）。 */
    fun importTheme(raw: String) {
        val pack = ThemePackageCodec.decode(raw)
        update { it.copy(theme = pack.theme, overlay = pack.overlay.copy(enabled = it.overlay.enabled)) }
    }

    /** 从当前草稿导出声明式主题包 JSON。 */
    fun exportTheme(): String {
        val config = mutableDraft.value
        return ThemePackageCodec.encode(
            HzzsThemePackage(
                name = "HZZS 自定义主题",
                theme = config.theme,
                overlay = config.overlay,
            ),
        )
    }

    /**
     * Composition 暂时移除时只清除仓库预览，不篡改草稿与 baseline。
     * 真正保存或丢弃必须由显式离开决策触发，避免断点切换/导航重建静默丢失编辑。
     */
    fun clearPreviewSilently() {
        previewJob?.cancel()
        viewModelScope.launch { repository.clearPreview() }
    }

    fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            // 更新检查读 baseline，避免未保存草稿改变通道/Wi‑Fi 策略
            val config = mutableBaseline.value
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

    fun downloadAvailableUpdate() {
        val available = mutableUpdate.value.available ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val config = mutableBaseline.value
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
            // 差分合并或直下后的 APK 须再验包名 / versionCode / 证书 / 哈希
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

    /**
     * 仅持久化 [ignoredVersionCode]，不把整份未保存草稿一并落盘。
     * 草稿中的其它编辑保留；会话 baseline 同步为「磁盘快照 + 忽略版本」。
     */
    fun ignoreAvailableUpdate() {
        val code = mutableUpdate.value.available?.manifest?.versionCode ?: return
        viewModelScope.launch {
            previewJob?.cancel()
            flushPendingDraft()
            val keptDraft = mutableDraft.value.copy(
                update = mutableDraft.value.update.copy(ignoredVersionCode = code),
            )
            val snap = repository.snapshot()
            val persisted = snap.copy(update = snap.update.copy(ignoredVersionCode = code))
            repository.save(persisted)
            openSession(persisted)
            if (keptDraft != persisted) {
                mutableDraft.value = keptDraft
                pendingDraft = keptDraft
                flushPendingDraft()
            }
            bindAlgorithm(mutableDraft.value)
            mutableUpdate.value = mutableUpdate.value.copy(message = "已忽略该版本（未保存的其它设置仍保留在草稿）")
        }
    }

    fun refreshAlgorithms() = algorithmCatalog.refreshCatalog(force = true)

    fun downloadAlgorithm(id: String) = algorithmCatalog.download(id)

    fun cancelAlgorithmDownload(id: String) = algorithmCatalog.cancelDownload(id)

    /** 将已安装算法钉选为手动选择写入草稿（保存后才真正切换运行时）。 */
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
}
