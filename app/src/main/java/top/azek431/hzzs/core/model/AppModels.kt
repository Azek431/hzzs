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

/**
 * 自动操作手势注入后端。
 *
 * 与 [CaptureBackend] 正交：改截图不改手势，反之亦然。
 *
 * 安全不变量：
 * - [AUTO] 优先无障碍；仅当无障碍未连接且 Shizuku **已授权就绪** 时用 Shizuku；
 *   **永不**静默探测或升权到 Root，AUTO 路径不弹 Shizuku 授权。
 * - [SHIZUKU] / [ROOT] 仅用户显式选择时启用。
 */
enum class GestureBackend { AUTO, ACCESSIBILITY, SHIZUKU, ROOT }

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
 * 单个 MCP **工具** 的策略覆盖（相对全局 [McpPermissionLevel]）。
 *
 * 仅存非 [DEFAULT] 项；未知工具名在 [top.azek431.hzzs.core.preferences.validated] 时丢弃。
 * 外部摄入只能更严（见 [top.azek431.hzzs.core.preferences.hardenedForExternalIngest]）。
 */
enum class McpToolPolicy {
    /** 跟随全局权限级 + 工具固有 [top.azek431.hzzs.mcp.McpToolRisk]。 */
    DEFAULT,

    /**
     * 非只读调用一律手机确认（即使全局为 TRUSTED_SESSION / FULL_ACCESS）。
     * 全局 READ_ONLY 仍整表拒绝写。
     */
    ALWAYS_ASK,

    /**
     * 在 TRUSTED_SESSION / FULL_ACCESS 下普通写可不经审批；
     * HIGH_RISK 仍须 FULL_ACCESS（或全局每次确认时走审批）。
     */
    ALLOW_WHEN_TRUSTED,

    /** 从 tools/list 隐藏，tools/call 拒绝。 */
    DISABLED,
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
    /** 产品默认调试 HUD：首装与缺字段回退；用户已保存样式不被迁移改写。 */
    val style: OverlayStyle = OverlayStyle.DEBUG_HUD,
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
    /**
     * 判定「障碍已完全落在玩家身后」时的水平容差（视口归一化）。
     * 运行时用障碍 **右缘** 与玩家左缘比较，重叠/贴身仍可触发；仅整块障碍在身后才丢弃。
     */
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
 * 生效前还须：免责声明版本、视觉运行中、所选 [gestureBackend] 可用等。
 *
 * 包名：默认**不**限制前台包。仅当 [restrictPackages] 为 true 时，
 * 才要求前台包 ∈ [allowedPackages]（用户可在设置中显式开启）。
 */
data class AutomationConfig(
    val enabled: Boolean = false,
    val disclaimerAcceptedVersion: Int = 0,
    /**
     * 手势注入后端；默认 [GestureBackend.AUTO]（无障碍优先，条件 Shizuku，永不 Root）。
     * 与 [AppConfig.captureBackend] 独立。
     */
    val gestureBackend: GestureBackend = GestureBackend.AUTO,
    /**
     * legacy：竹影书屋实验锁字段，仅保留 schema/配置兼容。
     *
     * **运行时、UI、MCP 门控均不再读取本字段**；自动操作对全部场景共用总开关。
     * 外部摄入 harden 仍可收敛，避免旧配置误导导入预览。
     */
    val bambooExperimentalAutoAction: Boolean = false,
    /**
     * 是否启用前台包名门控。
     * 默认 false：任意前台包均可（仍须所选手势后端可用 + 其它门控）。
     * 开启后仅 [allowedPackages] 内的包可派发手势；须用户在设置中明确打开。
     */
    val restrictPackages: Boolean = false,
    /**
     * 允许的前台包名集合。
     * 仅在 [restrictPackages]=true 时生效；空集在 validated 时回退 [SUGGESTED_PACKAGES]。
     */
    val allowedPackages: Set<String> = SUGGESTED_PACKAGES,
    val maxActionsPerSecond: Int = 4,
    val minimumSceneConfidence: Float = 0.82f,
    val retryLimit: Int = 1,
    /** 相对玩家宽度的触发距离（甜甜圈，对齐历史 main 规划器）。 */
    val sweetTriggerDistancePlayerWidths: Float = 1.50f,
    /** 相对玩家宽度的触发距离（竹影）。 */
    val bambooTriggerDistancePlayerWidths: Float = 1.35f,
    /**
     * 相对玩家宽度的触发距离（海盐客厅）。
     * 酱油脚本按设计分辨率在较远 x 就点（约屏宽 0.3+），FIXED 玩家宽约 0.05，
     * 1.4 倍仅 ~0.07 屏宽会导致「框已稳、永远 no_candidate」。默认放宽到约 5 倍玩家宽。
     */
    val seaSaltTriggerDistancePlayerWidths: Float = 5.0f,
    /**
     * 运行时根据近障碍间隙缓升/缓降触发距离（玩家宽度倍数），并节流写回本配置。
     * 默认开启；关闭后仅使用上方固定倍数。不改变自动化总开关与其它门控。
     */
    val autoAdjustTriggerDistance: Boolean = true,
) {
    companion object {
        /**
         * 建议的前台包（快手系小游戏容器）。
         * 仅作默认列表与「填入建议」；**不再**与用户列表强制求交。
         */
        val SUGGESTED_PACKAGES: Set<String> = setOf(
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
        )

        /** @deprecated 使用 [SUGGESTED_PACKAGES]；保留别名避免旧测试硬编码断裂。 */
        @Deprecated("Renamed to SUGGESTED_PACKAGES", ReplaceWith("SUGGESTED_PACKAGES"))
        val DEFAULT_ALLOWED_PACKAGES: Set<String> = SUGGESTED_PACKAGES
    }
}

/**
 * MCP 本地服务配置。
 *
 * 默认关闭；启用后默认仅 loopback。
 * [bindLocalhostOnly]=false 时服务绑定 `0.0.0.0`（局域网可达）；须用户在设置页显式确认风险。
 * [requireAuth] 默认 **false**（同机 RikkaHub 免填 Header；局域网也可免鉴权但风险更高）；
 * 开启后使用持久化 [authToken]，**不会**在每次服务启动时轮换，仅用户主动「轮换 Token」时更新。
 * [toolPolicies]：按工具名覆盖审批/禁用；键为 MCP 工具准确名（如 `start_analysis`）。
 * [accessLogEnabled]：是否写入进程内 MCP 访问日志 ring（默认 true；永不记 Token/参数体）。
 * 权限型字段；设置预览阶段不启动服务。
 */
data class McpConfig(
    val enabled: Boolean = false,
    val permissionLevel: McpPermissionLevel = McpPermissionLevel.ASK_EVERY_TIME,
    val port: Int = 8765,
    /**
     * true：只绑 IPv4 `127.0.0.1`（默认）。
     * false：绑 `0.0.0.0`，同网段设备可连；外部导入默认不得静默打开。
     */
    val bindLocalhostOnly: Boolean = true,
    val allowDebugFrames: Boolean = false,
    /**
     * 是否要求 `Authorization: Bearer`。
     * 默认 false：客户端可不填请求头（loopback 或局域网均可，由用户自担风险）。
     */
    val requireAuth: Boolean = false,
    /**
     * 持久化配对令牌（hex）。仅 [requireAuth]=true 时生效；
     * 空串表示尚未生成，服务启动或用户开启鉴权时会补齐并写回配置。
     * 不得写入日志；诊断导出须脱敏。
     */
    val authToken: String = "",
    /**
     * 工具策略覆盖：仅保留非 [McpToolPolicy.DEFAULT] 的条目。
     * 键必须是已知 MCP 工具名；未知键在校验时丢弃。
     */
    val toolPolicies: Map<String, McpToolPolicy> = emptyMap(),
    /**
     * 是否记录 MCP 访问日志（进程内 ring，见 [top.azek431.hzzs.mcp.McpAccessLog]）。
     * 默认 true；关闭后不再追加，已有条目保留直至清空。
     */
    val accessLogEnabled: Boolean = true,
) {
    fun policyFor(toolName: String): McpToolPolicy =
        toolPolicies[toolName] ?: McpToolPolicy.DEFAULT
}

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
 * 默认关闭；关于页连续点击版本号 7 次开启后，设置首页出现「开发者选项」分类。
 * 本页开关可关闭；预览阶段不强制切换截图后端等副作用。
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
        const val CURRENT_SCHEMA = 9

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
    val activeScene: SceneId = AppConfig.DEFAULT_SELECTED_SCENE,
    val activeBackend: CaptureBackend = CaptureBackend.AUTO,
    /** 解析后的有效手势注入后端（AUTO 展开后）；未运行时默认 AUTO。 */
    val activeGestureBackend: GestureBackend = GestureBackend.AUTO,
    val fps: Float = 0f,
    val processingMs: Float = 0f,
    val obstacleCount: Int = 0,
    val lastError: String? = null,
    /**
     * 最近一次自动操作决策摘要（skip / plan / dispatch_*）。
     * 供运行页展示「为何没有动作」。
     */
    val lastAutomationDecision: String? = null,
)
