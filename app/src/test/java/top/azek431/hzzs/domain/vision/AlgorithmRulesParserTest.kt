package top.azek431.hzzs.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.algorithm.AlgorithmIds
import top.azek431.hzzs.core.model.SceneId

class AlgorithmRulesParserTest {
    @Test
    fun parsesV2DualSectionAndFillsMissingScene() {
        val rules = """
            {
              "schemaVersion": 2,
              "scenes": {
                "BAMBOO_BOOKSTORE": {
                  "userThresholds": {
                    "workWidth": 400,
                    "minimumConfidence": 0.8,
                    "stableFrames": 3,
                    "playerReferenceMode": "FIXED_RATIO",
                    "fixedPlayerXRatio": 0.2
                  },
                  "engineParams": {
                    "sceneConfidenceFloor": 0.77,
                    "playerConfidenceFloor": 0.41,
                    "gapWidthMin": 0.12
                  }
                }
              }
            }
        """.trimIndent()
        val parsed = AlgorithmRulesParser.parse(
            rulesJson = rules,
            algorithmId = AlgorithmIds.runtimeIdForCatalog("official-bamboo-baseline"),
            version = "1.0.0",
            supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
        ).getOrThrow()

        assertEquals(2, parsed.rulesSchemaVersion)
        assertEquals(AlgorithmIds.runtimeIdForCatalog("official-bamboo-baseline"), parsed.profile.algorithmId)
        assertFalse(parsed.profile.isBuiltin)
        val bamboo = parsed.profile.params(SceneId.BAMBOO_BOOKSTORE)
        assertEquals(0.77f, bamboo.sceneConfidenceFloor, 1e-5f)
        assertEquals(0.41f, bamboo.playerConfidenceFloor, 1e-5f)
        assertEquals(0.12f, bamboo.gapWidthMin, 1e-5f)
        // Missing scene filled from builtin.
        val sweet = parsed.profile.params(SceneId.SWEET_FACTORY)
        assertEquals(SceneAlgorithmParams.sweetBuiltin().sceneConfidenceFloor, sweet.sceneConfidenceFloor, 1e-5f)
        val recommended = parsed.recommendedScenes.getValue(SceneId.BAMBOO_BOOKSTORE)
        assertEquals(400, recommended.thresholds.workWidth)
        assertEquals(0.8f, recommended.thresholds.minimumConfidence, 1e-5f)
    }

    @Test
    fun parsesV1AsUserOnly() {
        val rules = """
            {
              "schemaVersion": 1,
              "scenes": {
                "BAMBOO_BOOKSTORE": {
                  "thresholds": {
                    "workWidth": 320,
                    "minimumConfidence": 0.7,
                    "stableFrames": 2,
                    "playerReferenceMode": "CONTINUOUS",
                    "fixedPlayerXRatio": 0.18
                  },
                  "disabledObstacles": ["HANGING_BRUSH"]
                }
              }
            }
        """.trimIndent()
        val parsed = AlgorithmRulesParser.parse(
            rulesJson = rules,
            algorithmId = "pack.demo",
            version = "0.1.0",
            supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
        ).getOrThrow()
        assertEquals(1, parsed.rulesSchemaVersion)
        // Engine untouched (builtin bamboo floors).
        assertEquals(
            SceneAlgorithmParams.bambooBuiltin().sceneConfidenceFloor,
            parsed.profile.params(SceneId.BAMBOO_BOOKSTORE).sceneConfidenceFloor,
            1e-5f,
        )
        val rec = parsed.recommendedScenes.getValue(SceneId.BAMBOO_BOOKSTORE)
        assertEquals(320, rec.thresholds.workWidth)
        assertTrue(rec.disabledObstacles.any { it.name == "HANGING_BRUSH" })
    }

    @Test
    fun parsesSeaSaltMulticolorSearchBand() {
        val rules = """
            {
              "schemaVersion": 2,
              "scenes": {
                "SEA_SALT_LIVING_ROOM": {
                  "engineParams": {
                    "searchRegionTopRatio": 0.438,
                    "searchRegionBottomRatio": 0.881,
                    "multicolorThreshold": 10
                  }
                }
              }
            }
        """.trimIndent()
        val parsed = AlgorithmRulesParser.parse(
            rulesJson = rules,
            algorithmId = AlgorithmIds.runtimeIdForCatalog("sea-salt-living-room-v1"),
            version = "0.1.0",
            supportedScenes = setOf(SceneId.SEA_SALT_LIVING_ROOM),
        ).getOrThrow()
        val sea = parsed.profile.params(SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals(0.438f, sea.searchRegionTopRatio, 1e-4f)
        assertEquals(0.881f, sea.searchRegionBottomRatio, 1e-4f)
        assertEquals(10f, sea.multicolorThreshold, 1e-4f)
        // 未声明赛季仍用 builtin 填洞。
        assertEquals(
            SceneAlgorithmParams.bambooBuiltin().sceneConfidenceFloor,
            parsed.profile.params(SceneId.BAMBOO_BOOKSTORE).sceneConfidenceFloor,
            1e-5f,
        )
    }

    @Test
    fun rejectsNanEngineField() {
        val rules = """
            {
              "schemaVersion": 2,
              "scenes": {
                "BAMBOO_BOOKSTORE": {
                  "engineParams": { "sceneConfidenceFloor": 1.5 }
                }
              }
            }
        """.trimIndent()
        // 超范围字段应由 AlgorithmProfileValidator 拒绝。
        val result = AlgorithmRulesParser.parse(
            rulesJson = rules,
            algorithmId = "pack.bad",
            version = "1.0.0",
            supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
        )
        assertTrue(result.isFailure)
    }
}
