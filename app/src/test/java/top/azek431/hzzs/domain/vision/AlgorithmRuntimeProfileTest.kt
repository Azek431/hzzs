package top.azek431.hzzs.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.data.vision.DefaultActiveAlgorithmProvider

class AlgorithmRuntimeProfileTest {
    @Test
    fun builtinProfileValidates() {
        val profile = AlgorithmRuntimeProfile.builtin()
        val result = AlgorithmProfileValidator.validate(profile)
        assertTrue(result.isSuccess)
        assertEquals(AlgorithmRuntimeProfile.BUILTIN_ID, profile.algorithmId)
        assertTrue(profile.isBuiltin)
        assertEquals(SceneId.entries.size, profile.scenes.size)
    }

    @Test
    fun rejectsNaN() {
        val bad = AlgorithmRuntimeProfile.builtin().let { base ->
            base.copy(
                algorithmId = "net.sample.v1",
                isBuiltin = false,
                scenes = base.scenes.mapValues { (_, params) ->
                    params.copy(sceneConfidenceFloor = Float.NaN)
                },
            )
        }
        val result = AlgorithmProfileValidator.validate(bad)
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsInfinity() {
        val bad = AlgorithmRuntimeProfile.builtin().let { base ->
            base.copy(
                algorithmId = "net.sample.v1",
                isBuiltin = false,
                scenes = base.scenes.mapValues { (_, params) ->
                    params.copy(bottleWidthMax = Float.POSITIVE_INFINITY)
                },
            )
        }
        assertTrue(AlgorithmProfileValidator.validate(bad).isFailure)
    }

    @Test
    fun rejectsInvertedRange() {
        val bad = AlgorithmRuntimeProfile.builtin().let { base ->
            base.copy(
                algorithmId = "net.sample.v1",
                isBuiltin = false,
                scenes = base.scenes.mapValues { (_, params) ->
                    params.copy(bottleWidthMin = 0.5f, bottleWidthMax = 0.1f)
                },
            )
        }
        assertTrue(AlgorithmProfileValidator.validate(bad).isFailure)
    }

    @Test
    fun rejectsUnsupportedSchema() {
        runCatching {
            AlgorithmRuntimeProfile(
                algorithmId = "net.sample.v1",
                version = "1.0.0",
                schemaVersion = 99,
                isBuiltin = false,
                scenes = AlgorithmRuntimeProfile.builtin().scenes,
            )
        }.fold(
            onSuccess = { assertTrue("should reject schema", false) },
            onFailure = { assertTrue(it is IllegalArgumentException) },
        )
    }

    @Test
    fun activationIncrementsGenerationAndFallsBackOnError() {
        val provider = DefaultActiveAlgorithmProvider()
        val first = provider.current()
        assertTrue(first.usingBuiltinFallback)

        val valid = AlgorithmRuntimeProfile.builtin().copy(
            algorithmId = "net.tuned.v1",
            version = "1.2.0",
            isBuiltin = false,
        )
        val activated = provider.activate(valid, fallbackToBuiltinOnError = false).getOrThrow()
        assertEquals("net.tuned.v1", activated.profile.algorithmId)
        assertNotEquals(first.generation, activated.generation)
        assertFalse(activated.usingBuiltinFallback)

        val invalid = valid.copy(
            scenes = valid.scenes.mapValues { (_, p) -> p.copy(groundConfidenceMin = Float.NaN) },
        )
        val fallback = provider.activate(invalid, fallbackToBuiltinOnError = true).getOrThrow()
        assertTrue(fallback.usingBuiltinFallback)
        assertEquals(AlgorithmRuntimeProfile.BUILTIN_ID, fallback.profile.algorithmId)
        assertTrue(!fallback.loadError.isNullOrBlank())
        assertNotEquals(activated.generation, fallback.generation)
    }

    @Test
    fun legalNetworkProfileLoads() {
        val tuned = AlgorithmRuntimeProfile.builtin().let { base ->
            base.copy(
                algorithmId = "net.bamboo.tight.v1",
                version = "2026.07.21",
                isBuiltin = false,
                scenes = base.scenes.mapValues { (scene, params) ->
                    if (scene == SceneId.BAMBOO_BOOKSTORE) {
                        params.copy(
                            gapWidthMin = 0.12f,
                            playerConfidenceFloor = 0.40f,
                            sceneConfidenceFloor = 0.80f,
                        )
                    } else {
                        params
                    }
                },
            )
        }
        assertTrue(AlgorithmProfileValidator.validate(tuned).isSuccess)
        val provider = DefaultActiveAlgorithmProvider()
        val activation = provider.activate(tuned).getOrThrow()
        assertEquals("net.bamboo.tight.v1", activation.profile.algorithmId)
        assertEquals(0.12f, activation.profile.params(SceneId.BAMBOO_BOOKSTORE).gapWidthMin)
    }
}
