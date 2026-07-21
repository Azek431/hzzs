/**
 * 首次引导向导。
 *
 * 职责：分步编辑内存草稿 [AppConfig]；主题等可经 [onPreview] 即时预览。
 * 数据流：完成前不落盘；点“完成”时才 [onComplete] 写入并标记 onboarding/免责声明版本。
 * 边界：自动操作默认强制关闭；开启须风险对话框倒计时确认。不直接申请 Root/Shizuku/JNI。
 */
package top.azek431.hzzs.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.azek431.hzzs.core.designsystem.HzzsSection
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ThemePreset
import top.azek431.hzzs.platform.compat.isSupportedOnThisDevice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    initial: AppConfig,
    onPreview: (AppConfig) -> Unit,
    onComplete: (AppConfig) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    // 内存草稿；进入向导时强制关闭自动操作，完成前不持久化
    var draft by remember(initial) { mutableStateOf(initial.copy(automation = initial.automation.copy(enabled = false))) }
    var showAutomationRisk by remember { mutableStateOf(false) }
    val pages = onboardingPages

    fun update(transform: (AppConfig) -> AppConfig) {
        draft = transform(draft)
        onPreview(draft)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("欢迎使用 HZZS") },
                actions = { Text("${page + 1}/${pages.size}", modifier = Modifier.padding(end = 16.dp)) },
            )
        },
        bottomBar = {
            Column {
                LinearProgressIndicator(
                    progress = { (page + 1f) / pages.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    if (page > 0) OutlinedButton(onClick = { page-- }) { Text("上一步") }
                    Button(
                        onClick = {
                            if (page == pages.lastIndex) {
                                onComplete(
                                    draft.copy(
                                        onboarding = draft.onboarding.copy(
                                            completed = true,
                                            acceptedDisclaimerVersion = AppConfig.DISCLAIMER_VERSION,
                                        ),
                                    ),
                                )
                            } else {
                                page++
                            }
                        },
                    ) { Text(if (page == pages.lastIndex) "完成" else "下一步") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                val meta = pages[page]
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(meta.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(meta.title, style = MaterialTheme.typography.headlineSmall)
                        Text(meta.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                when (page) {
                    0 -> IntroPage()
                    1 -> PrivacyPage()
                    2 -> SeasonPage(draft) { scene -> update { it.copy(selectedScene = scene) } }
                    3 -> CapturePage(draft) { backend -> update { it.copy(captureBackend = backend) } }
                    4 -> AppearancePage(
                        draft = draft,
                        onTheme = { preset -> update { it.copy(theme = it.theme.copy(preset = preset)) } },
                        onOverlay = { style -> update { it.copy(overlay = it.overlay.copy(style = style)) } },
                    )
                    5 -> AutomationPage(
                        enabled = draft.automation.enabled,
                        onChange = { enabled ->
                            if (enabled) showAutomationRisk = true
                            else update { it.copy(automation = it.automation.copy(enabled = false)) }
                        },
                    )
                    else -> FinishPage(draft)
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAutomationRisk) {
        OnboardingAutomationRiskDialog(
            onDismiss = { showAutomationRisk = false },
            onConfirm = {
                showAutomationRisk = false
                update {
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
private fun IntroPage() = HzzsSection("项目介绍", "火崽崽奇妙屋本地数据分析工具") {
    Text("HZZS 在设备本地读取你主动授权的屏幕帧，识别赛季障碍并展示结构化结果。它不是游戏官方产品，也不保证游戏结果、账号安全或持续兼容。")
    Text("默认只启用低权限的数据分析。Root、Shizuku、无障碍自动操作与 MCP 完整访问都需要你主动开启。")
}

@Composable
private fun PrivacyPage() = HzzsSection("隐私与免责声明", "请在继续前理解数据边界") {
    Text("屏幕帧默认只在本机内存中处理，不上传、不写入相册。仅当你在开发者设置中主动保存调试帧时，图片才会落盘。")
    Text("自动操作、Root 与第三方 Shell 能力可能引发账号、设备稳定性和隐私风险。使用者应自行判断并承担相应后果。")
    Text("完成引导代表你已阅读并接受当前版本的简体中文免责声明。")
}

@Composable
private fun SeasonPage(config: AppConfig, onSelect: (SceneId) -> Unit) = HzzsSection("选择赛季", "两个算法共享比例坐标，但障碍贴图与规则独立") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SceneId.entries.forEach { scene ->
            FilterChip(
                selected = config.selectedScene == scene,
                onClick = { onSelect(scene) },
                label = { Text(scene.label()) },
            )
        }
    }
}

@Composable
private fun CapturePage(config: AppConfig, onSelect: (CaptureBackend) -> Unit) = HzzsSection("截图方式", "默认推荐屏幕录制，不会自动请求 Root 或 Shell") {
    listOf(CaptureBackend.AUTO, CaptureBackend.MEDIA_PROJECTION, CaptureBackend.ACCESSIBILITY).forEach { backend ->
        val supported = backend.isSupportedOnThisDevice()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(backend.label())
                Text(backend.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(
                selected = config.captureBackend == backend,
                enabled = supported,
                onClick = { onSelect(backend) },
                label = { Text(if (supported) "选择" else "系统不支持") },
            )
        }
    }
    Text("Shizuku 与 Root 位于高级/开发者设置中，首次引导不会推荐它们。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AppearancePage(
    draft: AppConfig,
    onTheme: (ThemePreset) -> Unit,
    onOverlay: (OverlayStyle) -> Unit,
) = HzzsSection("主题与悬浮窗", "此处会立即预览，完成引导后才保存") {
    Text("主题")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ThemePreset.FIRE_ORANGE, ThemePreset.BAMBOO, ThemePreset.OCEAN, ThemePreset.BLACK_GOLD).forEach { preset ->
            FilterChip(selected = draft.theme.preset == preset, onClick = { onTheme(preset) }, label = { Text(preset.label()) })
        }
    }
    Text("悬浮窗")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OverlayStyle.entries.forEach { style ->
            FilterChip(selected = draft.overlay.style == style, onClick = { onOverlay(style) }, label = { Text(style.label()) })
        }
    }
}

@Composable
private fun AutomationPage(enabled: Boolean, onChange: (Boolean) -> Unit) = HzzsSection("自动操作", "默认关闭，可稍后在设置中开启") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("允许自动操作")
            Text("需要无障碍服务、风险确认和每次会话解锁。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun FinishPage(config: AppConfig) = HzzsSection("配置完成", "点击完成后进入首页") {
    Text("赛季：${config.selectedScene.label()}")
    Text("截图：${config.captureBackend.label()}")
    Text("主题：${config.theme.preset.label()}")
    Text("悬浮窗：${config.overlay.style.label()}")
    Text("自动操作：${if (config.automation.enabled) "已启用，仍需会话解锁" else "关闭"}")
    Text("所有配置以后都能在设置中修改。修改会先临时预览，点击保存后才永久生效。")
}

@Composable
private fun OnboardingAutomationRiskDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf(4) }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
        title = { Text("自动操作风险提示") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动操作会通过无障碍服务发送手势，可能因游戏更新、误识别、设备卡顿或规则变化产生错误操作。请勿在不能承担风险的账号或设备上使用。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text("我已阅读并理解风险")
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = remaining == 0 && accepted) {
                Text(if (remaining > 0) "请等待 ${remaining}s" else "确认开启")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("保持关闭") } },
    )
}

private data class OnboardingPage(val title: String, val subtitle: String, val icon: ImageVector)
private val onboardingPages = listOf(
    OnboardingPage("认识 HZZS", "本地、可控、面向火崽崽奇妙屋", Icons.Rounded.Info),
    OnboardingPage("隐私与边界", "先了解权限和风险", Icons.Rounded.Security),
    OnboardingPage("选择赛季", "甜甜圈或竹影书屋", Icons.Rounded.Analytics),
    OnboardingPage("截图方式", "优先低权限公开 API", Icons.Rounded.DesktopWindows),
    OnboardingPage("外观", "主题与悬浮窗", Icons.Rounded.ColorLens),
    OnboardingPage("自动操作", "默认关闭，按需启用", Icons.Rounded.Tune),
    OnboardingPage("准备完成", "保存配置并进入首页", Icons.Rounded.CheckCircle),
)

private fun SceneId.label() = when (this) {
    SceneId.SWEET_FACTORY -> "甜甜圈赛季"
    SceneId.BAMBOO_BOOKSTORE -> "竹影书屋赛季"
}

private fun CaptureBackend.label() = when (this) {
    CaptureBackend.AUTO -> "自动推荐"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍截图"
    CaptureBackend.SHIZUKU -> "Shizuku / ADB"
    CaptureBackend.ROOT -> "Root"
}

private fun CaptureBackend.summary() = when (this) {
    CaptureBackend.AUTO -> "使用稳定的低权限路径，不会自动升级到 Root。"
    CaptureBackend.MEDIA_PROJECTION -> "Android 7+ 推荐，系统会显示屏幕捕获授权。"
    CaptureBackend.ACCESSIBILITY -> "Android 11+，仅适合已经主动开启无障碍的用户。"
    CaptureBackend.SHIZUKU -> "高级 Shell 能力，需要用户单独安装并授权。"
    CaptureBackend.ROOT -> "高风险实验能力，只在开发者设置提供。"
}

private fun ThemePreset.label() = when (this) {
    ThemePreset.DYNAMIC -> "动态取色"
    ThemePreset.FIRE_ORANGE -> "焰火橙"
    ThemePreset.CORAL -> "珊瑚"
    ThemePreset.BAMBOO -> "竹影青"
    ThemePreset.OCEAN -> "深海蓝"
    ThemePreset.INDIGO -> "靛青"
    ThemePreset.LAVENDER -> "紫晶夜"
    ThemePreset.BLACK_GOLD -> "黑金"
    ThemePreset.HIGH_CONTRAST -> "高对比"
    ThemePreset.CUSTOM -> "自定义"
}

private fun OverlayStyle.label() = when (this) {
    OverlayStyle.MINIMAL -> "极简"
    OverlayStyle.COMPACT -> "紧凑"
    OverlayStyle.DEBUG_HUD -> "调试 HUD"
}
