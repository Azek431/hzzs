/**
 * 关于页与开发者入口。
 *
 * 职责：展示版本/免责声明/捐赠入口；连续点击版本号 7 次开启 [DeveloperConfig.enabled]。
 * 数据流：读 [SettingsRepository] 与 MCP 状态；写开发者开关与诊断相关字段。
 * 边界：不直接操作 WindowManager / Root / JNI；Native 自检经 [NativeBenchmarkRunner]，
 * 调试帧经 [DebugFrameRecorder]，MCP 仅展示连接信息。
 */
package top.azek431.hzzs.feature.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.logging.AlgorithmDiagnosticsSnapshot
import top.azek431.hzzs.core.logging.DiagnosticsExporter
import top.azek431.hzzs.core.logging.McpDiagnosticsSnapshot
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.DeveloperConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.developerLabel
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.platform.ClipboardHelper
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.algorithm.AlgorithmActivationCoordinator
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.NativeBenchmarkResult
import top.azek431.hzzs.data.vision.NativeBenchmarkRunner
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.domain.vision.VisionEngine
import top.azek431.hzzs.mcp.McpServerState
import top.azek431.hzzs.mcp.McpUiBridge
import top.azek431.hzzs.nativevision.NativeVision
import javax.inject.Inject

enum class DonationKind { WECHAT, ALIPAY }

/** 关于页状态：配置流、MCP 运行态、调试帧计数与 Native 自检结果。 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    mcpUiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
    private val benchmarkRunner: NativeBenchmarkRunner,
    private val visionEngine: VisionEngine,
    private val visionRuntime: VisionRuntimeController,
    private val algorithmActivation: AlgorithmActivationCoordinator,
) : ViewModel() {
    val config: StateFlow<AppConfig> = repository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
    val mcpState: StateFlow<McpServerState> = mcpUiBridge.serverState
    private val mutableDebugFrameCount = MutableStateFlow(0)
    val debugFrameCount: StateFlow<Int> = mutableDebugFrameCount.asStateFlow()
    private val mutableBenchmark = MutableStateFlow<Result<NativeBenchmarkResult>?>(null)
    val benchmark: StateFlow<Result<NativeBenchmarkResult>?> = mutableBenchmark.asStateFlow()

    init {
        refreshDebugFrameCount()
    }

    fun setDeveloperEnabled(enabled: Boolean) = updateDeveloper { it.copy(enabled = enabled) }

    fun updateDeveloper(transform: (DeveloperConfig) -> DeveloperConfig) {
        viewModelScope.launch {
            val current = repository.snapshot()
            repository.save(current.copy(developer = transform(current.developer)))
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
        val iterations = config.value.developer.nativeBenchmarkIterations
        viewModelScope.launch { mutableBenchmark.value = benchmarkRunner.run(iterations) }
    }

    /** 脱敏诊断摘要：版本 / 机型 / 配置 / 算法激活 / 运行态 / 最近日志；不含 Bearer。 */
    fun buildDiagnosticsReport(): String {
        val current = config.value
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
            config = current,
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
}

/**
 * 关于主界面；开发者已开启时可进入 [DeveloperScreen]。
 * 版本号 AssistChip 连点 7 次调用 [AboutViewModel.setDeveloperEnabled]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onSaveQr: (DonationKind) -> Unit,
    vm: AboutViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    val mcpState by vm.mcpState.collectAsState()
    val debugFrameCount by vm.debugFrameCount.collectAsState()
    val benchmark by vm.benchmark.collectAsState()
    var donation by remember { mutableStateOf<DonationKind?>(null) }
    var developerPage by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var unlockMessage by remember { mutableStateOf<String?>(null) }

    if (developerPage && config.developer.enabled) {
        DeveloperScreen(
            config = config,
            mcpState = mcpState,
            onBack = { developerPage = false },
            onEnabledChange = { enabled -> vm.setDeveloperEnabled(enabled); if (!enabled) developerPage = false },
            onUpdate = vm::updateDeveloper,
            debugFrameCount = debugFrameCount,
            benchmark = benchmark,
            onRefreshDebugFrames = vm::refreshDebugFrameCount,
            onClearDebugFrames = vm::clearDebugFrames,
            onRunBenchmark = vm::runNativeBenchmark,
            onBuildDiagnostics = vm::buildDiagnosticsReport,
        )
        return
    }

    val dimensions = LocalHzzsDimensions.current
    Scaffold(topBar = { TopAppBar(title = { Text("关于") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap),
        ) {
            item {
                ElevatedCard(
                    colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(dimensions.cardPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("HZZS", style = MaterialTheme.typography.headlineSmall)
                        Text("本地识别 · 多赛季障碍分析 · 高度自定义", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        AssistChip(
                            onClick = {
                                versionTapCount++
                                val remaining = 7 - versionTapCount
                                when {
                                    remaining > 0 && remaining <= 3 -> unlockMessage = "再点击 $remaining 次即可开启开发者设置"
                                    remaining <= 0 -> {
                                        versionTapCount = 0
                                        vm.setDeveloperEnabled(true)
                                        unlockMessage = "开发者设置已开启"
                                    }
                                }
                            },
                            label = { Text("版本 $versionName") },
                            leadingIcon = { Icon(Icons.Rounded.Tag, null) },
                        )
                        unlockMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            if (config.developer.enabled) {
                item {
                    ListItem(
                        headlineContent = { Text("开发者设置") },
                        supportingContent = { Text("诊断、MCP、截图覆盖、坐标网格与 Native Benchmark") },
                        leadingContent = { Icon(Icons.Rounded.DeveloperMode, null) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clickable { developerPage = true },
                    )
                }
            }
            item {
                SectionCard {
                    Text("项目说明", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "HZZS 用于火崽崽奇妙屋的本地画面分析。截图与识别默认仅在设备本地完成，不上传画面。自动操作高风险且默认关闭。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HzzsCallout(
                    title = "免责声明",
                    text = "第三方技术研究工具，与游戏及平台官方无关。请遵守规则与法律，并自行承担识别误差、自动操作与权限配置风险。",
                    tone = HzzsCalloutTone.WARNING,
                )
            }
            item {
                Text("支持开发", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { donation = DonationKind.WECHAT }) { Icon(Icons.Rounded.QrCode2, null); Spacer(Modifier.width(6.dp)); Text("微信") }
                    FilledTonalButton(onClick = { donation = DonationKind.ALIPAY }) { Icon(Icons.Rounded.AccountBalanceWallet, null); Spacer(Modifier.width(6.dp)); Text("支付宝") }
                }
            }
            item { AboutRow(Icons.Rounded.Code, "GitHub", "源码、Issue 与版本记录", "https://github.com/Azek431/hzzs") }
            item { AboutRow(Icons.AutoMirrored.Rounded.Article, "开源许可", "查看项目许可证") }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    donation?.let { kind ->
        DonationDialog(kind, onDismiss = { donation = null }, onSave = { onSaveQr(kind) })
    }
}

/**
 * 开发者诊断页：强制截图后端、调试帧、坐标网格、日志级别、Native 自检与诊断导出。
 * 仅改 [DeveloperConfig]；不直接拉起截图或 MCP 服务。设置页「MCP 与开发者」含同等字段。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeveloperScreen(
    config: AppConfig,
    mcpState: McpServerState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onUpdate: ((DeveloperConfig) -> DeveloperConfig) -> Unit,
    debugFrameCount: Int,
    benchmark: Result<NativeBenchmarkResult>?,
    onRefreshDebugFrames: () -> Unit,
    onClearDebugFrames: () -> Unit,
    onRunBenchmark: () -> Unit,
    onBuildDiagnostics: () -> String,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发者设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ElevatedCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("开发者设置", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "关闭后入口会隐藏，可再次连续点击版本号 7 次开启。完整项也在设置 → MCP 与开发者。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = config.developer.enabled, onCheckedChange = onEnabledChange)
                    }
                }
            }
            item {
                Text("强制截图后端", style = MaterialTheme.typography.titleMedium)
                Text("仅用于诊断。选择“跟随设置”可恢复普通用户配置。AUTO 仍不升权。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<CaptureBackend?>(null) + CaptureBackend.entries).forEach { backend ->
                        FilterChip(
                            selected = config.developer.forceCaptureBackend == backend,
                            onClick = { onUpdate { it.copy(forceCaptureBackend = backend) } },
                            label = { Text(backend?.developerLabel() ?: "跟随设置") },
                        )
                    }
                }
            }
            item {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeveloperSwitch("保存调试帧", config.developer.saveDebugFrames) { value ->
                            onUpdate { it.copy(saveDebugFrames = value) }
                        }
                        Text(
                            "最多保留 20 张，每 5 秒采样一次，仅存储在应用私有目录。当前：$debugFrameCount 张",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRefreshDebugFrames) { Text("刷新") }
                            TextButton(onClick = onClearDebugFrames, enabled = debugFrameCount > 0) { Text("清除") }
                        }
                    }
                }
            }
            item {
                DeveloperSwitch("显示比例坐标网格", config.developer.showCoordinateGrid) { value ->
                    onUpdate { it.copy(showCoordinateGrid = value) }
                }
            }
            item {
                Text("日志级别：${config.developer.logLevel.displayName()}", style = MaterialTheme.typography.titleMedium)
                Text("控制 Logcat 与内存 ring buffer；关闭开发者后 DEBUG 以下不入缓冲。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLogLevel.entries.forEach { level ->
                        FilterChip(
                            selected = config.developer.logLevel == level,
                            onClick = { onUpdate { it.copy(logLevel = level) } },
                            label = { Text(level.name) },
                        )
                    }
                }
            }
            item {
                Text(
                    "识别帧率上限：${config.developer.frameRateLimit}（保留字段，完成驱动下暂不消费）",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = config.developer.frameRateLimit.toFloat(),
                    onValueChange = { value -> onUpdate { it.copy(frameRateLimit = value.toInt()) } },
                    valueRange = 1f..120f,
                )
            }
            item {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Native 自检次数：${config.developer.nativeBenchmarkIterations}")
                        Slider(
                            value = config.developer.nativeBenchmarkIterations.toFloat(),
                            onValueChange = { value -> onUpdate { it.copy(nativeBenchmarkIterations = value.toInt()) } },
                            valueRange = 10f..1000f,
                        )
                        Button(onClick = onRunBenchmark) { Text("运行 JNI + C++ 合成帧自检") }
                        benchmark?.fold(
                            onSuccess = { result ->
                                Text(
                                    "${result.iterations} 次 · 平均 %.3f ms · P50 %.3f ms · P95 %.3f ms".format(
                                        result.meanMs,
                                        result.p50Ms,
                                        result.p95Ms,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onFailure = { error ->
                                Text(
                                    "自检失败：${error.message}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                }
            }
            item {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MCP 本地服务", style = MaterialTheme.typography.titleMedium)
                        Text(if (mcpState.running) "运行中 · 127.0.0.1:${mcpState.port}" else "未运行")
                        mcpState.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (mcpState.running) {
                            Button(onClick = {
                                val command =
                                    "adb forward tcp:${mcpState.port} tcp:${mcpState.port}\n" +
                                        "Authorization: Bearer ${mcpState.token}"
                                val ok = ClipboardHelper.copyText(context, "HZZS MCP", command)
                                Toast.makeText(
                                    context,
                                    if (ok) {
                                        "MCP 连接信息已复制（含 Bearer，勿公开分享）"
                                    } else {
                                        "复制失败：剪贴板不可用"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                Icon(Icons.Rounded.ContentCopy, null)
                                Spacer(Modifier.width(6.dp))
                                Text("复制连接信息")
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val report = onBuildDiagnostics()
                            check(report.isNotBlank()) { "诊断内容为空" }
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                                putExtra(Intent.EXTRA_SUBJECT, "HZZS diagnostics")
                            }
                            context.startActivity(Intent.createChooser(send, "导出诊断摘要"))
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                "导出失败：${error.message ?: error.javaClass.simpleName}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.BugReport, null)
                    Spacer(Modifier.width(6.dp))
                    Text("导出诊断摘要")
                }
            }
            item {
                TextButton(
                    onClick = {
                        val report = runCatching { onBuildDiagnostics() }.getOrElse { error ->
                            Toast.makeText(
                                context,
                                "生成诊断失败：${error.message ?: error.javaClass.simpleName}",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@TextButton
                        }
                        if (report.isBlank()) {
                            Toast.makeText(context, "诊断内容为空", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val ok = ClipboardHelper.copyText(context, "HZZS diagnostics", report)
                        Toast.makeText(
                            context,
                            if (ok) {
                                "诊断摘要已复制（${report.lines().size} 行）"
                            } else {
                                "复制失败：剪贴板不可用"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Text("复制诊断摘要到剪贴板")
                }
            }
        }
    }
}

@Composable
private fun DeveloperSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DonationDialog(kind: DonationKind, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (kind == DonationKind.WECHAT) {
                    stringResource(R.string.about_donation_wechat)
                } else {
                    stringResource(R.string.about_donation_alipay)
                },
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val id = if (kind == DonationKind.WECHAT) {
                    R.drawable.donation_wechat
                } else {
                    R.drawable.donation_alipay
                }
                Image(
                    bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id),
                    contentDescription = stringResource(R.string.about_donation_qr_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .sizeIn(maxWidth = 340.dp, maxHeight = 460.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onSave() })
                        },
                )
                Text(
                    stringResource(R.string.about_donation_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(R.string.action_save_to_gallery))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    url: String? = null,
) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { if (url != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(enabled = url != null) {
            url?.let { target ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    .onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.about_open_link_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
            }
        },
    )
}

