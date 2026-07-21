/**
 * 算法与识别设置页。
 *
 * 职责：选择自动/手动算法、赛季与识别阈值；展示远端/已安装算法卡。
 * 数据流：草稿经 [update]；检查/下载/取消/选用经 ViewModel 即时任务；
 * 算法字段预览被 baseline 锁定，保存后才真正切换运行时。
 * 边界：不直接网络/JNI；目录状态来自 [AlgorithmCatalogState]。
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
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.label
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.AlgorithmCard
import top.azek431.hzzs.feature.settings.components.SettingsEmptyState
import top.azek431.hzzs.feature.settings.components.SettingsErrorBanner
import top.azek431.hzzs.feature.settings.components.SettingsHeroCard
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsStatusChip
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
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
    val scene = config.scenes.getValue(config.selectedScene)
    val active = algorithmState.active
    val pending = algorithmState.pendingActivation
    val latestId = algorithmState.remote
        .filter { it.isCompatible && config.selectedScene in it.supportedScenes }
        .maxByOrNull { it.versionCode }
        ?.id
    var details by remember { mutableStateOf<AlgorithmPackageInfo?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsHeroCard(
                title = active?.name ?: "尚未选择算法",
                subtitle = active?.let { "v${it.versionName} · ${it.origin.label()}" } ?: "将使用内置算法",
                icon = Icons.Rounded.AutoAwesome,
                badges = listOfNotNull(
                    config.algorithm.selectionMode.displayName(),
                    config.selectedScene.displayName(),
                    when (val phase = algorithmState.phase) {
                        is AlgorithmCatalogPhase.PendingActivation -> "待启用"
                        is AlgorithmCatalogPhase.MirrorFallback -> "镜像回退"
                        is AlgorithmCatalogPhase.OfflineWithCache -> "离线缓存"
                        is AlgorithmCatalogPhase.Loading -> "检查中"
                        else -> null
                    },
                ),
                meta = {
                    MetaLine("当前场景", config.selectedScene.displayName())
                    MetaLine("来源", active?.downloadSource?.label() ?: "内置")
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
                            "已选择 ${pending.name}，${if (algorithmState.analysisRunning) "下次启动分析时应用" else "保存后启用"}",
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

        item {
            PhaseBanner(algorithmState.phase, onRefresh)
        }

        item {
            SettingsSectionCard(
                title = "选择方式",
                description = null,
            ) {
                SettingsRadioCard(
                    title = "自动选择（推荐）",
                    subtitle = "自动获取并使用当前场景下最新且兼容的官方算法。网络失败时继续使用已安装算法或内置算法。",
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
                    subtitle = "从已安装算法中指定版本；下载不会自动激活，需保存选择。",
                    selected = config.algorithm.selectionMode == AlgorithmSelectionMode.MANUAL,
                    onClick = {
                        update {
                            it.copy(
                                algorithm = it.algorithm.copy(
                                    selectionMode = AlgorithmSelectionMode.MANUAL,
                                    pinnedAlgorithmId = it.algorithm.pinnedAlgorithmId
                                        ?: active?.id,
                                ),
                            )
                        }
                    },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "当前赛季与识别",
                description = "采用比例坐标适配不同分辨率；默认识别当前赛季的全部障碍。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneId.entries.forEach { id ->
                        SettingsRadioCard(
                            title = id.displayName(),
                            selected = config.selectedScene == id,
                            onClick = { update { it.copy(selectedScene = id) } },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("不识别的障碍", style = MaterialTheme.typography.labelLarge)
                obstaclesFor(config.selectedScene).forEach { obstacle ->
                    val enabled = obstacle !in scene.disabledObstacles
                    SettingsSwitchRow(
                        title = obstacle.displayName(),
                        checked = enabled,
                        onCheckedChange = { include ->
                            update { app ->
                                val current = app.scenes.getValue(app.selectedScene)
                                val disabled = current.disabledObstacles.toMutableSet().apply {
                                    if (include) remove(obstacle) else add(obstacle)
                                }
                                app.copy(
                                    scenes = app.scenes + (
                                        app.selectedScene to current.copy(disabledObstacles = disabled)
                                        ),
                                )
                            }
                        },
                    )
                }
                Text("玩家基准", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerReferenceMode.entries.forEach { mode ->
                        SettingsRadioCard(
                            title = mode.displayName(),
                            selected = scene.thresholds.playerReferenceMode == mode,
                            onClick = {
                                updateScene(update, config) {
                                    it.copy(thresholds = it.thresholds.copy(playerReferenceMode = mode))
                                }
                            },
                        )
                    }
                }
                if (scene.thresholds.playerReferenceMode == PlayerReferenceMode.FIXED_RATIO) {
                    LabeledSlider(
                        "玩家水平位置",
                        scene.thresholds.fixedPlayerXRatio,
                        0.05f..0.45f,
                    ) { value ->
                        updateScene(update, config) {
                            it.copy(thresholds = it.thresholds.copy(fixedPlayerXRatio = value))
                        }
                    }
                }
                LabeledSlider(
                    "识别工作宽度",
                    scene.thresholds.workWidth.toFloat(),
                    192f..960f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    updateScene(update, config) {
                        it.copy(thresholds = it.thresholds.copy(workWidth = value.toInt()))
                    }
                }
                LabeledSlider(
                    "最低置信度",
                    scene.thresholds.minimumConfidence,
                    0.4f..0.95f,
                ) { value ->
                    updateScene(update, config) {
                        it.copy(thresholds = it.thresholds.copy(minimumConfidence = value))
                    }
                }
            }
        }

        item {
            Text("最新算法", style = MaterialTheme.typography.titleMedium)
            Text(
                "按兼容性与发布时间排序。下载是即时任务；手动模式下不会自动激活。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (algorithmState.remote.isEmpty()) {
            item {
                SettingsEmptyState(
                    title = "暂无远端算法目录",
                    body = "可以检查更新，或继续使用已安装 / 内置算法。网络失败时会保留本地缓存。",
                    actionLabel = "检查更新",
                    onAction = onRefresh,
                )
            }
        } else {
            items(algorithmState.remote, key = { it.id }) { info ->
                val status = resolveStatus(info, active?.id, pending?.id, latestId)
                AlgorithmCard(
                    info = info,
                    status = status,
                    download = algorithmState.downloads[info.id],
                    manualMode = config.algorithm.selectionMode == AlgorithmSelectionMode.MANUAL,
                    onDownload = {
                        onDownload(info.id)
                        onMessage("开始下载 ${info.name}")
                    },
                    onUpdate = {
                        onDownload(info.id)
                        onMessage("开始更新 ${info.name}")
                    },
                    onSelect = { onSelect(info.id) },
                    onDetails = { details = info },
                    onCancelDownload = { onCancelDownload(info.id) },
                )
            }
        }

        item {
            Text("已安装算法", style = MaterialTheme.typography.titleMedium)
        }

        items(algorithmState.installed, key = { "installed-${it.id}" }) { info ->
            val status = resolveStatus(info, active?.id, pending?.id, latestId)
            val roleLabel = when {
                info.id == active?.id -> "当前使用"
                info.id == algorithmState.previousRollback?.id -> "可回滚"
                info.isBuiltin -> "内置算法"
                else -> "其他安装版本"
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsStatusChip(roleLabel, emphasis = info.id == active?.id)
                AlgorithmCard(
                    info = info,
                    status = status,
                    download = algorithmState.downloads[info.id],
                    manualMode = config.algorithm.selectionMode == AlgorithmSelectionMode.MANUAL,
                    onDownload = { onDownload(info.id) },
                    onUpdate = { onDownload(info.id) },
                    onSelect = { onSelect(info.id) },
                    onDetails = { details = info },
                    onCancelDownload = { onCancelDownload(info.id) },
                )
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

/** 根据目录 phase 展示加载/离线/安全等横幅。 */
@Composable
private fun PhaseBanner(phase: AlgorithmCatalogPhase, onRefresh: () -> Unit) {
    when (phase) {
        AlgorithmCatalogPhase.Loading -> {
            SettingsEmptyState(
                title = "正在加载算法目录",
                body = "正在连接更新源并校验清单。小型目录检查不受“仅 Wi‑Fi 下载大文件”限制。",
            )
        }
        AlgorithmCatalogPhase.Empty -> {
            SettingsEmptyState(
                title = "目录为空",
                body = "远端没有可用算法条目。可稍后重试，或继续使用内置算法。",
                actionLabel = "重试",
                onAction = onRefresh,
            )
        }
        is AlgorithmCatalogPhase.OfflineWithCache -> {
            SettingsErrorBanner(
                message = phase.message,
                actionLabel = "重试",
                onAction = onRefresh,
            )
        }
        is AlgorithmCatalogPhase.MirrorFallback -> {
            SettingsWarningCard(
                title = "已切换镜像",
                body = phase.reason.take(160),
            )
        }
        is AlgorithmCatalogPhase.SecurityWarning -> {
            SettingsWarningCard(
                title = "安全警告",
                body = phase.message.take(160),
            )
        }
        is AlgorithmCatalogPhase.Error -> {
            SettingsErrorBanner(
                message = phase.message,
                actionLabel = "重试",
                onAction = onRefresh,
            )
        }
        is AlgorithmCatalogPhase.Incompatible -> {
            SettingsWarningCard(
                title = "不兼容",
                body = phase.message.take(160),
            )
        }
        is AlgorithmCatalogPhase.PendingActivation -> {
            SettingsEmptyState(
                title = "待启用",
                body = phase.message,
            )
        }
        is AlgorithmCatalogPhase.Downloading,
        is AlgorithmCatalogPhase.Verifying,
        AlgorithmCatalogPhase.Idle,
        -> Unit
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 解析算法卡展示状态（当前/待启用/可更新等）。 */
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
    if (info.isInstalled) return AlgorithmCardStatus.INSTALLED
    return AlgorithmCardStatus.DOWNLOADABLE
}

/** 仅更新当前选中赛季的 [SceneConfig]。 */
private fun updateScene(
    update: ((AppConfig) -> AppConfig) -> Unit,
    config: AppConfig,
    transform: (SceneConfig) -> SceneConfig,
) {
    update { app ->
        app.copy(
            scenes = app.scenes + (
                config.selectedScene to transform(app.scenes.getValue(config.selectedScene))
                ),
        )
    }
}

private fun obstaclesFor(scene: SceneId): List<ObstacleKind> = when (scene) {
    SceneId.SWEET_FACTORY -> listOf(
        ObstacleKind.POISON_BOTTLE,
        ObstacleKind.CAKE_STRUCTURE,
        ObstacleKind.HANGING_SPIKE,
        ObstacleKind.PIT,
    )
    SceneId.BAMBOO_BOOKSTORE -> listOf(
        ObstacleKind.PANDA_STATUE,
        ObstacleKind.BAMBOO_GAP,
        ObstacleKind.HANGING_BRUSH,
        ObstacleKind.PIT,
    )
}

private fun formatTime(epochMs: Long): String =
    runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))
    }.getOrDefault("—")
