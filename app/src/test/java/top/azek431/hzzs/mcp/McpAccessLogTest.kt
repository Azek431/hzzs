package top.azek431.hzzs.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpAccessLogTest {
    @Before
    fun setUp() {
        McpAccessLog.clear()
        McpAccessLog.setEnabled(true)
    }

    @Test
    fun recordAssignsMonotonicIdsAndQueryFilters() {
        McpAccessLog.record(
            remote = "127.0.0.1",
            httpMethod = "POST",
            path = "/mcp",
            rpcMethod = "tools/call",
            tool = "get_status",
            httpStatus = 200,
            durationMs = 12,
        )
        McpAccessLog.record(
            remote = "127.0.0.1",
            httpMethod = "POST",
            path = "/mcp",
            rpcMethod = "tools/call",
            tool = "patch_settings",
            httpStatus = 200,
            durationMs = 30,
        )
        val all = McpAccessLog.snapshot(newestFirst = false)
        assertEquals(2, all.size)
        assertTrue(all[1].id > all[0].id)
        val filtered = McpAccessLog.query(query = "patch", newestFirst = true)
        assertEquals(1, filtered.size)
        assertEquals("patch_settings", filtered.single().tool)
        assertTrue(McpAccessLog.formatText().contains("get_status"))
    }

    @Test
    fun disabledDoesNotAppend() {
        McpAccessLog.setEnabled(false)
        McpAccessLog.record(
            remote = "-",
            httpMethod = "GET",
            path = "/health",
            httpStatus = 200,
            durationMs = 1,
        )
        assertEquals(0, McpAccessLog.size())
        McpAccessLog.setEnabled(true)
    }
}
