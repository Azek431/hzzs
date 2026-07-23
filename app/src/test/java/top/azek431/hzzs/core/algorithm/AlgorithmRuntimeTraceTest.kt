package top.azek431.hzzs.core.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AppLogLevel

class AlgorithmRuntimeTraceTest {
    @Before
    fun setUp() {
        AlgorithmRuntimeTrace.resetSession()
        AppLog.clear()
        AppLog.configure(enabled = true, level = AppLogLevel.DEBUG)
    }

    @Test
    fun recordKeepsCapacityAndFormatsText() {
        repeat(AlgorithmRuntimeTrace.CAPACITY + 5) { index ->
            AlgorithmRuntimeTrace.record(
                sampleEntry(
                    seq = index.toLong(),
                    obstacleCount = index % 3,
                    kindHistogram = if (index % 3 == 0) "" else "BOTTLE:${index % 3}",
                ),
                writeAppLog = false,
            )
        }
        val frames = AlgorithmRuntimeTrace.recentFrames()
        assertEquals(AlgorithmRuntimeTrace.CAPACITY, frames.size)
        assertEquals(
            (AlgorithmRuntimeTrace.CAPACITY + 4).toLong(),
            frames.last().analysisSequence,
        )
        val text = AlgorithmRuntimeTrace.formatText()
        assertTrue(text.contains("HZZS algorithm runtime frames"))
        assertTrue(text.contains("dets="))
        assertTrue(text.contains("decision="))
    }

    @Test
    fun changeSignatureTriggersLogWhileSameSignatureThrottles() {
        val base = sampleEntry(seq = 1, obstacleCount = 0, kindHistogram = "")
        AlgorithmRuntimeTrace.record(base, writeAppLog = true)
        val afterFirst = AppLog.snapshot().count { it.tag == "algo.frame" }
        assertEquals(1, afterFirst)

        // 相同状态：在周期内不刷
        repeat(AlgorithmRuntimeTrace.PERIODIC_FRAMES - 2) { index ->
            AlgorithmRuntimeTrace.record(
                base.copy(analysisSequence = (index + 2).toLong(), epochMs = index + 2L),
                writeAppLog = true,
            )
        }
        val mid = AppLog.snapshot().count { it.tag == "algo.frame" }
        assertEquals(1, mid)

        // 状态变化：立即刷
        AlgorithmRuntimeTrace.record(
            sampleEntry(seq = 100, obstacleCount = 2, kindHistogram = "BOTTLE:2"),
            writeAppLog = true,
        )
        val afterChange = AppLog.snapshot().count { it.tag == "algo.frame" }
        assertEquals(2, afterChange)
        assertTrue(AppLog.snapshot().any { it.tag == "algo.det" && it.message.contains("BOTTLE") })
    }

    @Test
    fun detectionFormatIncludesTrackAndBounds() {
        val det = AlgorithmDetectionTrace(
            kind = "BOTTLE",
            confidence = 0.88f,
            left = 0.10f,
            top = 0.20f,
            right = 0.30f,
            bottom = 0.40f,
            avoidance = "JUMP",
            actionable = true,
            diagnosticOnly = false,
            trackId = 7L,
            stableFrames = 3,
        )
        val line = det.formatShort()
        assertTrue(line.contains("BOTTLE@0.88"))
        assertTrue(line.contains("t=7"))
        assertTrue(line.contains("s=3"))
        assertTrue(line.contains("JUMP"))
    }

    @Test
    fun resetSessionClearsRing() {
        AlgorithmRuntimeTrace.record(sampleEntry(seq = 1, obstacleCount = 1, kindHistogram = "A:1"), writeAppLog = false)
        assertEquals(1, AlgorithmRuntimeTrace.recentFrames().size)
        AlgorithmRuntimeTrace.resetSession()
        assertTrue(AlgorithmRuntimeTrace.recentFrames().isEmpty())
        assertFalse(AlgorithmRuntimeTrace.formatText().contains("seq=1"))
    }

    private fun sampleEntry(
        seq: Long,
        obstacleCount: Int,
        kindHistogram: String,
    ): AlgorithmFrameTraceEntry = AlgorithmFrameTraceEntry(
        epochMs = seq,
        analysisSequence = seq,
        scene = "BAMBOO_BOOKSTORE",
        sceneConfidence = 0.8f,
        hasPlayer = true,
        playerConfidence = 0.9f,
        playerBounds = "0.10,0.50-0.20,0.70",
        obstacleCount = obstacleCount,
        actionableCount = obstacleCount,
        kindHistogram = kindHistogram,
        processingMs = 12.5f,
        algorithmId = "builtin.hzzs.base",
        algorithmVersion = "0.1.0",
        generation = 1L,
        usingBuiltinFallback = true,
        frameError = null,
        disabledObstaclesDropped = false,
        detections = if (obstacleCount == 0) {
            emptyList()
        } else {
            listOf(
                AlgorithmDetectionTrace(
                    kind = "BOTTLE",
                    confidence = 0.7f,
                    left = 0.4f,
                    top = 0.5f,
                    right = 0.5f,
                    bottom = 0.7f,
                    avoidance = "JUMP",
                    actionable = true,
                    diagnosticOnly = false,
                    trackId = 1L,
                    stableFrames = 2,
                ),
            )
        },
        trackSummary = if (obstacleCount == 0) null else "t=1:BOTTLE:s=2",
        decision = if (obstacleCount == 0) "skip:no_candidate" else "plan kind=BOTTLE",
    )
}
