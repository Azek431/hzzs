/**
 * 设置分类路由与首页摘要。
 *
 * 职责：定义设置子页路由/标题/图标，以及根据草稿配置生成首页一行摘要。
 * 边界：纯 UI 模型，不读写仓库、不触发预览或权限能力。
 * 标题/说明使用 string 资源 ID；[summary] 为纯函数便于 JVM 单测。
 */
package top.azek431.hzzs.feature.settings.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import top.azek431.hzzs.R
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.displayName

/** 设置首页分类枚举：路由与展示元数据（文案资源 ID）。 */
enum class SettingsCategory(
    val route: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {
    APPEARANCE(
        route = "appearance",
        titleRes = R.string.settings_cat_appearance_title,
        descriptionRes = R.string.settings_cat_appearance_desc,
        icon = Icons.Rounded.ColorLens,
    ),
    ALGORITHM(
        route = "algorithm",
        titleRes = R.string.settings_cat_algorithm_title,
        descriptionRes = R.string.settings_cat_algorithm_desc,
        icon = Icons.Rounded.AutoAwesome,
    ),
    CAPTURE(
        route = "capture",
        titleRes = R.string.settings_cat_capture_title,
        descriptionRes = R.string.settings_cat_capture_desc,
        icon = Icons.Rounded.PhotoCamera,
    ),
    OVERLAY(
        route = "overlay",
        titleRes = R.string.settings_cat_overlay_title,
        descriptionRes = R.string.settings_cat_overlay_desc,
        icon = Icons.Rounded.Layers,
    ),
    AUTOMATION(
        route = "automation",
        titleRes = R.string.settings_cat_automation_title,
        descriptionRes = R.string.settings_cat_automation_desc,
        icon = Icons.Rounded.Security,
    ),
    NETWORK(
        route = "network",
        titleRes = R.string.settings_cat_network_title,
        descriptionRes = R.string.settings_cat_network_desc,
        icon = Icons.Rounded.CloudSync,
    ),
    MCP(
        route = "mcp",
        titleRes = R.string.settings_cat_mcp_title,
        descriptionRes = R.string.settings_cat_mcp_desc,
        icon = Icons.Rounded.SmartToy,
    ),
}

/**
 * 根据当前草稿配置与算法目录状态生成分类卡摘要文案。
 * 动态拼接，保持纯函数以便 JVM 单测；静态壳文案见 strings.xml。
 */
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

/** 设置模块内嵌导航路由常量。 */
object SettingsRoutes {
    const val HOME = "settings_home"
    /** 开发者运行日志查看器（从 MCP/开发者页进入）。 */
    const val LOG_VIEWER = "log_viewer"
    /** 算法执行流程可视化（从 MCP/开发者页进入）。 */
    const val ALGORITHM_PIPELINE = "algorithm_pipeline"
}
