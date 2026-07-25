/**
 * 应用内日志查看器。
 *
 * 职责：浏览 [AppLog] ring buffer；折叠筛选、暂停实时、单条复制/展开。
 * 边界：只读缓冲；不采集系统 logcat；不含 Bearer。
 * 布局：内容页（不自建 Scaffold），由外层 Settings/About 提供顶栏。
 */
package top.azek431.hzzs.feature.settings.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.logging.AppLogEntry
import top.azek431.hzzs.core.logging.AppLogLevelCounts
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.platform.ClipboardHelper
import top.azek431.hzzs.feature.settings.components.SettingsEmptyState
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow

private const val TOP_TAG_CHIPS = 12
private const val COLLAPSED_MSG_LINES = 3
private const val POLL_MS = 1_000L

/**
 * 全屏日志内容（无自建 Scaffold）。
 *
 * @param onBack 可选返回；外层已有返回时可忽略
 * @param initialTag 初始标签筛选（如从算法流程页跳入 "algorithm"）
 * @param initialQuery 初始搜索
 * @param onMessage 操作反馈
 * @param showToolbar 是否在内容顶显示操作条（设置外层无 actions 时 true）
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    initialTag: String? = null,
    initialQuery: String = "",
    showToolbar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dimensions = LocalHzzsDimensions.current
    var minLevel by remember { mutableStateOf(AppLogLevel.VERBOSE) }
    var selectedTag by remember { mutableStateOf(initialTag) }
    var query by remember { mutableStateOf(initialQuery) }
    var newestFirst by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(true) }
    var filtersExpanded by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var revision by remember { mutableLongStateOf(AppLog.revision()) }
    var pendingRevision by remember { mutableLongStateOf(AppLog.revision()) }
    var entries by remember { mutableStateOf<List<AppLogEntry>>(emptyList()) }
    var tagCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var levelCounts by remember { mutableStateOf(AppLogLevelCounts()) }
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val listState = rememberLazyListState()
    val timeFormat = remember { AppLogEntry.defaultTimeFormat() }

    val copiedN = stringResource(R.string.log_viewer_copied)
    val copyOne = stringResource(R.string.log_viewer_copy_one)
    val copyFailed = stringResource(R.string.dev_copy_failed)
    val clearedMsg = stringResource(R.string.log_viewer_cleared)
    val refreshedMsg = stringResource(R.string.log_viewer_refreshed)
    val shareFailedTemplate = stringResource(R.string.log_viewer_share_failed)
    val shareChooser = stringResource(R.string.log_viewer_share_chooser)

    fun reload() {
        revision = AppLog.revision()
        pendingRevision = revision
        entries = AppLog.query(
            minLevel = minLevel,
            tagEquals = selectedTag,
            query = query,
            newestFirst = newestFirst,
            limit = AppLog.capacity(),
        )
        tagCounts = AppLog.tagCounts()
        levelCounts = AppLog.levelCounts()
    }

    // 实时：revision 变则刷新；暂停：只更新 pendingRevision 提示
    LaunchedEffect(live) {
        while (true) {
            val rev = AppLog.revision()
            if (live) {
                if (rev != revision) {
                    revision = rev
                    pendingRevision = rev
                    entries = AppLog.query(
                        minLevel = minLevel,
                        tagEquals = selectedTag,
                        query = query,
                        newestFirst = newestFirst,
                        limit = AppLog.capacity(),
                    )
                    tagCounts = AppLog.tagCounts()
                    levelCounts = AppLog.levelCounts()
                }
            } else if (rev != pendingRevision) {
                pendingRevision = rev
            }
            delay(POLL_MS)
        }
    }

    LaunchedEffect(minLevel, selectedTag, query, newestFirst) {
        entries = AppLog.query(
            minLevel = minLevel,
            tagEquals = selectedTag,
            query = query,
            newestFirst = newestFirst,
            limit = AppLog.capacity(),
        )
        tagCounts = AppLog.tagCounts()
        levelCounts = AppLog.levelCounts()
    }

    LaunchedEffect(entries, autoScroll, newestFirst, live) {
        if (!autoScroll || !live || entries.isEmpty()) return@LaunchedEffect
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
            limit = AppLog.capacity(),
        )
        val ok = ClipboardHelper.copyText(context, "HZZS logs", text)
        onMessage(if (ok) copiedN.format(entries.size) else copyFailed)
    }

    fun shareFiltered() {
        val text = AppLog.formatText(
            minLevel = minLevel,
            tagEquals = selectedTag,
            query = query,
            newestFirst = newestFirst,
            limit = AppLog.capacity(),
        )
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "HZZS logs")
            }
            context.startActivity(Intent.createChooser(send, shareChooser))
        }.onFailure {
            onMessage(shareFailedTemplate.format(it.message ?: it.javaClass.simpleName))
        }
    }

    fun copyOneEntry(entry: AppLogEntry) {
        val ok = ClipboardHelper.copyText(context, "HZZS log line", entry.formatLine(timeFormat))
        onMessage(if (ok) copyOne else copyFailed)
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.log_viewer_clear_confirm_title)) },
            text = { Text(stringResource(R.string.log_viewer_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        AppLog.clear()
                        AppLog.i("app", "log buffer cleared by user")
                        reload()
                        onMessage(clearedMsg)
                    },
                ) {
                    Text(stringResource(R.string.log_viewer_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val pendingNew = if (!live && pendingRevision != revision) {
        (pendingRevision - revision).coerceAtLeast(1L).toInt()
    } else {
        0
    }

    Column(modifier.fillMaxSize()) {
        if (showToolbar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensions.screenPadding, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.log_viewer_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (live) {
                            stringResource(
                                R.string.log_viewer_subtitle,
                                AppLog.size(),
                                entries.size,
                                AppLog.capacity(),
                            )
                        } else {
                            stringResource(
                                R.string.log_viewer_subtitle_paused,
                                AppLog.size(),
                                entries.size,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.log_viewer_level_summary,
                            levelCounts.error,
                            levelCounts.warn,
                            levelCounts.info,
                            levelCounts.debug + levelCounts.verbose,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { live = !live }) {
                    Icon(
                        if (live) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.log_viewer_live),
                    )
                }
                IconButton(onClick = {
                    reload()
                    onMessage(refreshedMsg)
                }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.log_viewer_refresh))
                }
                IconButton(onClick = { copyFiltered() }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.log_viewer_copy))
                }
                IconButton(onClick = { shareFiltered() }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.log_viewer_share))
                }
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.log_viewer_clear))
                }
            }
        }

        if (pendingNew > 0) {
            TextButton(
                onClick = {
                    reload()
                    onMessage(refreshedMsg)
                },
                modifier = Modifier.padding(horizontal = dimensions.screenPadding),
            ) {
                Text(stringResource(R.string.log_viewer_pending_new, pendingNew))
            }
        }

        // 紧凑搜索 + 可折叠筛选
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.screenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.log_viewer_search_label)) },
                placeholder = { Text(stringResource(R.string.log_viewer_search_placeholder)) },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { filtersExpanded = !filtersExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.log_viewer_filters_toggle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (filtersExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = filtersExpanded) {
                SettingsSectionCard(
                    title = stringResource(R.string.log_viewer_filter_section),
                    description = stringResource(R.string.log_viewer_filter_collapsed_hint),
                ) {
                    Text(
                        stringResource(R.string.log_viewer_min_level),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = minLevel == AppLogLevel.VERBOSE,
                            onClick = { minLevel = AppLogLevel.VERBOSE },
                            label = { Text(stringResource(R.string.log_viewer_level_all)) },
                        )
                        AppLogLevel.entries.forEach { level ->
                            if (level == AppLogLevel.VERBOSE) return@forEach
                            FilterChip(
                                selected = minLevel == level,
                                onClick = { minLevel = level },
                                label = {
                                    val n = levelCounts.of(level)
                                    Text(if (n > 0) "${level.name}($n)" else level.name)
                                },
                            )
                        }
                    }
                    if (tagCounts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.log_viewer_tags),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedTag == null,
                                onClick = { selectedTag = null },
                                label = { Text(stringResource(R.string.log_viewer_tag_all)) },
                            )
                            tagCounts.take(TOP_TAG_CHIPS).forEach { (tag, count) ->
                                FilterChip(
                                    selected = selectedTag.equals(tag, ignoreCase = true),
                                    onClick = {
                                        selectedTag = if (selectedTag.equals(tag, ignoreCase = true)) {
                                            null
                                        } else {
                                            tag
                                        }
                                    },
                                    label = { Text("$tag($count)") },
                                )
                            }
                        }
                        if (tagCounts.size > TOP_TAG_CHIPS) {
                            Text(
                                stringResource(R.string.log_viewer_tag_more, tagCounts.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SettingsSwitchRow(
                        title = stringResource(R.string.log_viewer_newest_first),
                        checked = newestFirst,
                        onCheckedChange = { newestFirst = it },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.log_viewer_auto_scroll),
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.log_viewer_live),
                        subtitle = stringResource(R.string.log_viewer_live_subtitle),
                        checked = live,
                        onCheckedChange = { live = it },
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(dimensions.screenPadding),
                verticalArrangement = Arrangement.Center,
            ) {
                SettingsEmptyState(
                    title = stringResource(R.string.log_viewer_empty_title),
                    body = stringResource(R.string.log_viewer_empty_body),
                    actionLabel = stringResource(R.string.log_viewer_filters_toggle),
                    onAction = { filtersExpanded = true },
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
                items(
                    items = entries,
                    key = { it.id },
                ) { entry ->
                    val expanded = entry.id in expandedIds
                    LogLine(
                        entry = entry,
                        timeFormat = timeFormat,
                        expanded = expanded,
                        onToggleExpand = {
                            expandedIds = if (expanded) {
                                expandedIds - entry.id
                            } else {
                                expandedIds + entry.id
                            }
                        },
                        onCopy = { copyOneEntry(entry) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LogLine(
    entry: AppLogEntry,
    timeFormat: java.text.SimpleDateFormat,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
) {
    val color = when (entry.level) {
        AppLogLevel.ERROR -> MaterialTheme.colorScheme.error
        AppLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        AppLogLevel.INFO -> MaterialTheme.colorScheme.primary
        AppLogLevel.DEBUG, AppLogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val longMessage = entry.message.length > 160 || entry.message.lines().size > COLLAPSED_MSG_LINES
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${timeFormat.format(java.util.Date(entry.epochMs))}  ${entry.level.name}/${entry.tag}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy, modifier = Modifier.padding(0.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.log_viewer_copy_line),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded || !longMessage) Int.MAX_VALUE else COLLAPSED_MSG_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        entry.throwableMessage?.let { ex ->
            Text(
                text = "ex: $ex",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (longMessage) {
            Text(
                text = stringResource(
                    if (expanded) R.string.log_viewer_collapse else R.string.log_viewer_expand,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
