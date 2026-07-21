/**
 * 设置分类首页。
 *
 * 职责：按 [SettingsCategory] 列出入口与当前草稿摘要；点击打开子页。
 * 数据流：只读 [config]/[algorithmState]；不直接改草稿。
 * 边界：返回本页不丢共享草稿（由模块级 ViewModel 持有）。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.HzzsScrollPage
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.feature.settings.components.SettingsCategoryCard
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.summary

/** 设置首页列表；[selectedRoute] 供宽屏高亮当前分类。 */
@Composable
fun SettingsHomeScreen(
    config: AppConfig,
    algorithmState: AlgorithmCatalogState,
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    selectedRoute: String? = null,
) {
    HzzsScrollPage(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                "分类管理外观、算法、截图与安全选项。子页共享草稿，返回本页不丢失未保存更改。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            HzzsCallout(
                text = "外观与悬浮窗可即时预览；截图、自动操作、MCP、更新与算法选择需「保存并应用」后生效。",
                tone = HzzsCalloutTone.INFO,
            )
        }
        items(SettingsCategory.entries, key = { it.route }) { category ->
            SettingsCategoryCard(
                title = category.title,
                description = category.description,
                summary = category.summary(config, algorithmState),
                icon = category.icon,
                onClick = { onOpen(category) },
                selected = selectedRoute == category.route,
            )
        }
    }
}
