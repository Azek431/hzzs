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
    fun sinceFiltersAndResetsSeqOnClear() {
        McpEventBus.append(McpEventBus.Type.ANALYSIS_START, JSONObject())
        McpEventBus.append(McpEventBus.Type.ANALYSIS_STOP, JSONObject())
        val first = McpEventBus.snapshot(since = 0L, limit = 10)
        assertEquals(2, first.events.size)
        McpEventBus.clear()
        // clear 重置序列号：新事件从 seq=1 重新开始
        McpEventBus.append(McpEventBus.Type.ERROR, JSONObject().put("msg", "x"))
        val after = McpEventBus.snapshot(since = 0L, limit = 10)
        assertEquals(1, after.events.size)
        assertEquals(1L, after.events[0].seq)
        assertEquals(McpEventBus.Type.ERROR, after.events[0].type)
        assertEquals(1L, after.oldestSeq)
        assertEquals(1L, after.latestSeq)
        assertFalse(after.dropped)
    }

    @Test
    fun ringOverflowDropsOldestAndReportsDropped() {
        // 填满并再写一条，挤掉 seq=1
        repeat(McpEventBus.CAPACITY + 1) { i ->
            McpEventBus.append("t", JSONObject().put("i", i))
        }
        val snap5 = McpEventBus.snapshot(since = 0L, limit = 5)
        assertEquals(5, snap5.events.size)
        // 第 1 条（seq=1）被挤掉；snapshot 返回最早 N 条，因此 events[0] 是 seq=2
        assertEquals(2L, snap5.events[0].seq)
        assertEquals(McpEventBus.CAPACITY, snap5.buffered)
        // since=0 落后于 oldest=2 → dropped=true
        val lag = McpEventBus.snapshot(since = 0L, limit = 1)
        assertEquals(2L, lag.oldestSeq)
        assertTrue("since=0 should report dropped when oldestSeq=2", lag.dropped)
        // since=1 且 oldest=2 → 边界不丢
        val caughtUp = McpEventBus.snapshot(since = 1L, limit = 1)
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
