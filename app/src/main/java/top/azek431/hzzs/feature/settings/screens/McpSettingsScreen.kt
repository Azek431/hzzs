/**
 * MCP 本地服务设置页。
 *
 * 职责：MCP 开关、权限级、连接信息复制、运行状态展示。
 * 数据流：mcp 字段经 [update] 即时落盘；MainActivity 订阅配置流同步前台服务。
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
import androidx.compose.material.icons.rounded.ContentCopy
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
import top.azek431.hzzs.mcp.generateMcpAuthToken

/**
 * MCP 本地服务设置页。
 *
 * 面向 RikkaHub / OperitAI 同机连接：一键复制 URL 或导入 JSON，
 * Bearer 鉴权可关，无需用户手写请求头。
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
    val copyFailedMsg = stringResource(R.string.mcp_copy_failed)
    val turnOnFirstMsg = stringResource(R.string.mcp_turn_on_first)
    val copiedUrl = stringResource(R.string.mcp_copied_url)
    val copiedJson = stringResource(R.string.mcp_copied_rikkahub_json)
    val copiedToken = stringResource(R.string.mcp_copied_token)
    val copiedAll = stringResource(R.string.mcp_copied_connection_info)
    val rotatedTokenMsg = stringResource(R.string.mcp_rotated_token)

    fun copy(label: String, text: String, okMsg: String) {
        val ok = ClipboardHelper.copyText(context, label, text)
        onMessage(if (ok) okMsg else copyFailedMsg)
    }

    fun ensureAuthToken(current: top.azek431.hzzs.core.model.AppConfig): String {
        val existing = current.mcp.authToken
        if (existing.isNotBlank()) return existing
        return generateMcpAuthToken()
    }

    fun rotateAuthToken() {
        val next = generateMcpAuthToken()
        update { it.copy(mcp = it.mcp.copy(requireAuth = true, authToken = next)) }
        onMessage(rotatedTokenMsg)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 1. 运行状态 ──
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
                            tint = if (mcpState.running) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
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
                            SettingsStatusChip(
                                if (mcpState.requireAuth) "Bearer 鉴权" else "免鉴权",
                                emphasis = false,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "URL",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CodeBlock(mcpState.endpointUrl())
                        if (mcpState.requireAuth && mcpState.token.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Token（前 12 位）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                mcpState.token.take(12) + "…",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
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

        // ── 2. 一键复制（RikkaHub 友好）──
        item {
            if (mcpState.running) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            copy("HZZS MCP JSON", mcpState.rikkaHubImportJson(), copiedJson)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.mcp_copy_rikkahub_json))
                    }
                    OutlinedButton(
                        onClick = {
                            copy("HZZS MCP URL", mcpState.endpointUrl(), copiedUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_copy_url))
                    }
                    if (mcpState.requireAuth && mcpState.token.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                copy("HZZS MCP Token", mcpState.token, copiedToken)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.mcp_copy_token))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val all = buildString {
                                appendLine(mcpState.endpointUrl())
                                appendLine("type: streamable_http")
                                if (mcpState.requireAuth) {
                                    appendLine("Authorization: Bearer ${mcpState.token}")
                                } else {
                                    appendLine("auth: off")
                                }
                                appendLine()
                                append(mcpState.rikkaHubImportJson())
                            }
                            copy("HZZS MCP", all, copiedAll)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_copy_connection_info))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { onMessage(turnOnFirstMsg) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mcp_turn_on_and_connect))
                }
            }
        }

        // ── 3. 启用 + 鉴权 ──
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
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_require_auth_switch),
                    subtitle = stringResource(R.string.mcp_require_auth_subtitle),
                    checked = config.mcp.requireAuth,
                    onCheckedChange = { value ->
                        if (value) {
                            val token = ensureAuthToken(config)
                            update {
                                it.copy(
                                    mcp = it.mcp.copy(
                                        requireAuth = true,
                                        authToken = it.mcp.authToken.ifBlank { token },
                                    ),
                                )
                            }
                        } else {
                            update { it.copy(mcp = it.mcp.copy(requireAuth = false)) }
                        }
                    },
                )
                if (config.mcp.requireAuth) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcp_token_stable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { rotateAuthToken() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_rotate_token))
                    }
                }
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
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        // ── 5. 调试帧 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_debug_frames_title),
                description = null,
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_debug_frames_switch),
                    checked = config.mcp.allowDebugFrames,
                    enabled = config.developer.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(allowDebugFrames = value)) }
                    },
                )
                Text(
                    if (!config.developer.enabled) {
                        stringResource(R.string.mcp_debug_frames_dev_required)
                    } else {
                        stringResource(R.string.mcp_debug_frames_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 6. 连接说明 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_how_to_connect_title),
                description = stringResource(R.string.mcp_how_to_connect_desc),
            ) {
                Text(stringResource(R.string.mcp_step_1), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mcp_step_2), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                CodeBlock(
                    if (mcpState.running) {
                        mcpState.rikkaHubImportJson()
                    } else {
                        "{\n  \"mcpServers\": {\n    \"hzzs\": {\n      \"type\": \"streamable_http\",\n      \"url\": \"http://127.0.0.1:${config.mcp.port}/mcp\"\n    }\n  }\n}"
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mcp_step_3), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ── 7. 安全 ──
        item {
            SettingsWarningCard(
                title = stringResource(R.string.mcp_security_title),
                body = stringResource(R.string.mcp_security_body),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun permissionDescription(level: McpPermissionLevel): String = when (level) {
    McpPermissionLevel.READ_ONLY -> "仅允许 AI 读取运行状态和配置，不可修改任何设置"
    McpPermissionLevel.ASK_EVERY_TIME -> "每次写操作需要你在手机上确认（推荐默认）"
    McpPermissionLevel.TRUSTED_SESSION ->
        "信任当前 MCP 握手会话的普通写操作（服务重启或会话过期后失效，不持久化）"
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
