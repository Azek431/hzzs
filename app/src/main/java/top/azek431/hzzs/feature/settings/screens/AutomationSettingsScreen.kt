/**
 * 自动操作与安全设置页。
 *
 * 职责：总开关、竹影实验锁、触发距离与节流参数；展示无障碍连接状态。
 * 数据流：automation 经 [update] 即时落盘；开启须风险确认。
 * 边界：不启动无障碍手势；默认关闭，导入/迁移不得静默开启。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess

@Composable
fun AutomationSettingsScreen(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var riskDialog by remember { mutableStateOf(false) }
    var accessibilityConnected by remember {
        mutableStateOf(SystemCapabilityAccess.isAccessibilityServiceConnected())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityConnected = SystemCapabilityAccess.isAccessibilityServiceConnected()
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
            SettingsWarningCard(
                title = "自动操作默认关闭",
                body = "自动操作依赖无障碍手势，可能因游戏更新、网络延迟或识别误差产生错误操作。配置导入与迁移不会静默开启。",
            )
        }
        item {
            SettingsSectionCard(
                title = stringResource(R.string.permission_accessibility_section),
                description = stringResource(R.string.permission_refresh_hint),
            ) {
                Text(
                    if (accessibilityConnected) {
                        stringResource(R.string.permission_accessibility_connected)
                    } else {
                        stringResource(R.string.permission_accessibility_disconnected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (accessibilityConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                OutlinedButton(
                    onClick = { SystemCapabilityAccess.openAccessibilitySettings(context) },
                ) {
                    Text(stringResource(R.string.permission_accessibility_open))
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "总开关",
                description = "开启后将在视觉分析运行时按识别结果自动发送手势。",
            ) {
                SettingsSwitchRow(
                    title = "启用自动操作",
                    checked = config.automation.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            riskDialog = true
                        } else {
                            update { it.copy(automation = it.automation.copy(enabled = false)) }
                        }
                    },
                )
                SettingsSwitchRow(
                    title = "允许竹影书屋实验性自动操作",
                    subtitle = "即使已启用自动操作，也需单独打开本开关才会在竹影场景规划动作。",
                    checked = config.automation.bambooExperimentalAutoAction,
                    onCheckedChange = { value ->
                        update {
                            it.copy(automation = it.automation.copy(bambooExperimentalAutoAction = value))
                        }
                    },
                )
                Text(
                    "甜品工厂与海盐客厅随总开关生效，无需额外实验锁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSectionCard(
                title = "触发距离（玩家宽度倍数）",
                description = "障碍进入玩家前方该倍数距离内才规划动作。范围 0.5–4.0。",
            ) {
                LabeledSlider(
                    "甜品工厂",
                    config.automation.sweetTriggerDistancePlayerWidths,
                    0.5f..4f,
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(sweetTriggerDistancePlayerWidths = value))
                    }
                }
                LabeledSlider(
                    "竹影书屋",
                    config.automation.bambooTriggerDistancePlayerWidths,
                    0.5f..4f,
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(bambooTriggerDistancePlayerWidths = value))
                    }
                }
                LabeledSlider(
                    "海盐客厅",
                    config.automation.seaSaltTriggerDistancePlayerWidths,
                    0.5f..4f,
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(seaSaltTriggerDistancePlayerWidths = value))
                    }
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "节流与场景门控",
                description = "限制动作频率与最低场景置信度，降低误触。",
            ) {
                LabeledSlider(
                    "每秒最多动作数",
                    config.automation.maxActionsPerSecond.toFloat(),
                    1f..8f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(maxActionsPerSecond = value.toInt()))
                    }
                }
                LabeledSlider(
                    "最低场景置信度",
                    config.automation.minimumSceneConfidence,
                    0.5f..1f,
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(minimumSceneConfidence = value))
                    }
                }
                LabeledSlider(
                    "失败重试上限",
                    config.automation.retryLimit.toFloat(),
                    0f..2f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    update {
                        it.copy(automation = it.automation.copy(retryLimit = value.toInt()))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (riskDialog) {
        AutomationRiskDialog(
            onDismiss = { riskDialog = false },
            onConfirm = {
                riskDialog = false
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
private fun AutomationRiskDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf(4) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
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
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = checked && remaining <= 0,
            ) {
                Text(if (remaining > 0) "请等待 ${remaining}s" else "确认开启")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
