package top.azek431.hzzs.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.algorithm.AlgorithmCardStatus
import top.azek431.hzzs.core.algorithm.AlgorithmDownloadSource
import top.azek431.hzzs.core.algorithm.AlgorithmOrigin
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.AlgorithmSignatureState
import top.azek431.hzzs.core.algorithm.label
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmConfig
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.preferences.ConfigJson
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.summary

class SettingsUiLogicTest {
    @Test
    fun algorithmDefaultsToAutoSelection() {
        val defaults = AppConfig()
        assertEquals(AlgorithmSelectionMode.AUTO, defaults.algorithm.selectionMode)
        assertEquals(AlgorithmChannel.STABLE, defaults.algorithm.channel)
        assertFalse(defaults.algorithm.autoDownload)
        assertEquals(UpdateSourcePreference.AUTO, defaults.update.sourcePreference)
    }

    @Test
    fun configJsonRoundTripKeepsAlgorithmAndSourcePreference() {
        val original = AppConfig(
            algorithm = AlgorithmConfig(
                selectionMode = AlgorithmSelectionMode.MANUAL,
                pinnedAlgorithmId = "builtin-bamboo-1.0.0",
                channel = AlgorithmChannel.BETA,
                autoCheck = false,
                autoDownload = true,
            ),
            update = AppConfig().update.copy(
                sourcePreference = UpdateSourcePreference.PREFER_GITHUB,
            ),
        )
        val decoded = ConfigJson.decode(ConfigJson.encode(original))
        assertEquals(AlgorithmSelectionMode.MANUAL, decoded.algorithm.selectionMode)
        assertEquals("builtin-bamboo-1.0.0", decoded.algorithm.pinnedAlgorithmId)
        assertEquals(AlgorithmChannel.BETA, decoded.algorithm.channel)
        assertTrue(decoded.algorithm.autoDownload)
        assertEquals(UpdateSourcePreference.PREFER_GITHUB, decoded.update.sourcePreference)
        assertEquals(AppConfig.CURRENT_SCHEMA, decoded.schemaVersion)
    }

    @Test
    fun legacySchemaWithoutAlgorithmStillDecodes() {
        val legacy = """
            {
              "schemaVersion": 5,
              "selectedScene": "BAMBOO_BOOKSTORE",
              "update": { "channel": "STABLE", "autoCheck": true, "wifiOnly": true }
            }
        """.trimIndent()
        val decoded = ConfigJson.decode(legacy)
        assertEquals(AlgorithmSelectionMode.AUTO, decoded.algorithm.selectionMode)
        assertEquals(UpdateSourcePreference.AUTO, decoded.update.sourcePreference)
    }

    @Test
    fun categorySummariesAreShortAndStable() {
        val config = AppConfig(
            theme = AppConfig().theme.copy(mode = AppThemeMode.AMOLED),
            algorithm = AlgorithmConfig(selectionMode = AlgorithmSelectionMode.AUTO),
        )
        val appearance = SettingsCategory.APPEARANCE.summary(config)
        val network = SettingsCategory.NETWORK.summary(config)
        assertTrue(appearance.contains("纯黑"))
        assertTrue(network.contains("自动选择") || network.contains("应用"))
        assertTrue(SettingsCategory.AUTOMATION.summary(config).contains("关闭"))
    }

    @Test
    fun remoteAlgorithmsSortCompatibleFirstThenVersion() {
        val a = sample(id = "old", version = 100, compatible = true, published = 1)
        val b = sample(id = "new", version = 200, compatible = true, published = 2)
        val c = sample(id = "bad", version = 300, compatible = false, published = 3)
        val sorted = listOf(a, b, c).sortedWith(
            compareByDescending<AlgorithmPackageInfo> { it.isCompatible }
                .thenByDescending { it.versionCode }
                .thenByDescending { it.publishedAtEpochMs },
        )
        assertEquals(listOf("new", "old", "bad"), sorted.map { it.id })
    }

    @Test
    fun statusLabelsStayShort() {
        assertEquals("当前", AlgorithmCardStatus.CURRENT.label())
        assertEquals("可更新", AlgorithmCardStatus.UPDATABLE.label())
        assertEquals("待启用", AlgorithmCardStatus.PENDING_ACTIVATION.label())
        assertEquals("不兼容", AlgorithmCardStatus.INCOMPATIBLE.label())
        assertEquals("官方签名", AlgorithmSignatureState.OFFICIAL.label())
    }

    @Test
    fun selectionModeDisplayNames() {
        assertEquals("自动选择", AlgorithmSelectionMode.AUTO.displayName())
        assertEquals("手动选择", AlgorithmSelectionMode.MANUAL.displayName())
        assertEquals("优先 Gitee", UpdateSourcePreference.PREFER_GITEE.displayName())
    }

    private fun sample(
        id: String,
        version: Long,
        compatible: Boolean,
        published: Long,
    ) = AlgorithmPackageInfo(
        id = id,
        name = id,
        versionName = version.toString(),
        versionCode = version,
        channel = AlgorithmChannel.STABLE,
        summary = "s",
        supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
        minAppVersionCode = 1,
        publishedAtEpochMs = published,
        sizeBytes = 1,
        origin = AlgorithmOrigin.REMOTE,
        signature = AlgorithmSignatureState.OFFICIAL,
        downloadSource = AlgorithmDownloadSource.GITEE,
        isCompatible = compatible,
    )
}
