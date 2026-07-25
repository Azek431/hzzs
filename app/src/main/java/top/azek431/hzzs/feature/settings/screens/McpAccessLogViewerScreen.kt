/**
 * MCP 访问日志全屏查看器。
 *
 * 职责：浏览 [McpAccessLog] ring；搜索、复制、暂停实时、单条复制。
 * 边界：永不展示 Token / 请求体；只读 ring。
 */
package top.azek431.hzzs.feature.settings.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.platform.ClipboardHelper
import top.azek431.hzzs.feature.settings.components.SettingsEmptyState
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.mcp.McpAccessLog
import top.azek431.hzzs.mcp.McpAccessLogEntry

@Composable
fun McpAccessLogViewerScreen(
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dimensions = LocalHzzsDimensions.current
    var query by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(true) }
    var revision by remember { mutableLongStateOf(McpAccessLog.revision()) }
    var entries by remember { mutableStateOf<List<McpAccessLogEntry>>(emptyList()) }
    val listState = rememberLazyListState()
    val timeFormat = remember { McpAccessLogEntry.defaultTimeFormat() }

    val copiedN = stringResource(R.string.mcp_access_log_copied)
    val copyOne = stringResource(R.string.mcp_access_log_copy_one)
    val copyFailed = stringResource(R.string.dev_copy_failed)
    val clearedMsg = stringResource(R.string.mcp_access_log_cleared)
    val refreshedMsg = stringResource(R.string.log_viewer_refreshed)
    val shareChooser = stringResource(R.string.mcp_access_log_share_chooser)
    val shareFailedTemplate = stringResource(R.string.log_viewer_share_failed)

    fun reload() {
        revision = McpAccessLog.revision()
        entries = McpAccessLog.query(query = query, newestFirst = true)
    }

    LaunchedEffect(live, query) {
        while (true) {
            val rev = McpAccessLog.revision()
            if (live && rev != revision) {
                reload()
            } else if (!live) {
                // still allow query-driven reload via other effect
            } else if (rev == revision) {
                // no-op
            }
            // query 变化时也刷新
            entries = McpAccessLog.query(query = query, newestFirst = true)
            revision = rev
            delay(1_200)
        }
    }

    fun copyAll() {
        val text = if (query.isBlank()) {
            McpAccessLog.formatText(newestFirst = true)
        } else {
            entries.joinToString("\n") { it.formatLine(timeFormat) }
        }
        val ok = ClipboardHelper.copyText(context, "HZZS mcp access log", text)
        onMessage(if (ok) copiedN.format(entries.size) else copyFailed)
    }

    fun shareAll() {
        val text = entries.joinToString("\n") { it.formatLine(timeFormat) }
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "HZZS mcp access log")
            }
            context.startActivity(Intent.createChooser(send, shareChooser))
        }.onFailure {
            onMessage(shareFailedTemplate.format(it.message ?: it.javaClass.simpleName))
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.screenPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.mcp_access_log_viewer_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        R.string.mcp_access_log_viewer_subtitle,
                        McpAccessLog.size(),
                        entries.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { live = !live }) {
                Icon(
                    if (live) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.mcp_access_log_live),
                )
            }
            IconButton(onClick = {
                reload()
                onMessage(refreshedMsg)
            }) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.log_viewer_refresh))
            }
            IconButton(onClick = { copyAll() }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
            }
            IconButton(onClick = { shareAll() }) {
                Icon(Icons.Rounded.Share, contentDescription = null)
            }
            IconButton(
                onClick = {
                    McpAccessLog.clear()
                    reload()
                    onMessage(clearedMsg)
                },
                enabled = McpAccessLog.size() > 0,
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.mcp_access_log_clear))
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.screenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.mcp_access_log_search_label)) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mcp_access_log_live),
                checked = live,
                onCheckedChange = { live = it },
            )
        }

        if (entries.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(dimensions.screenPadding),
                verticalArrangement = Arrangement.Center,
            ) {
                SettingsEmptyState(
                    title = stringResource(R.string.mcp_access_log_section_title),
                    body = stringResource(R.string.mcp_access_log_empty_viewer),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(
                    horizontal = dimensions.screenPadding,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AccessLogLine(
                        entry = entry,
                        timeFormat = timeFormat,
                        onCopy = {
                            val ok = ClipboardHelper.copyText(
                                context,
                                "HZZS mcp access line",
                                entry.formatLine(timeFormat),
                            )
                            onMessage(if (ok) copyOne else copyFailed)
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun AccessLogLine(
    entry: McpAccessLogEntry,
    timeFormat: java.text.SimpleDateFormat,
    onCopy: () -> Unit,
) {
    val statusColor = when {
        entry.httpStatus >= 500 || (entry.rpcErrorCode != null && entry.rpcErrorCode != 0) ->
            MaterialTheme.colorScheme.error
        entry.httpStatus >= 400 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.formatLine(timeFormat),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = statusColor,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.log_viewer_copy_line),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
