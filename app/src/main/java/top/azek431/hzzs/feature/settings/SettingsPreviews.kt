/**
 * 设置相关 Compose Preview 样例。
 *
 * 职责：为首页/分类卡/算法卡提供静态假数据预览；不接入真实 ViewModel 或仓库。
 * 边界：仅设计时预览，不影响运行时配置与权限型能力。
 */
package top.azek431.hzzs.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogState
import top.azek431.hzzs.core.algorithm.AlgorithmDownloadSource
import top.azek431.hzzs.core.algorithm.AlgorithmOrigin
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.AlgorithmSignatureState
import top.azek431.hzzs.core.designsystem.HzzsTheme
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ThemeConfig
import top.azek431.hzzs.feature.settings.components.AlgorithmCard
import top.azek431.hzzs.feature.settings.components.SettingsCategoryCard
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.summary
import top.azek431.hzzs.feature.settings.screens.SettingsHomeScreen
import top.azek431.hzzs.core.algorithm.AlgorithmCardStatus

@Preview(name = "Settings Home Light", showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun PreviewSettingsHomeLight() {
    HzzsTheme(ThemeConfig(mode = AppThemeMode.LIGHT)) {
        Surface {
            SettingsHomeScreen(
                config = AppConfig(),
                algorithmState = previewAlgorithmState(),
                onOpen = {},
            )
        }
    }
}

@Preview(name = "Settings Home AMOLED", showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun PreviewSettingsHomeAmoled() {
    HzzsTheme(ThemeConfig(mode = AppThemeMode.AMOLED)) {
        Surface {
            SettingsHomeScreen(
                config = AppConfig(),
                algorithmState = previewAlgorithmState(),
                onOpen = {},
            )
        }
    }
}

@Preview(name = "Category Card", showBackground = true, widthDp = 390)
@Composable
private fun PreviewCategoryCard() {
    HzzsTheme(ThemeConfig()) {
        Surface {
            SettingsCategoryCard(
                title = stringResource(SettingsCategory.ALGORITHM.titleRes),
                description = stringResource(SettingsCategory.ALGORITHM.descriptionRes),
                summary = SettingsCategory.ALGORITHM.summary(AppConfig(), previewAlgorithmState()),
                icon = SettingsCategory.ALGORITHM.icon,
                onClick = {},
            )
        }
    }
}

@Preview(name = "Algorithm Card", showBackground = true, widthDp = 390)
@Composable
private fun PreviewAlgorithmCard() {
    HzzsTheme(ThemeConfig()) {
        Surface {
            AlgorithmCard(
                info = previewPackage(),
                status = AlgorithmCardStatus.LATEST,
                download = null,
                manualMode = false,
                onDownload = {},
                onUpdate = {},
                onSelect = {},
                onDetails = {},
                onCancelDownload = {},
            )
        }
    }
}

@Preview(name = "Large Font Home", showBackground = true, widthDp = 390, heightDp = 800, fontScale = 1.5f)
@Composable
private fun PreviewSettingsHomeLargeFont() {
    HzzsTheme(ThemeConfig(fontScale = 1.5f)) {
        Surface {
            SettingsHomeScreen(
                config = AppConfig(),
                algorithmState = previewAlgorithmState(),
                onOpen = {},
            )
        }
    }
}

private fun previewAlgorithmState() = AlgorithmCatalogState(
    active = previewPackage(),
    installed = listOf(previewPackage()),
)

private fun previewPackage() = AlgorithmPackageInfo(
    id = "builtin-hzzs-base-0.1.0",
    name = "竹影书屋内置算法",
    versionName = "0.1.0",
    versionCode = 100,
    channel = AlgorithmChannel.STABLE,
    summary = "随应用分发的默认竹影识别引擎。",
    supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
    minAppVersionCode = 1,
    publishedAtEpochMs = 1_750_000_000_000L,
    sizeBytes = 0,
    origin = AlgorithmOrigin.BUILTIN,
    signature = AlgorithmSignatureState.OFFICIAL,
    downloadSource = AlgorithmDownloadSource.BUILTIN,
    isBuiltin = true,
    isInstalled = true,
)
