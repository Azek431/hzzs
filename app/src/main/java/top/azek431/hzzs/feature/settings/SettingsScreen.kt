package top.azek431.hzzs.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import top.azek431.hzzs.core.designsystem.HzzsSection
import top.azek431.hzzs.core.model.*
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.theme.HzzsThemePackage
import top.azek431.hzzs.core.theme.ThemePackageCodec
import top.azek431.hzzs.core.update.ApkInstaller
import top.azek431.hzzs.core.update.DeltaPatchApplier
import top.azek431.hzzs.core.update.SourceResult
import top.azek431.hzzs.core.update.UpdateRepository
import top.azek431.hzzs.platform.compat.CaptureCapabilityResolver
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val available: SourceResult? = null,
    val localApk: File? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val capabilityResolver: CaptureCapabilityResolver,
    private val updateRepository: UpdateRepository,
) : ViewModel() {
    private val mutableDraft = MutableStateFlow(AppConfig())
    val draft: StateFlow<AppConfig> = mutableDraft.asStateFlow()
    private val mutableBaseline = MutableStateFlow(AppConfig())
    val baseline: StateFlow<AppConfig> = mutableBaseline.asStateFlow()
    val capabilities = capabilityResolver.all()
    private val mutableUpdate = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = mutableUpdate.asStateFlow()
    private var previewJob: Job? = null
    private var updateJob: Job? = null

    init {
        viewModelScope.launch {
            val snapshot = repository.snapshot()
            mutableBaseline.value = snapshot
            mutableDraft.value = snapshot
        }
    }

    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(mutableDraft.value)
        mutableDraft.value = next
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(40)
            // Visual and algorithm parameters may preview live. Privilege-bearing
            // controls only become effective after Save, so merely browsing away
            // cannot start MCP, Root, Shell or automatic operation.
            val baseline = mutableBaseline.value
            repository.preview(
                next.copy(
                    captureBackend = baseline.captureBackend,
                    automation = baseline.automation,
                    mcp = baseline.mcp,
                    developer = baseline.developer,
                    update = baseline.update,
                ),
            )
        }
    }

    fun save(onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.save(mutableDraft.value)
        mutableBaseline.value = mutableDraft.value
        onDone()
    }

    fun discard(onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.clearPreview()
        mutableDraft.value = mutableBaseline.value
        onDone()
    }

    fun importTheme(raw: String) {
        val pack = ThemePackageCodec.decode(raw)
        update { it.copy(theme = pack.theme, overlay = pack.overlay.copy(enabled = it.overlay.enabled)) }
    }

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

    /** Clears an unsaved preview when navigation removes this screen. */
    fun discardSilently() {
        previewJob?.cancel()
        viewModelScope.launch {
            repository.clearPreview()
            mutableDraft.value = mutableBaseline.value
        }
    }

    fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val config = mutableBaseline.value
            mutableUpdate.value = UpdateUiState(busy = true, message = "正在检查更新…")
            runCatching {
                if (config.update.wifiOnly && !isOnUnmeteredNetwork()) {
                    error("当前设置要求仅在 Wi‑Fi 下检查/下载更新")
                }
                val result = updateRepository.check(beta = config.update.channel == UpdateChannel.BETA)
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
        runCatching { ApkInstaller.launch(appContext, apk) }
            .onFailure { error ->
                mutableUpdate.value = mutableUpdate.value.copy(
                    message = "无法启动安装：${error.message ?: error.javaClass.simpleName}",
                )
            }
    }

    fun ignoreAvailableUpdate() {
        val code = mutableUpdate.value.available?.manifest?.versionCode ?: return
        viewModelScope.launch {
            val current = repository.snapshot()
            val next = current.copy(update = current.update.copy(ignoredVersionCode = code))
            repository.save(next)
            mutableBaseline.value = next
            mutableDraft.value = mutableDraft.value.copy(update = next.update)
            mutableUpdate.value = mutableUpdate.value.copy(message = "已忽略该版本")
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val config by vm.draft.collectAsState()
    val baseline by vm.baseline.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val context = LocalContext.current
    var automationDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(vm) {
        onDispose { vm.discardSilently() }
    }

    val importTheme = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取主题") }
            .onSuccess { raw -> runCatching { vm.importTheme(raw) }.onSuccess { message = "主题已临时预览，点击保存后永久生效" }.onFailure { message = "主题导入失败：${it.message}" } }
            .onFailure { message = "主题读取失败：${it.message}" }
    }
    val exportTheme = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(vm.exportTheme()) } ?: error("无法写入主题") }
            .onSuccess { message = "主题已导出" }
            .onFailure { message = "主题导出失败：${it.message}" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { vm.discard(onExit) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (config != baseline) AssistChip(onClick = {}, label = { Text("未保存") })
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = { vm.discard(onExit) }) { Text("取消") }
                    Button(onClick = { vm.save(onExit) }, enabled = config != baseline) { Text("保存并应用") }
                }
            }
        },
        snackbarHost = {
            message?.let { text ->
                LaunchedEffect(text) { delay(3_000); message = null }
                Snackbar { Text(text) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item { ThemeSection(config, vm::update, onImport = { importTheme.launch(arrayOf("application/json", "text/plain")) }, onExport = { exportTheme.launch("hzzs-theme.hzzstheme") }, onCopy = { copyTheme(context, vm.exportTheme()); message = "主题 JSON 已复制" }) }
            item { OverlaySection(config, vm::update) }
            item { CaptureSection(config, vm.capabilities, vm::update) }
            item { VisionSection(config, vm::update) }
            item {
                AutomationSection(
                    config = config,
                    update = vm::update,
                    onEnableRequested = { automationDialog = true },
                    onDisable = {
                        vm.update { it.copy(automation = it.automation.copy(enabled = false)) }
                    },
                )
            }
            item { McpSection(config, vm::update) }
            item {
                UpdateSection(
                    config = config,
                    updateState = updateState,
                    update = vm::update,
                    onCheck = vm::checkForUpdates,
                    onDownload = vm::downloadAvailableUpdate,
                    onInstall = vm::installDownloadedUpdate,
                    onIgnore = vm::ignoreAvailableUpdate,
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (automationDialog) {
        AutomationRiskDialog(
            onDismiss = { automationDialog = false },
            onConfirm = {
                automationDialog = false
                vm.update {
                    it.copy(
                        automation = it.automation.copy(
                            enabled = true,
                            disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun ThemeSection(config: AppConfig, update: ((AppConfig) -> AppConfig) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onCopy: () -> Unit) {
    HzzsSection("外观与主题", "所有改动立即预览，只有点击保存才会永久写入。") {
        Text("明暗模式", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppThemeMode.entries.forEach { mode ->
                FilterChip(selected = config.theme.mode == mode, onClick = { update { it.copy(theme = it.theme.copy(mode = mode)) } }, label = { Text(mode.label()) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("配色", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePreset.entries.forEach { preset ->
                FilterChip(selected = config.theme.preset == preset, onClick = { update { it.copy(theme = it.theme.copy(preset = preset)) } }, label = { Text(preset.label()) })
            }
        }
        if (config.theme.preset == ThemePreset.CUSTOM) {
            HexColorField("自定义主题种子色", config.theme.customSeed) { color ->
                update { it.copy(theme = it.theme.copy(customSeed = color)) }
            }
        }
        SettingSwitch("支持时使用系统动态取色", config.theme.dynamicColorEnabled) { value ->
            update { it.copy(theme = it.theme.copy(dynamicColorEnabled = value)) }
        }
        SettingSwitch("增强文字与组件对比度", config.theme.highContrast) { value ->
            update { it.copy(theme = it.theme.copy(highContrast = value)) }
        }
        LabeledSlider("字体缩放", config.theme.fontScale, 0.8f..1.5f) { value -> update { it.copy(theme = it.theme.copy(fontScale = value)) } }
        LabeledSlider("圆角强度", config.theme.cornerScale, 0f..2f) { value -> update { it.copy(theme = it.theme.copy(cornerScale = value)) } }
        LabeledSlider("间距密度", config.theme.spacingScale, 0.75f..1.5f) { value -> update { it.copy(theme = it.theme.copy(spacingScale = value)) } }
        LabeledSlider("动画强度", config.theme.animationScale, 0f..2f) { value -> update { it.copy(theme = it.theme.copy(animationScale = value, reduceMotion = value == 0f)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("导入") }
            OutlinedButton(onClick = onExport) { Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text("导出") }
            TextButton(onClick = onCopy) { Text("复制 JSON") }
        }
    }
}

@Composable
private fun OverlaySection(config: AppConfig, update: ((AppConfig) -> AppConfig) -> Unit) {
    HzzsSection("悬浮窗", "默认极简；调试 HUD 适合校准和反馈问题。") {
        SettingSwitch("显示悬浮窗", config.overlay.enabled) { enabled -> update { it.copy(overlay = it.overlay.copy(enabled = enabled)) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayStyle.entries.forEach { style ->
                FilterChip(selected = config.overlay.style == style, onClick = { update { it.copy(overlay = it.overlay.copy(style = style)) } }, label = { Text(style.label()) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayTheme.entries.forEach { theme ->
                FilterChip(selected = config.overlay.theme == theme, onClick = { update { it.copy(overlay = it.overlay.copy(theme = theme)) } }, label = { Text(theme.label()) })
            }
        }
        if (config.overlay.theme == OverlayTheme.CUSTOM) {
            HexColorField("悬浮窗强调色", config.overlay.customColor) { color ->
                update { it.copy(overlay = it.overlay.copy(customColor = color)) }
            }
        }
        Text("排列方向", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayOrientation.entries.forEach { orientation ->
                FilterChip(
                    selected = config.overlay.orientation == orientation,
                    onClick = { update { it.copy(overlay = it.overlay.copy(orientation = orientation)) } },
                    label = { Text(if (orientation == OverlayOrientation.HORIZONTAL) "横向" else "纵向") },
                )
            }
        }
        LabeledSlider("透明度", config.overlay.backgroundAlpha, 0.1f..1f) { value -> update { it.copy(overlay = it.overlay.copy(backgroundAlpha = value)) } }
        LabeledSlider("缩放", config.overlay.scale, 0.6f..2f) { value -> update { it.copy(overlay = it.overlay.copy(scale = value)) } }
        LabeledSlider("文字缩放", config.overlay.textScale, 0.7f..1.6f) { value -> update { it.copy(overlay = it.overlay.copy(textScale = value)) } }
        LabeledSlider("检测框线宽", config.overlay.strokeWidthDp, 1f..6f) { value -> update { it.copy(overlay = it.overlay.copy(strokeWidthDp = value)) } }
        SettingSwitch("显示检测框", config.overlay.showBoxes) { value -> update { it.copy(overlay = it.overlay.copy(showBoxes = value)) } }
        SettingSwitch("显示状态文字", config.overlay.showText) { value -> update { it.copy(overlay = it.overlay.copy(showText = value)) } }
        SettingSwitch("显示 FPS", config.overlay.showFps) { value -> update { it.copy(overlay = it.overlay.copy(showFps = value)) } }
        SettingSwitch("显示置信度", config.overlay.showConfidence) { value -> update { it.copy(overlay = it.overlay.copy(showConfidence = value)) } }
        SettingSwitch("显示诊断信息", config.overlay.showDiagnostics) { value -> update { it.copy(overlay = it.overlay.copy(showDiagnostics = value)) } }
        SettingSwitch("触摸穿透（全屏检测框模式）", config.overlay.clickThrough) { value -> update { it.copy(overlay = it.overlay.copy(clickThrough = value)) } }
        if (!config.overlay.clickThrough) {
            Text("关闭穿透后悬浮窗会变为可拖动的小型 HUD，并停止绘制全屏检测框，避免拦截游戏触摸。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingSwitch("贴边吸附", config.overlay.snapToEdge) { value -> update { it.copy(overlay = it.overlay.copy(snapToEdge = value)) } }
        SettingSwitch("锁定位置", config.overlay.lockPosition) { value -> update { it.copy(overlay = it.overlay.copy(lockPosition = value)) } }
    }
}

@Composable
private fun CaptureSection(config: AppConfig, capabilities: List<top.azek431.hzzs.platform.compat.CaptureCapability>, update: ((AppConfig) -> AppConfig) -> Unit) {
    HzzsSection("截图方式", "自动推荐不会尝试 Root 或 ADB。高级方式必须由玩家手动选择。") {
        capabilities.forEach { capability ->
            ListItem(
                headlineContent = { Text(capability.title) },
                supportingContent = { Text(capability.summary) },
                leadingContent = { RadioButton(selected = config.captureBackend == capability.backend, onClick = null) },
                trailingContent = { if (!capability.supported) Text("不可用", color = MaterialTheme.colorScheme.error) else if (capability.recommended) Text("推荐") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = capability.supported) {
                        update { it.copy(captureBackend = capability.backend) }
                    },
                colors = ListItemDefaults.colors(containerColor = if (config.captureBackend == capability.backend) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
            )
            HorizontalDivider()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            capabilities.forEach { capability ->
                FilterChip(
                    selected = config.captureBackend == capability.backend,
                    enabled = capability.supported,
                    onClick = { update { it.copy(captureBackend = capability.backend) } },
                    label = { Text(capability.title) },
                )
            }
        }
    }
}

@Composable
private fun VisionSection(config: AppConfig, update: ((AppConfig) -> AppConfig) -> Unit) {
    val scene = config.scenes.getValue(config.selectedScene)
    HzzsSection("识别算法", "采用比例坐标适配不同分辨率；默认识别当前赛季的全部障碍。") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SceneId.entries.forEach { id ->
                FilterChip(selected = config.selectedScene == id, onClick = { update { it.copy(selectedScene = id) } }, label = { Text(id.label()) })
            }
        }
        Text("不识别的障碍", style = MaterialTheme.typography.labelLarge)
        obstaclesFor(config.selectedScene).forEach { obstacle ->
            val enabled = obstacle !in scene.disabledObstacles
            SettingSwitch(obstacle.label(), enabled) { include ->
                update { app ->
                    val current = app.scenes.getValue(app.selectedScene)
                    val disabled = current.disabledObstacles.toMutableSet().apply { if (include) remove(obstacle) else add(obstacle) }
                    app.copy(scenes = app.scenes + (app.selectedScene to current.copy(disabledObstacles = disabled)))
                }
            }
        }
        Text("玩家基准", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerReferenceMode.entries.forEach { mode ->
                FilterChip(selected = scene.thresholds.playerReferenceMode == mode, onClick = { updateScene(update, config) { it.copy(thresholds = it.thresholds.copy(playerReferenceMode = mode)) } }, label = { Text(mode.label()) })
            }
        }
        if (scene.thresholds.playerReferenceMode == PlayerReferenceMode.FIXED_RATIO) {
            LabeledSlider("玩家水平位置", scene.thresholds.fixedPlayerXRatio, 0.05f..0.45f) { value -> updateScene(update, config) { it.copy(thresholds = it.thresholds.copy(fixedPlayerXRatio = value)) } }
        }
        LabeledSlider("识别工作宽度", scene.thresholds.workWidth.toFloat(), 192f..960f, valueText = { it.toInt().toString() }) { value -> updateScene(update, config) { it.copy(thresholds = it.thresholds.copy(workWidth = value.toInt())) } }
        LabeledSlider("最低置信度", scene.thresholds.minimumConfidence, 0.4f..0.95f) { value -> updateScene(update, config) { it.copy(thresholds = it.thresholds.copy(minimumConfidence = value)) } }
    }
}

@Composable
private fun AutomationSection(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    onEnableRequested: () -> Unit,
    onDisable: () -> Unit,
) {
    HzzsSection("自动操作", "默认关闭。开启后仍需在每次运行会话中手动解锁。") {
        SettingSwitch("启用自动操作", config.automation.enabled) { enabled ->
            if (enabled) onEnableRequested() else onDisable()
        }
        SettingSwitch(
            "允许竹影书屋实验性自动操作",
            config.automation.bambooExperimentalAutoAction,
        ) { value ->
            update {
                it.copy(automation = it.automation.copy(bambooExperimentalAutoAction = value))
            }
        }
        Text(
            "竹影书屋动作阈值仍属实验配置：即使已解锁会话，也需单独打开上方开关。甜甜圈使用历史标定距离 ${"%.2f".format(config.automation.sweetTriggerDistancePlayerWidths)} 倍玩家宽度触发。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "自动操作依赖无障碍手势，可能因游戏更新、网络延迟或识别误差产生错误操作。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun McpSection(config: AppConfig, update: ((AppConfig) -> AppConfig) -> Unit) {
    HzzsSection("MCP 服务", "让 Claude Code 等 AI 读取状态和操作应用。默认每次写操作都需要确认。") {
        SettingSwitch("启用 MCP", config.mcp.enabled) { value -> update { it.copy(mcp = it.mcp.copy(enabled = value)) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            McpPermissionLevel.entries.forEach { level ->
                FilterChip(selected = config.mcp.permissionLevel == level, onClick = { update { it.copy(mcp = it.mcp.copy(permissionLevel = level)) } }, label = { Text(level.label()) })
            }
        }
        Text("完整访问允许 AI 执行应用内所有功能，但无法绕过 Android 系统权限窗口。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSwitch(
            "允许 MCP 读取调试帧元数据",
            config.mcp.allowDebugFrames,
        ) { value ->
            update { it.copy(mcp = it.mcp.copy(allowDebugFrames = value)) }
        }
        if (config.mcp.allowDebugFrames && !config.developer.enabled) {
            Text(
                "该权限只有在关于页开启开发者设置后才生效。图片仍保存在应用私有目录，MCP 默认只返回文件元数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateSection(
    config: AppConfig,
    updateState: UpdateUiState,
    update: ((AppConfig) -> AppConfig) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
) {
    HzzsSection("更新", "Gitee 优先、GitHub 校验的签名更新源。未发布正式索引时检查失败是预期行为。") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateChannel.entries.forEach { channel ->
                FilterChip(
                    selected = config.update.channel == channel,
                    onClick = { update { it.copy(update = it.update.copy(channel = channel)) } },
                    label = {
                        Text(
                            when (channel) {
                                UpdateChannel.STABLE -> "稳定"
                                UpdateChannel.BETA -> "测试"
                            },
                        )
                    },
                )
            }
        }
        SettingSwitch("自动检查更新", config.update.autoCheck) { value ->
            update { it.copy(update = it.update.copy(autoCheck = value)) }
        }
        SettingSwitch("仅 Wi-Fi 下载", config.update.wifiOnly) { value ->
            update { it.copy(update = it.update.copy(wifiOnly = value)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheck, enabled = !updateState.busy) {
                Text(if (updateState.busy) "处理中…" else "检查更新")
            }
            if (updateState.available != null) {
                OutlinedButton(onClick = onDownload, enabled = !updateState.busy) { Text("下载") }
            }
            if (updateState.localApk != null) {
                Button(onClick = onInstall, enabled = !updateState.busy) { Text("安装") }
            }
            if (updateState.available != null) {
                TextButton(onClick = onIgnore, enabled = !updateState.busy) { Text("忽略此版本") }
            }
        }
        updateState.available?.let { available ->
            Text(
                "远端 ${available.manifest.versionName}（code ${available.manifest.versionCode}，来源 ${available.source.name}）\n${available.manifest.releaseNotes.take(240)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        updateState.message?.let { text ->
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AutomationRiskDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf(4) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1_000); remaining-- }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null) },
        title = { Text("自动操作风险说明") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动操作会通过无障碍服务模拟点击或滑动。识别误差、系统卡顿、游戏更新和网络延迟都可能导致错误操作。请自行承担使用风险。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text("我已阅读并理解风险")
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = remaining == 0 && checked) { Text(if (remaining > 0) "请等待 ${remaining}s" else "确认开启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HexColorField(title: String, color: Int, onColorChange: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf("#%08X".format(color)) }
    val parsed = remember(text) { parseHexColor(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            if (next.length <= 9) {
                text = next.uppercase()
                parseHexColor(next)?.let(onColorChange)
            }
        },
        label = { Text(title) },
        supportingText = {
            Text(if (parsed == null) "格式：#RRGGBB 或 #AARRGGBB" else "颜色预览会立即应用，保存后永久生效")
        },
        isError = parsed == null,
        leadingIcon = {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = MaterialTheme.shapes.small,
                color = androidx.compose.ui.graphics.Color(parsed ?: color),
            ) {}
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun parseHexColor(raw: String): Int? {
    val digits = raw.trim().removePrefix("#")
    if (digits.length !in setOf(6, 8) || digits.any { !it.isDigit() && it.uppercaseChar() !in 'A'..'F' }) return null
    return runCatching {
        val value = digits.toULong(16)
        val argb = if (digits.length == 6) value or 0xFF000000uL else value
        argb.toLong().toInt()
    }.getOrNull()
}

@Composable
private fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: (Float) -> String = { "%.2f".format(it) }, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title); Text(valueText(value), color = MaterialTheme.colorScheme.primary) }
        Slider(value = value.coerceIn(range), onValueChange = onValueChange, valueRange = range)
    }
}

private fun updateScene(update: ((AppConfig) -> AppConfig) -> Unit, config: AppConfig, transform: (SceneConfig) -> SceneConfig) {
    update { app -> app.copy(scenes = app.scenes + (config.selectedScene to transform(app.scenes.getValue(config.selectedScene)))) }
}

private fun obstaclesFor(scene: SceneId): List<ObstacleKind> = when (scene) {
    SceneId.SWEET_FACTORY -> listOf(ObstacleKind.POISON_BOTTLE, ObstacleKind.CAKE_STRUCTURE, ObstacleKind.HANGING_SPIKE, ObstacleKind.PIT)
    SceneId.BAMBOO_BOOKSTORE -> listOf(ObstacleKind.PANDA_STATUE, ObstacleKind.BAMBOO_GAP, ObstacleKind.HANGING_BRUSH, ObstacleKind.PIT)
}

private fun copyTheme(context: Context, raw: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("HZZS Theme", raw))
}

private fun AppThemeMode.label() = when (this) { AppThemeMode.SYSTEM -> "跟随系统"; AppThemeMode.LIGHT -> "浅色"; AppThemeMode.DARK -> "深色"; AppThemeMode.AMOLED -> "纯黑" }
private fun ThemePreset.label() = when (this) { ThemePreset.DYNAMIC -> "动态取色"; ThemePreset.FIRE_ORANGE -> "焰火橙"; ThemePreset.CORAL -> "珊瑚红"; ThemePreset.BAMBOO -> "竹影青"; ThemePreset.OCEAN -> "深海蓝"; ThemePreset.INDIGO -> "靛青"; ThemePreset.LAVENDER -> "紫晶夜"; ThemePreset.BLACK_GOLD -> "黑金"; ThemePreset.HIGH_CONTRAST -> "高对比"; ThemePreset.CUSTOM -> "自定义" }
private fun OverlayStyle.label() = when (this) { OverlayStyle.MINIMAL -> "极简"; OverlayStyle.COMPACT -> "紧凑"; OverlayStyle.DEBUG_HUD -> "调试 HUD" }
private fun OverlayTheme.label() = when (this) { OverlayTheme.FOLLOW_APP -> "跟随应用"; OverlayTheme.AUTO_CONTRAST -> "自动对比"; OverlayTheme.DARK_GLASS -> "深色玻璃"; OverlayTheme.LIGHT_GLASS -> "浅色玻璃"; OverlayTheme.AMOLED -> "纯黑"; OverlayTheme.FIRE_ORANGE -> "焰火橙"; OverlayTheme.BAMBOO -> "竹影青"; OverlayTheme.NEON_GREEN -> "霓虹绿"; OverlayTheme.WARNING_ORANGE -> "警示橙"; OverlayTheme.CUSTOM -> "自定义" }
private fun SceneId.label() = if (this == SceneId.SWEET_FACTORY) "甜甜圈" else "竹影书屋"
private fun PlayerReferenceMode.label() = when (this) { PlayerReferenceMode.FIXED_RATIO -> "固定比例"; PlayerReferenceMode.DETECT_ONCE -> "启动检测一次"; PlayerReferenceMode.CONTINUOUS -> "持续检测" }
private fun ObstacleKind.label() = when (this) { ObstacleKind.POISON_BOTTLE -> "毒药瓶"; ObstacleKind.CAKE_STRUCTURE -> "蛋糕结构"; ObstacleKind.HANGING_SPIKE -> "悬挂尖刺"; ObstacleKind.PIT -> "地坑"; ObstacleKind.PANDA_STATUE -> "熊猫摆件"; ObstacleKind.BAMBOO_GAP -> "竹林缺口"; ObstacleKind.HANGING_BRUSH -> "悬挂毛笔" }
private fun McpPermissionLevel.label() = when (this) { McpPermissionLevel.READ_ONLY -> "只读"; McpPermissionLevel.ASK_EVERY_TIME -> "每次确认"; McpPermissionLevel.TRUSTED_SESSION -> "信任本次会话"; McpPermissionLevel.FULL_ACCESS -> "完整访问" }
