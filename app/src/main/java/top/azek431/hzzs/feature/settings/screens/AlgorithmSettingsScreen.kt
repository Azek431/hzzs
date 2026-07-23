/**
 * 算法库设置页。
 *
 * 职责：自动/手动选择、算法卡片切换（内置 / 捆绑 / 已装 / 远端）、检查更新。
 * 数据流：偏好经 [update] 即时落盘；检查/下载/选用经 ViewModel。
 * 边界：不直接网络/JNI；检测阈值见「检测参数」页。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.algorithm.AlgorithmCardStatus
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.algorithm.AlgorithmOrigin
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.label
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.AlgorithmCard
import top.azek431.hzzs.feature.settings.components.SettingsEmptyState
import top.azek431.hzzs.feature.settings.components.SettingsErrorBanner
import top.azek431.hzzs.feature.settings.components.SettingsHeroCard
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsStatusChip
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlgorithmSettingsScreen(
    config: AppConfig,
    algorithmState: AlgorithmCatalogState,
    update: ((AppConfig) -> AppConfig) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSelect: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val active = algorithmState.active
    val pending = algorithmState.pendingActivation
    val latestId = algorithmState.remote
        .filter { it.isCompatible && config.selectedScene in it.supportedScenes }
        .maxByOrNull { it.versionCode }
        ?.id
    var details by remember { mutableStateOf<AlgorithmPackageInfo?>(null) }

    val libraryCards = remember(algorithmState.installed, algorithmState.remote) {
        val installedIds = algorithmState.installed.map { it.id }.toSet()
        val remoteOnly = algorithmState.remote.filter { it.id !in installedIds }
        algorithmState.installed + remoteOnly
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsHeroCard(
                title = active?.name ?: "内置算法",
                subtitle = active?.let {
                    buildString {
                        append("v${it.versionName}")
                        append(" · ")
                        append(it.origin.label())
                        it.author?.takeIf { a -> a.isNotBlank() }?.let { a ->
                            append(" · ")
                            append(a)
                        }
                    }
                } ?: "将使用内置引擎",
                icon = Icons.Rounded.AutoAwesome,
                badges = listOfNotNull(
                    config.algorithm.selectionMode.displayName(),
                    config.selectedScene.displayName(),
                    config.algorithm.channel.displayName(),
                    when (algorithmState.phase) {
                        is AlgorithmCatalogPhase.PendingActivation -> "待启用"
                        is AlgorithmCatalogPhase.MirrorFallback -> "镜像回退"
                        is AlgorithmCatalogPhase.OfflineWithCache -> "离线缓存"
                        is AlgorithmCatalogPhase.Loading -> "检查中"
                        else -> null
                    },
                ),
                meta = {
                    MetaLine("当前场景", config.selectedScene.displayName())
                    MetaLine("来源", active?.downloadSource?.label() ?: "内置引擎")
                    MetaLine(
                        "更新状态",
                        when (val phase = algorithmState.phase) {
                            is AlgorithmCatalogPhase.Error -> phase.message.take(80)
                            is AlgorithmCatalogPhase.OfflineWithCache -> phase.message.take(80)
                            is AlgorithmCatalogPhase.MirrorFallback -> phase.reason.take(80)
                            is AlgorithmCatalogPhase.SecurityWarning -> phase.message.take(80)
                            is AlgorithmCatalogPhase.PendingActivation -> phase.message
                            is AlgorithmCatalogPhase.Loading -> "正在检查…"
                            else -> algorithmState.message?.take(80) ?: "就绪"
                        },
                    )
                    MetaLine(
                        "最近检查",
                        algorithmState.lastCheckedAtEpochMs?.let { formatTime(it) } ?: "尚未检查",
                    )
                    if (pending != null) {
                        Text(
                            "已选择 ${pending.name}，" +
                                if (algorithmState.analysisRunning) {
                                    "停止分析后或下次启动时应用"
                                } else {
                                    "将在保存后立即启用"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRefresh) { Text("检查更新") }
                        OutlinedButton(
                            onClick = { details = active },
                            enabled = active != null,
                        ) { Text("查看详情") }
                    }
                },
            )
        }

        item { PhaseBanner(algorithmState.phase, onRefresh) }

        if (!algorithmState.trustAnchorsConfigured) {
            item {
                SettingsWarningCard(
                    title = "远端安装暂不可用",
                    body = "客户端尚未配置官方算法公钥时，仍可自由切换内置引擎与应用捆绑包（如酱油海盐包）。远端下载按钮已禁用，避免「能点却装不上」。",
                )
            }
        } else {
            item {
                SettingsWarningCard(
                    title = "算法库说明",
                    body = "内置引擎与应用捆绑包可直接切换；远端包经 HTTPS 双源目录下载，并校验哈希与官方签名。分析运行中切换只会排队，停止或下次启动分析时生效。检测阈值请到「检测参数」。",
                )
            }
        }

        item {
            SettingsSectionCard(title = "选择方式", description = null) {
                SettingsRadioCard(
                    title = "自动选择（推荐）",
                    subtitle = "优先使用当前场景下已安装且兼容的最新算法；无外装时回退内置。开启自动下载后，检查更新成功才会尝试拉包。",
                    selected = config.algorithm.selectionMode == AlgorithmSelectionMode.AUTO,
                    onClick = {
                        update {
                            it.copy(
                                algorithm = it.algorithm.copy(
                                    selectionMode = AlgorithmSelectionMode.AUTO,
                                    pinnedAlgorithmId = null,
                                ),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsRadioCard(
                    title = "手动选择",
                    subtitle = "在下方算法库中点「使用此版本」钉选；分析运行中仅排队待启用。",
                    selected = config.algorithm.selectionMode == AlgorithmSelectionMode.MANUAL,
                    onClick = {
                        update {
                            it.copy(
                                algorithm = it.algorithm.copy(
                                    selectionMode = AlgorithmSelectionMode.MANUAL,
                                    pinnedAlgorithmId = it.algorithm.pinnedAlgorithmId ?: active?.id,
                                ),
                            )
                        }
                    },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "算法更新通道",
                description = "影响远端目录 stable / beta；捆绑包与内置不受通道限制。",
            ) {
                SettingsRadioCard(
                    title = "稳定通道",
                    subtitle = "面向大众的已验证算法。",
                    selected = config.algorithm.channel == AlgorithmChannel.STABLE,
                    onClick = {
                        update { it.copy(algorithm = it.algorithm.copy(channel = AlgorithmChannel.STABLE)) }
                        onRefresh()
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsRadioCard(
                    title = "测试通道",
                    subtitle = "实验与预发布包（如酱油海盐远端更新）。",
                    selected = config.algorithm.channel == AlgorithmChannel.BETA,
                    onClick = {
                        update { it.copy(algorithm = it.algorithm.copy(channel = AlgorithmChannel.BETA)) }
                        onRefresh()
                    },
                )
            }
        }

        item {
            Text("算法库", style = MaterialTheme.typography.titleMedium)
            Text(
                "内置引擎 · 应用捆绑（酱油/官方示例）· 已下载 · 远端可更新。点「使用此版本」即可切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (libraryCards.isEmpty()) {
            item {
                SettingsEmptyState(
                    title = "暂无算法条目",
                    body = "至少应有内置引擎。可点检查更新或重装应用。",
                    actionLabel = "检查更新",
                    onAction = onRefresh,
                )
            }
        } else {
            items(libraryCards, key = { "lib-${it.id}-${it.origin.name}" }) { info ->
                val status = resolveStatus(info, active?.id, pending?.id, latestId)
                val roleLabel = when {
                    info.id == active?.id -> "当前使用"
                    info.id == pending?.id -> "待启用"
                    info.id == algorithmState.previousRollback?.id -> "可回滚"
                    info.isBuiltin -> "内置引擎"
                    info.origin == AlgorithmOrigin.BUNDLED -> "应用捆绑"
                    info.isInstalled -> "已安装"
                    else -> "远端"
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsStatusChip(roleLabel, emphasis = info.id == active?.id)
                    AlgorithmCard(
                        info = info,
                        status = status,
                        download = algorithmState.downloads[info.id],
                        manualMode = true,
                        canDownloadRemote = algorithmState.trustAnchorsConfigured,
                        onDownload = {
                            if (!algorithmState.trustAnchorsConfigured) {
                                onMessage("未配置官方公钥，无法下载远端算法")
                            } else {
                                onDownload(info.id)
                                onMessage("开始下载 ${info.name}")
                            }
                        },
                        onUpdate = {
                            if (!algorithmState.trustAnchorsConfigured) {
                                onMessage("未配置官方公钥，无法更新远端算法")
                            } else {
                                onDownload(info.id)
                                onMessage("开始更新 ${info.name}")
                            }
                        },
                        onSelect = {
                            onSelect(info.id)
                            onMessage("已选择 ${info.name}")
                        },
                        onDetails = { details = info },
                        onCancelDownload = { onCancelDownload(info.id) },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    details?.let { info ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(info.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("版本：${info.versionName} (${info.versionCode})")
                    Text("通道：${info.channel.displayName()}")
                    Text("签名：${info.signature.label()}")
                    Text("来源：${info.downloadSource.label()}")
                    info.author?.let { Text("作者：$it") }
                    Text("场景：${info.supportedScenes.joinToString { it.displayName() }}")
                    Text(info.releaseNotes.ifBlank { info.summary })
                }
            },
            confirmButton = {
                TextButton(onClick = { details = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun PhaseBanner(phase: AlgorithmCatalogPhase, onRefresh: () -> Unit) {
    when (phase) {
        AlgorithmCatalogPhase.Loading -> {
            SettingsEmptyState(
                title = "正在加载算法目录",
                body = "正在连接更新源。小型目录检查不受「仅 Wi‑Fi 下载大文件」限制。",
            )
        }
        AlgorithmCatalogPhase.Empty -> {
            SettingsEmptyState(
                title = "远端目录为空",
                body = "仍可使用内置与捆绑算法。可稍后重试检查更新。",
                actionLabel = "重试",
                onAction = onRefresh,
            )
        }
        is AlgorithmCatalogPhase.OfflineWithCache -> {
            SettingsErrorBanner(message = phase.message, actionLabel = "重试", onAction = onRefresh)
        }
        is AlgorithmCatalogPhase.MirrorFallback -> {
            SettingsWarningCard(title = "已切换镜像", body = phase.reason.take(160))
        }
        is AlgorithmCatalogPhase.SecurityWarning -> {
            SettingsWarningCard(title = "安全提示", body = phase.message.take(160))
        }
        is AlgorithmCatalogPhase.Error -> {
            SettingsErrorBanner(message = phase.message, actionLabel = "重试", onAction = onRefresh)
        }
        is AlgorithmCatalogPhase.Incompatible -> {
            SettingsWarningCard(title = "不兼容", body = phase.message.take(160))
        }
        is AlgorithmCatalogPhase.PendingActivation -> {
            SettingsEmptyState(title = "待启用", body = phase.message)
        }
        is AlgorithmCatalogPhase.Downloading,
        is AlgorithmCatalogPhase.Verifying,
        AlgorithmCatalogPhase.Idle,
        -> Unit
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun resolveStatus(
    info: AlgorithmPackageInfo,
    activeId: String?,
    pendingId: String?,
    latestId: String?,
): AlgorithmCardStatus {
    if (!info.isCompatible) return AlgorithmCardStatus.INCOMPATIBLE
    if (pendingId != null && info.id == pendingId) return AlgorithmCardStatus.PENDING_ACTIVATION
    if (activeId != null && info.id == activeId) return AlgorithmCardStatus.CURRENT
    if (latestId == info.id) {
        return if (info.isInstalled) AlgorithmCardStatus.LATEST else AlgorithmCardStatus.UPDATABLE
    }
    if (info.isInstalled || info.isBuiltin || info.origin == AlgorithmOrigin.BUNDLED) {
        return AlgorithmCardStatus.INSTALLED
    }
    return AlgorithmCardStatus.DOWNLOADABLE
}

private fun formatTime(epochMs: Long): String =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSXXX", Locale.CHINA).format(Date(epochMs))
    }.getOrDefault("—")
