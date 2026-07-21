/**
 * 首页概览（工具专业风）。
 *
 * 职责：展示已保存配置速览与进入运行/设置；不编辑草稿。
 * 数据流：只读 [SettingsRepository.config] 与运行状态。
 * 边界：不启动分析、不触碰权限型运行时能力。
 */
package top.azek431.hzzs.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.HzzsMetricGrid
import top.azek431.hzzs.core.designsystem.HzzsPrimaryAction
import top.azek431.hzzs.core.designsystem.HzzsScrollPage
import top.azek431.hzzs.core.designsystem.HzzsSecondaryAction
import top.azek431.hzzs.core.designsystem.HzzsStatusStrip
import top.azek431.hzzs.core.designsystem.MetricTile
import top.azek431.hzzs.core.designsystem.PageHeader
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.StatusChip
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.VisionRuntimeController
import javax.inject.Inject

/** 首页只读已落盘配置与运行状态摘要。 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    repo: SettingsRepository,
    runtime: VisionRuntimeController,
) : ViewModel() {
    val config: StateFlow<AppConfig> = repo.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
    val status: StateFlow<RuntimeStatus> = runtime.status
}

/** 首页 UI：就绪摘要 + 单一主路径进入运行。 */
@Composable
fun HomeScreen(
    onOpenRuntime: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    val status by vm.status.collectAsState()

    HzzsScrollPage(modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(
                title = "HZZS",
                subtitle = "本地画面分析 · 低权限默认 · 受控自动操作",
            )
        }

        item {
            HzzsStatusStrip {
                StatusChip(
                    if (status.running) "分析运行中" else "分析未启动",
                    active = status.running,
                )
                StatusChip(config.selectedScene.displayName(), active = true)
                StatusChip(config.captureBackend.displayName(), active = false)
            }
        }

        item {
            HeroCard(
                title = if (status.running) "分析正在进行" else "准备开始分析",
                subtitle = "${config.selectedScene.displayName()} · ${config.captureBackend.displayName()}",
                icon = Icons.Rounded.Visibility,
            ) {
                Text(
                    "识别仅在本机完成。自动操作默认关闭，需在运行页按会话解锁后才会向白名单窗口发送手势。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HzzsPrimaryAction(
                    text = if (status.running) "打开运行控制台" else "进入运行控制",
                    onClick = onOpenRuntime,
                    icon = Icons.Rounded.PlayArrow,
                )
            }
        }

        item {
            SectionCard {
                Text(
                    "当前生效配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "已保存配置，不是设置页临时预览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HzzsMetricGrid {
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
                    HzzsMetricGrid {
                        MetricTile(
                            label = "自动操作",
                            value = if (config.automation.enabled) {
                                if (status.automationArmed) "已解锁" else "待解锁"
                            } else {
                                "关闭"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "MCP",
                            value = if (config.mcp.enabled) {
                                config.mcp.permissionLevel.displayName()
                            } else {
                                "关闭"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            HzzsCallout(
                title = "安全边界",
                text = "自动操作默认关闭。开启后仍须在运行页确认当前游戏页面；切页、失败或停止分析会自动解除。",
                tone = HzzsCalloutTone.INFO,
                icon = Icons.Rounded.Security,
            )
        }

        item {
            HzzsSecondaryAction(
                text = "打开设置",
                onClick = onOpenSettings,
                icon = Icons.Rounded.Settings,
                tonal = true,
            )
        }
    }
}
