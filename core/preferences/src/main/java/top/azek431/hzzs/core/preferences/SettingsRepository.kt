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

private val Context.settingsDataStore by preferencesDataStore(name = "hzzs_settings_v3")

interface SettingsRepository {
    /** Effective configuration, including a non-persistent settings preview. */
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
    @ApplicationContext private val context: Context,
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
     * Best-effort one-time migration from the current public HZZS preference file.
     * The historic auto_action flag is deliberately ignored: an upgrade can never
     * arm automation or make it persistent.
     */
    private suspend fun migrateLegacyOnce(): Unit = migrationMutex.withLock {
        if (context.settingsDataStore.data.first()[legacyMigratedKey] == true) return@withLock
        val legacy = context.getSharedPreferences("hzzs_runtime_v2", Context.MODE_PRIVATE)
        val migrated = if (legacy.all.isEmpty()) {
            null
        } else {
            val mode = legacy.getString("capture_mode", "AUTO").orEmpty().uppercase()
            val algorithm = legacy.getString("vision_algorithm", "").orEmpty().lowercase()
            val viewport = parseLegacyViewport(legacy.getString("viewport", null))
            AppConfig(
                captureBackend = when {
                    "ROOT" in mode -> CaptureBackend.ROOT
                    "ACCESS" in mode -> CaptureBackend.ACCESSIBILITY
                    "MEDIA" in mode -> CaptureBackend.MEDIA_PROJECTION
                    else -> CaptureBackend.AUTO
                },
                selectedScene = if ("bamboo" in algorithm || "竹" in algorithm) {
                    SceneId.BAMBOO_BOOKSTORE
                } else {
                    SceneId.SWEET_FACTORY
                },
                viewport = viewport,
                overlay = OverlayConfig(
                    enabled = legacy.getBoolean("draw_overlay", true),
                    showDiagnostics = legacy.getBoolean("detailed_overlay", true),
                ),
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

/**
 * A settings page owns one edit session. Preview is live and non-persistent;
 * save writes atomically, discard restores the baseline and clears the preview.
 */
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
            if (closed) {
                null
            } else {
                draft.also {
                    draft = baseline
                    closed = true
                }
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
    val l = left.takeIf(Float::isFinite)?.coerceIn(0f, 0.95f) ?: 0f
    val t = top.takeIf(Float::isFinite)?.coerceIn(0f, 0.95f) ?: 0f
    val r = right.takeIf(Float::isFinite)?.coerceIn(0.05f, 1f) ?: 1f
    val b = bottom.takeIf(Float::isFinite)?.coerceIn(0.05f, 1f) ?: 1f
    return if (r - l >= 0.05f && b - t >= 0.05f) ViewportConfig(l, t, r, b) else ViewportConfig()
}

fun AppConfig.validated(): AppConfig {
    val completeScenes = SceneId.entries.associateWith { id ->
        val scene = scenes[id] ?: SceneConfig(id)
        scene.copy(
            sceneId = id,
            thresholds = scene.thresholds.copy(
                workWidth = scene.thresholds.workWidth.coerceIn(160, 720),
                minimumConfidence = scene.thresholds.minimumConfidence.finiteOr(0.72f).coerceIn(0.1f, 1f),
                stableFrames = scene.thresholds.stableFrames.coerceIn(1, 12),
                behindPlayerMarginRatio = scene.thresholds.behindPlayerMarginRatio.finiteOr(0.02f).coerceIn(0f, 0.2f),
                boundaryTolerancePlayerWidthRatio = scene.thresholds.boundaryTolerancePlayerWidthRatio.finiteOr(0.05f).coerceIn(0.01f, 0.25f),
            ),
        )
    }
    val packages = automation.allowedPackages.map(String::trim).filter(String::isNotBlank).toSet()
        .ifEmpty { AutomationConfig().allowedPackages }
    return copy(
        schemaVersion = AppConfig.CURRENT_SCHEMA,
        viewport = viewport.validated(),
        overlay = overlay.copy(
            backgroundAlpha = overlay.backgroundAlpha.finiteOr(0.74f).coerceIn(0.15f, 1f),
            strokeWidthDp = overlay.strokeWidthDp.finiteOr(2f).coerceIn(0.5f, 8f),
            textScale = overlay.textScale.finiteOr(1f).coerceIn(0.75f, 2f),
        ),
        scenes = completeScenes,
        automation = automation.copy(
            allowedPackages = packages,
            maxActionsPerSecond = automation.maxActionsPerSecond.coerceIn(1, 8),
            minimumSceneConfidence = automation.minimumSceneConfidence.finiteOr(0.82f).coerceIn(0.5f, 1f),
            retryLimit = automation.retryLimit.coerceIn(0, 2),
        ),
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

object ConfigJson {
    fun encode(config: AppConfig): String {
        val safe = config.validated()
        return JSONObject().apply {
            put("schemaVersion", safe.schemaVersion)
            put("theme", JSONObject().apply {
                put("mode", safe.theme.mode.name)
                put("preset", safe.theme.preset.name)
                put("customSeed", safe.theme.customSeed)
                put("dynamicColorEnabled", safe.theme.dynamicColorEnabled)
                put("reduceMotion", safe.theme.reduceMotion)
                put("highContrast", safe.theme.highContrast)
            })
            put("overlay", JSONObject().apply {
                put("enabled", safe.overlay.enabled)
                put("theme", safe.overlay.theme.name)
                put("customColor", safe.overlay.customColor)
                put("backgroundAlpha", safe.overlay.backgroundAlpha.toDouble())
                put("blurEnabled", safe.overlay.blurEnabled)
                put("strokeWidthDp", safe.overlay.strokeWidthDp.toDouble())
                put("textScale", safe.overlay.textScale.toDouble())
                put("showConfidence", safe.overlay.showConfidence)
                put("showDiagnostics", safe.overlay.showDiagnostics)
                put("clickThrough", safe.overlay.clickThrough)
                put("compactMode", safe.overlay.compactMode)
            })
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
                        put("sceneId", scene.sceneId.name)
                        put("enabled", scene.enabled)
                        put("workWidth", scene.thresholds.workWidth)
                        put("minimumConfidence", scene.thresholds.minimumConfidence.toDouble())
                        put("stableFrames", scene.thresholds.stableFrames)
                        put("playerFallbackForHudOnly", scene.thresholds.playerFallbackForHudOnly)
                        put("behindPlayerMarginRatio", scene.thresholds.behindPlayerMarginRatio.toDouble())
                        put("boundaryTolerancePlayerWidthRatio", scene.thresholds.boundaryTolerancePlayerWidthRatio.toDouble())
                    })
                }
            })
            put("automation", JSONObject().apply {
                // No persistent enabled field exists. Session arming is runtime-only.
                put("requireSessionArm", safe.automation.requireSessionArm)
                put("allowedPackages", JSONArray(safe.automation.allowedPackages.sorted()))
                put("maxActionsPerSecond", safe.automation.maxActionsPerSecond)
                put("minimumSceneConfidence", safe.automation.minimumSceneConfidence.toDouble())
                put("retryLimit", safe.automation.retryLimit)
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
        val update = root.optJSONObject("update")
        val parsedScenes = mutableMapOf<SceneId, SceneConfig>()
        val arr = root.optJSONArray("scenes") ?: JSONArray()
        repeat(minOf(arr.length(), SceneId.entries.size * 2)) { index ->
            val value = arr.optJSONObject(index) ?: return@repeat
            val id = enumOr(value.optString("sceneId"), SceneId.SWEET_FACTORY)
            parsedScenes[id] = SceneConfig(
                sceneId = id,
                enabled = value.optBoolean("enabled", true),
                thresholds = VisionThresholds(
                    workWidth = value.optInt("workWidth", 320),
                    minimumConfidence = value.optDouble("minimumConfidence", 0.72).toFloat(),
                    stableFrames = value.optInt("stableFrames", 3),
                    playerFallbackForHudOnly = value.optBoolean("playerFallbackForHudOnly", true),
                    behindPlayerMarginRatio = value.optDouble("behindPlayerMarginRatio", 0.02).toFloat(),
                    boundaryTolerancePlayerWidthRatio = value.optDouble("boundaryTolerancePlayerWidthRatio", 0.05).toFloat(),
                ),
            )
        }
        return defaults.copy(
            theme = defaults.theme.copy(
                mode = enumOr(theme?.optString("mode"), defaults.theme.mode),
                preset = enumOr(theme?.optString("preset"), defaults.theme.preset),
                customSeed = theme?.optInt("customSeed", defaults.theme.customSeed) ?: defaults.theme.customSeed,
                dynamicColorEnabled = theme?.optBoolean("dynamicColorEnabled", true) ?: true,
                reduceMotion = theme?.optBoolean("reduceMotion", false) ?: false,
                highContrast = theme?.optBoolean("highContrast", false) ?: false,
            ),
            overlay = defaults.overlay.copy(
                enabled = overlay?.optBoolean("enabled", true) ?: true,
                theme = enumOr(overlay?.optString("theme"), defaults.overlay.theme),
                customColor = overlay?.optInt("customColor", defaults.overlay.customColor) ?: defaults.overlay.customColor,
                backgroundAlpha = overlay?.optDouble("backgroundAlpha", 0.74)?.toFloat() ?: 0.74f,
                blurEnabled = overlay?.optBoolean("blurEnabled", true) ?: true,
                strokeWidthDp = overlay?.optDouble("strokeWidthDp", 2.0)?.toFloat() ?: 2f,
                textScale = overlay?.optDouble("textScale", 1.0)?.toFloat() ?: 1f,
                showConfidence = overlay?.optBoolean("showConfidence", false) ?: false,
                showDiagnostics = overlay?.optBoolean("showDiagnostics", false) ?: false,
                clickThrough = overlay?.optBoolean("clickThrough", true) ?: true,
                compactMode = overlay?.optBoolean("compactMode", true) ?: true,
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
                requireSessionArm = automation?.optBoolean("requireSessionArm", true) ?: true,
                allowedPackages = automation?.optJSONArray("allowedPackages")?.let { packages ->
                    buildSet { repeat(minOf(packages.length(), 32)) { add(packages.optString(it).trim()) } }
                        .filter(String::isNotBlank)
                        .toSet()
                } ?: defaults.automation.allowedPackages,
                maxActionsPerSecond = automation?.optInt("maxActionsPerSecond", 4) ?: 4,
                minimumSceneConfidence = automation?.optDouble("minimumSceneConfidence", 0.82)?.toFloat() ?: 0.82f,
                retryLimit = automation?.optInt("retryLimit", 1) ?: 1,
            ),
            update = defaults.update.copy(
                channel = enumOr(update?.optString("channel"), defaults.update.channel),
                autoCheck = update?.optBoolean("autoCheck", true) ?: true,
                wifiOnly = update?.optBoolean("wifiOnly", true) ?: true,
                ignoredVersionCode = update?.takeIf { it.has("ignoredVersionCode") }?.optLong("ignoredVersionCode"),
            ),
        ).validated()
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private const val MAX_CONFIG_BYTES = 256 * 1024
}

/** Human-readable differences used by the import confirmation dialog. */
fun AppConfig.diff(other: AppConfig): List<String> = buildList {
    if (theme != other.theme) add("外观主题")
    if (overlay != other.overlay) add("悬浮窗")
    if (selectedScene != other.selectedScene) add("主题场景")
    if (captureBackend != other.captureBackend) add("截图方式")
    if (viewport != other.viewport) add("游戏画面区域")
    SceneId.entries.forEach { id -> if (scenes[id] != other.scenes[id]) add("${id.name} 算法参数") }
    if (automation != other.automation) add("自动操作安全参数（不会启用自动操作）")
    if (update != other.update) add("更新设置")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindings {
    @Binds abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
