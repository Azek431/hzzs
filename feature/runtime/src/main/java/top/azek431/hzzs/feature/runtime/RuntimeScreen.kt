package top.azek431.hzzs.feature.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.data.vision.VisionRuntimeController
import javax.inject.Inject

@HiltViewModel
class RuntimeViewModel @Inject constructor(
    private val controller: VisionRuntimeController,
) : ViewModel() {
    val status: StateFlow<RuntimeStatus> = controller.status
    var transientMessage by mutableStateOf<String?>(null)
        private set

    fun toggle() = viewModelScope.launch {
        if (status.value.running) controller.stop() else controller.start()
    }

    fun arm() = viewModelScope.launch {
        controller.armAutomation().onFailure { transientMessage = it.message }
    }

    fun disarm() = controller.disarmAutomation()
    fun clearMessage() { transientMessage = null }
}

@Composable
fun RuntimeScreen(vm: RuntimeViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.transientMessage) {
        vm.transientMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("运行控制", style = MaterialTheme.typography.headlineMedium) }
            item {
                ElevatedCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (status.running) "视觉分析正在运行" else "视觉分析已停止", style = MaterialTheme.typography.titleLarge)
                        Text("场景：${if (status.activeScene.name == "SWEET_FACTORY") "甜品工厂" else "竹影书屋"}")
                        Text("捕获：${if (status.captureReady) "已就绪" else "等待授权或连接"} · ${"%.1f".format(status.fps)} FPS")
                        LinearProgressIndicator(
                            progress = { if (status.running && status.captureReady) 1f else if (status.running) .35f else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = vm::toggle, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (status.running) Icons.Rounded.Stop else Icons.Rounded.Visibility, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (status.running) "停止分析" else "开始分析")
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Security, null)
                            Text("自动操作安全锁", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("只绑定本次会话的当前游戏窗口。切换页面、场景、捕获后端或执行失败都会自动解除。")
                        if (status.automationArmed) {
                            FilledTonalButton(onClick = vm::disarm, modifier = Modifier.fillMaxWidth()) { Text("立即解除") }
                        } else {
                            Button(onClick = vm::arm, enabled = status.running && status.captureReady, modifier = Modifier.fillMaxWidth()) {
                                Text("确认当前页面并临时启用")
                            }
                        }
                    }
                }
            }
            status.lastError?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Warning, null)
                            Text(error)
                        }
                    }
                }
            }
        }
    }
}
