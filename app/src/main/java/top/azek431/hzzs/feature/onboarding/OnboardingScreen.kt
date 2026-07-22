/**
 * 首次引导向导。
 *
 * 职责：分步编辑内存草稿 [AppConfig]；主题等可经 [onPreview] 即时预览。
 * 数据流：完成前不落盘；点“完成”时才 [onComplete] 写入并标记 onboarding/免责声明版本。
 * 边界：自动操作默认强制关闭；开启须风险对话框倒计时确认。不直接申请 Root/Shizuku/JNI。
 * 动效：步骤切换走 [LocalHzzsMotion] shared-axis；减少动效时即时切换。
 */
package top.azek431.hzzs.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HzzsSection
import top.azek431.hzzs.core.designsystem.LocalHzzsMotion
import top.azek431.hzzs.core.designsystem.contentStepBackwardEnter
import top.azek431.hzzs.core.designsystem.contentStepBackwardExit
import top.azek431.hzzs.core.designsystem.contentStepForwardEnter
import top.azek431.hzzs.core.designsystem.contentStepForwardExit
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ThemePreset
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import top.azek431.hzzs.platform.compat.isSupportedOnThisDevice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    initial: AppConfig,
    onPreview: (AppConfig) -> Unit,
    onComplete: (AppConfig) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    var draft by remember(initial) {
        mutableStateOf(initial.copy(automation = initial.automation.copy(enabled = false)))
    }
    var showAutomationRisk by remember { mutableStateOf(false) }
    val pageMetas = onboardingPageMetas()
    val motion = LocalHzzsMotion.current

    fun update(transform: (AppConfig) -> AppConfig) {
        draft = transform(draft)
        onPreview(draft)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_top_title)) },
                actions = {
                    Text(
                        stringResource(R.string.onboarding_step_counter, page + 1, pageMetas.size),
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        bottomBar = {
            Column {
                LinearProgressIndicator(
                    progress = { (page + 1f) / pageMetas.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    if (page > 0) {
                        OutlinedButton(onClick = { page-- }) {
                            Text(stringResource(R.string.action_previous))
                        }
                    }
                    Button(
                        onClick = {
                            if (page == pageMetas.lastIndex) {
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
                    ) {
                        Text(
                            if (page == pageMetas.lastIndex) {
                                stringResource(R.string.action_finish)
                            } else {
                                stringResource(R.string.action_next)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                val forward = targetState >= initialState
                if (forward) {
                    motion.contentStepForwardEnter() togetherWith motion.contentStepForwardExit()
                } else {
                    motion.contentStepBackwardEnter() togetherWith motion.contentStepBackwardExit()
                }
            },
            label = "onboarding-step",
        ) { current ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    val meta = pageMetas[current]
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(meta.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(meta.title, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                meta.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { HorizontalDivider() }
                item {
                    when (current) {
                        0 -> IntroPage()
                        1 -> PrivacyPage()
                        2 -> SeasonPage(draft) { scene -> update { it.copy(selectedScene = scene) } }
                        3 -> CapturePage(draft) { backend -> update { it.copy(captureBackend = backend) } }
                        4 -> PermissionsPage()
                        5 -> AppearancePage(
                            draft = draft,
                            onTheme = { preset -> update { it.copy(theme = it.theme.copy(preset = preset)) } },
                            onOverlay = { style -> update { it.copy(overlay = it.overlay.copy(style = style)) } },
                        )
                        6 -> AutomationPage(
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
private fun IntroPage() = HzzsSection(
    stringResource(R.string.onboarding_intro_section),
    stringResource(R.string.onboarding_intro_desc),
) {
    Text(stringResource(R.string.onboarding_intro_p1))
    Text(stringResource(R.string.onboarding_intro_p2))
}

@Composable
private fun PrivacyPage() = HzzsSection(
    stringResource(R.string.onboarding_privacy_section),
    stringResource(R.string.onboarding_privacy_desc),
) {
    Text(stringResource(R.string.onboarding_privacy_p1))
    Text(stringResource(R.string.onboarding_privacy_p2))
    Text(stringResource(R.string.onboarding_privacy_p3))
}

@Composable
private fun SeasonPage(config: AppConfig, onSelect: (SceneId) -> Unit) = HzzsSection(
    stringResource(R.string.onboarding_season_section),
    stringResource(R.string.onboarding_season_desc),
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SceneId.entries.forEach { scene ->
            FilterChip(
                selected = config.selectedScene == scene,
                onClick = { onSelect(scene) },
                label = { Text(scene.displayName()) },
            )
        }
    }
}

@Composable
private fun CapturePage(config: AppConfig, onSelect: (CaptureBackend) -> Unit) = HzzsSection(
    stringResource(R.string.onboarding_capture_section),
    stringResource(R.string.onboarding_capture_desc),
) {
    listOf(CaptureBackend.AUTO, CaptureBackend.MEDIA_PROJECTION, CaptureBackend.ACCESSIBILITY).forEach { backend ->
        val supported = backend.isSupportedOnThisDevice()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(backend.displayName())
                Text(
                    backend.onboardingSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = config.captureBackend == backend,
                enabled = supported,
                onClick = { onSelect(backend) },
                label = {
                    Text(
                        if (supported) stringResource(R.string.action_select)
                        else stringResource(R.string.state_unsupported),
                    )
                },
            )
        }
    }
    Text(
        stringResource(R.string.onboarding_capture_advanced_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayGranted by remember {
        mutableStateOf(SystemCapabilityAccess.canDrawOverlays(context))
    }
    var accessibilityConnected by remember {
        mutableStateOf(SystemCapabilityAccess.isAccessibilityServiceConnected())
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = SystemCapabilityAccess.canDrawOverlays(context)
                accessibilityConnected = SystemCapabilityAccess.isAccessibilityServiceConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HzzsSection(
        stringResource(R.string.onboarding_permissions_section),
        stringResource(R.string.onboarding_permissions_desc),
    ) {
        Text(
            stringResource(R.string.onboarding_permissions_overlay_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.onboarding_permissions_overlay_body))
        Text(
            if (overlayGranted) {
                stringResource(R.string.permission_overlay_granted)
            } else {
                stringResource(R.string.permission_overlay_denied)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (overlayGranted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        OutlinedButton(onClick = { SystemCapabilityAccess.openOverlayPermissionSettings(context) }) {
            Text(stringResource(R.string.permission_overlay_open))
        }

        Text(
            stringResource(R.string.onboarding_permissions_a11y_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.onboarding_permissions_a11y_body))
        Text(
            if (accessibilityConnected) {
                stringResource(R.string.permission_accessibility_connected)
            } else {
                stringResource(R.string.permission_accessibility_disconnected)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (accessibilityConnected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        OutlinedButton(onClick = { SystemCapabilityAccess.openAccessibilitySettings(context) }) {
            Text(stringResource(R.string.permission_accessibility_open))
        }

        Text(
            stringResource(R.string.onboarding_permissions_capture_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.permission_refresh_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppearancePage(
    draft: AppConfig,
    onTheme: (ThemePreset) -> Unit,
    onOverlay: (OverlayStyle) -> Unit,
) = HzzsSection(
    stringResource(R.string.onboarding_appearance_section),
    stringResource(R.string.onboarding_appearance_desc),
) {
    Text(stringResource(R.string.onboarding_label_theme))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ThemePreset.FIRE_ORANGE, ThemePreset.BAMBOO, ThemePreset.OCEAN, ThemePreset.BLACK_GOLD).forEach { preset ->
            FilterChip(
                selected = draft.theme.preset == preset,
                onClick = { onTheme(preset) },
                label = { Text(preset.displayName()) },
            )
        }
    }
    Text(stringResource(R.string.onboarding_label_overlay))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OverlayStyle.entries.forEach { style ->
            FilterChip(
                selected = draft.overlay.style == style,
                onClick = { onOverlay(style) },
                label = { Text(style.displayName()) },
            )
        }
    }
}

@Composable
private fun AutomationPage(enabled: Boolean, onChange: (Boolean) -> Unit) = HzzsSection(
    stringResource(R.string.onboarding_automation_section),
    stringResource(R.string.onboarding_automation_desc),
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.onboarding_automation_allow))
            Text(
                stringResource(R.string.onboarding_automation_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun FinishPage(config: AppConfig) = HzzsSection(
    stringResource(R.string.onboarding_finish_section),
    stringResource(R.string.onboarding_finish_desc),
) {
    Text(stringResource(R.string.onboarding_finish_scene, config.selectedScene.displayName()))
    Text(stringResource(R.string.onboarding_finish_capture, config.captureBackend.displayName()))
    Text(stringResource(R.string.onboarding_finish_theme, config.theme.preset.displayName()))
    Text(stringResource(R.string.onboarding_finish_overlay, config.overlay.style.displayName()))
    Text(
        if (config.automation.enabled) stringResource(R.string.onboarding_finish_automation_on)
        else stringResource(R.string.onboarding_finish_automation_off),
    )
    Text(stringResource(R.string.onboarding_finish_footer))
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
        title = { Text(stringResource(R.string.onboarding_risk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.onboarding_risk_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(stringResource(R.string.onboarding_risk_checkbox))
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = remaining == 0 && accepted) {
                Text(
                    if (remaining > 0) stringResource(R.string.onboarding_risk_wait, remaining)
                    else stringResource(R.string.onboarding_risk_confirm),
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_risk_keep_off))
            }
        },
    )
}

private data class OnboardingPageMeta(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
private fun onboardingPageMetas(): List<OnboardingPageMeta> = listOf(
    OnboardingPageMeta(stringResource(R.string.onboarding_page0_title), stringResource(R.string.onboarding_page0_subtitle), Icons.Rounded.Info),
    OnboardingPageMeta(stringResource(R.string.onboarding_page1_title), stringResource(R.string.onboarding_page1_subtitle), Icons.Rounded.Security),
    OnboardingPageMeta(stringResource(R.string.onboarding_page2_title), stringResource(R.string.onboarding_page2_subtitle), Icons.Rounded.Analytics),
    OnboardingPageMeta(stringResource(R.string.onboarding_page3_title), stringResource(R.string.onboarding_page3_subtitle), Icons.Rounded.DesktopWindows),
    OnboardingPageMeta(stringResource(R.string.onboarding_page4_title), stringResource(R.string.onboarding_page4_subtitle), Icons.Rounded.VerifiedUser),
    OnboardingPageMeta(stringResource(R.string.onboarding_page5_title), stringResource(R.string.onboarding_page5_subtitle), Icons.Rounded.ColorLens),
    OnboardingPageMeta(stringResource(R.string.onboarding_page6_title), stringResource(R.string.onboarding_page6_subtitle), Icons.Rounded.Tune),
    OnboardingPageMeta(stringResource(R.string.onboarding_page7_title), stringResource(R.string.onboarding_page7_subtitle), Icons.Rounded.CheckCircle),
)

@Composable
private fun CaptureBackend.onboardingSummary(): String = when (this) {
    CaptureBackend.AUTO -> "仅 MediaProjection，永不升权"
    CaptureBackend.MEDIA_PROJECTION -> "系统屏幕录制，需用户授权"
    CaptureBackend.ACCESSIBILITY -> "API 30+ 无障碍截图"
    else -> displayName()
}
