/**
 * 应用内日志查看器。
 *
 * 职责：浏览 [AppLog] ring buffer，支持级别/标签/关键字筛选、复制与清空。
 * 边界：只读缓冲；不采集系统 logcat；不含 Bearer。
 */
package top.azek431.hzzs.feature.settings.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.AppLogEntry
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.platform.ClipboardHelper

/**
 * 全屏日志查看：轮询 [AppLog.revision] 自动刷新。
 *
 * @param onBack 返回上一页
 * @param onMessage 操作反馈（复制成功等）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var minLevel by remember { mutableStateOf(AppLogLevel.VERBOSE) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var newestFirst by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    var revision by remember { mutableLongStateOf(AppLog.revision()) }
    var entries by remember { mutableStateOf<List<AppLogEntry>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    val timeFormat = remember { AppLogEntry.defaultTimeFormat() }

    // 轮询缓冲变更；开发者关闭后仍可读已有 INFO+ 日志。
    LaunchedEffect(Unit) {
        while (true) {
            val rev = AppLog.revision()
            if (rev != revision) {
                revision = rev
            }
            delay(800)
        }
    }

    LaunchedEffect(revision, minLevel, selectedTag, query, newestFirst) {
        entries = AppLog.query(
            minLevel = minLevel,
            tagEquals = selectedTag,
            query = query,
            newestFirst = newestFirst,
        )
        tags = AppLog.knownTags()
    }

    LaunchedEffect(entries, autoScroll, newestFirst) {
        if (!autoScroll || entries.isEmpty()) return@LaunchedEffect
        // 新在前：顶；旧在前：底
        if (newestFirst) {
            listState.scrollToItem(0)
        } else {
            listState.scrollToItem(entries.lastIndex.coerceAtLeast(0))
        }
    }

    fun copyFiltered() {
        val text = AppLog.formatText(
            minLevel = minLevel,
            tagEquals = selectedTag,
            query = query,
            newestFirst = newestFirst,
        )
        val ok = ClipboardHelper.copyText(context, "HZZS logs", text)
        onMessage(
            if (ok) {
                "已复制 ${entries.size} 条日志"
            } else {
                "复制失败：剪贴板不可用"
            },
        )
    }

    fun shareFiltered() {
        val text = AppLog.formatText(
            minLevel = minLevel,
            tagEquals = selectedTag,
            query = query,
            newestFirst = newestFirst,
        )
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "HZZS logs")
            }
            context.startActivity(Intent.createChooser(send, "分享日志"))
        }.onFailure {
            onMessage("分享失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("运行日志")
                        Text(
                            "缓冲 ${AppLog.size()} 条 · 显示 ${entries.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { copyFiltered() }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = { shareFiltered() }) {
                        Icon(Icons.Rounded.Share, contentDescription = "分享")
                    }
                    IconButton(
                        onClick = {
                            AppLog.clear()
                            AppLog.i("app", "log buffer cleared by user")
                            onMessage("日志已清空")
                        },
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索消息 / 标签") },
                    placeholder = { Text("例如 algorithm、failed、overlay") },
                )
                Text("最低级别", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = minLevel == AppLogLevel.VERBOSE,
                        onClick = { minLevel = AppLogLevel.VERBOSE },
                        label = { Text("全部") },
                    )
                    AppLogLevel.entries.forEach { level ->
                        if (level == AppLogLevel.VERBOSE) return@forEach
                        FilterChip(
                            selected = minLevel == level,
                            onClick = { minLevel = level },
                            label = { Text(level.name) },
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    Text("标签", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                            label = { Text("全部标签") },
                        )
                        tags.forEach { tag ->
                            FilterChip(
                                selected = selectedTag.equals(tag, ignoreCase = true),
                                onClick = {
                                    selectedTag = if (selectedTag.equals(tag, ignoreCase = true)) {
                                        null
                                    } else {
                                        tag
                                    }
                                },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("新在前", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = newestFirst, onCheckedChange = { newestFirst = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动滚动", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                    }
                    TextButton(onClick = {
                        revision = AppLog.revision()
                        entries = AppLog.query(
                            minLevel = minLevel,
                            tagEquals = selectedTag,
                            query = query,
                            newestFirst = newestFirst,
                        )
                        tags = AppLog.knownTags()
                        onMessage("已刷新")
                    }) {
                        Text("刷新")
                    }
                }
            }
            if (entries.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "暂无匹配日志",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "启动分析、切换算法或保存设置后会写入运行日志。开发者关闭时 DEBUG 不入缓冲。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = entries,
                        key = { "${it.epochMs}-${it.level}-${it.tag}-${it.message.hashCode()}" },
                    ) { entry ->
                        LogLine(entry = entry, timeFormat = timeFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(
    entry: AppLogEntry,
    timeFormat: java.text.SimpleDateFormat,
) {
    val color = when (entry.level) {
        AppLogLevel.ERROR -> MaterialTheme.colorScheme.error
        AppLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        AppLogLevel.INFO -> MaterialTheme.colorScheme.primary
        AppLogLevel.DEBUG, AppLogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "${timeFormat.format(java.util.Date(entry.epochMs))}  ${entry.level.name}/${entry.tag}",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.throwableMessage?.let { ex ->
            Text(
                text = "ex: $ex",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
