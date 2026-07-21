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
