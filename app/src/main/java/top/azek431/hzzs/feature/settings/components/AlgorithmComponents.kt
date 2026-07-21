/**
 * 算法目录相关 Compose 组件。
 *
 * 职责：算法卡、来源徽标、下载进度；动作经回调交给 SettingsViewModel/目录控制器。
 * 边界：不直接下载或写仓库；签名不可信仅展示限制提示。
 */
package top.azek431.hzzs.feature.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.algorithm.AlgorithmCardStatus
import top.azek431.hzzs.core.algorithm.AlgorithmDownloadTask
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.AlgorithmSignatureState
import top.azek431.hzzs.core.algorithm.formatByteSize
import top.azek431.hzzs.core.algorithm.label
import top.azek431.hzzs.core.model.displayName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单条算法包卡片：状态、摘要、场景标签与下载/选用操作。
 * [manualMode] 决定是否展示“使用此版本”；下载中展示进度与取消。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlgorithmCard(
    info: AlgorithmPackageInfo,
    status: AlgorithmCardStatus,
    download: AlgorithmDownloadTask?,
    manualMode: Boolean,
    onDownload: () -> Unit,
    onUpdate: () -> Unit,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "v${info.versionName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                SettingsStatusChip(status.label(), emphasis = status == AlgorithmCardStatus.CURRENT)
            }
            Text(
                info.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsStatusChip(formatPublished(info.publishedAtEpochMs))
                AlgorithmSourceBadge(info)
                SettingsStatusChip(info.signature.label())
                if (info.sizeBytes > 0) {
                    SettingsStatusChip(formatByteSize(info.sizeBytes))
                }
                info.supportedScenes.forEach { scene ->
                    SettingsStatusChip(scene.displayName())
                }
            }
            if (download != null) {
                AlgorithmProgressCard(
                    title = if (download.verifying) "正在校验…" else "正在下载…",
                    progress = download.progress,
                    onCancel = onCancelDownload,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (status) {
                        AlgorithmCardStatus.CURRENT -> {
                            TextButton(onClick = onDetails) { Text("查看详情") }
                        }
                        AlgorithmCardStatus.PENDING_ACTIVATION -> {
                            Text(
                                "待启用",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(onClick = onDetails) { Text("查看详情") }
                        }
                        AlgorithmCardStatus.INCOMPATIBLE -> {
                            TextButton(onClick = onDetails) { Text("查看详情") }
                        }
                        AlgorithmCardStatus.UPDATABLE -> {
                            Button(onClick = onUpdate) { Text("更新") }
                            TextButton(onClick = onDetails) { Text("查看详情") }
                        }
                        AlgorithmCardStatus.DOWNLOADABLE, AlgorithmCardStatus.LATEST -> {
                            if (!info.isInstalled) {
                                Button(onClick = onDownload) { Text("下载并使用") }
                            } else if (manualMode) {
                                Button(onClick = onSelect) { Text("使用此版本") }
                            }
                            TextButton(onClick = onDetails) { Text("查看详情") }
                        }
                        AlgorithmCardStatus.INSTALLED -> {
                            if (manualMode) {
                                Button(onClick = onSelect) { Text("使用此版本") }
                            }
                            TextButton(onClick = onDetails) { Text("查看详情") }
                        }
                    }
                }
            }
            if (info.signature == AlgorithmSignatureState.UNTRUSTED) {
                Text(
                    "签名不可信，已限制安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 下载来源徽标。 */
@Composable
fun AlgorithmSourceBadge(
    info: AlgorithmPackageInfo,
    modifier: Modifier = Modifier,
) {
    SettingsStatusChip(
        text = info.downloadSource.label(),
        emphasis = false,
        modifier = modifier,
    )
}

/** 算法下载/校验进度与取消。 */
@Composable
fun AlgorithmProgressCard(
    title: String,
    progress: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel) { Text("取消下载") }
        }
    }
}

private fun formatPublished(epochMs: Long): String {
    if (epochMs <= 0L) return "未知时间"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochMs))
    }.getOrDefault("未知时间")
}
