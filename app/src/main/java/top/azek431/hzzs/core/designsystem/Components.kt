package top.azek431.hzzs.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Design System 2.0 跨 feature 页面积木。
 *
 * 依赖 [LocalHzzsDimensions] / [LocalHzzsStatusColors] / MaterialTheme。
 * feature 应组合这些积木，避免复制间距与卡片样式。
 */

enum class HzzsCalloutTone { INFO, WARNING, ERROR, SUCCESS }

/** 带标题与可选说明的分区；标题带 heading 语义。 */
@Composable
fun HzzsSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val dimensions = LocalHzzsDimensions.current
    Column(verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

/** 一行式状态卡：左标题、右强调值。 */
@Composable
fun StatusCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val dimensions = LocalHzzsDimensions.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensions.cardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 紧凑指标块。 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 指标网格：横排等分；子项应自带 [Modifier.weight]。
 * 调用方在极窄场景可拆成多行 [HzzsMetricGrid]。
 */
@Composable
fun HzzsMetricGrid(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val dimensions = LocalHzzsDimensions.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensions.metricGap),
        content = content,
    )
}

/** 状态胶囊：圆点 + 文案。 */
@Composable
fun StatusChip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
) {
    val container = if (active) {
        activeColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (active) {
        activeColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = container,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (active) activeColor else content, CircleShape),
            )
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/** 可横向滚动的状态条。 */
@Composable
fun HzzsStatusStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 主操作台卡片：图标 + 标题副标题 + 内容。
 * 工具专业风使用实心 surfaceContainerLow，弱化半透明叠层。
 */
@Composable
fun HeroCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimensions = LocalHzzsDimensions.current
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.padding(dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.heroGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/** 通用内容卡。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimensions = LocalHzzsDimensions.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** 页面大标题；标题带 heading 语义供 TalkBack。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 语义提示条：信息 / 警告 / 错误 / 成功。 */
@Composable
fun HzzsCallout(
    text: String,
    tone: HzzsCalloutTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
) {
    val dimensions = LocalHzzsDimensions.current
    val status = LocalHzzsStatusColors.current
    val (container, contentColor) = when (tone) {
        HzzsCalloutTone.INFO -> MaterialTheme.colorScheme.surfaceContainer to
            MaterialTheme.colorScheme.onSurface
        HzzsCalloutTone.WARNING -> status.warning.copy(alpha = 0.14f) to status.warning
        HzzsCalloutTone.ERROR -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        HzzsCalloutTone.SUCCESS -> status.running.copy(alpha = 0.14f) to status.running
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
    ) {
        Row(
            Modifier.padding(dimensions.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = contentColor)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tone == HzzsCalloutTone.ERROR) {
                        contentColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 全宽主按钮。 */
@Composable
fun HzzsPrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LocalHzzsDimensions.current.touchMin),
        shape = MaterialTheme.shapes.medium,
    ) {
        icon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** 全宽次级按钮。 */
@Composable
fun HzzsSecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = true,
    icon: ImageVector? = null,
) {
    val min = LocalHzzsDimensions.current.touchMin
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = min),
            shape = MaterialTheme.shapes.medium,
        ) {
            icon?.let {
                Icon(it, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = min),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(),
        ) {
            icon?.let {
                Icon(it, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(text)
        }
    }
}

/**
 * 统一滚动页骨架：标准 contentPadding、区块间距，并在宽屏限制 [HzzsDimensions.contentMaxWidth]。
 * 调用方在 [content] 中写 LazyList item。
 */
@Composable
fun HzzsScrollPage(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val dimensions = LocalHzzsDimensions.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = dimensions.contentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = dimensions.screenPadding,
                end = dimensions.screenPadding,
                top = dimensions.screenPadding,
                bottom = dimensions.screenPadding + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap),
            content = content,
        )
    }
}
