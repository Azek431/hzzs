package top.azek431.hzzs.core.preferences

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AutomationConfig
import top.azek431.hzzs.core.model.SceneId

class SettingsSessionTest {
    @Test
    fun discardRestoresBaselineAndClearsPreviewOnce() = runTest {
        val original = AppConfig()
        var effective = original
        var clearCount = 0
        val session = SettingsEditSession(
            original = original,
            onPreview = { effective = it },
            onPersist = {},
            onClearPreview = {
                clearCount += 1
                effective = original
            },
        )

        session.update { it.copy(selectedScene = SceneId.SWEET_FACTORY) }
        assertEquals(SceneId.SWEET_FACTORY, effective.selectedScene)
        assertTrue(session.hasChanges())

        assertEquals(original, session.discard())
        assertEquals(original, effective)
        assertEquals(original, session.current())
        assertFalse(session.hasChanges())
        assertEquals(1, clearCount)

        session.discard()
        assertEquals(1, clearCount)
    }

    @Test
    fun replaceKeepsFullDraftSnapshot() = runTest {
        val original = AppConfig()
        var effective = original
        val session = SettingsEditSession(
            original = original,
            onPreview = { effective = it },
            onPersist = {},
            onClearPreview = { effective = original },
        )

        // 模拟连续 UI 修改后整份草稿写回，而不是只应用最后一个 transform。
        val composed = original.copy(
            selectedScene = SceneId.SWEET_FACTORY,
            overlay = original.overlay.copy(showFps = true),
        )
        session.replace(composed)
        assertEquals(SceneId.SWEET_FACTORY, effective.selectedScene)
        assertTrue(effective.overlay.showFps)
        assertEquals(composed.selectedScene, session.current().selectedScene)
        assertTrue(session.current().overlay.showFps)
    }

    @Test
    fun automationDefaultsToOffAndPersistsOnlyAfterRiskAcceptance() {
        val defaults = ConfigJson.decode(ConfigJson.encode(AppConfig()))
        assertFalse(defaults.automation.enabled)

        val accepted = AppConfig().copy(
            automation = AppConfig().automation.copy(
                enabled = true,
                disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
            ),
        )
        assertTrue(ConfigJson.decode(ConfigJson.encode(accepted)).automation.enabled)
    }

    @Test
    fun seaSaltTriggerDistanceRoundTripsAndValidates() {
        val configured = AppConfig().copy(
            automation = AutomationConfig(
                sweetTriggerDistancePlayerWidths = 1.6f,
                bambooTriggerDistancePlayerWidths = 1.2f,
                seaSaltTriggerDistancePlayerWidths = 1.75f,
            ),
        )
        val decoded = ConfigJson.decode(ConfigJson.encode(configured))
        assertEquals(1.75f, decoded.automation.seaSaltTriggerDistancePlayerWidths, 0.001f)

        val root = JSONObject(ConfigJson.encode(configured))
        assertTrue(root.getJSONObject("automation").has("seaSaltTriggerDistancePlayerWidths"))

        val clamped = AppConfig().copy(
            automation = AutomationConfig(seaSaltTriggerDistancePlayerWidths = 99f),
        ).validated()
        assertEquals(4f, clamped.automation.seaSaltTriggerDistancePlayerWidths, 0.001f)

        val missingField = JSONObject(ConfigJson.encode(AppConfig()))
        missingField.getJSONObject("automation").remove("seaSaltTriggerDistancePlayerWidths")
        val fallback = ConfigJson.decode(missingField.toString())
        assertEquals(1.40f, fallback.automation.seaSaltTriggerDistancePlayerWidths, 0.001f)
    }

    @Test
    fun importedAutomationPackagesAreRestrictedToKnownGameHosts() {
        val imported = JSONObject(ConfigJson.encode(AppConfig())).apply {
            getJSONObject("automation").put(
                "allowedPackages",
                org.json.JSONArray(listOf("com.example.untrusted", "com.smile.gifmaker")),
            )
        }

        val decoded = ConfigJson.decode(imported.toString())
        assertEquals(setOf("com.smile.gifmaker"), decoded.automation.allowedPackages)
        assertTrue(decoded.automation.allowedPackages.all { it in AutomationConfig.DEFAULT_ALLOWED_PACKAGES })
    }

    @Test
    fun importedAutomationWithoutCurrentDisclaimerFailsClosed() {
        val imported = JSONObject(ConfigJson.encode(AppConfig())).apply {
            getJSONObject("automation")
                .put("enabled", true)
                .put("disclaimerAcceptedVersion", 0)
        }.toString()

        assertFalse(ConfigJson.decode(imported).automation.enabled)
    }

    @Test
    fun externalIngestCannotSilentlyEnableAutomationEvenWithDisclaimer() {
        val baseline = AppConfig() // automation off, disclaimer 0
        val malicious = AppConfig(
            automation = AutomationConfig(
                enabled = true,
                disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
            ),
        )
        val hardened = malicious.hardenedForExternalIngest(baseline)
        assertFalse(hardened.automation.enabled)
        assertEquals(0, hardened.automation.disclaimerAcceptedVersion)
    }

    @Test
    fun externalIngestCannotEscalateMcpPermissionOrEnableMcp() {
        val baseline = AppConfig(
            mcp = top.azek431.hzzs.core.model.McpConfig(
                enabled = true,
                permissionLevel = top.azek431.hzzs.core.model.McpPermissionLevel.TRUSTED_SESSION,
                requireAuth = true,
                authToken = "aabbccddeeff00112233445566778899aabbccddeeff0011",
            ),
        )
        val malicious = AppConfig(
            mcp = top.azek431.hzzs.core.model.McpConfig(
                enabled = true,
                permissionLevel = top.azek431.hzzs.core.model.McpPermissionLevel.FULL_ACCESS,
                allowDebugFrames = true,
                requireAuth = false,
                authToken = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            ),
        )
        val hardened = malicious.hardenedForExternalIngest(baseline)
        assertEquals(
            top.azek431.hzzs.core.model.McpPermissionLevel.TRUSTED_SESSION,
            hardened.mcp.permissionLevel,
        )
        assertFalse(hardened.mcp.allowDebugFrames)
        assertTrue(hardened.mcp.requireAuth)
        // 外部不得改写配对令牌
        assertEquals(baseline.mcp.authToken, hardened.mcp.authToken)
    }

    @Test
    fun mcpDefaultIsNoAuthAndTokenPersistedInJson() {
        val defaults = AppConfig()
        assertFalse(defaults.mcp.requireAuth)
        assertEquals("", defaults.mcp.authToken)
        val withToken = defaults.copy(
            mcp = defaults.mcp.copy(
                requireAuth = true,
                authToken = "aabbccddeeff00112233445566778899aabbccddeeff0011",
            ),
        )
        val encoded = ConfigJson.encode(withToken)
        val decoded = ConfigJson.decode(encoded)
        assertTrue(decoded.mcp.requireAuth)
        assertEquals(withToken.mcp.authToken, decoded.mcp.authToken)
        // 缺字段回退产品默认（免鉴权）
        val legacy = ConfigJson.decode("""{"schemaVersion":6,"mcp":{"enabled":false,"port":8765}}""")
        assertFalse(legacy.mcp.requireAuth)
    }

    @Test
    fun externalIngestCannotEscalateCaptureBackend() {
        val baseline = AppConfig(
            captureBackend = top.azek431.hzzs.core.model.CaptureBackend.MEDIA_PROJECTION,
        )
        val malicious = AppConfig(
            captureBackend = top.azek431.hzzs.core.model.CaptureBackend.ROOT,
        )
        val hardened = malicious.hardenedForExternalIngest(baseline)
        assertEquals(
            top.azek431.hzzs.core.model.CaptureBackend.MEDIA_PROJECTION,
            hardened.captureBackend,
        )
    }

    @Test
    fun externalIngestKeepsAutomationWhenBaselineAlreadyEnabled() {
        val baseline = AppConfig(
            automation = AutomationConfig(
                enabled = true,
                disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
            ),
        )
        val next = AppConfig(
            automation = AutomationConfig(
                enabled = true,
                disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION,
                maxActionsPerSecond = 3,
            ),
        )
        val hardened = next.hardenedForExternalIngest(baseline)
        assertTrue(hardened.automation.enabled)
        assertEquals(3, hardened.automation.maxActionsPerSecond)
    }
}
