package top.azek431.hzzs.core.model

import androidx.annotation.ColorInt

enum class AppThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class ThemePreset {
    DYNAMIC,
    CORAL,
    SAKURA,
    PEACH,
    AMBER,
    LEMON,
    FOREST,
    MINT,
    AQUA,
    SKY,
    INDIGO,
    LAVENDER,
    CUSTOM,
}

enum class OverlayTheme {
    FOLLOW_APP,
    AUTO_CONTRAST,
    DARK_GLASS,
    LIGHT_GLASS,
    AMOLED,
    CORAL,
    NEON_GREEN,
    WARNING_ORANGE,
    CUSTOM,
}

enum class GameProfileId { HUO_ZAI_ZAI_WONDER_HOUSE }
enum class SceneId { SWEET_FACTORY, BAMBOO_BOOKSTORE }
enum class CaptureBackend { AUTO, MEDIA_PROJECTION, ACCESSIBILITY, ROOT }
enum class UpdateChannel { STABLE, BETA }

data class ThemeConfig(
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.CORAL,
    @ColorInt val customSeed: Int = 0xFFC73650.toInt(),
    val dynamicColorEnabled: Boolean = true,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

data class OverlayConfig(
    val enabled: Boolean = true,
    val theme: OverlayTheme = OverlayTheme.FOLLOW_APP,
    @ColorInt val customColor: Int = 0xFF20E89B.toInt(),
    val backgroundAlpha: Float = 0.74f,
    val blurEnabled: Boolean = true,
    val strokeWidthDp: Float = 2f,
    val textScale: Float = 1f,
    val showConfidence: Boolean = false,
    val showDiagnostics: Boolean = false,
    val clickThrough: Boolean = true,
    val compactMode: Boolean = true,
)

/** Visible game area in full-screen normalized coordinates. */
data class ViewportConfig(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class VisionThresholds(
    /** The native detector chooses an adaptive stride targeting this width. */
    val workWidth: Int = 320,
    val minimumConfidence: Float = 0.72f,
    val stableFrames: Int = 3,
    /** A guessed player position may be rendered for diagnostics, never for actions. */
    val playerFallbackForHudOnly: Boolean = true,
    val behindPlayerMarginRatio: Float = 0.02f,
    /** Evaluation target, not a confidence claim. */
    val boundaryTolerancePlayerWidthRatio: Float = 0.05f,
)

data class SceneConfig(
    val sceneId: SceneId,
    val enabled: Boolean = true,
    val thresholds: VisionThresholds = VisionThresholds(),
)

data class AutomationConfig(
    val requireSessionArm: Boolean = true,
    val allowedPackages: Set<String> = setOf(
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
    ),
    val maxActionsPerSecond: Int = 4,
    val minimumSceneConfidence: Float = 0.82f,
    val retryLimit: Int = 1,
)

data class UpdateConfig(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val autoCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val ignoredVersionCode: Long? = null,
)

data class AppConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val theme: ThemeConfig = ThemeConfig(),
    val overlay: OverlayConfig = OverlayConfig(),
    val gameProfile: GameProfileId = GameProfileId.HUO_ZAI_ZAI_WONDER_HOUSE,
    val selectedScene: SceneId = SceneId.SWEET_FACTORY,
    val captureBackend: CaptureBackend = CaptureBackend.AUTO,
    val viewport: ViewportConfig = ViewportConfig(),
    val scenes: Map<SceneId, SceneConfig> = SceneId.entries.associateWith { SceneConfig(it) },
    val automation: AutomationConfig = AutomationConfig(),
    val update: UpdateConfig = UpdateConfig(),
) {
    companion object { const val CURRENT_SCHEMA = 3 }
}

data class RuntimeStatus(
    val running: Boolean = false,
    val captureReady: Boolean = false,
    val overlayVisible: Boolean = false,
    val automationArmed: Boolean = false,
    val activeScene: SceneId = SceneId.SWEET_FACTORY,
    val fps: Float = 0f,
    val lastError: String? = null,
)
