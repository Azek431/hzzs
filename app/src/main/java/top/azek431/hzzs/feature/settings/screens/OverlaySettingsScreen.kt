/**
 * 悬浮窗设置页。
 *
 * 职责：编辑悬浮窗开关、样式、主题、透明度与显示项；展示系统悬浮窗权限状态。
 * 数据流：经 [update] 即时落盘，配置流驱动 OverlayController。
 * 边界：不创建/操作 WindowManager；跳转系统设置经 [SystemCapabilityAccess]；加窗由 service 层完成。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.OverlayOrientation
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.OverlayTheme
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess

/**
 * 悬浮窗样式设置页。
 *
 * 主题/样式变更即时落盘并反映到 OverlayController。
 * 不直接操作 WindowManager；真正加窗/移窗由 service 层完成。
 */
@Composable
fun OverlaySettingsScreen(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayGranted by remember {
        mutableStateOf(SystemCapabilityAccess.canDrawOverlays(context))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = SystemCapabilityAccess.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(
                title = stringResource(R.string.permission_overlay_section),
                description = stringResource(R.string.permission_refresh_hint),
            ) {
                Text(
                    if (overlayGranted) {
                        stringResource(R.string.permission_overlay_granted)
                    } else {
                        stringResource(R.string.permission_overlay_denied)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overlayGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                OutlinedButton(
                    onClick = { SystemCapabilityAccess.openOverlayPermissionSettings(context) },
                ) {
                    Text(stringResource(R.string.permission_overlay_open))
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "悬浮窗总开关",
                description = "应用内开关不能代替系统权限；无系统权限时即使打开也不会显示。",
            ) {
                SettingsSwitchRow(
                    title = "显示悬浮窗",
                    checked = config.overlay.enabled,
                    onCheckedChange = { enabled ->
                        update { it.copy(overlay = it.overlay.copy(enabled = enabled)) }
                    },
                )
            }
        }
        item {
            SettingsSectionCard(title = "样式") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlayStyle.entries.forEach { style ->
                        SettingsRadioCard(
                            title = style.displayName(),
                            selected = config.overlay.style == style,
                            onClick = { update { it.copy(overlay = it.overlay.copy(style = style)) } },
                        )
                    }
                }
            }
        }
        item {
            SettingsSectionCard(title = "主题") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlayTheme.entries.forEach { theme ->
                        SettingsRadioCard(
                            title = theme.displayName(),
                            selected = config.overlay.theme == theme,
                            onClick = { update { it.copy(overlay = it.overlay.copy(theme = theme)) } },
                        )
                    }
                }
                if (config.overlay.theme == OverlayTheme.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    HexColorField("悬浮窗强调色", config.overlay.customColor) { color ->
                        update { it.copy(overlay = it.overlay.copy(customColor = color)) }
                    }
                }
            }
        }
        item {
            SettingsSectionCard(title = "排列方向") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlayOrientation.entries.forEach { orientation ->
                        SettingsRadioCard(
                            title = if (orientation == OverlayOrientation.HORIZONTAL) "横向" else "纵向",
                            selected = config.overlay.orientation == orientation,
                            onClick = {
                                update { it.copy(overlay = it.overlay.copy(orientation = orientation)) }
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSectionCard(title = "尺寸与透明度") {
                LabeledSlider("透明度", config.overlay.backgroundAlpha, 0.1f..1f) { value ->
                    update { it.copy(overlay = it.overlay.copy(backgroundAlpha = value)) }
                }
                LabeledSlider("缩放", config.overlay.scale, 0.6f..2f) { value ->
                    update { it.copy(overlay = it.overlay.copy(scale = value)) }
                }
                LabeledSlider("文字缩放", config.overlay.textScale, 0.7f..1.6f) { value ->
                    update { it.copy(overlay = it.overlay.copy(textScale = value)) }
                }
                LabeledSlider("检测框线宽", config.overlay.strokeWidthDp, 1f..6f) { value ->
                    update { it.copy(overlay = it.overlay.copy(strokeWidthDp = value)) }
                }
            }
        }
        item {
            SettingsSectionCard(title = "显示项与交互") {
                SettingsSwitchRow(
                    title = "显示检测框",
                    checked = config.overlay.showBoxes,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(showBoxes = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "显示状态文字",
                    checked = config.overlay.showText,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(showText = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "显示 FPS",
                    checked = config.overlay.showFps,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(showFps = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "显示置信度",
                    checked = config.overlay.showConfidence,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(showConfidence = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "显示诊断信息",
                    checked = config.overlay.showDiagnostics,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(showDiagnostics = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "触摸穿透（兼容项）",
                    subtitle = "当前为双层架构：穿透层始终绘制检测框，交互层始终为可拖 HUD；本开关保留兼容旧配置。",
                    checked = config.overlay.clickThrough,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(clickThrough = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "贴边吸附",
                    checked = config.overlay.snapToEdge,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(snapToEdge = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "锁定位置",
                    checked = config.overlay.lockPosition,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(lockPosition = value)) }
                    },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
