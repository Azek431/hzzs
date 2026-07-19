package top.azek431.hzzs.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.azek431.hzzs.core.designsystem.HzzsSection
import top.azek431.hzzs.core.designsystem.StatusCard
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(repo: SettingsRepository) : ViewModel() {
    val config: StateFlow<AppConfig> = repo.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppConfig())
}

@Composable
fun HomeScreen(onOpenRuntime: () -> Unit, onOpenSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val config by vm.config.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("火崽崽奇妙屋", style = MaterialTheme.typography.headlineMedium)
            Text("轻量、本地、可解释的视觉辅助", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ElevatedCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, null)
                        Spacer(Modifier.width(10.dp))
                        Text("自动操作默认关闭", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("只有本次会话明确启用、场景稳定且前台页面匹配时，动作系统才会工作。")
                    Button(onClick = onOpenRuntime, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("进入运行控制")
                    }
                }
            }
        }
        item {
            HzzsSection("当前配置") {
                StatusCard("主题场景", if (config.selectedScene.name == "SWEET_FACTORY") "甜品工厂" else "竹影书屋")
                StatusCard("截图后端", config.captureBackend.name)
                StatusCard("自动操作", "已关闭，需要会话授权")
            }
        }
        item { OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("调整设置") } }
    }
}
