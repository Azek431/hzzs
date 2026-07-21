package top.azek431.hzzs.core.model

import androidx.annotation.ColorInt

/** Global light/dark behavior. AMOLED is a dark scheme with a true-black canvas. */
enum class AppThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** Built-in palettes. CUSTOM uses [ThemeConfig.customSeed]. */
enum class ThemePreset {
    DYNAMIC,
    FIRE_ORANGE,
    CORAL,
    BAMBOO,
    OCEAN,
    INDIGO,
    LAVENDER,
    BLACK_GOLD,
    HIGH_CONTRAST,
    CUSTOM,
}

/** Density of information shown by the floating overlay. */
enum class OverlayStyle { MINIMAL, COMPACT, DEBUG_HUD }

enum class OverlayTheme {
    FOLLOW_APP,
    AUTO_CONTRAST,
    DARK_GLASS,
    LIGHT_GLASS,
    AMOLED,
    FIRE_ORANGE,
    BAMBOO,
    NEON_GREEN,
    WARNING_ORANGE,
    CUSTOM,
}

enum class OverlayOrientation { HORIZONTAL, VERTICAL }

enum class GameProfileId { HUO_ZAI_ZAI_WONDER_HOUSE }

/** Two seasons of the same game. The gameplay coordinate system is shared. */
enum class SceneId { SWEET_FACTORY, BAMBOO_BOOKSTORE }

/**
 * Capture backends exposed to the user. AUTO never silently escalates to Shell or Root.
 * SHIZUKU is the supported form of an ADB/Shell-backed in-app capability.
 */
enum class CaptureBackend { AUTO, MEDIA_PROJECTION, ACCESSIBILITY, SHIZUKU, ROOT }

enum class UpdateChannel { STABLE, BETA }

/** 应用/算法下载来源偏好；AUTO 默认 Gitee，不可达时回退 GitHub。 */
enum class UpdateSourcePreference { AUTO, PREFER_GITEE, PREFER_GITHUB }

/** 算法选择方式：自动取兼容最新官方包，或手动钉选已安装版本。 */
enum class AlgorithmSelectionMode { AUTO, MANUAL }

/** 算法发布通道，与应用更新通道独立。 */
enum class AlgorithmChannel { STABLE, BETA }

enum class PlayerReferenceMode { FIXED_RATIO, DETECT_ONCE, CONTINUOUS }

enum class McpPermissionLevel {
    READ_ONLY,
    ASK_EVERY_TIME,
    TRUSTED_SESSION,
    FULL_ACCESS,
}

/** Stable obstacle identifiers used by settings, C++, reports and theme-season filters. */
enum class ObstacleKind {
    POISON_BOTTLE,
    CAKE_STRUCTURE,
    HANGING_SPIKE,
    PIT,
    PANDA_STATUE,
    BAMBOO_GAP,
    HANGING_BRUSH,
}

data class ThemeConfig(
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.FIRE_ORANGE,
    @param:ColorInt val customSeed: Int = 0xFFFF6B2C.toInt(),
    val dynamicColorEnabled: Boolean = true,
    val fontScale: Float = 1f,
    val cornerScale: Float = 1f,
    val spacingScale: Float = 1f,
    val animationScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

data class OverlayConfig(
    val enabled: Boolean = true,
    val style: OverlayStyle = OverlayStyle.MINIMAL,
    val theme: OverlayTheme = OverlayTheme.FOLLOW_APP,
    @param:ColorInt val customColor: Int = 0xFF20E89B.toInt(),
    val backgroundAlpha: Float = 0.70f,
    val scale: Float = 1f,
    val strokeWidthDp: Float = 2f,
    val textScale: Float = 1f,
    val orientation: OverlayOrientation = OverlayOrientation.HORIZONTAL,
    val showBoxes: Boolean = true,
    val showText: Boolean = true,
    val showFps: Boolean = false,
    val showConfidence: Boolean = false,
    val showDiagnostics: Boolean = false,
    val clickThrough: Boolean = true,
    val snapToEdge: Boolean = true,
    val lockPosition: Boolean = false,
)

/** Visible game area in normalized full-screen coordinates. */
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
    /** Native detector chooses an adaptive stride around this work width. */
    val workWidth: Int = 384,
    val minimumConfidence: Float = 0.72f,
    val stableFrames: Int = 2,
    val playerReferenceMode: PlayerReferenceMode = PlayerReferenceMode.FIXED_RATIO,
    /** Horizontal player reference in viewport-normalized coordinates. */
    val fixedPlayerXRatio: Float = 0.185f,
    val behindPlayerMarginRatio: Float = 0.018f,
    /** Evaluation target only. It is not an accuracy claim. */
    val boundaryTolerancePlayerWidthRatio: Float = 0.05f,
)

data class SceneConfig(
    val sceneId: SceneId,
    val enabled: Boolean = true,
    /** Empty means every obstacle category is enabled. */
    val disabledObstacles: Set<ObstacleKind> = emptySet(),
    val thresholds: VisionThresholds = VisionThresholds(),
)

data class AutomationConfig(
    val enabled: Boolean = false,
    val disclaimerAcceptedVersion: Int = 0,
    val requireSessionArm: Boolean = true,
    /**
     * 竹影书屋自动操作在历史 main 线中默认锁定，需用户单独确认实验风险。
     * 与会话 arm 叠加：即使已 arm，未开启本开关时也不对竹影场景规划动作。
     */
    val bambooExperimentalAutoAction: Boolean = false,
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    val maxActionsPerSecond: Int = 4,
    val minimumSceneConfidence: Float = 0.82f,
    val retryLimit: Int = 1,
    /** 相对玩家宽度的触发距离（甜甜圈，对齐 main VisionActionPlanner）。 */
    val sweetTriggerDistancePlayerWidths: Float = 1.50f,
    /** 相对玩家宽度的触发距离（竹影）。 */
    val bambooTriggerDistancePlayerWidths: Float = 1.35f,
) {
    companion object {
        val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
        )
    }
}

data class McpConfig(
    val enabled: Boolean = false,
    val permissionLevel: McpPermissionLevel = McpPermissionLevel.ASK_EVERY_TIME,
    val port: Int = 8765,
    val bindLocalhostOnly: Boolean = true,
    val allowDebugFrames: Boolean = false,
)

data class DeveloperConfig(
    val enabled: Boolean = false,
    val forceCaptureBackend: CaptureBackend? = null,
    val saveDebugFrames: Boolean = false,
    val showCoordinateGrid: Boolean = false,
    val frameRateLimit: Int = 60,
    val nativeBenchmarkIterations: Int = 200,
)

data class OnboardingConfig(
    val completed: Boolean = false,
    val acceptedDisclaimerVersion: Int = 0,
)

data class UpdateConfig(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val autoCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val ignoredVersionCode: Long? = null,
    val sourcePreference: UpdateSourcePreference = UpdateSourcePreference.AUTO,
)

/**
 * 算法包选择与更新策略。
 *
 * 选择模式、通道与手动钉选属于草稿；下载/检查是即时任务，不写入本配置。
 * 手动下载的算法不会在保存前自动激活。
 */
data class AlgorithmConfig(
    val selectionMode: AlgorithmSelectionMode = AlgorithmSelectionMode.AUTO,
    /** 手动模式下钉选的算法包 ID；自动模式忽略。 */
    val pinnedAlgorithmId: String? = null,
    val channel: AlgorithmChannel = AlgorithmChannel.STABLE,
    val autoCheck: Boolean = true,
    val autoDownload: Boolean = false,
)

data class AppConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val theme: ThemeConfig = ThemeConfig(),
    val overlay: OverlayConfig = OverlayConfig(),
    val gameProfile: GameProfileId = GameProfileId.HUO_ZAI_ZAI_WONDER_HOUSE,
    val selectedScene: SceneId = SceneId.BAMBOO_BOOKSTORE,
    val captureBackend: CaptureBackend = CaptureBackend.AUTO,
    val viewport: ViewportConfig = ViewportConfig(),
    val scenes: Map<SceneId, SceneConfig> = SceneId.entries.associateWith { SceneConfig(it) },
    val automation: AutomationConfig = AutomationConfig(),
    val mcp: McpConfig = McpConfig(),
    val developer: DeveloperConfig = DeveloperConfig(),
    val onboarding: OnboardingConfig = OnboardingConfig(),
    val update: UpdateConfig = UpdateConfig(),
    val algorithm: AlgorithmConfig = AlgorithmConfig(),
) {
    companion object {
        const val CURRENT_SCHEMA = 6
        const val DISCLAIMER_VERSION = 1
    }
}

data class RuntimeStatus(
    val running: Boolean = false,
    val captureReady: Boolean = false,
    val overlayVisible: Boolean = false,
    val automationArmed: Boolean = false,
    val activeScene: SceneId = SceneId.BAMBOO_BOOKSTORE,
    val activeBackend: CaptureBackend = CaptureBackend.AUTO,
    val fps: Float = 0f,
    val processingMs: Float = 0f,
    val obstacleCount: Int = 0,
    val lastError: String? = null,
)
