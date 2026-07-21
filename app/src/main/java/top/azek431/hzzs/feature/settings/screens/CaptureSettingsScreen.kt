/**
 * 截图与权限设置页。
 *
 * 职责：按设备能力列表选择截图后端；AUTO 只走低权限公开接口。
 * 数据流：写入草稿的 [captureBackend]；预览阶段被 ViewModel 强制保留 baseline，
 * 仅“保存并应用”后生效。
 * 边界：不探测 Root/Shizuku 可用性之外的能力；不直接申请系统权限。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.platform.compat.CaptureCapability

/**
 * 截图后端设置页。
 *
 * 仅改草稿中的 [AppConfig.captureBackend]；权限型字段预览阶段由 ViewModel
 * 强制保留 baseline，保存后才重启采集。AUTO 文案强调不升权。
 * 本页不直接申请录屏权限或探测 Root/Shizuku。
 */
@Composable
fun CaptureSettingsScreen(
    config: AppConfig,
    capabilities: List<CaptureCapability>,
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
                title = "截图方式",
                description = "自动推荐不会尝试 Root 或 ADB。高级方式必须由玩家手动选择。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.forEach { capability ->
                        SettingsRadioCard(
                            title = capability.title,
                            subtitle = capability.summary,
                            selected = config.captureBackend == capability.backend,
                            enabled = capability.supported,
                            trailing = when {
                                !capability.supported -> "不可用"
                                capability.recommended -> "推荐"
                                else -> null
                            },
                            onClick = {
                                update { it.copy(captureBackend = capability.backend) }
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsWarningCard(
                title = "权限说明",
                body = "截图后端属于权限型配置：预览阶段不会真正切换，只有点击“保存并应用”后才会生效。AUTO 永远只走低权限公开接口。",
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
