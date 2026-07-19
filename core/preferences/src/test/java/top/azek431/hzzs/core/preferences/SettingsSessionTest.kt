package top.azek431.hzzs.core.preferences

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
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

        session.update { it.copy(selectedScene = SceneId.BAMBOO_BOOKSTORE) }
        assertEquals(SceneId.BAMBOO_BOOKSTORE, effective.selectedScene)
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
    fun exportNeverPersistsAutomationEnabled() {
        val automation = JSONObject(ConfigJson.encode(AppConfig())).getJSONObject("automation")

        assertFalse(automation.has("enabled"))
        assertTrue(automation.getBoolean("requireSessionArm"))
    }

    @Test
    fun legacyAutomationEnabledIsIgnoredDuringRoundTrip() {
        val legacyJson = JSONObject(ConfigJson.encode(AppConfig())).apply {
            getJSONObject("automation").put("enabled", true)
        }.toString()

        val roundTripped = JSONObject(ConfigJson.encode(ConfigJson.decode(legacyJson)))
        assertFalse(roundTripped.getJSONObject("automation").has("enabled"))
    }
}
