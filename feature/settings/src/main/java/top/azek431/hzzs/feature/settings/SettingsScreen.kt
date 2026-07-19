package top.azek431.hzzs.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.model.*
import top.azek431.hzzs.core.preferences.SettingsEditSession
import top.azek431.hzzs.core.preferences.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {
    private val _draft = MutableStateFlow(AppConfig())
    val draft: StateFlow<AppConfig> = _draft
    private var session: SettingsEditSession? = null

    init { viewModelScope.launch { open() } }
    private suspend fun open() {
        val original = repository.snapshot()
        _draft.value = original
        session = SettingsEditSession(original, onPreview = { config -> _draft.value = config; repository.preview(config) }, onPersist = repository::save)
    }
    fun update(transform: (AppConfig) -> AppConfig) = viewModelScope.launch { session?.update(transform) }
    fun save(onDone: () -> Unit) = viewModelScope.launch { session?.save(); onDone() }
    fun discard(onDone: () -> Unit) = viewModelScope.launch { session?.discard(); repository.clearPreview(); onDone() }
    fun discardSilently() = viewModelScope.launch { session?.discard(); repository.clearPreview() }
    fun export(): String = repository.exportJson(_draft.value)
    fun import(json: String) = viewModelScope.launch { runCatching { repository.importJson(json) }.onSuccess { config -> session?.update { config } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onExit: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val config by vm.draft.collectAsState()
    var showLeave by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取配置") }
            .onSuccess { vm.import(it); Toast.makeText(context, "配置已载入为临时预览", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(vm.export()) } ?: error("无法写入配置") }
            .onSuccess { Toast.makeText(context, "配置已导出", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) vm.discardSilently() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = { showLeave = true }) { Icon(Icons.Rounded.ArrowBack, null) } }, actions = { TextButton(onClick = { vm.save(onExit) }) { Text("保存") } }) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.discard(onExit) }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { vm.save(onExit) }, modifier = Modifier.weight(1f)) { Text("保存更改") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("更改会立即预览，但只有点击保存才会永久生效。离开页面会恢复原配置。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SectionTitle("外观") }
            item { ThemeModeSetting(config.theme.mode) { mode -> vm.update { it.copy(theme = it.theme.copy(mode = mode)) } } }
            item { ThemePresetSetting(config.theme.preset) { preset -> vm.update { it.copy(theme = it.theme.copy(preset = preset)) } } }
            item { SwitchSetting("动态取色", "Android 12+ 从壁纸生成颜色", config.theme.dynamicColorEnabled) { enabled -> vm.update { it.copy(theme = it.theme.copy(dynamicColorEnabled = enabled)) } } }
            item { SectionTitle("悬浮窗") }
            item { EnumDropdown("悬浮窗主题", config.overlay.theme, OverlayTheme.entries) { value -> vm.update { it.copy(overlay = it.overlay.copy(theme = value)) } } }
            item { SliderSetting("背景透明度", config.overlay.backgroundAlpha, 0.15f..1f) { value -> vm.update { it.copy(overlay = it.overlay.copy(backgroundAlpha = value)) } } }
            item { SwitchSetting("磨砂效果", "支持时使用原生模糊，不支持时安全降级", config.overlay.blurEnabled) { value -> vm.update { it.copy(overlay = it.overlay.copy(blurEnabled = value)) } } }
            item { SectionTitle("火崽崽奇妙屋") }
            item { EnumDropdown("主题场景", config.selectedScene, SceneId.entries) { scene -> vm.update { it.copy(selectedScene = scene) } } }
            item { EnumDropdown("截图方式", config.captureBackend, CaptureBackend.entries) { backend -> vm.update { it.copy(captureBackend = backend) } } }
            item {
                val scene = config.scenes.getValue(config.selectedScene)
                SliderSetting("最低置信度", scene.thresholds.minimumConfidence, 0.1f..1f) { value ->
                    vm.update { app -> app.copy(scenes = app.scenes + (app.selectedScene to scene.copy(thresholds = scene.thresholds.copy(minimumConfidence = value)))) }
                }
            }
            item { SectionTitle("配置管理") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, label = { Text("导入") }, leadingIcon = { Icon(Icons.Rounded.FileOpen, null) })
                    AssistChip(onClick = { exportLauncher.launch("hzzs-config-v${config.schemaVersion}.json") }, label = { Text("导出") }, leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) })
                    AssistChip(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("HZZS 配置", vm.export()))
                        Toast.makeText(context, "配置已复制，自动操作状态未包含", Toast.LENGTH_SHORT).show()
                    }, label = { Text("复制 JSON") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                }
            }
        }
    }
    if (showLeave) AlertDialog(onDismissRequest = { showLeave = false }, title = { Text("放弃临时更改？") }, text = { Text("尚未保存的预览参数将恢复为打开页面前的值。") }, confirmButton = { TextButton(onClick = { vm.discard(onExit) }) { Text("放弃") } }, dismissButton = { TextButton(onClick = { showLeave = false }) { Text("继续编辑") } })
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable private fun ThemeModeSetting(value: AppThemeMode, onChange: (AppThemeMode) -> Unit) = EnumDropdown("显示模式", value, AppThemeMode.entries, onChange)
@Composable private fun ThemePresetSetting(value: ThemePreset, onChange: (ThemePreset) -> Unit) = EnumDropdown("主题颜色", value, ThemePreset.entries, onChange)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun <T : Enum<T>> EnumDropdown(title: String, value: T, values: List<T>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value = value.name, onValueChange = {}, readOnly = true, label = { Text(title) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { expanded = false; onChange(item) }) }
        }
    }
}

@Composable private fun SwitchSetting(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) }, trailingContent = { Switch(checked = value, onCheckedChange = onChange) })
}
@Composable private fun SliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column { Text("$title · ${"%.2f".format(value)}"); Slider(value = value, onValueChange = onChange, valueRange = range) }
}
