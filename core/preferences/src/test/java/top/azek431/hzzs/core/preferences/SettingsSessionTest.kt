package top.azek431.hzzs.core.preferences

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.SceneId

class SettingsSessionTest {
    @Test fun discardRestoresBaseline() = runTest {
        val original = AppConfig()
        var preview = original
        val session = SettingsEditSession(original, { preview = it }, {})
        session.update { it.copy(selectedScene = SceneId.BAMBOO_BOOKSTORE) }
        assertEquals(SceneId.BAMBOO_BOOKSTORE, preview.selectedScene)
        session.discard()
        assertEquals(SceneId.SWEET_FACTORY, preview.selectedScene)
    }

    @Test fun exportNeverPersistsAutomationEnabled() {
        val json = ConfigJson.encode(AppConfig().copy(automation = AppConfig().automation.copy(enabled = true)))
        assertFalse(json.contains("\"enabled\": true"))
    }
}
