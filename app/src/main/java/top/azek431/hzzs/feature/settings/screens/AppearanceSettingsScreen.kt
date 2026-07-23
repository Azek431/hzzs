/**
 * 外观与显示设置页。
 *
 * 职责：编辑主题模式/预设/可读性/动效，以及主题包导入导出。
 * 数据流：经 [update] 即时落盘；主题变更立即作用于全局配置流。
 * 边界：主题包导入后同样即时写入主题/悬浮窗字段。
 */
package top.azek431.hzzs.feature.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HzzsColorContrast
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.ThemePreset
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    config: AppConfig,
    update: ((AppConfig) -> AppConfig) -> Unit,
    exportTheme: () -> String,
    importTheme: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dimensions = LocalHzzsDimensions.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取主题")
        }.onSuccess { raw ->
            runCatching { importTheme(raw) }
                .onSuccess { onMessage("主题已导入并生效") }
                .onFailure { onMessage("主题导入失败：${it.message}") }
        }.onFailure { onMessage("主题读取失败：${it.message}") }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                it.write(exportTheme())
            } ?: error("无法写入主题")
        }.onSuccess { onMessage("主题已导出") }
            .onFailure { onMessage("主题导出失败：${it.message}") }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(
                title = "明暗模式",
                description = "支持系统、浅色、深色与 AMOLED 纯黑。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        SettingsRadioCard(
                            title = mode.displayName(),
                            selected = config.theme.mode == mode,
                            onClick = { update { it.copy(theme = it.theme.copy(mode = mode)) } },
                        )
                    }
                }
            }
        }
        item {
            SettingsSectionCard(
                title = "配色",
                description = "动态取色仅在系统支持且选择“动态取色”时生效。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreset.entries.forEach { preset ->
                        SettingsRadioCard(
                            title = preset.displayName(),
                            selected = config.theme.preset == preset,
                            onClick = { update { it.copy(theme = it.theme.copy(preset = preset)) } },
                        )
                    }
                }
                if (config.theme.preset == ThemePreset.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    HexColorField("自定义主题种子色", config.theme.customSeed) { color ->
                        update { it.copy(theme = it.theme.copy(customSeed = color)) }
                    }
                }
            }
        }
        item {
            SettingsSectionCard(title = "可读性与密度") {
                SettingsSwitchRow(
                    title = "支持时使用系统动态取色",
                    checked = config.theme.dynamicColorEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(theme = it.theme.copy(dynamicColorEnabled = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = "增强文字与组件对比度",
                    checked = config.theme.highContrast,
                    onCheckedChange = { value ->
                        update { it.copy(theme = it.theme.copy(highContrast = value)) }
                    },
                )
                LabeledSlider("字体缩放", config.theme.fontScale, 0.8f..1.5f) { value ->
                    update { it.copy(theme = it.theme.copy(fontScale = value)) }
                }
                LabeledSlider("圆角强度", config.theme.cornerScale, 0f..2f) { value ->
                    update { it.copy(theme = it.theme.copy(cornerScale = value)) }
                }
                LabeledSlider("间距密度", config.theme.spacingScale, 0.75f..1.5f) { value ->
                    update { it.copy(theme = it.theme.copy(spacingScale = value)) }
                }
                LabeledSlider("动画强度", config.theme.animationScale, 0f..2f) { value ->
                    update {
                        it.copy(
                            theme = it.theme.copy(
                                animationScale = value,
                                reduceMotion = value == 0f,
                            ),
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "减少动效",
                    subtitle = "开启后动画强度视为 0",
                    checked = config.theme.reduceMotion,
                    onCheckedChange = { value ->
                        update {
                            it.copy(
                                theme = it.theme.copy(
                                    reduceMotion = value,
                                    animationScale = if (value) 0f else it.theme.animationScale.coerceAtLeast(1f),
                                ),
                            )
                        }
                    },
                )
            }
        }
        item {
            SettingsSectionCard(
                title = "主题包",
                description = "导入/导出声明式 .hzzstheme，不含脚本与远程资源。",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("导入")
                    }
                    OutlinedButton(
                        onClick = { exportLauncher.launch("hzzs-theme.hzzstheme") },
                    ) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("导出")
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("HZZS Theme", exportTheme()))
                            onMessage("主题 JSON 已复制")
                        },
                    ) { Text("复制 JSON") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 带当前值的滑条行；供外观/识别等设置页复用。 */
@Composable
internal fun LabeledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String = { "%.2f".format(it) },
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(valueText(value), color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValueChange, valueRange = range)
    }
}

/** 十六进制颜色输入；解析成功即回调，供主题/悬浮窗自定义色使用。显示与白底对比度提示。 */
@Composable
internal fun HexColorField(title: String, color: Int, onColorChange: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf("#%08X".format(color)) }
    val parsed = remember(text) { parseHexColor(text) }
    val contrastRatio = remember(parsed, color) {
        HzzsColorContrast.contrastRatio(parsed ?: color, 0xFFFFFFFF.toInt())
    }
    val contrastOk = contrastRatio >= HzzsColorContrast.AA_NORMAL_TEXT
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            if (next.length <= 9) {
                text = next.uppercase()
                parseHexColor(next)?.let(onColorChange)
            }
        },
        label = { Text(title) },
        supportingText = {
            Column {
                Text(
                    if (parsed == null) {
                        stringResource(R.string.color_hex_hint_bad)
                    } else {
                        stringResource(R.string.color_hex_hint_ok)
                    },
                )
                if (parsed != null) {
                    Text(
                        if (contrastOk) {
                            stringResource(R.string.color_contrast_ok, contrastRatio)
                        } else {
                            stringResource(R.string.color_contrast_warn, contrastRatio)
                        },
                        color = if (contrastOk) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        isError = parsed == null,
        leadingIcon = {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = MaterialTheme.shapes.small,
                color = androidx.compose.ui.graphics.Color(parsed ?: color),
            ) {}
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 解析 #RRGGBB / #AARRGGBB；非法返回 null。 */
internal fun parseHexColor(raw: String): Int? {
    val digits = raw.trim().removePrefix("#")
    if (digits.length !in setOf(6, 8) || digits.any { !it.isDigit() && it.uppercaseChar() !in 'A'..'F' }) {
        return null
    }
    return runCatching {
        val value = digits.toULong(16)
        val argb = if (digits.length == 6) value or 0xFF000000uL else value
        argb.toLong().toInt()
    }.getOrNull()
}
