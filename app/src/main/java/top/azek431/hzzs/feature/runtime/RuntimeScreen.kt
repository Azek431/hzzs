/**
 * 运行控制页（工具专业风操作台）。
 *
 * 职责：启停视觉分析、按 [requireSessionArm] 展示手动解锁/自动窗口，并 arm/disarm。
 * 数据流：状态来自 [VisionRuntimeController]；配置只读。
 * 边界：feature 只发意图；不直接 JNI / 截图 / WindowManager。
 */
package top.azek431.hzzs.feature.runtime

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import top.azek431.hzzs.core.designsystem.HzzsSecondaryAction
import top.azek431.hzzs.core.designsystem.HzzsStatusStrip
import top.azek431.hzzs.core.designsystem.LocalHzzsStatusColors
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

    fun arm() = viewModelScope.launch {
        controller.armAutomation().onFailure { transientMessage = it.message }
    }

    fun disarm() = controller.disarmAutomation()

    fun clearMessage() {
        transientMessage = null
    }
}

@Composable
fun RuntimeScreen(vm: RuntimeViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val requireSessionArm = config.automation.requireSessionArm
    val statusColors = LocalHzzsStatusColors.current
    val snackbar = remember { SnackbarHostState() }

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
                    subtitle = if (requireSessionArm) {
                        stringResource(R.string.runtime_subtitle_arm)
                    } else {
                        stringResource(R.string.runtime_subtitle_auto)
                    },
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

            item {
                SectionCard {
                    Text(
                        stringResource(R.string.runtime_automation_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!config.automation.enabled) {
                        Text(
                            stringResource(R.string.runtime_automation_disabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (requireSessionArm) {
                        Text(
                            stringResource(R.string.runtime_arm_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusChip(
                            if (status.automationArmed) {
                                stringResource(R.string.runtime_armed)
                            } else {
                                stringResource(R.string.runtime_locked)
                            },
                            active = status.automationArmed,
                            activeColor = if (status.automationArmed) {
                                statusColors.armed
                            } else {
                                statusColors.locked
                            },
                        )
                        if (status.automationArmed) {
                            HzzsSecondaryAction(
                                text = stringResource(R.string.runtime_disarm),
                                onClick = vm::disarm,
                                icon = Icons.Rounded.Lock,
                                tonal = true,
                            )
                        } else {
                            HzzsPrimaryAction(
                                text = stringResource(R.string.runtime_arm),
                                onClick = vm::arm,
                                enabled = status.running && status.captureReady,
                                icon = Icons.Rounded.LockOpen,
                            )
                            if (!status.running || !status.captureReady) {
                                Text(
                                    stringResource(R.string.runtime_arm_need_ready),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.runtime_auto_mode_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusChip(
                            if (status.running && status.captureReady) {
                                stringResource(R.string.runtime_auto_ready)
                            } else {
                                stringResource(R.string.runtime_auto_wait)
                            },
                            active = status.running && status.captureReady,
                            activeColor = statusColors.armed,
                        )
                        Text(
                            stringResource(R.string.runtime_auto_settings_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            status.lastError?.let { error ->
                item {
                    HzzsCallout(
                        title = stringResource(R.string.runtime_error_title),
                        text = error,
                        tone = HzzsCalloutTone.ERROR,
                        icon = Icons.Rounded.Warning,
                    )
                }
            }

            item {
                HzzsCallout(
                    text = "悬浮窗与录屏授权由系统对话框授予。自动操作仅在前台包名位于白名单时生效。",
                    tone = HzzsCalloutTone.INFO,
                    icon = Icons.Rounded.Security,
                )
            }
        }
    }
}
