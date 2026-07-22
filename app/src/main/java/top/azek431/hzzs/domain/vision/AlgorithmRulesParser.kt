package top.azek431.hzzs.domain.vision

import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.PlayerReferenceMode
import top.azek431.hzzs.core.model.SceneConfig
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.VisionThresholds

/**
 * 解析算法包 rules.json（v1 / v2）为运行时 profile 与可选用户阈值推荐。
 *
 * - v1：仅 user 段（旧 thresholds）；engine 全用 builtin 填洞
 * - v2：userThresholds + engineParams；缺省字段用 builtin 同场景覆盖
 * - 单场景包：未声明赛季用 [AlgorithmRuntimeProfile.builtin] 填洞
 */
object AlgorithmRulesParser {
    data class ParsedRules(
        val profile: AlgorithmRuntimeProfile,
        val recommendedScenes: Map<SceneId, SceneConfig>,
        val rulesSchemaVersion: Int,
    )

    fun parse(
        rulesJson: String,
        algorithmId: String,
        version: String,
        supportedScenes: Set<SceneId>,
    ): Result<ParsedRules> = runCatching {
        val root = JSONObject(rulesJson)
        val schema = root.optInt("schemaVersion", -1)
        require(schema == 1 || schema == 2) { "unsupported rules schemaVersion=$schema" }
        val scenesObj = root.getJSONObject("scenes")
        val base = AlgorithmRuntimeProfile.builtin()
        val mergedParams = linkedMapOf<SceneId, SceneAlgorithmParams>()
        val recommended = linkedMapOf<SceneId, SceneConfig>()

        SceneId.entries.forEach { scene ->
            val key = scene.name
            if (!scenesObj.has(key) || scene !in supportedScenes) {
                mergedParams[scene] = base.params(scene)
                return@forEach
            }
            val payload = scenesObj.getJSONObject(key)
            val engineJson = when (schema) {
                2 -> payload.optJSONObject("engineParams")
                else -> null
            }
            val userJson = when (schema) {
                1 -> payload.optJSONObject("thresholds")
                else -> payload.optJSONObject("userThresholds")
                    ?: payload.optJSONObject("thresholds")
            }
            val disabled = payload.optJSONArray("disabledObstacles").toObstacleSet()
            mergedParams[scene] = mergeEngine(base.params(scene), engineJson)
            if (userJson != null || disabled.isNotEmpty()) {
                recommended[scene] = SceneConfig(
                    sceneId = scene,
                    enabled = true,
                    disabledObstacles = disabled,
                    thresholds = mergeUser(VisionThresholds(), userJson),
                )
            }
        }

        val profile = AlgorithmRuntimeProfile(
            algorithmId = algorithmId,
            version = version,
            schemaVersion = AlgorithmRuntimeProfile.SCHEMA_VERSION,
            isBuiltin = false,
            scenes = mergedParams,
        )
        val validated = AlgorithmProfileValidator.validate(profile).getOrThrow()
        ParsedRules(
            profile = validated,
            recommendedScenes = recommended,
            rulesSchemaVersion = schema,
        )
    }

    private fun mergeUser(base: VisionThresholds, json: JSONObject?): VisionThresholds {
        if (json == null) return base
        return base.copy(
            workWidth = json.optInt("workWidth", base.workWidth),
            minimumConfidence = json.optDouble("minimumConfidence", base.minimumConfidence.toDouble()).toFloat(),
            stableFrames = json.optInt("stableFrames", base.stableFrames),
            playerReferenceMode = enumOr(
                json.optString("playerReferenceMode"),
                base.playerReferenceMode,
            ),
            fixedPlayerXRatio = json.optDouble("fixedPlayerXRatio", base.fixedPlayerXRatio.toDouble()).toFloat(),
            behindPlayerMarginRatio = json.optDouble(
                "behindPlayerMarginRatio",
                base.behindPlayerMarginRatio.toDouble(),
            ).toFloat(),
            boundaryTolerancePlayerWidthRatio = json.optDouble(
                "boundaryTolerancePlayerWidthRatio",
                base.boundaryTolerancePlayerWidthRatio.toDouble(),
            ).toFloat(),
        )
    }

    private fun mergeEngine(base: SceneAlgorithmParams, json: JSONObject?): SceneAlgorithmParams {
        if (json == null) return base
        val colorsJson = json.optJSONObject("colors")
        return base.copy(
            sceneConfidenceFloor = json.optFloat("sceneConfidenceFloor", base.sceneConfidenceFloor),
            playerConfidenceFloor = json.optFloat("playerConfidenceFloor", base.playerConfidenceFloor),
            fixedPlayerTop = json.optFloat("fixedPlayerTop", base.fixedPlayerTop),
            fixedPlayerBottom = json.optFloat("fixedPlayerBottom", base.fixedPlayerBottom),
            fixedPlayerWidthDivisor = json.optInt("fixedPlayerWidthDivisor", base.fixedPlayerWidthDivisor),
            fallbackSceneConfidenceMax = json.optFloat(
                "fallbackSceneConfidenceMax",
                base.fallbackSceneConfidenceMax,
            ),
            fallbackMaxDetections = json.optInt("fallbackMaxDetections", base.fallbackMaxDetections),
            groundSearchTop = json.optFloat("groundSearchTop", base.groundSearchTop),
            groundSearchBottom = json.optFloat("groundSearchBottom", base.groundSearchBottom),
            groundConfidenceMin = json.optFloat("groundConfidenceMin", base.groundConfidenceMin),
            bottleWidthMin = json.optFloat("bottleWidthMin", base.bottleWidthMin),
            bottleWidthMax = json.optFloat("bottleWidthMax", base.bottleWidthMax),
            bottleHeightMin = json.optFloat("bottleHeightMin", base.bottleHeightMin),
            bottleHeightMax = json.optFloat("bottleHeightMax", base.bottleHeightMax),
            cakeWidthMin = json.optFloat("cakeWidthMin", base.cakeWidthMin),
            cakeWidthMax = json.optFloat("cakeWidthMax", base.cakeWidthMax),
            cakeHeightMin = json.optFloat("cakeHeightMin", base.cakeHeightMin),
            cakeWideWidthRatio = json.optFloat("cakeWideWidthRatio", base.cakeWideWidthRatio),
            statueWidthMin = json.optFloat("statueWidthMin", base.statueWidthMin),
            statueWidthMax = json.optFloat("statueWidthMax", base.statueWidthMax),
            statueHeightMin = json.optFloat("statueHeightMin", base.statueHeightMin),
            statueHeightMax = json.optFloat("statueHeightMax", base.statueHeightMax),
            gapWidthMin = json.optFloat("gapWidthMin", base.gapWidthMin),
            gapWidthMax = json.optFloat("gapWidthMax", base.gapWidthMax),
            gapHeightMin = json.optFloat("gapHeightMin", base.gapHeightMin),
            gapWideWidthRatio = json.optFloat("gapWideWidthRatio", base.gapWideWidthRatio),
            brushWidthMin = json.optFloat("brushWidthMin", base.brushWidthMin),
            brushWidthMax = json.optFloat("brushWidthMax", base.brushWidthMax),
            brushHeightMin = json.optFloat("brushHeightMin", base.brushHeightMin),
            brushHeightMax = json.optFloat("brushHeightMax", base.brushHeightMax),
            spikeWidthMin = json.optFloat("spikeWidthMin", base.spikeWidthMin),
            spikeWidthMax = json.optFloat("spikeWidthMax", base.spikeWidthMax),
            spikeHeightMin = json.optFloat("spikeHeightMin", base.spikeHeightMin),
            spikeHeightMax = json.optFloat("spikeHeightMax", base.spikeHeightMax),
            colors = mergeColors(base.colors, colorsJson),
        )
    }

    private fun mergeColors(base: SceneColorThresholds, json: JSONObject?): SceneColorThresholds {
        if (json == null) return base
        return base.copy(
            bottleGreenMin = json.optInt("bottleGreenMin", base.bottleGreenMin),
            bottleGreenOverRed = json.optFloat("bottleGreenOverRed", base.bottleGreenOverRed),
            bottleGreenOverBlue = json.optFloat("bottleGreenOverBlue", base.bottleGreenOverBlue),
            bottleRedMax = json.optInt("bottleRedMax", base.bottleRedMax),
            cakeRedMin = json.optInt("cakeRedMin", base.cakeRedMin),
            cakeGreenMin = json.optInt("cakeGreenMin", base.cakeGreenMin),
            cakeBlueMax = json.optInt("cakeBlueMax", base.cakeBlueMax),
            spikeRedMin = json.optInt("spikeRedMin", base.spikeRedMin),
            spikeBlueMin = json.optInt("spikeBlueMin", base.spikeBlueMin),
            spikeRedOverGreen = json.optFloat("spikeRedOverGreen", base.spikeRedOverGreen),
            bambooGreenMin = json.optInt("bambooGreenMin", base.bambooGreenMin),
            bambooGreenOverRed = json.optFloat("bambooGreenOverRed", base.bambooGreenOverRed),
            bambooGreenOverBlue = json.optFloat("bambooGreenOverBlue", base.bambooGreenOverBlue),
            bambooBlueMax = json.optInt("bambooBlueMax", base.bambooBlueMax),
            brushDarkMax = json.optInt("brushDarkMax", base.brushDarkMax),
            statueChromaMax = json.optInt("statueChromaMax", base.statueChromaMax),
        )
    }

    private fun JSONObject.optFloat(key: String, default: Float): Float =
        if (has(key) && !isNull(key)) optDouble(key, default.toDouble()).toFloat() else default

    private fun JSONArray?.toObstacleSet(): Set<ObstacleKind> {
        if (this == null) return emptySet()
        val out = linkedSetOf<ObstacleKind>()
        for (i in 0 until length()) {
            val name = optString(i)
            runCatching { ObstacleKind.valueOf(name) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { java.lang.Enum.valueOf(T::class.java, raw) }.getOrDefault(default)
    }
}
