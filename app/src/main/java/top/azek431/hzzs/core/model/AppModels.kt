package top.azek431.hzzs.core.model

import androidx.annotation.ColorInt

/**
 * 应用级稳定配置模型。
 *
 * 职责：
 * - 定义主题、悬浮窗、截图、场景、自动操作、MCP、开发者、更新、算法等配置结构
 * - 作为 DataStore / 设置草稿 / 运行时快照的共享类型
 *
 * 约定：
 * - 本文件尽量少依赖 Android 运行时（仅 [ColorInt] 注解）
 * - 默认值必须安全：自动操作关、MCP 关、截图 AUTO 不升权
 * - 修改字段时同步：`validated()`、JSON 编解码、设置 UI、MCP schema、单测
 */

/** 应用明暗模式。AMOLED 为真黑背景的深色方案。 */
enum class AppThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * 内置调色板。
 *
 * [CUSTOM] 使用 [ThemeConfig.customSeed]；[DYNAMIC] 走系统动态取色（支持时）。
 */
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

/** 悬浮窗信息密度：极简 / 紧凑 / 调试 HUD。 */
enum class OverlayStyle { MINIMAL, COMPACT, DEBUG_HUD }

/** 悬浮窗视觉主题；可与应用主题解耦。 */
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

/** 悬浮窗内容排布方向。 */
enum class OverlayOrientation { HORIZONTAL, VERTICAL }

/** 游戏档案 ID；当前仅支持《火崽崽奇妙屋》。 */
enum class GameProfileId { HUO_ZAI_ZAI_WONDER_HOUSE }

/**
 * 赛季 ID。
 *
 * 枚举序与 C++ `scene` 参数一致：
 * `SWEET_FACTORY = 0`，`BAMBOO_BOOKSTORE = 1`，`SEA_SALT_LIVING_ROOM = 2`。
 * 三赛季共用同一套算法引擎与比例坐标体系，差异只在赛季参数。
 */
enum class SceneId { SWEET_FACTORY, BAMBOO_BOOKSTORE, SEA_SALT_LIVING_ROOM }

/**
 * 截图后端。
 *
 * 安全不变量：[AUTO] 只选择低权限 MediaProjection，**永不**探测 Root / Shizuku / 无障碍。
 * [SHIZUKU] / [ROOT] / [ACCESSIBILITY] 仅当用户显式选择时启用。
 */
enum class CaptureBackend { AUTO, MEDIA_PROJECTION, ACCESSIBILITY, SHIZUKU, ROOT }

/** 应用更新通道。 */
enum class UpdateChannel { STABLE, BETA }

/**
 * 应用/算法下载来源偏好。
 *
 * [AUTO]：默认优先 Gitee，不可达时回退 GitHub。
 */
enum class UpdateSourcePreference { AUTO, PREFER_GITEE, PREFER_GITHUB }

/**
 * 算法选择方式。
 *
 * [AUTO] 取兼容的最新官方包；[MANUAL] 钉选已安装版本。
 */
enum class AlgorithmSelectionMode { AUTO, MANUAL }

/** 算法发布通道，与应用 [UpdateChannel] 相互独立。 */
enum class AlgorithmChannel { STABLE, BETA }

/**
 * 玩家水平基准策略。
 *
 * - [FIXED_RATIO]：使用配置的固定 X 比例
 * - [DETECT_ONCE]：启动后检测一次并锁定
 * - [CONTINUOUS]：持续跟随检测结果
 */
enum class PlayerReferenceMode { FIXED_RATIO, DETECT_ONCE, CONTINUOUS }

/**
 * MCP 权限级别（从紧到松）。
 *
 * 即使 [FULL_ACCESS] 也不能绕过系统录屏 / 悬浮窗 / 无障碍 / 安装界面。
 */
enum class McpPermissionLevel {
    READ_ONLY,
    ASK_EVERY_TIME,
    TRUSTED_SESSION,
    FULL_ACCESS,
}

/**
 * 稳定障碍标识。
 *
 * 设置过滤、C++ 位掩码、报告与赛季过滤器共用此集合。
 * 增删时必须同步 Kotlin 枚举、JNI 位、C++ Kind 与标注工具。
 *
 * 命名与算法引擎 / 研究版 kind 对齐（如 GREEN_BOTTLE）。
 * 枚举序：Native Kind = ObstacleKind.ordinal + 1（0 保留给 PLAYER）。
 */
enum class ObstacleKind {
    GREEN_BOTTLE,
    CAKE_STRUCTURE,
    HANGING_SPIKE,
    PIT,
    PANDA_STATUE,
    BAMBOO_GAP,
    HANGING_BRUSH,
    SAND_CASTLE,
    HANGING_ANCHOR,
    SEA_PIT,
}

/**
 * 应用主题配置。
 *
 * 可在设置中临时预览；保存后写入 DataStore。
 */
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

/**
 * 悬浮窗配置。
 *
 * 可预览。真正创建/更新窗口由 `OverlayController` 在主线程完成。
 */
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

/**
 * 可见游戏区域，全屏归一化坐标。
 *
 * 视觉引擎在视口内裁剪分析；默认全屏。
 */
data class ViewportConfig(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 单赛季视觉阈值（用户可调部分）。
 *
 * 更细的算法参数见声明式 [top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile]，
 * 两者职责不同：本结构偏“用户设置”，算法包偏“发布参数”。
 */
data class VisionThresholds(
    /** Native 检测器围绕该工作宽度选择自适应步长。 */
    val workWidth: Int = 384,
    val minimumConfidence: Float = 0.72f,
    val stableFrames: Int = 2,
    val playerReferenceMode: PlayerReferenceMode = PlayerReferenceMode.FIXED_RATIO,
    /** 固定玩家水平参考，视口归一化 X。 */
    val fixedPlayerXRatio: Float = 0.185f,
    val behindPlayerMarginRatio: Float = 0.018f,
    /**
     * 评估用边界容差（相对玩家宽度）。
     * **不是**准确率承诺，仅用于数据集工具。
     */
    val boundaryTolerancePlayerWidthRatio: Float = 0.05f,
)

/**
 * 单赛季配置。
 *
 * @property disabledObstacles 空集合表示全部障碍类别启用
 */
data class SceneConfig(
    val sceneId: SceneId,
    val enabled: Boolean = true,
    val disabledObstacles: Set<ObstacleKind> = emptySet(),
    val thresholds: VisionThresholds = VisionThresholds(),
)

/**
 * 自动操作配置。
 *
 * 默认关闭。导入/迁移不得静默开启。
 * 生效前还须：免责声明版本、视觉运行中、无障碍前台白名单、会话 arm（若要求）等。
 */
data class AutomationConfig(
    val enabled: Boolean = false,
    val disclaimerAcceptedVersion: Int = 0,
    /**
     * 为 true（默认）时，每次运行会话须在运行页手动解锁（arm）。
     * 为 false 时，按当前前台白名单窗口自动规划（仍受其它门控约束）。
     */
    val requireSessionArm: Boolean = true,
    /**
     * 竹影书屋实验性自动操作锁。
     *
     * 与会话 arm 叠加：即使已 arm，未开启本开关时也不对竹影场景规划动作。
     */
    val bambooExperimentalAutoAction: Boolean = false,
    /** 与内置默认集合求交后的允许包名。 */
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    val maxActionsPerSecond: Int = 4,
    val minimumSceneConfidence: Float = 0.82f,
    val retryLimit: Int = 1,
    /** 相对玩家宽度的触发距离（甜甜圈，对齐历史 main 规划器）。 */
    val sweetTriggerDistancePlayerWidths: Float = 1.50f,
    /** 相对玩家宽度的触发距离（竹影）。 */
    val bambooTriggerDistancePlayerWidths: Float = 1.35f,
) {
    companion object {
        /**
         * 默认允许的前台包（快手系小游戏容器）。
         * 用户列表会与此集合求交，防止任意包名注入。
         */
        val DEFAULT_ALLOWED_PACKAGES: Set<String> = setOf(
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
        )
    }
}

/**
 * MCP 本地服务配置。
 *
 * 默认关闭；启用后仅 loopback + 随机 Bearer。
 * 权限型字段，设置预览阶段不启动服务。
 */
data class McpConfig(
    val enabled: Boolean = false,
    val permissionLevel: McpPermissionLevel = McpPermissionLevel.ASK_EVERY_TIME,
    val port: Int = 8765,
    val bindLocalhostOnly: Boolean = true,
    val allowDebugFrames: Boolean = false,
)

/**
 * 应用日志最低级别（开发者可配置）。
 *
 * 关闭开发者选项时，ring buffer 仍保留 INFO 及以上；DEBUG/VERBOSE 仅在开启后生效。
 */
enum class AppLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * 开发者选项。
 *
 * 需关于页连续点击版本号解锁；预览阶段不强制切换截图后端等副作用。
 * [frameRateLimit] 字段保留并校验，但完成驱动取帧下运行时暂不消费。
 */
data class DeveloperConfig(
    val enabled: Boolean = false,
    val forceCaptureBackend: CaptureBackend? = null,
    val saveDebugFrames: Boolean = false,
    val showCoordinateGrid: Boolean = false,
    val frameRateLimit: Int = 60,
    val nativeBenchmarkIterations: Int = 200,
    /** 写入 ring buffer / Logcat 的最低级别；关闭开发者时 DEBUG 以下仍被压制。 */
    val logLevel: AppLogLevel = AppLogLevel.INFO,
)

/** 首次引导与免责声明接受状态。 */
data class OnboardingConfig(
    val completed: Boolean = false,
    val acceptedDisclaimerVersion: Int = 0,
)

/**
 * 应用更新策略。
 *
 * 检查/下载是即时任务；[ignoredVersionCode] 用于用户忽略某版本。
 */
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
 * 选择模式、通道与手动钉选属于可保存配置；
 * 下载/检查是即时任务，不写入本结构。
 * 手动下载的算法不会在“保存设置”前自动激活。
 */
data class AlgorithmConfig(
    val selectionMode: AlgorithmSelectionMode = AlgorithmSelectionMode.AUTO,
    /** 手动模式下钉选的算法包 ID；自动模式忽略。 */
    val pinnedAlgorithmId: String? = null,
    val channel: AlgorithmChannel = AlgorithmChannel.STABLE,
    val autoCheck: Boolean = true,
    val autoDownload: Boolean = false,
)

/**
 * 完整应用配置快照。
 *
 * DataStore schema 版本见 [CURRENT_SCHEMA]。
 * 默认赛季只定义在 [DEFAULT_SELECTED_SCENE]；自动操作与 MCP 默认关闭。
 * 文档与代理说明应引用该常量，不要写死赛季中文名。
 */
data class AppConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val theme: ThemeConfig = ThemeConfig(),
    val overlay: OverlayConfig = OverlayConfig(),
    val gameProfile: GameProfileId = GameProfileId.HUO_ZAI_ZAI_WONDER_HOUSE,
    val selectedScene: SceneId = DEFAULT_SELECTED_SCENE,
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
        /** DataStore 配置 schema 版本；迁移逻辑依赖此常量。 */
        const val CURRENT_SCHEMA = 6

        /**
         * 自动操作免责声明版本。
         * 用户接受版本低于此值时不得 arm。
         */
        const val DISCLAIMER_VERSION = 1

        /**
         * 首次安装、配置重置与运行时回退时的默认赛季。
         *
         * **唯一写死点**：变更产品默认赛季时只改这里，并跑设置/迁移相关单测。
         * README / CLAUDE / AGENTS / PROGRESS 等文档不得再抄写具体赛季名。
         */
        /** 产品默认永远指向当前最新赛季（海盐客厅）。 */
        val DEFAULT_SELECTED_SCENE: SceneId = SceneId.SEA_SALT_LIVING_ROOM
    }
}

/**
 * 悬浮窗未能显示的原因（与分析 [RuntimeStatus.lastError] 分离）。
 *
 * [null] 表示未尝试、已隐藏或当前可见；仅在期望显示但失败时写入。
 */
enum class OverlayBlockReason {
    /** 应用内悬浮窗总开关关闭。 */
    DISABLED,
    /** 缺少系统「显示在其他应用上层」权限。 */
    PERMISSION,
    /** WindowManager 添加/更新失败。 */
    ADD_VIEW_FAILED,
}

/**
 * 运行时对外状态（UI / MCP 只读）。
 *
 * 由 [top.azek431.hzzs.data.vision.VisionRuntimeController] 作为唯一所有者更新。
 */
data class RuntimeStatus(
    val running: Boolean = false,
    val captureReady: Boolean = false,
    val overlayVisible: Boolean = false,
    /** 期望显示悬浮窗但失败时的原因；可见或未尝试时为 null。 */
    val overlayBlockReason: OverlayBlockReason? = null,
    val automationArmed: Boolean = false,
    val activeScene: SceneId = AppConfig.DEFAULT_SELECTED_SCENE,
    val activeBackend: CaptureBackend = CaptureBackend.AUTO,
    val fps: Float = 0f,
    val processingMs: Float = 0f,
    val obstacleCount: Int = 0,
    val lastError: String? = null,
)
