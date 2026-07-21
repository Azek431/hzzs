/**
 * 运行控制页。
 *
 * 职责：启停视觉分析、按 [requireSessionArm] 展示手动解锁/自动窗口文案，并 arm/disarm 自动操作。
 * 数据流：状态来自 [VisionRuntimeController.status]；配置只读 [SettingsRepository]；
 * 启停/解锁委托 Controller，本层不直接 JNI、截图或 WindowManager。
 * 边界：feature 只发意图；权限对话框与平台能力由 Controller/平台层处理。
 */
package top.azek431.hzzs.feature.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.designsystem.HeroCard
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

/**
 * 运行页 ViewModel：桥接 [VisionRuntimeController] 与已保存配置。
 * 瞬时错误经 [transientMessage] 交给 Snackbar，不改持久配置。
 */
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

/**
 * 运行控制 UI。
 * [requireSessionArm]=true：需手动确认当前窗口；false：自动窗口模式，无需 arm 按钮。
 */
@Composable
fun RuntimeScreen(vm: RuntimeViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val requireSessionArm = config.automation.requireSessionArm
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.transientMessage) {
        vm.transientMessage?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                PageHeader(
                    title = "运行控制",
                    subtitle = if (requireSessionArm) {
                        "先启动分析，再按需解锁自动操作"
                    } else {
                        "先启动分析；当前为自动窗口模式，无需手动解锁"
                    },
                )
            }

            item {
                HeroCard(
                    title = if (status.running) "视觉分析运行中" else "视觉分析已停止",
                    subtitle = "${status.activeScene.displayName()} · ${status.activeBackend.displayName()}",
                    icon = if (status.running) Icons.Rounded.Visibility else Icons.Rounded.Stop,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            if (status.running) "分析中" else "未运行",
                            active = status.running,
                        )
                        StatusChip(
                            if (status.captureReady) "截图就绪" else "等待截图",
                            active = status.captureReady,
                        )
                        StatusChip(
                            if (status.overlayVisible) "悬浮窗显示" else "悬浮窗隐藏",
                            active = status.overlayVisible,
                        )
                    }

                    LinearProgressIndicator(
                        progress = {
                            when {
                                status.running && status.captureReady -> 1f
                                status.running -> 0.35f
                                else -> 0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetricTile(
                            label = "帧率",
                            value = "${"%.1f".format(status.fps)} FPS",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "耗时",
                            value = "${"%.1f".format(status.processingMs)} ms",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "障碍",
                            value = "${status.obstacleCount}",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Button(onClick = vm::toggle, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (status.running) Icons.Rounded.Stop else Icons.Rounded.Visibility,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (status.running) "停止分析" else "开始分析")
                    }
                }
            }

            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "自动操作安全锁",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (requireSessionArm) {
                        Text(
                            "只会绑定本次会话的当前游戏窗口。切换页面、场景、截图方式或执行失败后会自动解除。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusChip(
                            if (status.automationArmed) "已解锁：可发送手势" else "已上锁：仅分析不操作",
                            active = status.automationArmed,
                        )
                        if (status.automationArmed) {
                            FilledTonalButton(onClick = vm::disarm, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Lock, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("立即解除")
                            }
                        } else {
                            Button(
                                onClick = vm::arm,
                                enabled = status.running && status.captureReady,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("确认当前页面并临时启用")
                            }
                            if (!status.running || !status.captureReady) {
                                Text(
                                    "请先开始分析并完成截图授权，才能解锁自动操作。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            "当前设置为自动窗口模式：分析运行中且前台应用在白名单时，将直接按当前页面规划手势，无需手动解锁。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusChip(
                            if (status.running && status.captureReady) {
                                "自动模式：满足条件即可发送手势"
                            } else {
                                "自动模式：等待分析与截图就绪"
                            },
                            active = status.running && status.captureReady,
                        )
                        Text(
                            "可在设置中重新打开“每次运行需手动解锁窗口”。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            status.lastError?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "需要处理",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "提示：悬浮窗权限、屏幕录制授权和游戏前台包名都会影响实际效果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
