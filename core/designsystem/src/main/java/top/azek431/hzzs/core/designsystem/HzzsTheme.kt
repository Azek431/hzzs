package top.azek431.hzzs.core.designsystem

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.ThemeConfig
import top.azek431.hzzs.core.model.ThemePreset
import kotlin.math.max
import kotlin.math.min

private val presetSeeds = mapOf(
    ThemePreset.CORAL to Color(0xFFC73650),
    ThemePreset.SAKURA to Color(0xFFE65A8B),
    ThemePreset.PEACH to Color(0xFFE77A62),
    ThemePreset.AMBER to Color(0xFFB76B00),
    ThemePreset.LEMON to Color(0xFF8D7900),
    ThemePreset.FOREST to Color(0xFF307A4B),
    ThemePreset.MINT to Color(0xFF13866D),
    ThemePreset.AQUA to Color(0xFF007C8A),
    ThemePreset.SKY to Color(0xFF2979B8),
    ThemePreset.INDIGO to Color(0xFF4D5BB7),
    ThemePreset.LAVENDER to Color(0xFF7957A8),
)

@Composable
fun HzzsTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (config.mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
    }
    val dynamic = config.dynamicColorEnabled && config.preset == ThemePreset.DYNAMIC && Build.VERSION.SDK_INT >= 31
    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        config.mode == AppThemeMode.AMOLED -> amoledScheme(seed(config))
        dark -> tonalDarkScheme(seed(config), config.highContrast)
        else -> tonalLightScheme(seed(config), config.highContrast)
    }
    if (context is Activity) {
        SideEffect {
            WindowCompat.getInsetsController(context.window, context.window.decorView)
                .isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

private fun seed(config: ThemeConfig): Color = when (config.preset) {
    ThemePreset.CUSTOM -> Color(config.customSeed)
    ThemePreset.DYNAMIC -> Color(0xFFC73650)
    else -> presetSeeds.getValue(config.preset)
}

private fun tonalLightScheme(seed: Color, highContrast: Boolean): ColorScheme {
    val primary = seed.adjustLightness(if (highContrast) -0.12f else -0.04f)
    return lightColorScheme(
        primary = primary,
        onPrimary = bestOn(primary),
        primaryContainer = seed.adjustLightness(0.33f),
        onPrimaryContainer = seed.adjustLightness(-0.35f),
        secondary = seed.rotateChannels().adjustSaturation(-0.18f),
        tertiary = seed.rotateChannels().adjustLightness(0.06f),
        background = Color(0xFFFFF8F7),
        surface = Color(0xFFFFF8F7),
        surfaceVariant = seed.adjustSaturation(-0.65f).adjustLightness(0.42f),
    )
}

private fun tonalDarkScheme(seed: Color, highContrast: Boolean): ColorScheme {
    val primary = seed.adjustLightness(if (highContrast) 0.32f else 0.24f)
    return darkColorScheme(
        primary = primary,
        onPrimary = bestOn(primary),
        primaryContainer = seed.adjustLightness(-0.24f),
        onPrimaryContainer = seed.adjustLightness(0.38f),
        secondary = seed.rotateChannels().adjustLightness(0.24f).adjustSaturation(-0.2f),
        tertiary = seed.rotateChannels().adjustLightness(0.3f),
        background = Color(0xFF151113),
        surface = Color(0xFF151113),
        surfaceVariant = Color(0xFF302A2D),
    )
}

private fun amoledScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = seed.adjustLightness(0.28f),
    onPrimary = bestOn(seed.adjustLightness(0.28f)),
    primaryContainer = seed.adjustLightness(-0.28f),
    onPrimaryContainer = seed.adjustLightness(0.38f),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF171717),
)

private fun bestOn(color: Color) = if (color.luminance() > 0.42f) Color.Black else Color.White

private fun Color.adjustLightness(delta: Float) = Color(
    red = (red + delta).coerceIn(0f, 1f),
    green = (green + delta).coerceIn(0f, 1f),
    blue = (blue + delta).coerceIn(0f, 1f),
    alpha = alpha,
)

private fun Color.adjustSaturation(delta: Float): Color {
    val avg = (red + green + blue) / 3f
    val factor = (1f + delta).coerceIn(0f, 2f)
    return Color(
        red = (avg + (red - avg) * factor).coerceIn(0f, 1f),
        green = (avg + (green - avg) * factor).coerceIn(0f, 1f),
        blue = (avg + (blue - avg) * factor).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun Color.rotateChannels() = Color(green, blue, red, alpha)
