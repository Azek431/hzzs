package top.azek431.hzzs.core.model

/**
 * 枚举 → 用户可见中文文案。
 *
 * 界面与悬浮窗应使用本文件扩展函数，避免直接展示枚举名或英文标识。
 * 仅负责展示字符串，不含业务逻辑。
 */

/** 赛季显示名。 */
fun SceneId.displayName(): String = when (this) {
    SceneId.SWEET_FACTORY -> "甜甜圈"
    SceneId.BAMBOO_BOOKSTORE -> "竹影书屋"
}

/** 截图后端显示名。 */
fun CaptureBackend.displayName(): String = when (this) {
    CaptureBackend.AUTO -> "自动推荐"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍截图"
    CaptureBackend.SHIZUKU -> "Shizuku"
    CaptureBackend.ROOT -> "Root"
}

/** 开发者强制截图后端的短标签（FilterChip）。 */
fun CaptureBackend.developerLabel(): String = when (this) {
    CaptureBackend.AUTO -> "自动"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍"
    CaptureBackend.SHIZUKU -> "Shizuku"
    CaptureBackend.ROOT -> "Root"
}

/** 应用日志级别显示名。 */
fun AppLogLevel.displayName(): String = when (this) {
    AppLogLevel.VERBOSE -> "详细 (VERBOSE)"
    AppLogLevel.DEBUG -> "调试 (DEBUG)"
    AppLogLevel.INFO -> "信息 (INFO)"
    AppLogLevel.WARN -> "警告 (WARN)"
    AppLogLevel.ERROR -> "错误 (ERROR)"
}

/** MCP 权限级别显示名。 */
fun McpPermissionLevel.displayName(): String = when (this) {
    McpPermissionLevel.READ_ONLY -> "只读"
    McpPermissionLevel.ASK_EVERY_TIME -> "每次确认"
    McpPermissionLevel.TRUSTED_SESSION -> "信任本次会话"
    McpPermissionLevel.FULL_ACCESS -> "完整访问"
}

/** 算法选择模式显示名。 */
fun AlgorithmSelectionMode.displayName(): String = when (this) {
    AlgorithmSelectionMode.AUTO -> "自动选择"
    AlgorithmSelectionMode.MANUAL -> "手动选择"
}

/** 算法发布通道显示名。 */
fun AlgorithmChannel.displayName(): String = when (this) {
    AlgorithmChannel.STABLE -> "稳定"
    AlgorithmChannel.BETA -> "测试"
}

/** 更新源偏好显示名。 */
fun UpdateSourcePreference.displayName(): String = when (this) {
    UpdateSourcePreference.AUTO -> "自动选择"
    UpdateSourcePreference.PREFER_GITEE -> "优先 Gitee"
    UpdateSourcePreference.PREFER_GITHUB -> "优先 GitHub"
}

/** 应用更新通道显示名。 */
fun UpdateChannel.displayName(): String = when (this) {
    UpdateChannel.STABLE -> "稳定"
    UpdateChannel.BETA -> "测试"
}

/** 应用主题模式显示名。 */
fun AppThemeMode.displayName(): String = when (this) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色"
    AppThemeMode.DARK -> "深色"
    AppThemeMode.AMOLED -> "纯黑"
}

/** 内置调色板显示名。 */
fun ThemePreset.displayName(): String = when (this) {
    ThemePreset.DYNAMIC -> "动态取色"
    ThemePreset.FIRE_ORANGE -> "焰火橙"
    ThemePreset.CORAL -> "珊瑚红"
    ThemePreset.BAMBOO -> "竹影青"
    ThemePreset.OCEAN -> "深海蓝"
    ThemePreset.INDIGO -> "靛青"
    ThemePreset.LAVENDER -> "紫晶夜"
    ThemePreset.BLACK_GOLD -> "黑金"
    ThemePreset.HIGH_CONTRAST -> "高对比"
    ThemePreset.CUSTOM -> "自定义"
}

/** 悬浮窗信息密度显示名。 */
fun OverlayStyle.displayName(): String = when (this) {
    OverlayStyle.MINIMAL -> "极简"
    OverlayStyle.COMPACT -> "紧凑"
    OverlayStyle.DEBUG_HUD -> "调试 HUD"
}

/** 悬浮窗主题显示名。 */
fun OverlayTheme.displayName(): String = when (this) {
    OverlayTheme.FOLLOW_APP -> "跟随应用"
    OverlayTheme.AUTO_CONTRAST -> "自动对比"
    OverlayTheme.DARK_GLASS -> "深色玻璃"
    OverlayTheme.LIGHT_GLASS -> "浅色玻璃"
    OverlayTheme.AMOLED -> "纯黑"
    OverlayTheme.FIRE_ORANGE -> "焰火橙"
    OverlayTheme.BAMBOO -> "竹影青"
    OverlayTheme.NEON_GREEN -> "霓虹绿"
    OverlayTheme.WARNING_ORANGE -> "警示橙"
    OverlayTheme.CUSTOM -> "自定义"
}

/** 玩家基准策略显示名。 */
fun PlayerReferenceMode.displayName(): String = when (this) {
    PlayerReferenceMode.FIXED_RATIO -> "固定比例"
    PlayerReferenceMode.DETECT_ONCE -> "启动检测一次"
    PlayerReferenceMode.CONTINUOUS -> "持续检测"
}

/** 障碍类别显示名（设置过滤、列表等）。 */
fun ObstacleKind.displayName(): String = when (this) {
    ObstacleKind.POISON_BOTTLE -> "毒药瓶"
    ObstacleKind.CAKE_STRUCTURE -> "蛋糕结构"
    ObstacleKind.HANGING_SPIKE -> "悬挂尖刺"
    ObstacleKind.PIT -> "地坑"
    ObstacleKind.PANDA_STATUE -> "熊猫摆件"
    ObstacleKind.BAMBOO_GAP -> "竹林缺口"
    ObstacleKind.HANGING_BRUSH -> "悬挂毛笔"
}

/**
 * 将检测类别字符串映射为中文。
 *
 * 用于悬浮窗 / 调试 HUD 等只持有字符串的场景；
 * 未知值原样返回，避免崩溃。
 */
fun detectionKindDisplayName(kindName: String): String = when (kindName) {
    "PLAYER" -> "玩家"
    "POISON_BOTTLE" -> "毒药瓶"
    "CAKE_STRUCTURE" -> "蛋糕结构"
    "HANGING_SPIKE" -> "悬挂尖刺"
    "PIT" -> "地坑"
    "PANDA_STATUE" -> "熊猫摆件"
    "BAMBOO_GAP" -> "竹林缺口"
    "HANGING_BRUSH" -> "悬挂毛笔"
    else -> kindName
}
