/**
 * 悬浮窗设置页。
 *
 * 职责：编辑悬浮窗开关、样式、主题、透明度与显示项等外观草稿。
 * 数据流：经 [update] 写入共享草稿；视觉相关字段可预览，保存后持久。
 * 边界：不创建/操作 WindowManager；实际悬浮窗由运行时/平台层根据已保存配置托管。
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.OverlayOrientation
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.OverlayTheme
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow

/**
 * 悬浮窗样式设置页。
 *
 * 可预览：主题/样式变更经草稿即时反映到 OverlayController。
 * 不直接操作 WindowManager；真正加窗/移窗由 service 层完成。
 */
@Composable
fun OverlaySettingsScreen(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(
                title = "悬浮窗总开关",
                description = "默认极简；调试 HUD 适合校准和反馈问题。",
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
                    title = "触摸穿透（全屏检测框模式）",
                    checked = config.overlay.clickThrough,
                    onCheckedChange = { value ->
                        update { it.copy(overlay = it.overlay.copy(clickThrough = value)) }
                    },
                )
                if (!config.overlay.clickThrough) {
                    Text(
                        "关闭穿透后悬浮窗会变为可拖动的小型 HUD，并停止绘制全屏检测框，避免拦截游戏触摸。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
