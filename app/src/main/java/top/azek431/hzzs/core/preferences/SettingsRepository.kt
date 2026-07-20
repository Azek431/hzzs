package top.azek431.hzzs.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.model.*
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "hzzs_settings_v5")

/**
 * Single source of truth for application configuration.
 *
 * The repository deliberately separates a non-persistent preview from the saved
 * configuration. Theme and overlay editors can therefore preview immediately,
 * while navigation away from the editor reliably restores the saved baseline.
 */
interface SettingsRepository {
    val config: Flow<AppConfig>
    suspend fun snapshot(): AppConfig
    suspend fun preview(config: AppConfig)
    suspend fun clearPreview()
    suspend fun save(config: AppConfig)
    suspend fun importJson(json: String): AppConfig
    fun exportJson(config: AppConfig): String
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsRepository {
    private val configKey = stringPreferencesKey("config_json")
    private val legacyMigratedKey = booleanPreferencesKey("legacy_migrated")
    private val preview = MutableStateFlow<AppConfig?>(null)
    private val migrationMutex = Mutex()

    private val stored: Flow<AppConfig> = flow {
        migrateLegacyOnce()
        emitAll(context.settingsDataStore.data.map { preferences ->
            preferences[configKey]
                ?.let { raw -> runCatching { ConfigJson.decode(raw) }.getOrElse { AppConfig() } }
                ?: AppConfig()
        })
    }

    override val config: Flow<AppConfig> = combine(stored, preview) { saved, temporary ->
        temporary ?: saved
    }

    override suspend fun snapshot(): AppConfig {
        migrateLegacyOnce()
        return stored.first()
    }

    override suspend fun preview(config: AppConfig) {
        preview.value = config.validated()
    }

    override suspend fun clearPreview() {
        preview.value = null
    }

    override suspend fun save(config: AppConfig) {
        val safe = config.validated()
        context.settingsDataStore.edit { it[configKey] = ConfigJson.encode(safe) }
        preview.value = null
    }

    override suspend fun importJson(json: String): AppConfig = ConfigJson.decode(json).validated()

    override fun exportJson(config: AppConfig): String = ConfigJson.encode(config.validated())

    /**
     * Migrates only low-risk legacy preferences. Root and automatic operation are
     * never enabled by migration because upgrades must not silently escalate power.
     */
    private suspend fun migrateLegacyOnce(): Unit = migrationMutex.withLock {
        if (context.settingsDataStore.data.first()[legacyMigratedKey] == true) return@withLock
        val legacy = context.getSharedPreferences("hzzs_runtime_v2", Context.MODE_PRIVATE)
        val migrated = if (legacy.all.isEmpty()) {
            null
        } else {
            val mode = legacy.getString("capture_mode", "AUTO").orEmpty().uppercase()
            val algorithm = legacy.getString("vision_algorithm", "").orEmpty().lowercase()
            AppConfig(
                captureBackend = when {
                    "ACCESS" in mode -> CaptureBackend.ACCESSIBILITY
                    "MEDIA" in mode -> CaptureBackend.MEDIA_PROJECTION
                    else -> CaptureBackend.AUTO
                },
                selectedScene = if ("bamboo" in algorithm || "竹" in algorithm) {
                    SceneId.BAMBOO_BOOKSTORE
                } else {
                    SceneId.SWEET_FACTORY
                },
                viewport = parseLegacyViewport(legacy.getString("viewport", null)),
                overlay = OverlayConfig(
                    enabled = legacy.getBoolean("draw_overlay", true),
                    showDiagnostics = legacy.getBoolean("detailed_overlay", false),
                ),
                automation = AutomationConfig(enabled = false),
            ).validated()
        }
        context.settingsDataStore.edit { preferences ->
            if (migrated != null && preferences[configKey] == null) {
                preferences[configKey] = ConfigJson.encode(migrated)
            }
            preferences[legacyMigratedKey] = true
        }
    }

    private fun parseLegacyViewport(raw: String?): ViewportConfig {
        val parts = raw?.split(',')?.mapNotNull(String::toFloatOrNull).orEmpty()
        if (parts.size != 4) return ViewportConfig()
        return ViewportConfig(parts[0], parts[1], parts[2], parts[3]).validated()
    }
}

/** Transaction-like settings editor with live preview and explicit commit. */
class SettingsEditSession(
    original: AppConfig,
    private val onPreview: suspend (AppConfig) -> Unit,
    private val onPersist: suspend (AppConfig) -> Unit,
    private val onClearPreview: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val baseline = original.validated()
    private var draft = baseline
    private var closed = false

    suspend fun current(): AppConfig = mutex.withLock { draft }

    suspend fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        val next = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            transform(draft).validated().also { draft = it }
        }
        onPreview(next)
        return next
    }

    suspend fun save(): AppConfig {
        val safe = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            closed = true
            draft.validated()
        }
        runCatching { onPersist(safe) }.onFailure {
            mutex.withLock { closed = false }
        }.getOrThrow()
        return safe
    }

    suspend fun discard(): AppConfig {
        val discardedDraft = mutex.withLock {
            if (closed) null else draft.also {
                draft = baseline
                closed = true
            }
        }
        if (discardedDraft == null) return baseline
        runCatching { onClearPreview() }.onFailure {
            mutex.withLock {
                draft = discardedDraft
                closed = false
            }
        }.getOrThrow()
        return baseline
    }

    suspend fun hasChanges(): Boolean = mutex.withLock { draft != baseline }
}

fun ViewportConfig.validated(): ViewportConfig {
    val l = left.finiteOr(0f).coerceIn(0f, 0.95f)
    val t = top.finiteOr(0f).coerceIn(0f, 0.95f)
    val r = right.finiteOr(1f).coerceIn(0.05f, 1f)
    val b = bottom.finiteOr(1f).coerceIn(0.05f, 1f)
    return if (r - l >= 0.05f && b - t >= 0.05f) {
        ViewportConfig(l, t, r, b)
    } else {
        ViewportConfig()
    }
}

fun AppConfig.validated(): AppConfig {
    val completeScenes = SceneId.entries.associateWith { id ->
        val scene = scenes[id] ?: SceneConfig(id)
        scene.copy(
            sceneId = id,
            disabledObstacles = scene.disabledObstacles.filterTo(mutableSetOf()) { obstacle ->
                when (id) {
                    SceneId.SWEET_FACTORY -> obstacle in setOf(
                        ObstacleKind.POISON_BOTTLE,
                        ObstacleKind.CAKE_STRUCTURE,
                        ObstacleKind.HANGING_SPIKE,
                        ObstacleKind.PIT,
                    )
                    SceneId.BAMBOO_BOOKSTORE -> obstacle in setOf(
                        ObstacleKind.PANDA_STATUE,
                        ObstacleKind.BAMBOO_GAP,
                        ObstacleKind.HANGING_BRUSH,
                        ObstacleKind.PIT,
                    )
                }
            },
            thresholds = scene.thresholds.copy(
                workWidth = scene.thresholds.workWidth.coerceIn(192, 960),
                minimumConfidence = scene.thresholds.minimumConfidence.finiteOr(0.72f).coerceIn(0.1f, 1f),
                stableFrames = scene.thresholds.stableFrames.coerceIn(1, 12),
                fixedPlayerXRatio = scene.thresholds.fixedPlayerXRatio.finiteOr(0.185f).coerceIn(0.05f, 0.45f),
                behindPlayerMarginRatio = scene.thresholds.behindPlayerMarginRatio.finiteOr(0.018f).coerceIn(0f, 0.2f),
                boundaryTolerancePlayerWidthRatio = scene.thresholds.boundaryTolerancePlayerWidthRatio
                    .finiteOr(0.05f).coerceIn(0.01f, 0.25f),
            ),
        )
    }
    val packages = automation.allowedPackages
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
        .intersect(AutomationConfig.DEFAULT_ALLOWED_PACKAGES)
        .ifEmpty { AutomationConfig.DEFAULT_ALLOWED_PACKAGES }
    return copy(
        schemaVersion = AppConfig.CURRENT_SCHEMA,
        theme = theme.copy(
            fontScale = theme.fontScale.finiteOr(1f).coerceIn(0.80f, 1.50f),
            cornerScale = theme.cornerScale.finiteOr(1f).coerceIn(0f, 2f),
            spacingScale = theme.spacingScale.finiteOr(1f).coerceIn(0.75f, 1.50f),
            animationScale = theme.animationScale.finiteOr(1f).coerceIn(0f, 2f),
        ),
        viewport = viewport.validated(),
        overlay = overlay.copy(
            backgroundAlpha = overlay.backgroundAlpha.finiteOr(0.70f).coerceIn(0.10f, 1f),
            scale = overlay.scale.finiteOr(1f).coerceIn(0.60f, 2f),
            strokeWidthDp = overlay.strokeWidthDp.finiteOr(2f).coerceIn(0.5f, 8f),
            textScale = overlay.textScale.finiteOr(1f).coerceIn(0.75f, 2f),
        ),
        scenes = completeScenes,
        automation = automation.copy(
            enabled = automation.enabled &&
                automation.disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION,
            allowedPackages = packages,
            maxActionsPerSecond = automation.maxActionsPerSecond.coerceIn(1, 8),
            minimumSceneConfidence = automation.minimumSceneConfidence.finiteOr(0.82f).coerceIn(0.5f, 1f),
            retryLimit = automation.retryLimit.coerceIn(0, 2),
            disclaimerAcceptedVersion = automation.disclaimerAcceptedVersion.coerceAtLeast(0),
        ),
        mcp = mcp.copy(
            port = mcp.port.coerceIn(1024, 65535),
            bindLocalhostOnly = true,
        ),
        developer = developer.copy(
            frameRateLimit = developer.frameRateLimit.coerceIn(1, 120),
            nativeBenchmarkIterations = developer.nativeBenchmarkIterations.coerceIn(10, 10_000),
        ),
        onboarding = onboarding.copy(
            acceptedDisclaimerVersion = onboarding.acceptedDisclaimerVersion.coerceAtLeast(0),
        ),
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/** Strict, size-limited JSON codec used for backup, import and MCP settings. */
object ConfigJson {
    fun encode(config: AppConfig): String {
        val safe = config.validated()
        return JSONObject().apply {
            put("schemaVersion", safe.schemaVersion)
            put("theme", themeJson(safe.theme))
            put("overlay", overlayJson(safe.overlay))
            put("gameProfile", safe.gameProfile.name)
            put("selectedScene", safe.selectedScene.name)
            put("captureBackend", safe.captureBackend.name)
            put("viewport", JSONObject().apply {
                put("left", safe.viewport.left.toDouble())
                put("top", safe.viewport.top.toDouble())
                put("right", safe.viewport.right.toDouble())
                put("bottom", safe.viewport.bottom.toDouble())
            })
            put("scenes", JSONArray().apply {
                SceneId.entries.forEach { id ->
                    val scene = safe.scenes.getValue(id)
                    put(JSONObject().apply {
                        put("sceneId", id.name)
                        put("enabled", scene.enabled)
                        put("disabledObstacles", JSONArray(scene.disabledObstacles.map { it.name }.sorted()))
                        put("workWidth", scene.thresholds.workWidth)
                        put("minimumConfidence", scene.thresholds.minimumConfidence.toDouble())
                        put("stableFrames", scene.thresholds.stableFrames)
                        put("playerReferenceMode", scene.thresholds.playerReferenceMode.name)
                        put("fixedPlayerXRatio", scene.thresholds.fixedPlayerXRatio.toDouble())
                        put("behindPlayerMarginRatio", scene.thresholds.behindPlayerMarginRatio.toDouble())
                        put("boundaryTolerancePlayerWidthRatio", scene.thresholds.boundaryTolerancePlayerWidthRatio.toDouble())
                    })
                }
            })
            put("automation", JSONObject().apply {
                put("enabled", safe.automation.enabled)
                put("disclaimerAcceptedVersion", safe.automation.disclaimerAcceptedVersion)
                put("requireSessionArm", safe.automation.requireSessionArm)
                put("allowedPackages", JSONArray(safe.automation.allowedPackages.sorted()))
                put("maxActionsPerSecond", safe.automation.maxActionsPerSecond)
                put("minimumSceneConfidence", safe.automation.minimumSceneConfidence.toDouble())
                put("retryLimit", safe.automation.retryLimit)
            })
            put("mcp", JSONObject().apply {
                put("enabled", safe.mcp.enabled)
                put("permissionLevel", safe.mcp.permissionLevel.name)
                put("port", safe.mcp.port)
                put("bindLocalhostOnly", safe.mcp.bindLocalhostOnly)
                put("allowDebugFrames", safe.mcp.allowDebugFrames)
            })
            put("developer", JSONObject().apply {
                put("enabled", safe.developer.enabled)
                safe.developer.forceCaptureBackend?.let { put("forceCaptureBackend", it.name) }
                put("saveDebugFrames", safe.developer.saveDebugFrames)
                put("showCoordinateGrid", safe.developer.showCoordinateGrid)
                put("frameRateLimit", safe.developer.frameRateLimit)
                put("nativeBenchmarkIterations", safe.developer.nativeBenchmarkIterations)
            })
            put("onboarding", JSONObject().apply {
                put("completed", safe.onboarding.completed)
                put("acceptedDisclaimerVersion", safe.onboarding.acceptedDisclaimerVersion)
            })
            put("update", JSONObject().apply {
                put("channel", safe.update.channel.name)
                put("autoCheck", safe.update.autoCheck)
                put("wifiOnly", safe.update.wifiOnly)
                safe.update.ignoredVersionCode?.let { put("ignoredVersionCode", it) }
            })
        }.toString(2)
    }

    fun decode(raw: String): AppConfig {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "配置文件过大" }
        val root = JSONObject(raw)
        val defaults = AppConfig()
        val theme = root.optJSONObject("theme")
        val overlay = root.optJSONObject("overlay")
        val viewport = root.optJSONObject("viewport")
        val automation = root.optJSONObject("automation")
        val mcp = root.optJSONObject("mcp")
        val developer = root.optJSONObject("developer")
        val onboarding = root.optJSONObject("onboarding")
        val update = root.optJSONObject("update")
        val parsedScenes = mutableMapOf<SceneId, SceneConfig>()
        val scenes = root.optJSONArray("scenes") ?: JSONArray()
        repeat(minOf(scenes.length(), SceneId.entries.size * 2)) { index ->
            val value = scenes.optJSONObject(index) ?: return@repeat
            val id = enumOr(value.optString("sceneId"), SceneId.SWEET_FACTORY)
            parsedScenes[id] = SceneConfig(
                sceneId = id,
                enabled = value.optBoolean("enabled", true),
                disabledObstacles = value.optJSONArray("disabledObstacles").toEnumSet(),
                thresholds = VisionThresholds(
                    workWidth = value.optInt("workWidth", 384),
                    minimumConfidence = value.optDouble("minimumConfidence", 0.72).toFloat(),
                    stableFrames = value.optInt("stableFrames", 2),
                    playerReferenceMode = enumOr(
                        value.optString("playerReferenceMode"),
                        PlayerReferenceMode.FIXED_RATIO,
                    ),
                    fixedPlayerXRatio = value.optDouble("fixedPlayerXRatio", 0.185).toFloat(),
                    behindPlayerMarginRatio = value.optDouble("behindPlayerMarginRatio", 0.018).toFloat(),
                    boundaryTolerancePlayerWidthRatio = value
                        .optDouble("boundaryTolerancePlayerWidthRatio", 0.05).toFloat(),
                ),
            )
        }
        return defaults.copy(
            theme = defaults.theme.copy(
                mode = enumOr(theme?.optString("mode"), defaults.theme.mode),
                preset = enumOr(theme?.optString("preset"), defaults.theme.preset),
                customSeed = theme?.optInt("customSeed", defaults.theme.customSeed) ?: defaults.theme.customSeed,
                dynamicColorEnabled = theme?.optBoolean("dynamicColorEnabled", true) ?: true,
                fontScale = theme?.optDouble("fontScale", 1.0)?.toFloat() ?: 1f,
                cornerScale = theme?.optDouble("cornerScale", 1.0)?.toFloat() ?: 1f,
                spacingScale = theme?.optDouble("spacingScale", 1.0)?.toFloat() ?: 1f,
                animationScale = theme?.optDouble("animationScale", 1.0)?.toFloat() ?: 1f,
                reduceMotion = theme?.optBoolean("reduceMotion", false) ?: false,
                highContrast = theme?.optBoolean("highContrast", false) ?: false,
            ),
            overlay = defaults.overlay.copy(
                enabled = overlay?.optBoolean("enabled", true) ?: true,
                style = enumOr(overlay?.optString("style"), defaults.overlay.style),
                theme = enumOr(overlay?.optString("theme"), defaults.overlay.theme),
                customColor = overlay?.optInt("customColor", defaults.overlay.customColor) ?: defaults.overlay.customColor,
                backgroundAlpha = overlay?.optDouble("backgroundAlpha", 0.70)?.toFloat() ?: 0.70f,
                scale = overlay?.optDouble("scale", 1.0)?.toFloat() ?: 1f,
                strokeWidthDp = overlay?.optDouble("strokeWidthDp", 2.0)?.toFloat() ?: 2f,
                textScale = overlay?.optDouble("textScale", 1.0)?.toFloat() ?: 1f,
                orientation = enumOr(overlay?.optString("orientation"), defaults.overlay.orientation),
                showBoxes = overlay?.optBoolean("showBoxes", true) ?: true,
                showText = overlay?.optBoolean("showText", true) ?: true,
                showFps = overlay?.optBoolean("showFps", false) ?: false,
                showConfidence = overlay?.optBoolean("showConfidence", false) ?: false,
                showDiagnostics = overlay?.optBoolean("showDiagnostics", false) ?: false,
                clickThrough = overlay?.optBoolean("clickThrough", true) ?: true,
                snapToEdge = overlay?.optBoolean("snapToEdge", true) ?: true,
                lockPosition = overlay?.optBoolean("lockPosition", false) ?: false,
            ),
            gameProfile = enumOr(root.optString("gameProfile"), defaults.gameProfile),
            selectedScene = enumOr(root.optString("selectedScene"), defaults.selectedScene),
            captureBackend = enumOr(root.optString("captureBackend"), defaults.captureBackend),
            viewport = ViewportConfig(
                left = viewport?.optDouble("left", 0.0)?.toFloat() ?: 0f,
                top = viewport?.optDouble("top", 0.0)?.toFloat() ?: 0f,
                right = viewport?.optDouble("right", 1.0)?.toFloat() ?: 1f,
                bottom = viewport?.optDouble("bottom", 1.0)?.toFloat() ?: 1f,
            ),
            scenes = SceneId.entries.associateWith { parsedScenes[it] ?: SceneConfig(it) },
            automation = defaults.automation.copy(
                enabled = automation?.optBoolean("enabled", false) ?: false,
                disclaimerAcceptedVersion = automation?.optInt("disclaimerAcceptedVersion", 0) ?: 0,
                requireSessionArm = automation?.optBoolean("requireSessionArm", true) ?: true,
                allowedPackages = automation?.optJSONArray("allowedPackages").toStringSet()
                    .ifEmpty { defaults.automation.allowedPackages },
                maxActionsPerSecond = automation?.optInt("maxActionsPerSecond", 4) ?: 4,
                minimumSceneConfidence = automation?.optDouble("minimumSceneConfidence", 0.82)?.toFloat() ?: 0.82f,
                retryLimit = automation?.optInt("retryLimit", 1) ?: 1,
            ),
            mcp = defaults.mcp.copy(
                enabled = mcp?.optBoolean("enabled", false) ?: false,
                permissionLevel = enumOr(mcp?.optString("permissionLevel"), defaults.mcp.permissionLevel),
                port = mcp?.optInt("port", defaults.mcp.port) ?: defaults.mcp.port,
                bindLocalhostOnly = mcp?.optBoolean("bindLocalhostOnly", true) ?: true,
                allowDebugFrames = mcp?.optBoolean("allowDebugFrames", false) ?: false,
            ),
            developer = defaults.developer.copy(
                enabled = developer?.optBoolean("enabled", false) ?: false,
                forceCaptureBackend = developer?.optString("forceCaptureBackend")
                    ?.takeIf(String::isNotBlank)
                    ?.let { raw -> CaptureBackend.entries.firstOrNull { it.name == raw } },
                saveDebugFrames = developer?.optBoolean("saveDebugFrames", false) ?: false,
                showCoordinateGrid = developer?.optBoolean("showCoordinateGrid", false) ?: false,
                frameRateLimit = developer?.optInt("frameRateLimit", 60) ?: 60,
                nativeBenchmarkIterations = developer?.optInt("nativeBenchmarkIterations", 200) ?: 200,
            ),
            onboarding = defaults.onboarding.copy(
                completed = onboarding?.optBoolean("completed", false) ?: false,
                acceptedDisclaimerVersion = onboarding?.optInt("acceptedDisclaimerVersion", 0) ?: 0,
            ),
            update = defaults.update.copy(
                channel = enumOr(update?.optString("channel"), defaults.update.channel),
                autoCheck = update?.optBoolean("autoCheck", true) ?: true,
                wifiOnly = update?.optBoolean("wifiOnly", true) ?: true,
                ignoredVersionCode = update?.takeIf { it.has("ignoredVersionCode") }
                    ?.optLong("ignoredVersionCode"),
            ),
        ).validated()
    }

    private fun themeJson(theme: ThemeConfig) = JSONObject().apply {
        put("mode", theme.mode.name)
        put("preset", theme.preset.name)
        put("customSeed", theme.customSeed)
        put("dynamicColorEnabled", theme.dynamicColorEnabled)
        put("fontScale", theme.fontScale.toDouble())
        put("cornerScale", theme.cornerScale.toDouble())
        put("spacingScale", theme.spacingScale.toDouble())
        put("animationScale", theme.animationScale.toDouble())
        put("reduceMotion", theme.reduceMotion)
        put("highContrast", theme.highContrast)
    }

    private fun overlayJson(overlay: OverlayConfig) = JSONObject().apply {
        put("enabled", overlay.enabled)
        put("style", overlay.style.name)
        put("theme", overlay.theme.name)
        put("customColor", overlay.customColor)
        put("backgroundAlpha", overlay.backgroundAlpha.toDouble())
        put("scale", overlay.scale.toDouble())
        put("strokeWidthDp", overlay.strokeWidthDp.toDouble())
        put("textScale", overlay.textScale.toDouble())
        put("orientation", overlay.orientation.name)
        put("showBoxes", overlay.showBoxes)
        put("showText", overlay.showText)
        put("showFps", overlay.showFps)
        put("showConfidence", overlay.showConfidence)
        put("showDiagnostics", overlay.showDiagnostics)
        put("clickThrough", overlay.clickThrough)
        put("snapToEdge", overlay.snapToEdge)
        put("lockPosition", overlay.lockPosition)
    }

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val array = this@toStringSet ?: return@buildSet
        repeat(minOf(array.length(), 64)) {
            array.optString(it).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private inline fun <reified T : Enum<T>> JSONArray?.toEnumSet(): Set<T> = buildSet {
        val array = this@toEnumSet ?: return@buildSet
        repeat(minOf(array.length(), 64)) {
            enumValues<T>().firstOrNull { value -> value.name == array.optString(it) }?.let(::add)
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private const val MAX_CONFIG_BYTES = 256 * 1024
}

/** Human-readable differences used by import and MCP audit screens. */
fun AppConfig.diff(other: AppConfig): List<String> = buildList {
    if (theme != other.theme) add("外观主题")
    if (overlay != other.overlay) add("悬浮窗")
    if (selectedScene != other.selectedScene) add("赛季")
    if (captureBackend != other.captureBackend) add("截图方式")
    if (viewport != other.viewport) add("游戏画面区域")
    SceneId.entries.forEach { id -> if (scenes[id] != other.scenes[id]) add("${id.name} 识别参数") }
    if (automation != other.automation) add("自动操作")
    if (mcp != other.mcp) add("MCP 服务")
    if (developer != other.developer) add("开发者设置")
    if (update != other.update) add("更新设置")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindings {
    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
