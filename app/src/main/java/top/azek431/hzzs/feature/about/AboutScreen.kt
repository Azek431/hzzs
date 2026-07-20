package top.azek431.hzzs.feature.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.R
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.DeveloperConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.NativeBenchmarkResult
import top.azek431.hzzs.data.vision.NativeBenchmarkRunner
import top.azek431.hzzs.mcp.McpServerState
import top.azek431.hzzs.mcp.McpUiBridge
import javax.inject.Inject

enum class DonationKind { WECHAT, ALIPAY }

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val repository: SettingsRepository,
    mcpUiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
    private val benchmarkRunner: NativeBenchmarkRunner,
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
}

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
        )
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("关于") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("HZZS 火崽崽数据分析", style = MaterialTheme.typography.headlineSmall)
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
                Text("项目说明", style = MaterialTheme.typography.titleMedium)
                Text("HZZS 主要用于火崽崽奇妙屋的本地画面分析。截图和识别默认在设备本地完成，不上传游戏画面。自动操作属于高风险可选功能，默认关闭。")
            }
            item {
                Text("免责声明", style = MaterialTheme.typography.titleMedium)
                Text("本项目为第三方技术研究与数据分析工具，与游戏及平台官方无关。使用者应遵守游戏规则、平台条款和当地法律，并自行承担由识别误差、自动操作或权限配置产生的风险。")
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
        DonationDialog(kind, onDismiss = { donation = null }, onLongPress = { onSaveQr(kind) })
    }
}

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
                        Column(Modifier.weight(1f)) { Text("开发者设置", style = MaterialTheme.typography.titleMedium); Text("关闭后入口会隐藏，可再次连续点击版本号 7 次开启。", style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = config.developer.enabled, onCheckedChange = onEnabledChange)
                    }
                }
            }
            item {
                Text("强制截图后端", style = MaterialTheme.typography.titleMedium)
                Text("仅用于诊断。选择“跟随设置”可恢复普通用户配置。", style = MaterialTheme.typography.bodySmall)
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
                        Text("最多保留 20 张，每 5 秒采样一次，仅存储在应用私有目录。当前：$debugFrameCount 张", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRefreshDebugFrames) { Text("刷新") }
                            TextButton(onClick = onClearDebugFrames, enabled = debugFrameCount > 0) { Text("清除") }
                        }
                    }
                }
            }
            item { DeveloperSwitch("显示比例坐标网格", config.developer.showCoordinateGrid) { value -> onUpdate { it.copy(showCoordinateGrid = value) } } }
            item {
                Text("识别帧率上限：${config.developer.frameRateLimit}")
                Slider(value = config.developer.frameRateLimit.toFloat(), onValueChange = { value -> onUpdate { it.copy(frameRateLimit = value.toInt()) } }, valueRange = 1f..120f)
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
                                Text("自检失败：${error.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                                val command = "adb forward tcp:${mcpState.port} tcp:${mcpState.port}\nAuthorization: Bearer ${mcpState.token}"
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HZZS MCP", command))
                            }) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("复制连接信息") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "HZZS developer diagnostics: mcp=${mcpState.running}, frameLimit=${config.developer.frameRateLimit}")
                    }
                    context.startActivity(Intent.createChooser(send, "导出诊断摘要"))
                }) { Icon(Icons.Rounded.BugReport, null); Spacer(Modifier.width(6.dp)); Text("导出诊断摘要") }
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
private fun DonationDialog(kind: DonationKind, onDismiss: () -> Unit, onLongPress: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, modifier = Modifier.padding(28.dp).clickable(enabled = false) {}) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (kind == DonationKind.WECHAT) "微信赞赏" else "支付宝赞赏", style = MaterialTheme.typography.titleLarge)
                val id = if (kind == DonationKind.WECHAT) R.drawable.donation_wechat else R.drawable.donation_alipay
                Image(bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.sizeIn(maxWidth = 340.dp, maxHeight = 460.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) })
                Text("长按保存图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun AboutRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, url: String? = null) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { if (url != null) Icon(Icons.Rounded.ChevronRight, null) },
        modifier = Modifier.clickable(enabled = url != null) {
            url?.let { target ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    .onFailure { Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show() }
            }
        },
    )
}

private fun CaptureBackend.developerLabel(): String = when (this) {
    CaptureBackend.AUTO -> "自动"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍"
    CaptureBackend.SHIZUKU -> "Shizuku"
    CaptureBackend.ROOT -> "Root"
}
