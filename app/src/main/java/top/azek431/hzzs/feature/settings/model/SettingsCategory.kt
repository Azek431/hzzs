package top.azek431.hzzs.feature.settings.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.displayName

enum class SettingsCategory(
    val route: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    APPEARANCE(
        route = "appearance",
        title = "外观与显示",
        description = "主题、动态取色、字体与动效",
        icon = Icons.Rounded.ColorLens,
    ),
    ALGORITHM(
        route = "algorithm",
        title = "算法与识别",
        description = "算法包、赛季与识别参数",
        icon = Icons.Rounded.AutoAwesome,
    ),
    CAPTURE(
        route = "capture",
        title = "截图与权限",
        description = "截图后端与能力状态",
        icon = Icons.Rounded.PhotoCamera,
    ),
    OVERLAY(
        route = "overlay",
        title = "悬浮窗",
        description = "样式、透明度与显示项",
        icon = Icons.Rounded.Layers,
    ),
    AUTOMATION(
        route = "automation",
        title = "自动操作与安全",
        description = "风险开关与会话解锁",
        icon = Icons.Rounded.Security,
    ),
    NETWORK(
        route = "network",
        title = "网络与更新",
        description = "下载来源、算法与应用更新",
        icon = Icons.Rounded.CloudSync,
    ),
    MCP(
        route = "mcp",
        title = "MCP 与开发者",
        description = "本地 MCP 权限与调试选项",
        icon = Icons.Rounded.SmartToy,
    ),
}

fun SettingsCategory.summary(
    config: AppConfig,
    algorithmState: AlgorithmCatalogState? = null,
): String = when (this) {
    SettingsCategory.APPEARANCE -> {
        val mode = config.theme.mode.displayName()
        val preset = config.theme.preset.displayName()
        "$mode · $preset"
    }
    SettingsCategory.ALGORITHM -> {
        val mode = config.algorithm.selectionMode.displayName()
        val name = algorithmState?.active?.name?.take(18) ?: config.selectedScene.displayName()
        "$mode · $name"
    }
    SettingsCategory.CAPTURE -> config.captureBackend.displayName()
    SettingsCategory.OVERLAY -> {
        val state = if (config.overlay.enabled) "已开启" else "已关闭"
        "$state · ${config.overlay.style.displayName()}"
    }
    SettingsCategory.AUTOMATION -> {
        if (config.automation.enabled) {
            if (config.automation.requireSessionArm) "已开启 · 需手动解锁" else "已开启 · 自动窗口"
        } else {
            "默认关闭"
        }
    }
    SettingsCategory.NETWORK -> {
        val source = config.update.sourcePreference.displayName()
        val channel = config.update.channel.displayName()
        "$source · 应用$channel"
    }
    SettingsCategory.MCP -> {
        val mcp = if (config.mcp.enabled) {
            config.mcp.permissionLevel.displayName()
        } else {
            "MCP 关闭"
        }
        val dev = if (config.developer.enabled) "开发者开" else "开发者关"
        "$mcp · $dev"
    }
}

object SettingsRoutes {
    const val HOME = "settings_home"
}
