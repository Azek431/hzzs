package top.azek431.hzzs.core.designsystem

import android.app.Activity
import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.ThemeConfig
import top.azek431.hzzs.core.model.ThemePreset
import kotlin.math.max
import kotlin.math.min

/**
 * HZZS Design System 2.0：工具专业风主题与令牌。
 *
 * - 主题包只存语义控制（mode / preset / 缩放），不存组件级裸色
 * - 页面优先读 [LocalHzzsDimensions] / [LocalHzzsStatusColors] / [LocalHzzsMotion]
 * - 气质：冷静中性表面 + 品牌 accent（种子色），适合本地分析工具
 * - 保留 Dynamic / 多预设 / 自定义种子（本轮不收紧主题能力）
 */

/** 窗口宽度断点（dp，基于当前窗口而非物理设备）。 */
object HzzsBreakpoints {
    /** 一级导航：≥ 此宽度使用 NavigationRail。 */
    val NavigationExpanded: Dp = 720.dp
    /** 设置双栏：≥ 此宽度左侧常驻目录。 */
    val SettingsTwoPane: Dp = 840.dp
    /** 紧凑手机参考宽度（Preview / 布局下限参考）。 */
    val Compact: Dp = 320.dp
}

/** 语义间距与布局边界；随 [ThemeConfig.spacingScale] 缩放间距类字段。 */
@Immutable
data class HzzsDimensions(
    val compactGap: Dp,
    val sectionGap: Dp,
    val cardPadding: Dp,
    val screenPadding: Dp,
    val heroGap: Dp,
    val metricGap: Dp,
    val bottomBarClearance: Dp,
    val touchMin: Dp,
    /** 宽屏内容最大宽度，避免把手机布局机械拉伸。 */
    val contentMaxWidth: Dp,
    val navigationExpandedBreakpoint: Dp = HzzsBreakpoints.NavigationExpanded,
    val settingsTwoPaneBreakpoint: Dp = HzzsBreakpoints.SettingsTwoPane,
)

val LocalHzzsDimensions = staticCompositionLocalOf {
    HzzsDimensions(
        compactGap = 8.dp,
        sectionGap = 12.dp,
        cardPadding = 16.dp,
        screenPadding = 20.dp,
        heroGap = 14.dp,
        metricGap = 10.dp,
        bottomBarClearance = 88.dp,
        touchMin = 48.dp,
        contentMaxWidth = 840.dp,
    )
}

/**
 * 运行态/安全态语义色（从 ColorScheme 派生，随主题变化）。
 * 不写入主题包，避免与安全门禁字段混淆。
 */
@Immutable
data class HzzsStatusColors(
    val running: Color,
    val idle: Color,
    val armed: Color,
    val locked: Color,
    val warning: Color,
    val onRunning: Color,
    val onIdle: Color,
    val onArmed: Color,
    val onLocked: Color,
    val onWarning: Color,
)

val LocalHzzsStatusColors = staticCompositionLocalOf {
    HzzsStatusColors(
        running = Color(0xFF1D7A58),
        idle = Color(0xFF6B7280),
        armed = Color(0xFFB45309),
        locked = Color(0xFF64748B),
        warning = Color(0xFFB45309),
        onRunning = Color.White,
        onIdle = Color.White,
        onArmed = Color.White,
        onLocked = Color.White,
        onWarning = Color.White,
    )
}

private val presetSeeds = mapOf(
    ThemePreset.FIRE_ORANGE to Color(0xFFFF6B2C),
    ThemePreset.CORAL to Color(0xFFC73650),
    ThemePreset.BAMBOO to Color(0xFF1D7A58),
    ThemePreset.OCEAN to Color(0xFF006A78),
    ThemePreset.INDIGO to Color(0xFF4D5BB7),
    ThemePreset.LAVENDER to Color(0xFF7957A8),
    ThemePreset.BLACK_GOLD to Color(0xFFC99B2E),
    ThemePreset.HIGH_CONTRAST to Color(0xFF0066FF),
)

/**
 * 根据 [ThemeConfig] 生成主题并提供间距/状态色 Local。
 *
 * 选择顺序：系统动态取色 → 高对比预设 → 种子色（含 AMOLED）。
 */
@Composable
fun HzzsTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (config.mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
    }
    val dynamic = config.dynamicColorEnabled &&
        config.mode != AppThemeMode.AMOLED &&
        config.preset == ThemePreset.DYNAMIC &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        config.preset == ThemePreset.HIGH_CONTRAST -> highContrastScheme(dark)
        else -> seedScheme(
            seed = if (config.preset == ThemePreset.CUSTOM) {
                Color(config.customSeed)
            } else {
                presetSeeds[config.preset] ?: presetSeeds.getValue(ThemePreset.FIRE_ORANGE)
            },
            dark = dark,
            amoled = config.mode == AppThemeMode.AMOLED,
            highContrast = config.highContrast,
        )
    }

    val corner = config.cornerScale.coerceIn(0f, 2f)
    val typography = scaledTypography(config.fontScale)
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((4f * corner).dp),
        small = RoundedCornerShape((8f * corner).dp),
        medium = RoundedCornerShape((12f * corner).dp),
        large = RoundedCornerShape((18f * corner).dp),
        extraLarge = RoundedCornerShape((24f * corner).dp),
    )

    val activity = context as? Activity
    SideEffect {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = colors.surface.luminance() > 0.55f
            controller.isAppearanceLightNavigationBars = colors.surface.luminance() > 0.55f
        }
    }

    val spacing = config.spacingScale.coerceIn(0.75f, 1.5f)
    val dimensions = HzzsDimensions(
        compactGap = (8f * spacing).dp,
        sectionGap = (12f * spacing).dp,
        cardPadding = (16f * spacing).dp,
        screenPadding = (20f * spacing).dp,
        heroGap = (14f * spacing).dp,
        metricGap = (10f * spacing).dp,
        bottomBarClearance = (88f * spacing).dp,
        touchMin = 48.dp,
        contentMaxWidth = 840.dp,
    )
    val statusColors = statusColorsFrom(colors, dark)
    val systemAnimatorScale = remember(context) {
        readSystemAnimatorDurationScale(context.contentResolver)
    }
    val motion = remember(config.animationScale, config.reduceMotion, systemAnimatorScale) {
        HzzsMotion.resolve(
            animationScale = config.animationScale,
            reduceMotion = config.reduceMotion,
            systemAnimatorDurationScale = systemAnimatorScale,
        )
    }

    CompositionLocalProvider(
        LocalHzzsDimensions provides dimensions,
        LocalHzzsStatusColors provides statusColors,
        LocalHzzsMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

/** 读取系统动画时长倍率；失败时按 1，避免影响业务逻辑。 */
internal fun readSystemAnimatorDurationScale(resolver: ContentResolver): Float {
    return runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f).coerceAtLeast(0f)
}

private fun statusColorsFrom(scheme: ColorScheme, dark: Boolean): HzzsStatusColors {
    val running = if (dark) Color(0xFF34D399) else Color(0xFF047857)
    val idle = scheme.onSurfaceVariant
    val armed = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
    val locked = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val warning = scheme.error
    return HzzsStatusColors(
        running = running,
        idle = idle,
        armed = armed,
        locked = locked,
        warning = warning,
        onRunning = if (dark) Color(0xFF052E1C) else Color.White,
        onIdle = scheme.surface,
        onArmed = if (dark) Color(0xFF1C1003) else Color.White,
        onLocked = scheme.surface,
        onWarning = scheme.onError,
    )
}

private fun scaledTypography(scale: Float): Typography {
    val safe = scale.coerceIn(0.8f, 1.5f)
    val base = Typography()
    fun TextStyle.scaled() = copy(fontSize = fontSize * safe, lineHeight = lineHeight * safe)
    return base.copy(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

/**
 * 工具专业风种子方案：中性灰阶表面 + 品牌 primary。
 * 浅色背景偏冷灰白，避免过度暖色粉感；深色/AMOLED 保持真黑可选。
 */
private fun seedScheme(seed: Color, dark: Boolean, amoled: Boolean, highContrast: Boolean): ColorScheme {
    val primary = if (dark) seed.lighten(0.14f) else seed.darken(0.06f)
    val secondary = seed.rotateChannels().let { if (dark) it.lighten(0.16f) else it.darken(0.10f) }
    val tertiary = seed.rotateChannels().rotateChannels().let {
        if (dark) it.lighten(0.12f) else it.darken(0.08f)
    }
    val surface = when {
        amoled -> Color.Black
        dark -> Color(0xFF101216)
        else -> Color(0xFFF6F7F9)
    }
    val surfaceContainer = when {
        amoled -> Color(0xFF0A0A0A)
        dark -> Color(0xFF181A20)
        else -> Color(0xFFEEF0F3)
    }
    val surfaceContainerLow = when {
        amoled -> Color(0xFF080808)
        dark -> Color(0xFF14161B)
        else -> Color(0xFFFFFFFF)
    }
    val surfaceContainerHigh = when {
        amoled -> Color(0xFF121212)
        dark -> Color(0xFF22252D)
        else -> Color(0xFFE4E7EC)
    }
    val onSurface = if (dark) Color(0xFFE8EAED) else Color(0xFF12151A)
    val onSurfaceVariant = if (dark) Color(0xFFA8B0BB) else Color(0xFF5B6570)
    val outline = if (highContrast) {
        onSurface.copy(alpha = 0.88f)
    } else {
        onSurface.copy(alpha = if (dark) 0.28f else 0.18f)
    }
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF101010),
            primaryContainer = primary.copy(alpha = 0.22f),
            onPrimaryContainer = primary.lighten(0.35f),
            secondary = secondary,
            tertiary = tertiary,
            background = surface,
            surface = surface,
            surfaceVariant = surfaceContainerHigh,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHigh.lighten(0.04f),
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.45f),
            error = Color(0xFFFF8A80),
            onError = Color(0xFF2A0505),
            errorContainer = Color(0xFF4A1515),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.12f),
            onPrimaryContainer = primary.darken(0.25f),
            secondary = secondary,
            tertiary = tertiary,
            background = surface,
            surface = surface,
            surfaceVariant = surfaceContainerHigh,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHigh.darken(0.04f),
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.55f),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
        )
    }
}

private fun highContrastScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFF70A7FF),
        onPrimary = Color.Black,
        background = Color.Black,
        surface = Color.Black,
        surfaceContainer = Color(0xFF121212),
        surfaceContainerLow = Color(0xFF0A0A0A),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFE0E0E0),
        outline = Color.White,
        error = Color(0xFFFF6B6B),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF003F9E),
        onPrimary = Color.White,
        background = Color.White,
        surface = Color.White,
        surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerLow = Color.White,
        onSurface = Color.Black,
        onSurfaceVariant = Color(0xFF222222),
        outline = Color.Black,
        error = Color(0xFF9E0000),
    )
}

private fun Color.lighten(amount: Float): Color = copy(
    red = min(1f, red + amount),
    green = min(1f, green + amount),
    blue = min(1f, blue + amount),
)

private fun Color.darken(amount: Float): Color = copy(
    red = max(0f, red - amount),
    green = max(0f, green - amount),
    blue = max(0f, blue - amount),
)

private fun Color.rotateChannels(): Color = Color(green, blue, red, alpha)
