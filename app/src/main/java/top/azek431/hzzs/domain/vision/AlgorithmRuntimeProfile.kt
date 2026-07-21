package top.azek431.hzzs.domain.vision

import top.azek431.hzzs.core.model.SceneId

/**
 * 声明式视觉算法运行时快照（CC-1）。
 *
 * 第一版算法包只允许调整视觉参数，不得携带可执行代码。
 * 本结构在激活时解析一次并校验，帧循环只读当前 generation 对应的不可变副本。
 *
 * 安全边界：不得包含手势、点击、Root、包名白名单或自动化门禁字段。
 */
data class AlgorithmRuntimeProfile(
    val algorithmId: String,
    val version: String,
    val schemaVersion: Int,
    val isBuiltin: Boolean,
    /** 两赛季独立参数；缺失赛季时由校验器拒绝。 */
    val scenes: Map<SceneId, SceneAlgorithmParams>,
) {
    init {
        require(algorithmId.isNotBlank() && algorithmId.length <= MAX_ID_LEN)
        require(ALGORITHM_ID_REGEX.matches(algorithmId)) {
            "algorithmId 仅允许 [A-Za-z0-9._-] 且长度 1..$MAX_ID_LEN"
        }
        require(version.isNotBlank() && version.length <= MAX_VERSION_LEN)
        require(VERSION_REGEX.matches(version)) {
            "version 仅允许 [A-Za-z0-9._+-] 且长度 1..$MAX_VERSION_LEN"
        }
        require(schemaVersion == SCHEMA_VERSION) {
            "不支持的 schemaVersion=$schemaVersion，当前仅支持 $SCHEMA_VERSION"
        }
        require(scenes.keys.containsAll(SceneId.entries)) {
            "profile 必须覆盖全部 SceneId"
        }
        require(scenes.size == SceneId.entries.size)
    }

    fun params(scene: SceneId): SceneAlgorithmParams = scenes.getValue(scene)

    companion object {
        const val SCHEMA_VERSION = 1
        const val BUILTIN_ID = "builtin.hzzs.v1"
        const val BUILTIN_VERSION = "1.0.0"

        private const val MAX_ID_LEN = 64
        private const val MAX_VERSION_LEN = 32
        private val ALGORITHM_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val VERSION_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$")

        /** 固化当前 C++ 主路径 + 启发式回退的默认行为。 */
        fun builtin(): AlgorithmRuntimeProfile = AlgorithmRuntimeProfile(
            algorithmId = BUILTIN_ID,
            version = BUILTIN_VERSION,
            schemaVersion = SCHEMA_VERSION,
            isBuiltin = true,
            scenes = mapOf(
                SceneId.SWEET_FACTORY to SceneAlgorithmParams.sweetBuiltin(),
                SceneId.BAMBOO_BOOKSTORE to SceneAlgorithmParams.bambooBuiltin(),
            ),
        )
    }
}

/**
 * 单赛季可调视觉参数。所有 Float 必须 finite，范围已在 [validate] 中收紧。
 */
data class SceneAlgorithmParams(
    /** 主路径成功时场景置信度下限（甜甜圈主路径固定置信度亦受此约束）。 */
    val sceneConfidenceFloor: Float,
    /** 竹影：玩家置信度低于此值时 fail-closed。 */
    val playerConfidenceFloor: Float,
    /** 固定玩家参考框在视口归一化坐标中的 top/bottom。 */
    val fixedPlayerTop: Float,
    val fixedPlayerBottom: Float,
    /** 固定玩家宽度约为视口宽度的 1/N（N 限制在合理范围）。 */
    val fixedPlayerWidthDivisor: Int,
    /** 主路径结果过弱时启用启发式回退的场景置信度阈值。 */
    val fallbackSceneConfidenceMax: Float,
    /** 主路径结果过弱时，检测数（含玩家）不超过该值才回退。 */
    val fallbackMaxDetections: Int,
    /** 启发式：地面搜索 y 比例区间。 */
    val groundSearchTop: Float,
    val groundSearchBottom: Float,
    /** 启发式：最低地面置信度。 */
    val groundConfidenceMin: Float,
    /** 障碍尺寸比例窗口（相对视口宽/高）。 */
    val bottleWidthMin: Float,
    val bottleWidthMax: Float,
    val bottleHeightMin: Float,
    val bottleHeightMax: Float,
    val cakeWidthMin: Float,
    val cakeWidthMax: Float,
    val cakeHeightMin: Float,
    val cakeWideWidthRatio: Float,
    val statueWidthMin: Float,
    val statueWidthMax: Float,
    val statueHeightMin: Float,
    val statueHeightMax: Float,
    val gapWidthMin: Float,
    val gapWidthMax: Float,
    val gapHeightMin: Float,
    val gapWideWidthRatio: Float,
    val brushWidthMin: Float,
    val brushWidthMax: Float,
    val brushHeightMin: Float,
    val brushHeightMax: Float,
    val spikeWidthMin: Float,
    val spikeWidthMax: Float,
    val spikeHeightMin: Float,
    val spikeHeightMax: Float,
    /** 颜色通道阈值（0..255）。 */
    val colors: SceneColorThresholds,
) {
    companion object {
        fun sweetBuiltin() = SceneAlgorithmParams(
            sceneConfidenceFloor = 0.92f,
            playerConfidenceFloor = 0.45f,
            fixedPlayerTop = 0.72f,
            fixedPlayerBottom = 0.94f,
            fixedPlayerWidthDivisor = 20,
            fallbackSceneConfidenceMax = 0.20f,
            fallbackMaxDetections = 1,
            groundSearchTop = 0.50f,
            groundSearchBottom = 0.82f,
            groundConfidenceMin = 0.32f,
            bottleWidthMin = 0.028f,
            bottleWidthMax = 0.19f,
            bottleHeightMin = 0.045f,
            bottleHeightMax = 0.28f,
            cakeWidthMin = 0.105f,
            cakeWidthMax = 0.60f,
            cakeHeightMin = 0.10f,
            cakeWideWidthRatio = 0.22f,
            statueWidthMin = 0.05f,
            statueWidthMax = 0.34f,
            statueHeightMin = 0.075f,
            statueHeightMax = 0.35f,
            gapWidthMin = 0.135f,
            gapWidthMax = 0.78f,
            gapHeightMin = 0.11f,
            gapWideWidthRatio = 0.22f,
            brushWidthMin = 0.032f,
            brushWidthMax = 0.23f,
            brushHeightMin = 0.10f,
            brushHeightMax = 0.54f,
            spikeWidthMin = 0.09f,
            spikeWidthMax = 0.42f,
            spikeHeightMin = 0.16f,
            spikeHeightMax = 0.54f,
            colors = SceneColorThresholds.sweetBuiltin(),
        )

        fun bambooBuiltin() = SceneAlgorithmParams(
            sceneConfidenceFloor = 0.82f,
            playerConfidenceFloor = 0.45f,
            fixedPlayerTop = 0.72f,
            fixedPlayerBottom = 0.94f,
            fixedPlayerWidthDivisor = 20,
            fallbackSceneConfidenceMax = 0.20f,
            fallbackMaxDetections = 1,
            groundSearchTop = 0.52f,
            groundSearchBottom = 0.82f,
            groundConfidenceMin = 0.28f,
            bottleWidthMin = 0.028f,
            bottleWidthMax = 0.19f,
            bottleHeightMin = 0.045f,
            bottleHeightMax = 0.28f,
            cakeWidthMin = 0.105f,
            cakeWidthMax = 0.60f,
            cakeHeightMin = 0.10f,
            cakeWideWidthRatio = 0.22f,
            statueWidthMin = 0.05f,
            statueWidthMax = 0.34f,
            statueHeightMin = 0.075f,
            statueHeightMax = 0.35f,
            gapWidthMin = 0.135f,
            gapWidthMax = 0.78f,
            gapHeightMin = 0.11f,
            gapWideWidthRatio = 0.22f,
            brushWidthMin = 0.032f,
            brushWidthMax = 0.23f,
            brushHeightMin = 0.10f,
            brushHeightMax = 0.54f,
            spikeWidthMin = 0.09f,
            spikeWidthMax = 0.42f,
            spikeHeightMin = 0.16f,
            spikeHeightMax = 0.54f,
            colors = SceneColorThresholds.bambooBuiltin(),
        )
    }
}

/** RGB 通道阈值；仅用于启发式颜色判定，不参与自动化门禁。 */
data class SceneColorThresholds(
    val bottleGreenMin: Int,
    val bottleGreenOverRed: Float,
    val bottleGreenOverBlue: Float,
    val bottleRedMax: Int,
    val cakeRedMin: Int,
    val cakeGreenMin: Int,
    val cakeBlueMax: Int,
    val spikeRedMin: Int,
    val spikeBlueMin: Int,
    val spikeRedOverGreen: Float,
    val bambooGreenMin: Int,
    val bambooGreenOverRed: Float,
    val bambooGreenOverBlue: Float,
    val bambooBlueMax: Int,
    val brushDarkMax: Int,
    val statueChromaMax: Int,
) {
    companion object {
        fun sweetBuiltin() = SceneColorThresholds(
            bottleGreenMin = 72,
            bottleGreenOverRed = 1.08f,
            bottleGreenOverBlue = 1.18f,
            bottleRedMax = 170,
            cakeRedMin = 145,
            cakeGreenMin = 92,
            cakeBlueMax = 190,
            spikeRedMin = 150,
            spikeBlueMin = 88,
            spikeRedOverGreen = 1.14f,
            bambooGreenMin = 80,
            bambooGreenOverRed = 0.78f,
            bambooGreenOverBlue = 1.25f,
            bambooBlueMax = 125,
            brushDarkMax = 94,
            statueChromaMax = 48,
        )

        fun bambooBuiltin() = SceneColorThresholds(
            bottleGreenMin = 72,
            bottleGreenOverRed = 1.08f,
            bottleGreenOverBlue = 1.18f,
            bottleRedMax = 170,
            cakeRedMin = 145,
            cakeGreenMin = 92,
            cakeBlueMax = 190,
            spikeRedMin = 150,
            spikeBlueMin = 88,
            spikeRedOverGreen = 1.14f,
            bambooGreenMin = 80,
            bambooGreenOverRed = 0.78f,
            bambooGreenOverBlue = 1.25f,
            bambooBlueMax = 125,
            brushDarkMax = 94,
            statueChromaMax = 48,
        )
    }
}

/**
 * 严格校验算法配置。失败时返回可读错误，调用方必须回退内置算法。
 */
object AlgorithmProfileValidator {
    fun validate(profile: AlgorithmRuntimeProfile): Result<AlgorithmRuntimeProfile> = runCatching {
        // data class init 已覆盖 id/version/schema/scenes 完整性。
        SceneId.entries.forEach { scene ->
            validateScene(scene, profile.params(scene)).getOrThrow()
        }
        profile
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(IllegalArgumentException(it.message ?: "invalid algorithm profile")) },
    )

    fun validateScene(scene: SceneId, params: SceneAlgorithmParams): Result<Unit> = runCatching {
        fun requireFinite(name: String, value: Float) {
            require(value.isFinite()) { "$scene.$name 必须为 finite" }
        }

        fun requireUnit(name: String, value: Float) {
            requireFinite(name, value)
            require(value in 0f..1f) { "$scene.$name 必须在 [0,1]" }
        }

        fun requireRange(name: String, value: Float, min: Float, max: Float) {
            requireFinite(name, value)
            require(value in min..max) { "$scene.$name=$value 超出 [$min,$max]" }
        }

        fun requireOrdered(minName: String, min: Float, maxName: String, max: Float) {
            requireFinite(minName, min)
            requireFinite(maxName, max)
            require(min <= max) { "$scene.$minName ($min) > $maxName ($max)" }
        }

        fun requireChannel(name: String, value: Int) {
            require(value in 0..255) { "$scene.$name=$value 必须在 0..255" }
        }

        requireUnit("sceneConfidenceFloor", params.sceneConfidenceFloor)
        requireUnit("playerConfidenceFloor", params.playerConfidenceFloor)
        requireUnit("fixedPlayerTop", params.fixedPlayerTop)
        requireUnit("fixedPlayerBottom", params.fixedPlayerBottom)
        require(params.fixedPlayerTop < params.fixedPlayerBottom) {
            "$scene.fixedPlayerTop 必须 < fixedPlayerBottom"
        }
        require(params.fixedPlayerWidthDivisor in 8..64) {
            "$scene.fixedPlayerWidthDivisor 必须在 8..64"
        }
        requireUnit("fallbackSceneConfidenceMax", params.fallbackSceneConfidenceMax)
        require(params.fallbackMaxDetections in 0..8) {
            "$scene.fallbackMaxDetections 必须在 0..8"
        }
        requireUnit("groundSearchTop", params.groundSearchTop)
        requireUnit("groundSearchBottom", params.groundSearchBottom)
        require(params.groundSearchTop < params.groundSearchBottom) {
            "$scene.groundSearchTop 必须 < groundSearchBottom"
        }
        requireUnit("groundConfidenceMin", params.groundConfidenceMin)

        requireOrdered("bottleWidthMin", params.bottleWidthMin, "bottleWidthMax", params.bottleWidthMax)
        requireRange("bottleWidthMin", params.bottleWidthMin, 0.001f, 0.5f)
        requireRange("bottleWidthMax", params.bottleWidthMax, 0.01f, 0.8f)
        requireOrdered("bottleHeightMin", params.bottleHeightMin, "bottleHeightMax", params.bottleHeightMax)
        requireRange("bottleHeightMin", params.bottleHeightMin, 0.001f, 0.6f)
        requireRange("bottleHeightMax", params.bottleHeightMax, 0.01f, 0.8f)

        requireOrdered("cakeWidthMin", params.cakeWidthMin, "cakeWidthMax", params.cakeWidthMax)
        requireRange("cakeWidthMin", params.cakeWidthMin, 0.01f, 0.7f)
        requireRange("cakeWidthMax", params.cakeWidthMax, 0.05f, 0.95f)
        requireRange("cakeHeightMin", params.cakeHeightMin, 0.01f, 0.8f)
        requireRange("cakeWideWidthRatio", params.cakeWideWidthRatio, 0.05f, 0.8f)

        requireOrdered("statueWidthMin", params.statueWidthMin, "statueWidthMax", params.statueWidthMax)
        requireRange("statueWidthMin", params.statueWidthMin, 0.01f, 0.5f)
        requireRange("statueWidthMax", params.statueWidthMax, 0.05f, 0.8f)
        requireOrdered("statueHeightMin", params.statueHeightMin, "statueHeightMax", params.statueHeightMax)
        requireRange("statueHeightMin", params.statueHeightMin, 0.01f, 0.6f)
        requireRange("statueHeightMax", params.statueHeightMax, 0.05f, 0.8f)

        requireOrdered("gapWidthMin", params.gapWidthMin, "gapWidthMax", params.gapWidthMax)
        requireRange("gapWidthMin", params.gapWidthMin, 0.01f, 0.8f)
        requireRange("gapWidthMax", params.gapWidthMax, 0.05f, 0.95f)
        requireRange("gapHeightMin", params.gapHeightMin, 0.01f, 0.8f)
        requireRange("gapWideWidthRatio", params.gapWideWidthRatio, 0.05f, 0.8f)

        requireOrdered("brushWidthMin", params.brushWidthMin, "brushWidthMax", params.brushWidthMax)
        requireRange("brushWidthMin", params.brushWidthMin, 0.005f, 0.5f)
        requireRange("brushWidthMax", params.brushWidthMax, 0.02f, 0.8f)
        requireOrdered("brushHeightMin", params.brushHeightMin, "brushHeightMax", params.brushHeightMax)
        requireRange("brushHeightMin", params.brushHeightMin, 0.01f, 0.7f)
        requireRange("brushHeightMax", params.brushHeightMax, 0.05f, 0.9f)

        requireOrdered("spikeWidthMin", params.spikeWidthMin, "spikeWidthMax", params.spikeWidthMax)
        requireRange("spikeWidthMin", params.spikeWidthMin, 0.01f, 0.6f)
        requireRange("spikeWidthMax", params.spikeWidthMax, 0.05f, 0.9f)
        requireOrdered("spikeHeightMin", params.spikeHeightMin, "spikeHeightMax", params.spikeHeightMax)
        requireRange("spikeHeightMin", params.spikeHeightMin, 0.01f, 0.7f)
        requireRange("spikeHeightMax", params.spikeHeightMax, 0.05f, 0.9f)

        val c = params.colors
        requireChannel("bottleGreenMin", c.bottleGreenMin)
        requireChannel("bottleRedMax", c.bottleRedMax)
        requireChannel("cakeRedMin", c.cakeRedMin)
        requireChannel("cakeGreenMin", c.cakeGreenMin)
        requireChannel("cakeBlueMax", c.cakeBlueMax)
        requireChannel("spikeRedMin", c.spikeRedMin)
        requireChannel("spikeBlueMin", c.spikeBlueMin)
        requireChannel("bambooGreenMin", c.bambooGreenMin)
        requireChannel("bambooBlueMax", c.bambooBlueMax)
        requireChannel("brushDarkMax", c.brushDarkMax)
        requireChannel("statueChromaMax", c.statueChromaMax)
        requireRange("bottleGreenOverRed", c.bottleGreenOverRed, 0.5f, 3f)
        requireRange("bottleGreenOverBlue", c.bottleGreenOverBlue, 0.5f, 3f)
        requireRange("spikeRedOverGreen", c.spikeRedOverGreen, 0.5f, 3f)
        requireRange("bambooGreenOverRed", c.bambooGreenOverRed, 0.2f, 3f)
        requireRange("bambooGreenOverBlue", c.bambooGreenOverBlue, 0.5f, 4f)
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.failure(IllegalArgumentException(it.message ?: "invalid scene params")) },
    )
}

/** 当前激活算法的不可变视图。 */
data class AlgorithmActivation(
    val profile: AlgorithmRuntimeProfile,
    val generation: Long,
    val usingBuiltinFallback: Boolean,
    val loadError: String? = null,
) {
    init {
        require(generation > 0L)
        require(loadError == null || loadError.length <= 240)
    }
}

/**
 * 算法激活契约（CC-1）。实现必须：
 * - 激活时解析并校验一次
 * - 失败回退内置
 * - 递增 generation
 * - 不在帧路径解析 JSON
 */
interface ActiveAlgorithmProvider {
    fun current(): AlgorithmActivation

    /**
     * 尝试激活 profile。校验失败时保留旧激活并返回 failure，
     * 或在 [fallbackToBuiltinOnError] 时切换内置并返回 success（带 loadError）。
     */
    fun activate(
        profile: AlgorithmRuntimeProfile,
        fallbackToBuiltinOnError: Boolean = true,
    ): Result<AlgorithmActivation>

    /** 强制切回内置算法。 */
    fun activateBuiltin(reason: String? = null): AlgorithmActivation
}
