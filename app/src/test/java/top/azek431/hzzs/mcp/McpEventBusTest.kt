package top.azek431.hzzs.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpEventBusTest {
    @Before
    fun clearBus() {
        McpEventBus.clear()
    }

    @Test
    fun appendIncrementsSeqAndSnapshotReturnsEarliest() {
        repeat(5) { i ->
            McpEventBus.append(McpEventBus.Type.CONFIG_CHANGE, JSONObject().put("i", i))
        }
        val snap = McpEventBus.snapshot(since = 0L, limit = 3)
        assertEquals(3, snap.events.size)
        assertEquals(1L, snap.events[0].seq)
        assertEquals(2L, snap.events[1].seq)
        assertEquals(3L, snap.events[2].seq)
        assertEquals(3L, snap.nextSince)
        assertFalse(snap.dropped)
    }

    @Test
    fun sinceFiltersAndDoesNotResetOnClear() {
        McpEventBus.append(McpEventBus.Type.ANALYSIS_START, JSONObject())
        McpEventBus.append(McpEventBus.Type.ANALYSIS_STOP, JSONObject())
        val first = McpEventBus.snapshot(since = 0L, limit = 10)
        assertEquals(2, first.events.size)
        val mid = first.events[0].seq
        McpEventBus.clear()
        McpEventBus.append(McpEventBus.Type.ERROR, JSONObject().put("msg", "x"))
        val after = McpEventBus.snapshot(since = mid, limit = 10)
        // clear 不重置 seq：新事件 seq 继续递增
        assertEquals(1, after.events.size)
        assertTrue(after.events[0].seq > mid)
        assertEquals(McpEventBus.Type.ERROR, after.events[0].type)
    }

    @Test
    fun ringOverflowDropsOldestAndReportsDropped() {
        // 填满并再写一条，挤掉 seq=1
        repeat(McpEventBus.CAPACITY + 1) { i ->
            McpEventBus.append("t", JSONObject().put("i", i))
        }
        val snap = McpEventBus.snapshot(since = 0L, limit = 5)
        assertEquals(5, snap.events.size)
        assertEquals(2L, snap.events[0].seq) // seq 1 被挤掉
        assertEquals(McpEventBus.CAPACITY, snap.buffered)
        // since 落后于 oldest：seq 1 已丢
        val lag = McpEventBus.snapshot(since = 0L, limit = 1)
        assertEquals(2L, lag.oldestSeq)
        assertTrue(lag.dropped)
        val caughtUp = McpEventBus.snapshot(since = 1L, limit = 1)
        // since=1 且 oldest=2 → 无缺口
        assertFalse(caughtUp.dropped)
    }

    @Test
    fun dataIsDeepCopied() {
        val mutable = JSONObject().put("k", "v1")
        McpEventBus.append(McpEventBus.Type.CONFIG_CHANGE, mutable)
        mutable.put("k", "mutated")
        val snap = McpEventBus.snapshot(since = 0L, limit = 1)
        assertEquals("v1", snap.events[0].data.getString("k"))
        // toJson 再拷贝
        val json = snap.events[0].toJson()
        json.getJSONObject("data").put("k", "again")
        assertEquals("v1", snap.events[0].data.getString("k"))
    }
}
