package top.azek431.hzzs.service.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dumpsys 文本解析纯函数测试（无 shell）。
 */
class ShellForegroundParserTest {

    @Test
    fun parseTopResumedActivity() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
              * Task{abc #1 type=standard A=1000:com.smile.gifmaker}
                mResumedActivity: ActivityRecord{deadbeef u0 com.smile.gifmaker/.MainActivity t1}
        """.trimIndent()
        val snap = ShellForegroundParser.parse(dump)
        assertNotNull(snap)
        assertEquals("com.smile.gifmaker", snap!!.packageName)
        assertTrue(snap.className.contains("MainActivity"))
    }

    @Test
    fun parseMFocusedApp() {
        val dump = "mFocusedApp=ActivityRecord{xyz u0 com.kuaishou.nebula/com.yxcorp.gifshow.HomeActivity t2}"
        val snap = ShellForegroundParser.parse(dump)
        assertNotNull(snap)
        assertEquals("com.kuaishou.nebula", snap!!.packageName)
        assertTrue(snap.className.contains("HomeActivity"))
    }

    @Test
    fun prefersMResumedOverEarlierActivityRecord() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES
              * Hist #1: ActivityRecord{aaa u0 com.old.app/.Old t1}
              mResumedActivity: ActivityRecord{bbb u0 com.smile.gifmaker/.MainActivity t2}
        """.trimIndent()
        val snap = ShellForegroundParser.parse(dump)
        assertNotNull(snap)
        assertEquals("com.smile.gifmaker", snap!!.packageName)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(ShellForegroundParser.parse(""))
        assertNull(ShellForegroundParser.parse("nothing useful here"))
        assertNull(ShellForegroundParser.parse("mResumedActivity: ActivityRecord{bad}"))
    }
}
