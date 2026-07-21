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
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard

@Composable
fun McpDeveloperSettingsScreen(
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
                title = "MCP 服务",
                description = "让 Claude Code 等 AI 读取状态和操作应用。默认每次写操作都需要确认。",
            ) {
                SettingsSwitchRow(
                    title = "启用 MCP",
                    subtitle = "仅监听 loopback，使用随机 Bearer Token。",
                    checked = config.mcp.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(enabled = value)) }
                    },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpPermissionLevel.entries.forEach { level ->
                        SettingsRadioCard(
                            title = level.displayName(),
                            selected = config.mcp.permissionLevel == level,
                            onClick = {
                                update { it.copy(mcp = it.mcp.copy(permissionLevel = level)) }
                            },
                        )
                    }
                }
                Text(
                    "完整访问允许 AI 执行应用内所有功能，但无法绕过 Android 系统权限窗口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSwitchRow(
                    title = "允许 MCP 读取调试帧元数据",
                    checked = config.mcp.allowDebugFrames,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(allowDebugFrames = value)) }
                    },
                )
                if (config.mcp.allowDebugFrames && !config.developer.enabled) {
                    Text(
                        "该权限只有在关于页开启开发者设置后才生效。图片仍保存在应用私有目录，MCP 默认只返回文件元数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "开发者选项",
                description = "高级调试项；预览阶段不会启动权限型能力。",
            ) {
                SettingsSwitchRow(
                    title = "启用开发者选项",
                    checked = config.developer.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(developer = it.developer.copy(enabled = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "保存调试帧",
                    checked = config.developer.saveDebugFrames,
                    enabled = config.developer.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(developer = it.developer.copy(saveDebugFrames = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "显示坐标网格",
                    checked = config.developer.showCoordinateGrid,
                    enabled = config.developer.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(developer = it.developer.copy(showCoordinateGrid = value)) }
                    },
                )
            }
        }
        item {
            SettingsWarningCard(
                title = "安全提示",
                body = "MCP 与开发者选项属于权限型配置，只有保存后才会生效。切勿在不可信网络暴露本机端口。",
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
