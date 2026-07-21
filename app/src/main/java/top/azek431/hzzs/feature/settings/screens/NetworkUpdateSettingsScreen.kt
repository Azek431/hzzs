package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.UpdateChannel
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.UpdateUiState
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard

@Composable
fun NetworkUpdateSettingsScreen(
    config: AppConfig,
    updateState: UpdateUiState,
    algorithmState: AlgorithmCatalogState,
    update: ((AppConfig) -> AppConfig) -> Unit,
    onCheckApp: () -> Unit,
    onDownloadApp: () -> Unit,
    onInstallApp: () -> Unit,
    onIgnoreApp: () -> Unit,
    onRefreshAlgorithms: () -> Unit,
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
                title = "下载来源",
                description = "默认使用 Gitee，无法访问时自动切换 GitHub。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRadioCard(
                        title = "自动选择",
                        subtitle = "默认使用 Gitee，无法访问时自动切换 GitHub。",
                        selected = config.update.sourcePreference == UpdateSourcePreference.AUTO,
                        onClick = {
                            update {
                                it.copy(update = it.update.copy(sourcePreference = UpdateSourcePreference.AUTO))
                            }
                        },
                    )
                    SettingsRadioCard(
                        title = "优先 Gitee",
                        subtitle = "始终先尝试 Gitee，失败再回退 GitHub。",
                        selected = config.update.sourcePreference == UpdateSourcePreference.PREFER_GITEE,
                        onClick = {
                            update {
                                it.copy(
                                    update = it.update.copy(
                                        sourcePreference = UpdateSourcePreference.PREFER_GITEE,
                                    ),
                                )
                            }
                        },
                    )
                    SettingsRadioCard(
                        title = "优先 GitHub",
                        subtitle = "始终先尝试 GitHub，失败再回退 Gitee。",
                        selected = config.update.sourcePreference == UpdateSourcePreference.PREFER_GITHUB,
                        onClick = {
                            update {
                                it.copy(
                                    update = it.update.copy(
                                        sourcePreference = UpdateSourcePreference.PREFER_GITHUB,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "网络策略",
                description = "仅限制大文件下载；小型目录检查不受该开关限制。",
            ) {
                SettingsSwitchRow(
                    title = "仅 Wi‑Fi 下载大文件",
                    subtitle = "应用 APK / 算法包下载受此限制；目录检查仍可在蜂窝网络进行。",
                    checked = config.update.wifiOnly,
                    onCheckedChange = { value ->
                        update { it.copy(update = it.update.copy(wifiOnly = value)) }
                    },
                )
                Text(
                    "当前实际来源：${algorithmState.activeSource?.name ?: "尚未探测"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                algorithmState.lastMirrorReason?.let { reason ->
                    Text(
                        "上次镜像切换：${reason.take(120)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "算法更新",
                description = "选择模式与通道属于草稿；检查/下载为即时任务。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlgorithmChannel.entries.forEach { channel ->
                        SettingsRadioCard(
                            title = "${channel.displayName()}算法通道",
                            selected = config.algorithm.channel == channel,
                            onClick = {
                                update {
                                    it.copy(algorithm = it.algorithm.copy(channel = channel))
                                }
                            },
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "自动检查算法更新",
                    checked = config.algorithm.autoCheck,
                    onCheckedChange = { value ->
                        update { it.copy(algorithm = it.algorithm.copy(autoCheck = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "自动下载算法更新",
                    subtitle = "下载后仍需保存选择才会激活（手动模式）。",
                    checked = config.algorithm.autoDownload,
                    onCheckedChange = { value ->
                        update { it.copy(algorithm = it.algorithm.copy(autoDownload = value)) }
                    },
                )
                Button(onClick = onRefreshAlgorithms) { Text("手动检查算法") }
            }
        }

        item {
            SettingsSectionCard(
                title = "应用更新",
                description = "Gitee 优先、GitHub 校验的签名更新源。未发布正式索引时检查失败是预期行为。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateChannel.entries.forEach { channel ->
                        SettingsRadioCard(
                            title = channel.displayName(),
                            selected = config.update.channel == channel,
                            onClick = {
                                update { it.copy(update = it.update.copy(channel = channel)) }
                            },
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "自动检查应用更新",
                    checked = config.update.autoCheck,
                    onCheckedChange = { value ->
                        update { it.copy(update = it.update.copy(autoCheck = value)) }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCheckApp, enabled = !updateState.busy) {
                        Text(if (updateState.busy) "处理中…" else "检查更新")
                    }
                    if (updateState.available != null) {
                        OutlinedButton(onClick = onDownloadApp, enabled = !updateState.busy) {
                            Text("下载")
                        }
                    }
                    if (updateState.localApk != null) {
                        Button(onClick = onInstallApp, enabled = !updateState.busy) {
                            Text("安装")
                        }
                    }
                    if (updateState.available != null) {
                        TextButton(onClick = onIgnoreApp, enabled = !updateState.busy) {
                            Text("忽略此版本")
                        }
                    }
                }
                updateState.available?.let { available ->
                    Text(
                        "远端 ${available.manifest.versionName}（code ${available.manifest.versionCode}，来源 ${available.source.name}）\n" +
                            available.manifest.releaseNotes.take(240),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                updateState.message?.let { text ->
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SettingsWarningCard(
                title = "不可信网络提示",
                body = "请勿在公共不可信网络强制关闭证书校验或安装未知来源算法包。官方算法必须通过签名校验。",
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
