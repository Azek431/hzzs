/**
 * 开发者设置页。
 *
 * 职责：调试帧管理、日志级别、强制截图后端、Native Benchmark、坐标网格等高级调试项。
 * 安全：需 [DeveloperConfig.enabled] 开启；普通用户通过关于页连点版本号解锁。
 * 边界：不启动 MCP 服务本体；诊断导出不含 Bearer。
 */
package top.azek431.hzzs.feature.settings.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppLogLevel
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.core.model.developerLabel
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.data.vision.NativeBenchmarkResult
import top.azek431.hzzs.feature.settings.components.SettingsNavigationRow
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.platform.compat.isSupportedOnThisDevice
import top.azek431.hzzs.platform.compat.resolveEffectiveCaptureBackend

/**
 * 开发者选项设置页。
 *
 * 各项功能需在 [DeveloperConfig.enabled] 打开后才可使用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperSettingsScreen(
    developerEnabled: Boolean,
    config: top.azek431.hzzs.core.model.AppConfig,
    update: ((top.azek431.hzzs.core.model.AppConfig) -> top.azek431.hzzs.core.model.AppConfig) -> Unit,
    debugFrameCount: Int,
    benchmark: Result<NativeBenchmarkResult>?,
    onRefreshDebugFrames: () -> Unit = {},
    onClearDebugFrames: () -> Unit = {},
    onRunBenchmark: () -> Unit = {},
    onBuildDiagnostics: () -> String = { "" },
    onOpenLogViewer: () -> Unit = {},
    onOpenAlgorithmPipeline: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    // Pre-compute strings for use inside non-@Composable click handlers
    val exportChooserTitle = stringResource(R.string.dev_export_chooser)
    val diagnosticEmptyMsg = stringResource(R.string.dev_diagnostic_empty)
    val copyFailedMsg = stringResource(R.string.dev_copy_failed)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 1. 开发者开关 ──
        item {
            SettingsSectionCard(
                title = stringResource(R.string.dev_section_enable_title),
                description = stringResource(R.string.dev_section_enable_desc),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.dev_enable_switch),
                    checked = developerEnabled,
                    enabled = false,
                    onCheckedChange = {},
                )
                Text(
                    stringResource(R.string.dev_enable_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (developerEnabled) {
            // ── 2. 调试帧管理 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_debug_frames_title),
                    description = stringResource(R.string.dev_debug_frames_desc, debugFrameCount),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.dev_save_debug_frames),
                        checked = config.developer.saveDebugFrames,
                        onCheckedChange = { value ->
                            update { it.copy(developer = it.developer.copy(saveDebugFrames = value)) }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefreshDebugFrames) {
                            Text(stringResource(R.string.dev_refresh_count))
                        }
                        TextButton(
                            onClick = onClearDebugFrames,
                            enabled = debugFrameCount > 0,
                        ) {
                            Text(stringResource(R.string.dev_clear_debug_frames))
                        }
                    }
                }
            }

            // ── 3. 坐标网格与导航 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_tools_title),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.dev_show_coordinate_grid),
                        subtitle = stringResource(R.string.dev_show_coordinate_grid_subtitle),
                        checked = config.developer.showCoordinateGrid,
                        onCheckedChange = { value ->
                            update {
                                it.copy(developer = it.developer.copy(showCoordinateGrid = value))
                            }
                        },
                    )
                    SettingsNavigationRow(
                        title = stringResource(R.string.dev_open_log_viewer),
                        subtitle = stringResource(R.string.dev_open_log_viewer_subtitle),
                        onClick = onOpenLogViewer,
                    )
                    SettingsNavigationRow(
                        title = stringResource(R.string.dev_open_algorithm_pipeline),
                        subtitle = stringResource(R.string.dev_open_algorithm_pipeline_subtitle),
                        onClick = onOpenAlgorithmPipeline,
                    )
                }
            }

            // ── 4. 强制截图后端 ──
            item {
                val captureResolution = resolveEffectiveCaptureBackend(
                    captureBackend = config.captureBackend,
                    developerEnabled = true,
                    forceCaptureBackend = config.developer.forceCaptureBackend,
                )
                SettingsSectionCard(
                    title = stringResource(R.string.dev_force_capture_backend_title),
                    description = stringResource(R.string.dev_force_capture_backend_desc),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (listOf<CaptureBackend?>(null) + CaptureBackend.entries).forEach { backend ->
                            val supported = backend == null || backend.isSupportedOnThisDevice()
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
                                    val base = backend?.developerLabel() ?: "跟随设置"
                                    Text(
                                        if (backend != null && !supported) {
                                            "$base（本机不可用）"
                                        } else {
                                            base
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (captureResolution.fellBack) {
                        Text(
                            stringResource(
                                R.string.dev_force_capture_fallback_reason,
                                captureResolution.effective.developerLabel(),
                            ) + "：" + (captureResolution.fallbackReason ?: stringResource(R.string.dev_force_capture_fell_back)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── 5. 日志级别 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_log_level_title),
                    description = stringResource(R.string.dev_log_level_desc),
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

            // ── 6. 识别帧率上限 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_frame_rate_title),
                    description = stringResource(R.string.dev_frame_rate_desc),
                ) {
                    Text(
                        stringResource(R.string.dev_frame_rate_current, config.developer.frameRateLimit),
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

            // ── 7. Native 自检 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_native_benchmark_title),
                    description = stringResource(R.string.dev_native_benchmark_desc),
                ) {
                    Text(
                        stringResource(R.string.dev_native_benchmark_iterations, config.developer.nativeBenchmarkIterations),
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
                        Text(stringResource(R.string.dev_run_benchmark))
                    }
                    benchmark?.fold(
                        onSuccess = { result ->
                            Text(
                                "%d 次 · 平均 %.3f ms · P50 %.3f ms · P95 %.3f ms"
                                    .format(result.iterations, result.meanMs, result.p50Ms, result.p95Ms),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onFailure = { error ->
                            Text(
                                stringResource(R.string.dev_benchmark_failed, error.message ?: error.javaClass.simpleName),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }

            // ── 8. 诊断导出 ──
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.dev_diagnostics_title),
                    description = stringResource(R.string.dev_diagnostics_desc),
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val report = onBuildDiagnostics()
                                check(report.isNotBlank()) { "诊断内容为空" }
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, report)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "HZZS diagnostics")
                                }
                                context.startActivity(android.content.Intent.createChooser(send, exportChooserTitle))
                            }.onFailure { error ->
                                onMessage("导出失败：${error.message ?: "unknown"}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.dev_export_diagnostics))
                    }
                    TextButton(
                        onClick = {
                            val report = runCatching { onBuildDiagnostics() }.getOrElse { error ->
                                onMessage("生成诊断失败：${error.message ?: error.javaClass.simpleName}")
                                return@TextButton
                            }
                            if (report.isBlank()) {
                                onMessage(diagnosticEmptyMsg)
                                return@TextButton
                            }
                            val ok = top.azek431.hzzs.core.platform.ClipboardHelper.copyText(
                                context,
                                "HZZS diagnostics",
                                report,
                            )
                            onMessage(
                                if (ok) {
                                    "诊断摘要已复制（${report.lines().size} 行）"
                                } else {
                                    copyFailedMsg
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.dev_copy_to_clipboard))
                    }
                }
            }
        }

        // ── 9. 安全提示 ──
        item {
            SettingsWarningCard(
                title = stringResource(R.string.dev_security_title),
                body = stringResource(R.string.dev_security_body),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
