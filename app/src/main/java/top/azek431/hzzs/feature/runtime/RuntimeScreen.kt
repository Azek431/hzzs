/**
 * 运行控制页（工具专业风操作台）。
 *
 * 职责：启停视觉分析、展示运行时指标。
 * 数据流：状态来自 [VisionRuntimeController]；配置只读。
 * 边界：feature 只发意图；不直接 JNI / 截图 / WindowManager。
 */
package top.azek431.hzzs.feature.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HeroCard
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.HzzsMetricGrid
import top.azek431.hzzs.core.designsystem.HzzsPrimaryAction
import top.azek431.hzzs.core.designsystem.HzzsScrollPage
import top.azek431.hzzs.core.designsystem.HzzsStatusStrip
import top.azek431.hzzs.core.designsystem.LocalHzzsStatusColors
import top.azek431.hzzs.core.designsystem.MetricTile
import top.azek431.hzzs.core.designsystem.PageHeader
import top.azek431.hzzs.core.designsystem.SectionCard
import top.azek431.hzzs.core.designsystem.StatusChip
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.platform.compat.SystemCapabilityAccess
import javax.inject.Inject

@HiltViewModel
class RuntimeViewModel @Inject constructor(
    private val controller: VisionRuntimeController,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val status: StateFlow<RuntimeStatus> = controller.status
    val config: StateFlow<AppConfig> = settingsRepository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )
    var transientMessage by mutableStateOf<String?>(null)
        private set

    fun toggle() = viewModelScope.launch {
        if (status.value.running) controller.stop() else controller.start()
    }

    fun clearMessage() {
        transientMessage = null
    }
}

/** 将 skip/dispatch 决策串转成简短中文提示，便于用户对照。 */
internal fun humanizeAutomationDecision(raw: String): String {
    val key = raw.substringBefore(' ').substringBefore('=')
    return when {
        raw.startsWith("skip:automation_off") -> "自动操作总开关关闭（免责声明不足时也会被校验关掉）。"
        raw.startsWith("skip:scene_conf") -> "场景置信度低于设置中的最低阈值。"
        raw.startsWith("skip:frame_age") -> "帧分析过慢，当前帧已过期。"
        raw.startsWith("skip:bamboo_experimental_off") ->
            "（已废弃）竹影实验锁不再拦截；请检查总开关与手势后端。"
        raw.startsWith("skip:action_in_flight") -> "上一动作尚未结束。"
        raw.startsWith("skip:no_player") -> "未识别到玩家参考，无法测距触发。"
        raw.startsWith("skip:no_candidate") -> "没有稳定且可动作的障碍进入触发距离。"
        raw.startsWith("skip:ledger") -> "同一目标冷却中（刚成功点过，约 1 秒内不重复）。"
        raw.startsWith("skip:no_accessibility") -> "无障碍服务未连接（当前手势后端需要无障碍）。"
        raw.startsWith("skip:no_foreground") -> "手势后端读不到前台窗口（无障碍未连/Shell dumpsys 失败）。"
        raw.startsWith("skip:package_gate") -> "已开启「仅允许指定应用」，当前前台不在列表中。"
        raw.startsWith("skip:foreground_gate") -> "前台包名无效或状态不可用。"
        raw.startsWith("plan ") -> "已选中候选，正在规划/派发手势。"
        raw.startsWith("dispatch_ok") || raw.contains("dispatch_ok") -> "手势已成功注入（Shell input 或无障碍）。"
        raw.startsWith("dispatch_fail") || raw.contains("dispatch_fail") ->
            "手势注入失败（看 detail：input 路径/exit 码/超时）。"
        raw.startsWith("dispatch_skip:package_gate") -> "派发时前台包不在允许列表。"
        raw.startsWith("dispatch_skip:foreground_stale") -> "派发时前台快照已过期。"
        raw.startsWith("dispatch_skip:foreground_recheck") -> "派发时前台窗口与规划时不一致。"
        raw.startsWith("dispatch_skip:ledger") -> "派发时账本冷却：同目标刚成功过。"
        raw.startsWith("dispatch_skip:rate_limit") -> "达到每秒动作上限。"
        raw.startsWith("dispatch_skip:empty_plan") -> "该障碍无规避动作（Avoidance.NONE）。"
        raw.startsWith("dispatch_skip:no_foreground") -> "派发时前台不可用（检查手势后端 / dumpsys）。"
        raw.startsWith("dispatch_abort") -> "派发过程中自动操作已关闭。"
        else -> "决策：$key"
    }
}

@Composable
fun RuntimeScreen(vm: RuntimeViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val statusColors = LocalHzzsStatusColors.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm.transientMessage) {
        vm.transientMessage?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        HzzsScrollPage(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                PageHeader(
                    title = stringResource(R.string.runtime_title),
                    subtitle = stringResource(R.string.runtime_subtitle_default),
                )
            }

            item {
                HeroCard(
                    title = if (status.running) {
                        stringResource(R.string.runtime_hero_running)
                    } else {
                        stringResource(R.string.runtime_hero_stopped)
                    },
                    subtitle = "${status.activeScene.displayName()} · ${status.activeBackend.displayName()}",
                    icon = if (status.running) Icons.Rounded.Visibility else Icons.Rounded.Stop,
                ) {
                    HzzsStatusStrip {
                        StatusChip(
                            if (status.running) {
                                stringResource(R.string.runtime_chip_analyzing)
                            } else {
                                stringResource(R.string.runtime_chip_idle)
                            },
                            active = status.running,
                            activeColor = statusColors.running,
                        )
                        StatusChip(
                            if (status.captureReady) {
                                stringResource(R.string.runtime_chip_capture_ready)
                            } else {
                                stringResource(R.string.runtime_chip_capture_wait)
                            },
                            active = status.captureReady,
                        )
                        StatusChip(
                            if (status.overlayVisible) {
                                stringResource(R.string.runtime_chip_overlay_on)
                            } else {
                                stringResource(R.string.runtime_chip_overlay_off)
                            },
                            active = status.overlayVisible,
                        )
                    }

                    if (status.running) {
                        HzzsMetricGrid {
                            MetricTile(
                                label = stringResource(R.string.runtime_metric_fps),
                                value = "${"%.1f".format(status.fps)}",
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = stringResource(R.string.runtime_metric_ms),
                                value = "${"%.1f".format(status.processingMs)}",
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = stringResource(R.string.runtime_metric_obstacles),
                                value = "${status.obstacleCount}",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    HzzsPrimaryAction(
                        text = if (status.running) {
                            stringResource(R.string.runtime_stop)
                        } else {
                            stringResource(R.string.runtime_start)
                        },
                        onClick = vm::toggle,
                        icon = if (status.running) Icons.Rounded.Stop else Icons.Rounded.Visibility,
                    )
                }
            }

            if (status.running && !status.overlayVisible) {
                when (status.overlayBlockReason) {
                    OverlayBlockReason.PERMISSION -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HzzsCallout(
                                title = stringResource(R.string.runtime_overlay_permission_title),
                                text = stringResource(R.string.runtime_overlay_permission_body),
                                tone = HzzsCalloutTone.WARNING,
                            )
                            HzzsPrimaryAction(
                                text = stringResource(R.string.runtime_overlay_permission_action),
                                onClick = { SystemCapabilityAccess.openOverlayPermissionSettings(context) },
                                icon = Icons.Rounded.Visibility,
                            )
                        }
                    }
                    OverlayBlockReason.ADD_VIEW_FAILED -> item {
                        HzzsCallout(
                            title = stringResource(R.string.runtime_overlay_add_failed_title),
                            text = stringResource(R.string.runtime_overlay_add_failed_body),
                            tone = HzzsCalloutTone.ERROR,
                        )
                    }
                    OverlayBlockReason.DISABLED -> item {
                        HzzsCallout(
                            title = stringResource(R.string.runtime_overlay_disabled_title),
                            text = stringResource(R.string.runtime_overlay_disabled_body),
                            tone = HzzsCalloutTone.INFO,
                        )
                    }
                    null -> Unit
                }
            }

            item {
                SectionCard {
                    if (!config.automation.enabled) {
                        Text(
                            stringResource(R.string.runtime_automation_disabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.runtime_automation_auto_mode_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val decision = status.lastAutomationDecision
                        if (status.running && !decision.isNullOrBlank()) {
                            Text(
                                stringResource(R.string.runtime_automation_last_decision, decision),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                humanizeAutomationDecision(decision),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            status.lastError?.let { error ->
                item {
                    HzzsCallout(
                        title = stringResource(R.string.runtime_error_title),
                        text = error,
                        tone = HzzsCalloutTone.ERROR,
                    )
                }
            }

            item {
                HzzsCallout(
                    text = stringResource(R.string.runtime_permission_hint),
                    tone = HzzsCalloutTone.INFO,
                    icon = Icons.Rounded.Security,
                )
            }
        }
    }
}
