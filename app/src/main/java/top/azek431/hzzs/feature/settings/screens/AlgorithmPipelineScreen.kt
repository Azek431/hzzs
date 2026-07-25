/**
 * 算法执行流程可视化页。
 *
 * 职责：展示 [AlgorithmPipelineTrace] 阶段状态与最近一帧摘要，直观看激活与分析路径。
 * 边界：只读进程内追踪器；不触发配置/激活；复制摘要经剪贴板 helper。
 * 布局：内容页（不自建 Scaffold），由外层 Settings/About 提供顶栏。
 */
package top.azek431.hzzs.feature.settings.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.algorithm.AlgorithmPipelineSnapshot
import top.azek431.hzzs.core.algorithm.AlgorithmPipelineStage
import top.azek431.hzzs.core.algorithm.AlgorithmPipelineTrace
import top.azek431.hzzs.core.algorithm.AlgorithmStageStatus
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.platform.ClipboardHelper

/**
 * 算法流程页：轮询 [AlgorithmPipelineTrace.revision] 刷新。
 *
 * @param onOpenLogs 跳转运行日志（可预筛 algorithm）
 */
@Composable
fun AlgorithmPipelineScreen(
    onBack: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    onOpenLogs: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dimensions = LocalHzzsDimensions.current
    var revision by remember { mutableLongStateOf(AlgorithmPipelineTrace.revision()) }
    var snapshot by remember { mutableStateOf(AlgorithmPipelineTrace.snapshot()) }

    val copiedMsg = stringResource(R.string.algorithm_pipeline_copied)
    val copyFailed = stringResource(R.string.dev_copy_failed)
    val refreshedMsg = stringResource(R.string.algorithm_pipeline_refreshed)
    val shareChooser = stringResource(R.string.algorithm_pipeline_share_chooser)
    val shareFailedTemplate = stringResource(R.string.algorithm_pipeline_share_failed)

    LaunchedEffect(Unit) {
        while (true) {
            val rev = AlgorithmPipelineTrace.revision()
            if (rev != revision) {
                revision = rev
                snapshot = AlgorithmPipelineTrace.snapshot()
            }
            delay(600)
        }
    }

    fun refreshNow() {
        revision = AlgorithmPipelineTrace.revision()
        snapshot = AlgorithmPipelineTrace.snapshot()
        onMessage(refreshedMsg)
    }

    fun copySnapshot() {
        val text = AlgorithmPipelineTrace.formatText()
        val ok = ClipboardHelper.copyText(context, "HZZS algorithm pipeline", text)
        onMessage(if (ok) copiedMsg else copyFailed)
    }

    fun shareSnapshot() {
        val text = AlgorithmPipelineTrace.formatText()
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "HZZS algorithm pipeline")
            }
            context.startActivity(Intent.createChooser(send, shareChooser))
        }.onFailure {
            onMessage(shareFailedTemplate.format(it.message ?: it.javaClass.simpleName))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.algorithm_pipeline_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.algorithm_pipeline_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { refreshNow() }) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.algorithm_pipeline_refresh),
                    )
                }
                IconButton(onClick = { copySnapshot() }) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.dev_copy_to_clipboard),
                    )
                }
                IconButton(onClick = { shareSnapshot() }) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                }
            }
        }
        item {
            ContextCard(snapshot)
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.algorithm_pipeline_stages),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { refreshNow() }) {
                    Text(stringResource(R.string.algorithm_pipeline_refresh))
                }
            }
        }
        itemsIndexed(snapshot.stages, key = { _, stage -> stage.id }) { index, stage ->
            StageRow(
                index = index + 1,
                stage = stage,
                isLast = index == snapshot.stages.lastIndex,
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.algorithm_pipeline_last_frame),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            LastFrameCard(snapshot)
        }
        if (onOpenLogs != null) {
            item {
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.algorithm_pipeline_open_logs))
                }
            }
        }
        item {
            Text(
                stringResource(R.string.algorithm_pipeline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ContextCard(snapshot: AlgorithmPipelineSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.algorithm_pipeline_context),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text("catalog：${snapshot.catalogId ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("选择模式：${snapshot.selectionMode ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("场景：${snapshot.selectedScene ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("revision：${snapshot.revision}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StageRow(index: Int, stage: AlgorithmPipelineStage, isLast: Boolean) {
    val statusColor = when (stage.status) {
        AlgorithmStageStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        AlgorithmStageStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        AlgorithmStageStatus.WARNING -> MaterialTheme.colorScheme.secondary
        AlgorithmStageStatus.FAILED -> MaterialTheme.colorScheme.error
        AlgorithmStageStatus.SKIPPED -> MaterialTheme.colorScheme.outline
        AlgorithmStageStatus.IDLE -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = index.toString(),
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.18f))
                    .padding(top = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
            if (!isLast) {
                Spacer(
                    Modifier
                        .width(2.dp)
                        .height(12.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stage.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stageStatusLabel(stage.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
                stage.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LastFrameCard(snapshot: AlgorithmPipelineSnapshot) {
    val frame = snapshot.lastFrame
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (frame == null) {
                Text(
                    stringResource(R.string.algorithm_pipeline_no_frame),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${frame.scene} · ${"%.0f".format(frame.processingMs)} ms · gen ${frame.generation}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("算法：${frame.algorithmId} v${frame.algorithmVersion}")
                Text("场景置信度：${"%.3f".format(frame.sceneConfidence)}")
                Text(
                    "玩家：${if (frame.hasPlayer) "有" else "无"} · 障碍 ${frame.obstacleCount} · 可动作 ${frame.actionableCount}",
                )
                if (frame.kindHistogram.isNotBlank()) {
                    Text("类别：${frame.kindHistogram}", fontFamily = FontFamily.Monospace)
                }
                Text("内置回退：${frame.usingBuiltinFallback}")
                frame.loadError?.let {
                    Text("加载错误：$it", color = MaterialTheme.colorScheme.error)
                }
                frame.frameError?.let {
                    Text("帧错误：$it", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun stageStatusLabel(status: AlgorithmStageStatus): String = when (status) {
    AlgorithmStageStatus.IDLE -> stringResource(R.string.algorithm_pipeline_status_idle)
    AlgorithmStageStatus.RUNNING -> stringResource(R.string.algorithm_pipeline_status_running)
    AlgorithmStageStatus.SUCCESS -> stringResource(R.string.algorithm_pipeline_status_success)
    AlgorithmStageStatus.WARNING -> stringResource(R.string.algorithm_pipeline_status_warning)
    AlgorithmStageStatus.FAILED -> stringResource(R.string.algorithm_pipeline_status_failed)
    AlgorithmStageStatus.SKIPPED -> stringResource(R.string.algorithm_pipeline_status_skipped)
}
