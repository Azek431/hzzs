/**
 * MCP 本地服务设置页。
 *
 * 职责：MCP 开关、权限级、连接信息复制、运行状态展示。
 * 数据流：mcp 字段进草稿但预览不启停服务，保存后才由 MainActivity 同步。
 * 边界：不启动 MCP 服务本体；调试帧元数据需开发者选项 + allowDebugFrames 同时开启。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.platform.ClipboardHelper
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsStatusChip
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.mcp.McpServerState

/**
 * MCP 本地服务设置页。
 *
 * 页面结构：
 * 1. 运行状态 Hero — 显示当前服务状态、端口、Token
 * 2. 快速连接 — 一键复制 adb forward + Bearer Token
 * 3. 权限选择 — 四级权限单选卡片
 * 4. 开关与选项 — 启用 / 调试帧元数据
 * 5. 使用说明 — 关闭时的引导文字
 */
@Composable
fun McpSettingsScreen(
    config: top.azek431.hzzs.core.model.AppConfig,
    update: ((top.azek431.hzzs.core.model.AppConfig) -> top.azek431.hzzs.core.model.AppConfig) -> Unit,
    mcpState: McpServerState,
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.mcp_copied_connection_info)
    val copyFailedMsg = stringResource(R.string.mcp_copy_failed)
    val turnOnFirstMsg = stringResource(R.string.mcp_turn_on_first)
    val connectHint = stringResource(R.string.mcp_turn_on_and_connect)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 1. 运行状态卡片 ──
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (mcpState.running) Icons.Rounded.PlayArrow else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (mcpState.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (mcpState.running) "MCP 正在运行" else "MCP 未运行",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (mcpState.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsStatusChip("127.0.0.1:${mcpState.port}")
                            SettingsStatusChip(mcpState.token.take(12) + "...", emphasis = false)
                        }
                        Text(
                            stringResource(R.string.mcp_status_running_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.mcp_status_not_running_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    mcpState.lastError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // ── 2. 快速连接操作 ──
        item {
            if (mcpState.running) {
                Button(
                    onClick = {
                        val command =
                            "adb forward tcp:${mcpState.port} tcp:${mcpState.port}\n" +
                                "Authorization: Bearer ${mcpState.token}"
                        val ok = ClipboardHelper.copyText(context, "HZZS MCP", command)
                        onMessage(
                            if (ok) copiedMsg else copyFailedMsg,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mcp_copy_connection_info))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        onMessage(turnOnFirstMsg)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mcp_turn_on_and_connect))
                }
            }
        }

        // ── 3. 启用开关 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_enable_title),
                description = stringResource(R.string.mcp_section_enable_desc),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_enable_switch),
                    subtitle = stringResource(R.string.mcp_enable_subtitle),
                    checked = config.mcp.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(enabled = value)) }
                    },
                )
            }
        }

        // ── 4. 权限级别 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_permission_title),
                description = stringResource(R.string.mcp_section_permission_desc),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpPermissionLevel.entries.forEach { level ->
                        SettingsRadioCard(
                            title = level.displayName(),
                            subtitle = permissionDescription(level),
                            selected = config.mcp.permissionLevel == level,
                            onClick = {
                                update { it.copy(mcp = it.mcp.copy(permissionLevel = level)) }
                            },
                            trailing = if (config.mcp.permissionLevel == level) {
                                stringResource(R.string.mcp_current)
                            } else null,
                        )
                    }
                }
            }
        }

        // ── 5. 调试帧元数据 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_debug_frames_title),
                description = null,
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_debug_frames_switch),
                    checked = config.mcp.allowDebugFrames,
                    enabled = config.developer.enabled && config.mcp.allowDebugFrames,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(allowDebugFrames = value)) }
                    },
                )
                if (!config.developer.enabled) {
                    Text(
                        stringResource(R.string.mcp_debug_frames_dev_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.mcp_debug_frames_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 6. 使用说明（当 MCP 关闭时突出显示） ──
        item {
            if (!mcpState.running) {
                SettingsSectionCard(
                    title = stringResource(R.string.mcp_how_to_connect_title),
                    description = stringResource(R.string.mcp_how_to_connect_desc),
                ) {
                    Text(
                        stringResource(R.string.mcp_step_1),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock("adb forward tcp:8765 tcp:8765")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcp_step_2),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock("在设置页点击「复制连接信息」")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcp_step_3),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock("adb shell dumpsys window | grep mCurrentFocus")
                }
            }
        }

        // ── 7. 安全提示 ──
        item {
            SettingsWarningCard(
                title = stringResource(R.string.mcp_security_title),
                body = stringResource(R.string.mcp_security_body),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ──────────────────────────────────────────────
// 辅助函数
// ──────────────────────────────────────────────

private fun permissionDescription(level: McpPermissionLevel): String = when (level) {
    McpPermissionLevel.READ_ONLY -> "仅允许 AI 读取运行状态和配置，不可修改任何设置"
    McpPermissionLevel.ASK_EVERY_TIME -> "每次写操作需要你在手机上确认（推荐默认）"
    McpPermissionLevel.TRUSTED_SESSION -> "信任本次会话的操作，但解锁自动操作仍需确认"
    McpPermissionLevel.FULL_ACCESS -> "允许 AI 执行应用内所有功能（仍不能绕过系统权限对话框）"
}

@Composable
private fun CodeBlock(content: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Text(
            content,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
}
