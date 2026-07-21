package top.azek431.hzzs.core.designsystem

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
 * HZZS Material 3 主题与间距令牌。
 *
 * 主题包只存语义控制（mode / preset / 缩放），不存组件级裸色，
 * 以便 Material 组件演进时导入主题仍稳定。
 */

/** 语义间距令牌；随用户 [ThemeConfig.spacingScale] 缩放。 */
data class HzzsDimensions(
    val compactGap: Dp,
    val sectionGap: Dp,
    val cardPadding: Dp,
    val screenPadding: Dp,
)

/** CompositionLocal：页面与组件读取统一间距。 */
val LocalHzzsDimensions = staticCompositionLocalOf {
    HzzsDimensions(
        compactGap = 8.dp,
        sectionGap = 10.dp,
        cardPadding = 16.dp,
        screenPadding = 16.dp,
    )
}

/** 内置调色板种子色（非 DYNAMIC / CUSTOM 时使用）。 */
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
 * 根据 [ThemeConfig] 生成应用主题并包裹 [content]。
 *
 * 选择顺序：系统动态取色（Android 12+ 且 preset=DYNAMIC）→
 * 高对比方案 → 种子色方案（含 AMOLED 真黑）。
 * 同时提供缩放后的 Typography / Shapes / [LocalHzzsDimensions]，
 * 并在 Activity 上同步状态栏图标明暗。
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

    val typography = scaledTypography(config.fontScale)
    val shapes = Shapes(
        extraSmall = MaterialTheme.shapes.extraSmall,
        small = androidx.compose.foundation.shape.RoundedCornerShape((8f * config.cornerScale).dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape((14f * config.cornerScale).dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape((22f * config.cornerScale).dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape((30f * config.cornerScale).dp),
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
        sectionGap = (10f * spacing).dp,
        cardPadding = (16f * spacing).dp,
        screenPadding = (16f * spacing).dp,
    )
    CompositionLocalProvider(LocalHzzsDimensions provides dimensions) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
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

private fun seedScheme(seed: Color, dark: Boolean, amoled: Boolean, highContrast: Boolean): ColorScheme {
    val primary = if (dark) seed.lighten(0.18f) else seed.darken(0.08f)
    val secondary = seed.rotateChannels().let { if (dark) it.lighten(0.20f) else it.darken(0.10f) }
    val tertiary = seed.rotateChannels().rotateChannels().let { if (dark) it.lighten(0.16f) else it.darken(0.08f) }
    val surface = when {
        amoled -> Color.Black
        dark -> Color(0xFF111318)
        else -> Color(0xFFFFF8F5)
    }
    val onSurface = if (dark) Color(0xFFF2F0F4) else Color(0xFF201A18)
    val outline = if (highContrast) onSurface.copy(alpha = 0.82f) else onSurface.copy(alpha = 0.42f)
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.Black,
            secondary = secondary,
            tertiary = tertiary,
            background = surface,
            surface = surface,
            surfaceContainer = if (amoled) Color(0xFF090909) else Color(0xFF1B1B20),
            onSurface = onSurface,
            outline = outline,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            secondary = secondary,
            tertiary = tertiary,
            background = surface,
            surface = surface,
            surfaceContainer = Color(0xFFFFEEE8),
            onSurface = onSurface,
            outline = outline,
        )
    }
}

private fun highContrastScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFF70A7FF),
        onPrimary = Color.Black,
        background = Color.Black,
        surface = Color.Black,
        onSurface = Color.White,
        outline = Color.White,
        error = Color(0xFFFF6B6B),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF003F9E),
        onPrimary = Color.White,
        background = Color.White,
        surface = Color.White,
        onSurface = Color.Black,
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
