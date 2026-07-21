/**
 * 首页概览。
 *
 * 职责：展示已保存生效配置速览与导航到运行/设置；不编辑草稿。
 * 数据流：只读 [SettingsRepository.config]；导航回调由上层 NavHost 注入。
 * 边界：不启动视觉分析、不触碰权限型运行时能力。
 */
package top.azek431.hzzs.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import top.azek431.hzzs.core.designsystem.HeroCard
import top.azek431.hzzs.core.designsystem.HzzsSection
import top.azek431.hzzs.core.designsystem.MetricTile
import top.azek431.hzzs.core.designsystem.PageHeader
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.StatusChip
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.SettingsRepository
import javax.inject.Inject

/** 首页只订阅已落盘配置，不维护设置草稿。 */
@HiltViewModel
class HomeViewModel @Inject constructor(repo: SettingsRepository) : ViewModel() {
    val config: StateFlow<AppConfig> = repo.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
}

/** 首页 UI：配置速览 + 进入运行控制 / 设置。 */
@Composable
fun HomeScreen(
    onOpenRuntime: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            PageHeader(
                title = "火崽崽奇妙屋",
                subtitle = "轻量、本地、可解释的画面分析助手",
            )
        }

        item {
            HeroCard(
                title = "准备开始分析",
                subtitle = "默认低权限截图 · 自动操作需会话解锁",
                icon = Icons.Rounded.Visibility,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("本地处理", active = true)
                    StatusChip(
                        if (config.automation.enabled) "自动操作已配置" else "自动操作关闭",
                        active = config.automation.enabled,
                    )
                    StatusChip(
                        if (config.mcp.enabled) "MCP 开启" else "MCP 关闭",
                        active = config.mcp.enabled,
                    )
                }
                Text(
                    "识别结果只在本机完成。只有你明确授权的会话，才会向当前游戏窗口发送手势。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("进入运行控制")
                }
            }
        }

        item {
            HzzsSection(
                title = "当前配置速览",
                description = "这些是已保存的生效配置，不是设置页里的临时预览。",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricTile(
                        label = "赛季",
                        value = config.selectedScene.displayName(),
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "截图",
                        value = config.captureBackend.displayName(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricTile(
                        label = "MCP",
                        value = if (config.mcp.enabled) {
                            config.mcp.permissionLevel.displayName()
                        } else {
                            "关闭"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "自动操作",
                        value = if (config.automation.enabled) "待解锁" else "关闭",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "安全提示",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "自动操作默认关闭。开启后仍需在运行页确认当前游戏页面，并会在切页、失败或停分析时自动解除。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("调整设置")
                }
                OutlinedButton(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
                    Text("直接去运行")
                }
            }
        }
    }
}
