package top.azek431.hzzs.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode

/**
 * 纯 JVM 测试：经 [McpProfileStore] 测试构造注入临时 filesDir，不依赖 Robolectric。
 */
class McpProfileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: McpProfileStore

    @Before
    fun setUp() {
        store = McpProfileStore(tmp.root)
    }

    @Test
    fun saveLoadRoundTripUnwrapsConfig() = runBlocking {
        val cfg = AppConfig().copy(theme = AppConfig().theme.copy(mode = AppThemeMode.DARK))
        val meta = store.save("demo", "desc", cfg)
        assertEquals("demo", meta.name)
        assertEquals("desc", meta.description)
        val loaded = store.load("demo")
        assertEquals(AppThemeMode.DARK, loaded.theme.mode)
        val raw = tmp.root.resolve("mcp-profiles/demo.json").readText()
        assertTrue(raw.contains("\"config\""))
        assertTrue(raw.contains("\"createdAtEpochMs\""))
    }

    @Test
    fun overwritePreservesCreatedAt() = runBlocking {
        val first = store.save("keep", "a", AppConfig())
        Thread.sleep(5)
        val second = store.save("keep", "b", AppConfig())
        assertEquals(first.createdAtEpochMs, second.createdAtEpochMs)
        assertTrue(second.updatedAtEpochMs >= first.updatedAtEpochMs)
        assertEquals("b", store.list().single { it.name == "keep" }.description)
    }

    @Test
    fun deleteOnlyTrueWhenExisted() = runBlocking {
        assertFalse(store.delete("missing"))
        store.save("x", "", AppConfig())
        assertTrue(store.delete("x"))
        assertFalse(store.delete("x"))
    }

    @Test
    fun rejectsInvalidName() = runBlocking {
        try {
            store.save("../evil", "", AppConfig())
            assertTrue("should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("profile 名"))
        }
    }

    @Test
    fun capacityLimitRejectsNewWithoutEvict() = runBlocking {
        repeat(32) { i ->
            store.save("p$i", "", AppConfig())
        }
        assertEquals(32, store.count())
        try {
            store.save("overflow", "", AppConfig())
            assertTrue("should reject 33rd", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("上限"))
        }
        store.save("p0", "updated", AppConfig())
        assertEquals(32, store.count())
    }
}
