package top.azek431.hzzs.core.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlgorithmPipelineTraceTest {
    @Before
    fun setUp() {
        AlgorithmPipelineTrace.beginActivationAttempt()
        AlgorithmPipelineTrace.setContext(null, null, null)
    }

    @Test
    fun markAndLastFrameUpdateSnapshot() {
        AlgorithmPipelineTrace.setContext("builtin-hzzs-base-0.1.0", "AUTO", "SEA_SALT_LIVING_ROOM")
        AlgorithmPipelineTrace.markSuccess("resolve", "catalog ok")
        AlgorithmPipelineTrace.markSuccess("profile", "profile loaded")
        AlgorithmPipelineTrace.markSuccess("validate", "validated")
        AlgorithmPipelineTrace.markSuccess("activate", "gen=2")
        AlgorithmPipelineTrace.markSuccess("native", "native ok")
        AlgorithmPipelineTrace.markSuccess("ready", "ready")
        AlgorithmPipelineTrace.updateLastFrame(
            AlgorithmLastFrameSummary(
                epochMs = 1L,
                scene = "SEA_SALT_LIVING_ROOM",
                sceneConfidence = 0.91f,
                hasPlayer = true,
                obstacleCount = 3,
                actionableCount = 2,
                kindHistogram = "SEA_PIT:1,SAND_CASTLE:2",
                processingMs = 12.5f,
                algorithmId = "builtin.hzzs.base",
                algorithmVersion = "0.1.0",
                generation = 2L,
                usingBuiltinFallback = true,
                loadError = null,
                frameError = null,
                disabledObstaclesDropped = false,
            ),
        )
        val snap = AlgorithmPipelineTrace.snapshot()
        assertEquals("builtin-hzzs-base-0.1.0", snap.catalogId)
        assertEquals(AlgorithmStageStatus.SUCCESS, snap.stages.first { it.id == "ready" }.status)
        assertEquals(3, snap.lastFrame?.obstacleCount)
        assertTrue(snap.lastFrame?.kindHistogram?.contains("SEA_PIT") == true)
        val text = AlgorithmPipelineTrace.formatText()
        assertTrue(text.contains("== Stages =="))
        assertTrue(text.contains("builtin.hzzs.base"))
        assertTrue(text.contains("SEA_PIT:1"))
    }

    @Test
    fun failedLastFrameMarksStageFailed() {
        AlgorithmPipelineTrace.updateLastFrame(
            AlgorithmLastFrameSummary(
                epochMs = 2L,
                scene = "BAMBOO_BOOKSTORE",
                sceneConfidence = 0f,
                hasPlayer = false,
                obstacleCount = 0,
                actionableCount = 0,
                kindHistogram = "",
                processingMs = 1f,
                algorithmId = "builtin.hzzs.base",
                algorithmVersion = "0.1.0",
                generation = 1L,
                usingBuiltinFallback = true,
                loadError = null,
                frameError = "Native 视觉库不可用",
                disabledObstaclesDropped = false,
            ),
        )
        val stage = AlgorithmPipelineTrace.snapshot().stages.first { it.id == "lastFrame" }
        assertEquals(AlgorithmStageStatus.FAILED, stage.status)
        assertTrue(stage.detail?.contains("Native") == true)
    }
}
