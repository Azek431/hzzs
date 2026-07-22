/**
 * MCP 与开发者设置页。
 *
 * 职责：MCP 开关/权限级/调试帧元数据，以及完整开发者诊断项。
 * 数据流：mcp/developer 进草稿但预览不生效，保存后才应用；完整访问仍不能绕过系统权限框。
 * 边界：不启动 MCP 服务本体；诊断导出不含 Bearer；Native 自检经注入回调。
 */
package top.azek431.hzzs.feature.settings.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.developerLabel
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.platform.ClipboardHelper
import top.azek431.hzzs.data.vision.NativeBenchmarkResult
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.mcp.McpServerState

/**
 * MCP 与开发者相关设置页。
 *
 * MCP 开关/权限为权限型：预览不启停服务，保存后由 MainActivity 同步。
 * 开发者细项需 [DeveloperConfig.enabled]；诊断操作（清帧/基准/导出）为即时任务。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpDeveloperSettingsScreen(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    mcpState: McpServerState,
    debugFrameCount: Int,
    benchmark: Result<NativeBenchmarkResult>?,
    onRefreshDebugFrames: () -> Unit,
    onClearDebugFrames: () -> Unit,
    onRunBenchmark: () -> Unit,
    onBuildDiagnostics: () -> String,
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val developerEnabled = config.developer.enabled
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(
                title = "MCP 服务",
                description = "让 Claude Code 等 AI 读取状态和操作应用。默认每次写操作都需要确认。",
            ) {
                SettingsSwitchRow(
                    title = "启用 MCP",
                    subtitle = "仅监听 loopback，使用随机 Bearer Token。",
                    checked = config.mcp.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(enabled = value)) }
                    },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpPermissionLevel.entries.forEach { level ->
                        SettingsRadioCard(
                            title = level.displayName(),
                            selected = config.mcp.permissionLevel == level,
                            onClick = {
                                update { it.copy(mcp = it.mcp.copy(permissionLevel = level)) }
                            },
                        )
                    }
                }
                Text(
                    "完整访问允许 AI 执行应用内所有功能，但无法绕过 Android 系统权限窗口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSwitchRow(
                    title = "允许 MCP 读取调试帧元数据",
                    checked = config.mcp.allowDebugFrames,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(allowDebugFrames = value)) }
                    },
                )
                if (config.mcp.allowDebugFrames && !developerEnabled) {
                    Text(
                        "该权限只有在开启开发者选项后才生效。图片仍保存在应用私有目录，MCP 默认只返回文件元数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (mcpState.running) {
                        "当前状态：运行中 · 127.0.0.1:${mcpState.port}"
                    } else {
                        "当前状态：未运行（保存并启用后生效）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                mcpState.lastError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (mcpState.running) {
                    TextButton(
                        onClick = {
                            val command =
                                "adb forward tcp:${mcpState.port} tcp:${mcpState.port}\n" +
                                    "Authorization: Bearer ${mcpState.token}"
                            val ok = ClipboardHelper.copyText(context, "HZZS MCP", command)
                            onMessage(
                                if (ok) {
                                    "MCP 连接信息已复制（含 Bearer，勿公开分享）"
                                } else {
                                    "复制失败：剪贴板不可用"
                                },
                            )
                        },
                    ) {
                        Text("复制 MCP 连接信息")
                    }
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "开发者选项",
                description = "高级调试项；权限型字段仅保存后生效。关于页连点版本号也可解锁。",
            ) {
                SettingsSwitchRow(
                    title = "启用开发者选项",
                    checked = developerEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(developer = it.developer.copy(enabled = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "保存调试帧",
                    subtitle = "最多 20 张，约每 5 秒采样；仅私有目录。当前 $debugFrameCount 张",
                    checked = config.developer.saveDebugFrames,
                    enabled = developerEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(developer = it.developer.copy(saveDebugFrames = value)) }
                    },
                )
                if (developerEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefreshDebugFrames) { Text("刷新计数") }
                        TextButton(
                            onClick = onClearDebugFrames,
                            enabled = debugFrameCount > 0,
                        ) { Text("清除调试帧") }
                    }
                }
                SettingsSwitchRow(
                    title = "显示坐标网格",
                    subtitle = "悬浮窗穿透模式下绘制 0.1 比例网格",
                    checked = config.developer.showCoordinateGrid,
                    enabled = developerEnabled,
                    onCheckedChange = { value ->
                        update {
                            it.copy(developer = it.developer.copy(showCoordinateGrid = value))
                        }
                    },
                )
            }
        }
        if (developerEnabled) {
            item {
                SettingsSectionCard(
                    title = "强制截图后端",
                    description = "仅诊断用。选择「跟随设置」恢复普通用户配置。AUTO 仍不升权。",
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (listOf<CaptureBackend?>(null) + CaptureBackend.entries).forEach { backend ->
                            FilterChip(
                                selected = config.developer.forceCaptureBackend == backend,
                                onClick = {
                                    update {
                                        it.copy(
                                            developer = it.developer.copy(
                                                forceCaptureBackend = backend,
                                            ),
                                        )
                                    }
                                },
                                label = {
                                    Text(backend?.developerLabel() ?: "跟随设置")
                                },
                            )
                        }
                    }
                }
            }
            item {
                SettingsSectionCard(
                    title = "日志级别",
                    description = "控制 Logcat 与内存 ring buffer 的最低级别；关闭开发者后 DEBUG 以下不入缓冲。",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLogLevel.entries.forEach { level ->
                            SettingsRadioCard(
                                title = level.displayName(),
                                selected = config.developer.logLevel == level,
                                onClick = {
                                    update {
                                        it.copy(developer = it.developer.copy(logLevel = level))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                SettingsSectionCard(
                    title = "识别帧率上限",
                    description = "配置字段仍保留（1–120），完成驱动取帧下运行时暂不消费，不会主动丢帧。",
                ) {
                    Text(
                        "当前值：${config.developer.frameRateLimit}（未生效）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = config.developer.frameRateLimit.toFloat(),
                        onValueChange = { value ->
                            update {
                                it.copy(
                                    developer = it.developer.copy(frameRateLimit = value.toInt()),
                                )
                            }
                        },
                        valueRange = 1f..120f,
                    )
                }
            }
            item {
                SettingsSectionCard(
                    title = "Native 自检",
                    description = "在合成帧上跑 JNI + C++ 冒烟基准，不读取真实截图。",
                ) {
                    Text(
                        "迭代次数：${config.developer.nativeBenchmarkIterations}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = config.developer.nativeBenchmarkIterations.toFloat(),
                        onValueChange = { value ->
                            update {
                                it.copy(
                                    developer = it.developer.copy(
                                        nativeBenchmarkIterations = value.toInt(),
                                    ),
                                )
                            }
                        },
                        valueRange = 10f..1000f,
                    )
                    Button(onClick = onRunBenchmark, modifier = Modifier.fillMaxWidth()) {
                        Text("运行 JNI + C++ 合成帧自检")
                    }
                    benchmark?.fold(
                        onSuccess = { result ->
                            Text(
                                "${result.iterations} 次 · 平均 %.3f ms · P50 %.3f ms · P95 %.3f ms"
                                    .format(result.meanMs, result.p50Ms, result.p95Ms),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onFailure = { error ->
                            Text(
                                "自检失败：${error.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }
            item {
                SettingsSectionCard(
                    title = "诊断导出",
                    description = "包含版本、机型、配置摘要、算法激活、运行态与最近日志；不含 Bearer 与调试帧像素。",
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val report = onBuildDiagnostics()
                                check(report.isNotBlank()) { "诊断内容为空" }
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, report)
                                    putExtra(Intent.EXTRA_SUBJECT, "HZZS diagnostics")
                                }
                                context.startActivity(Intent.createChooser(send, "导出诊断摘要"))
                            }.onFailure { error ->
                                onMessage("导出失败：${error.message ?: error.javaClass.simpleName}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导出诊断摘要")
                    }
                    TextButton(
                        onClick = {
                            val report = runCatching { onBuildDiagnostics() }.getOrElse { error ->
                                onMessage("生成诊断失败：${error.message ?: error.javaClass.simpleName}")
                                return@TextButton
                            }
                            if (report.isBlank()) {
                                onMessage("诊断内容为空")
                                return@TextButton
                            }
                            val ok = ClipboardHelper.copyText(context, "HZZS diagnostics", report)
                            onMessage(
                                if (ok) {
                                    "诊断摘要已复制（${report.lines().size} 行）"
                                } else {
                                    "复制失败：剪贴板不可用"
                                },
                            )
                        },
                    ) {
                        Text("复制诊断摘要到剪贴板")
                    }
                }
            }
        }
        item {
            SettingsWarningCard(
                title = "安全提示",
                body = "MCP 与开发者选项属于权限型配置，只有保存后才会生效。" +
                    "切勿在不可信网络暴露本机端口；复制连接信息含 Bearer，勿写入日志或公开渠道。",
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
