/**
 * 检测与识别参数设置页。
 *
 * 职责：赛季、障碍开关、玩家基准、工作宽度与自动动作门控参数。
 * 数据流：经 [update] 即时落盘；不直接 JNI。
 * 边界：算法包选择在「算法库」页；本页只调用户侧阈值。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard

@Composable
fun DetectionSettingsScreen(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val scene = config.scenes.getValue(config.selectedScene)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsWarningCard(
                title = "参数说明",
                body = "赛季与障碍开关影响检测输出；工作宽度与玩家基准进入识别引擎。" +
                    "置信度、稳定帧与玩家后方边距只过滤自动动作候选，不改变 HUD 画框。",
            )
        }

        item {
            SettingsSectionCard(
                title = "当前赛季",
                description = "切换后立即写入；分析运行中会取消在飞动作。",
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
            }
        }

        item {
            SettingsSectionCard(
                title = "启用检测的障碍",
                description = "关闭后该类别不再出现在识别结果中。",
            ) {
                obstaclesFor(config.selectedScene).forEach { obstacle ->
                    val enabled = obstacle !in scene.disabledObstacles
                    SettingsSwitchRow(
                        title = obstacle.displayName(),
                        subtitle = if (enabled) "正在检测" else "已关闭",
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
            }
        }

        item {
            SettingsSectionCard(
                title = "玩家基准",
                description = "决定玩家水平参考如何取得。",
            ) {
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
                    Spacer(Modifier.height(8.dp))
                    LabeledSlider(
                        "玩家水平位置（视口比例）",
                        scene.thresholds.fixedPlayerXRatio,
                        0.05f..0.45f,
                    ) { value ->
                        updateScene(update, config) {
                            it.copy(thresholds = it.thresholds.copy(fixedPlayerXRatio = value))
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "识别工作参数",
                description = "工作宽度越大通常越慢、细节更多；范围 192–960。",
            ) {
                LabeledSlider(
                    "识别工作宽度（像素）",
                    scene.thresholds.workWidth.toFloat(),
                    192f..960f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    updateScene(update, config) {
                        it.copy(thresholds = it.thresholds.copy(workWidth = value.toInt()))
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "自动动作门控（本赛季）",
                description = "仅影响是否对手势规划；HUD 仍显示全部有效检测。",
            ) {
                LabeledSlider(
                    "最低置信度",
                    scene.thresholds.minimumConfidence,
                    0.1f..1.0f,
                ) { value ->
                    updateScene(update, config) {
                        it.copy(thresholds = it.thresholds.copy(minimumConfidence = value))
                    }
                }
                LabeledSlider(
                    "稳定帧数",
                    scene.thresholds.stableFrames.toFloat(),
                    1f..12f,
                    valueText = { it.toInt().toString() },
                ) { value ->
                    updateScene(update, config) {
                        it.copy(thresholds = it.thresholds.copy(stableFrames = value.toInt()))
                    }
                }
                LabeledSlider(
                    "玩家后方边距（视口比例）",
                    scene.thresholds.behindPlayerMarginRatio,
                    0f..0.2f,
                ) { value ->
                    updateScene(update, config) {
                        it.copy(thresholds = it.thresholds.copy(behindPlayerMarginRatio = value))
                    }
                }
                Text(
                    "边界容差 ${"%.3f".format(scene.thresholds.boundaryTolerancePlayerWidthRatio)} " +
                        "（相对玩家宽度，主要供评估工具，主路径不消费）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

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
        ObstacleKind.GREEN_BOTTLE,
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
    SceneId.SEA_SALT_LIVING_ROOM -> listOf(
        ObstacleKind.SAND_CASTLE,
        ObstacleKind.HANGING_ANCHOR,
        ObstacleKind.SEA_PIT,
        ObstacleKind.PIT,
    )
}
