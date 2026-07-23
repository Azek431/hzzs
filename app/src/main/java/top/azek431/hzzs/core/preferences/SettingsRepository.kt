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
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 配置持久化与校验。
 *
 * 职责：
 * - DataStore 读写 [AppConfig]
 * - 内存预览层（可立即作用于主题/悬浮窗）
 * - 导入导出 JSON、旧版 SharedPreferences 安全迁移
 * - 所有写入前执行 [AppConfig.validated]
 *
 * 安全：迁移与导入不得静默开启自动操作 / Root；MCP 强制 loopback。
 *
 * 注意：DataStore 文件名仍为 `hzzs_settings_v5`（历史命名），schema 版本见 [AppConfig.CURRENT_SCHEMA]。
 */
private val Context.settingsDataStore by preferencesDataStore(name = "hzzs_settings_v5")

/**
 * 应用配置的唯一真相源。
 *
 * 设置模块以 [save] 即时落盘；[preview] 仍供首次引导与 MCP 等外部预览路径使用。
 * 生效配置 = preview（若有）否则已保存快照。
 */
interface SettingsRepository {
    /** 当前生效配置（预览优先，否则已保存）。 */
    val config: Flow<AppConfig>

    /** 读取已保存快照（不含预览）。 */
    suspend fun snapshot(): AppConfig

    /** 设置内存预览；不写盘。 */
    suspend fun preview(config: AppConfig)

    /** 丢弃预览，回到已保存配置。 */
    suspend fun clearPreview()

    /** 校验后持久化，并清空预览。 */
    suspend fun save(config: AppConfig)

    /** 解析外部 JSON 并校验；**不**自动 harden。MCP/导入 UI 须再调 [hardenedForExternalIngest]。 */
    suspend fun importJson(json: String): AppConfig

    /** 导出已校验配置的 JSON 文本。 */
    fun exportJson(config: AppConfig): String
}

/**
 * DataStore 实现：单例，进程内共享。
 *
 * 线程：DataStore 自身串行；预览用 [MutableStateFlow] 即时覆盖。
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsRepository {
    private val configKey = stringPreferencesKey("config_json")
    private val legacyMigratedKey = booleanPreferencesKey("legacy_migrated")
    /** 非空时覆盖 stored，供设置页预览。 */
    private val preview = MutableStateFlow<AppConfig?>(null)
    private val migrationMutex = Mutex()

    /** 磁盘配置流；首次收集时触发一次性旧版迁移。 */
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
        val config = stored.first()
        syncLogging(config)
        return config
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
        syncLogging(safe)
        AppLog.i("settings", "config saved schema=${safe.schemaVersion} developer=${safe.developer.enabled}")
    }

    /** 将已保存开发者日志策略同步到 [AppLog]（预览不改日志级别）。 */
    private fun syncLogging(config: AppConfig) {
        AppLog.configure(
            enabled = config.developer.enabled,
            level = config.developer.logLevel,
        )
    }

    override suspend fun importJson(json: String): AppConfig = ConfigJson.decode(json).validated()

    override fun exportJson(config: AppConfig): String = ConfigJson.encode(config.validated())

    /**
     * 一次性迁移旧 SharedPreferences（`hzzs_runtime_v2`）。
     *
     * 仅迁移低风险项（截图后端、赛季、视口、悬浮窗开关）。
     * **永不**通过迁移开启自动操作或 Root，避免升级静默提权。
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

/**
 * 类事务的设置编辑会话：草稿 + 实时预览 + 显式提交/丢弃。
 *
 * 生命周期：
 * 1. 以 [original] 为 baseline 打开
 * 2. [update] / [replace] 改草稿并触发 [onPreview]
 * 3. [save] 持久化并关闭；失败则重新打开会话
 * 4. [discard] 清除预览并关闭
 *
 * 线程：内部 Mutex 保护 draft；回调由调用方保证线程安全。
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

    /** 当前草稿快照。 */
    suspend fun current(): AppConfig = mutex.withLock { draft }

    /**
     * 基于当前草稿做增量变换并预览。
     * 适合单字段修改；连续多字段 debounce 请用 [replace] 提交完整草稿。
     */
    suspend fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        val next = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            transform(draft).validated().also { draft = it }
        }
        onPreview(next)
        return next
    }

    /**
     * 用完整草稿覆盖会话内容。
     * 避免 debounce 只保留最后一个 transform 导致中间字段丢失。
     */
    suspend fun replace(next: AppConfig): AppConfig {
        val safe = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            next.validated().also { draft = it }
        }
        onPreview(safe)
        return safe
    }

    /** 校验并持久化；成功后会话关闭。持久化失败会重新打开以便重试。 */
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

    /** 丢弃草稿、清除预览，恢复 baseline。 */
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

    /** 草稿是否相对 baseline 有变化。 */
    suspend fun hasChanges(): Boolean = mutex.withLock { draft != baseline }
}

/**
 * 清洗视口矩形：finite、落在合法区间，且宽高至少 5%。
 * 非法时回退全屏默认。
 */
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

/** 各赛季算法引擎可识别的障碍集合（与 C++ 三槽参数绑定一致）。 */
fun obstaclesForScene(scene: SceneId): Set<ObstacleKind> = when (scene) {
    SceneId.SWEET_FACTORY -> setOf(
        ObstacleKind.GREEN_BOTTLE,
        ObstacleKind.CAKE_STRUCTURE,
        ObstacleKind.HANGING_SPIKE,
        ObstacleKind.PIT,
    )
    SceneId.BAMBOO_BOOKSTORE -> setOf(
        ObstacleKind.PANDA_STATUE,
        ObstacleKind.BAMBOO_GAP,
        ObstacleKind.HANGING_BRUSH,
        ObstacleKind.PIT,
    )
    SceneId.SEA_SALT_LIVING_ROOM -> setOf(
        ObstacleKind.SAND_CASTLE,
        ObstacleKind.HANGING_ANCHOR,
        ObstacleKind.SEA_PIT,
        ObstacleKind.PIT,
    )
}

/**
 * 将任意 [AppConfig] 清洗为可安全落盘/生效的快照。
 *
 * 关键策略：
 * - 补齐全部赛季配置，并过滤掉不属于该赛季的障碍关闭项
 * - 数值 clamp 到产品允许区间
 * - 自动操作：免责声明版本不足时强制 `enabled=false`
 * - 包名与默认白名单求交
 * - MCP 始终 `bindLocalhostOnly=true`
 * - schema 写回 [AppConfig.CURRENT_SCHEMA]
 *
 * 注意：本函数**不会**单独拦截「已接受免责声明后的 enabled=true」。
 * 外部 JSON / MCP 摄入请再经 [hardenedForExternalIngest]，避免静默开启自动操作或自提 MCP 权限。
 */
fun AppConfig.validated(): AppConfig {
    val completeScenes = SceneId.entries.associateWith { id ->
        val scene = scenes[id] ?: SceneConfig(id)
        scene.copy(
            sceneId = id,
            // 只保留当前赛季合法障碍，防止跨赛季脏数据。
            disabledObstacles = scene.disabledObstacles.filterTo(mutableSetOf()) { obstacle ->
                obstacle in obstaclesForScene(id)
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
    // 用户列表 ∩ 默认白名单；空则回退默认，防止任意包名注入。
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
            // 免责声明未达当前版本时强制关闭，导入也走同一路径。
            enabled = automation.enabled &&
                automation.disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION,
            allowedPackages = packages,
            maxActionsPerSecond = automation.maxActionsPerSecond.coerceIn(1, 8),
            minimumSceneConfidence = automation.minimumSceneConfidence.finiteOr(0.82f).coerceIn(0.5f, 1f),
            retryLimit = automation.retryLimit.coerceIn(0, 2),
            disclaimerAcceptedVersion = automation.disclaimerAcceptedVersion.coerceAtLeast(0),
            bambooExperimentalAutoAction = automation.bambooExperimentalAutoAction,
            sweetTriggerDistancePlayerWidths = automation.sweetTriggerDistancePlayerWidths
                .finiteOr(1.50f)
                .coerceIn(0.5f, 4f),
            bambooTriggerDistancePlayerWidths = automation.bambooTriggerDistancePlayerWidths
                .finiteOr(1.35f)
                .coerceIn(0.5f, 4f),
            seaSaltTriggerDistancePlayerWidths = automation.seaSaltTriggerDistancePlayerWidths
                .finiteOr(1.40f)
                .coerceIn(0.5f, 4f),
        ),
        mcp = mcp.copy(
            port = mcp.port.coerceIn(1024, 65535),
            // 安全不变量：禁止绑定非 loopback。
            bindLocalhostOnly = true,
            // requireAuth 可由用户关闭以便同机客户端免填 Header。
        ),
        developer = developer.copy(
            frameRateLimit = developer.frameRateLimit.coerceIn(1, 120),
            nativeBenchmarkIterations = developer.nativeBenchmarkIterations.coerceIn(10, 10_000),
            logLevel = developer.logLevel,
        ),
        onboarding = onboarding.copy(
            acceptedDisclaimerVersion = onboarding.acceptedDisclaimerVersion.coerceAtLeast(0),
        ),
        algorithm = algorithm.copy(
            pinnedAlgorithmId = algorithm.pinnedAlgorithmId
                ?.trim()
                ?.takeIf { it.length in 1..96 },
        ),
    )
}

/**
 * 外部摄入（配置导入、MCP `save_settings`/`preview_settings`）相对 [baseline] 的安全收敛。
 *
 * 硬规则（对齐 CLAUDE / SECURITY）：
 * - 不得静默把自动操作从关→开；若 baseline 已开，可保留；
 * - 不得自提 MCP `permissionLevel` / 不得静默打开 `mcp.enabled` / `allowDebugFrames`；
 * - 不得静默打开开发者选项或写入 `forceCaptureBackend`（避免升权截图后端）；
 * - 截图后端不得从低权限静默升到 Root/Shizuku/无障碍（保持 baseline 或更低风险）。
 *
 * 调用方应先 [validated] 再 harden，或对本函数返回值再 `validated()`。
 */
fun AppConfig.hardenedForExternalIngest(baseline: AppConfig): AppConfig {
    val base = baseline.validated()
    val candidate = validated()

    val automation = candidate.automation.copy(
        // 外部路径不得静默开启；仅当 baseline 已开时允许保持。
        enabled = candidate.automation.enabled && base.automation.enabled,
        // 外部不得伪造「用户已接受」：免责版本只可 ≤ baseline。
        disclaimerAcceptedVersion = minOf(
            candidate.automation.disclaimerAcceptedVersion,
            base.automation.disclaimerAcceptedVersion,
        ),
        bambooExperimentalAutoAction =
            candidate.automation.bambooExperimentalAutoAction &&
                base.automation.bambooExperimentalAutoAction,
    )

    val mcp = candidate.mcp.copy(
        enabled = candidate.mcp.enabled && base.mcp.enabled,
        // 权限级只允许降级或持平，禁止外部自提。
        permissionLevel = minPermission(base.mcp.permissionLevel, candidate.mcp.permissionLevel),
        allowDebugFrames = candidate.mcp.allowDebugFrames && base.mcp.allowDebugFrames,
        // 外部不得静默关闭鉴权。
        requireAuth = candidate.mcp.requireAuth || base.mcp.requireAuth,
        bindLocalhostOnly = true,
    )

    val developer = candidate.developer.copy(
        enabled = candidate.developer.enabled && base.developer.enabled,
        forceCaptureBackend = if (base.developer.enabled) {
            // 开发者已开时，允许改 force，但仍受运行时 isSupported fail-soft。
            candidate.developer.forceCaptureBackend
        } else {
            null
        },
    )

    val captureBackend = saferCaptureBackend(base.captureBackend, candidate.captureBackend)

    return candidate.copy(
        automation = automation,
        mcp = mcp,
        developer = developer,
        captureBackend = captureBackend,
    ).validated()
}

/** MCP 权限级序：数字越大权限越高。 */
private fun mcpPermissionRank(level: McpPermissionLevel): Int =
    when (level) {
        McpPermissionLevel.READ_ONLY -> 0
        McpPermissionLevel.ASK_EVERY_TIME -> 1
        McpPermissionLevel.TRUSTED_SESSION -> 2
        McpPermissionLevel.FULL_ACCESS -> 3
    }

private fun minPermission(
    baseline: McpPermissionLevel,
    candidate: McpPermissionLevel,
): McpPermissionLevel =
    if (mcpPermissionRank(candidate) <= mcpPermissionRank(baseline)) candidate else baseline

/**
 * 截图后端风险序：AUTO/MP 最低；无障碍中等；Shizuku/Root 最高。
 * 外部摄入不得升到比 baseline 更高风险的后端。
 */
private fun saferCaptureBackend(
    baseline: CaptureBackend,
    candidate: CaptureBackend,
): CaptureBackend {
    fun rank(b: CaptureBackend): Int = when (b) {
        CaptureBackend.AUTO -> 0
        CaptureBackend.MEDIA_PROJECTION -> 1
        CaptureBackend.ACCESSIBILITY -> 2
        CaptureBackend.SHIZUKU -> 3
        CaptureBackend.ROOT -> 4
    }
    return if (rank(candidate) <= rank(baseline)) candidate else baseline
}

/** 非 finite 浮点回退为默认值。 */
private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/**
 * 严格、有体积上限的配置 JSON 编解码。
 *
 * 用于备份、导入与 MCP 设置通道。解码后必经 [AppConfig.validated]。
 * 体积上限 [MAX_CONFIG_BYTES]，防止超大 payload。
 */
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
                put("bambooExperimentalAutoAction", safe.automation.bambooExperimentalAutoAction)
                put("allowedPackages", JSONArray(safe.automation.allowedPackages.sorted()))
                put("maxActionsPerSecond", safe.automation.maxActionsPerSecond)
                put("minimumSceneConfidence", safe.automation.minimumSceneConfidence.toDouble())
                put("retryLimit", safe.automation.retryLimit)
                put(
                    "sweetTriggerDistancePlayerWidths",
                    safe.automation.sweetTriggerDistancePlayerWidths.toDouble(),
                )
                put(
                    "bambooTriggerDistancePlayerWidths",
                    safe.automation.bambooTriggerDistancePlayerWidths.toDouble(),
                )
                put(
                    "seaSaltTriggerDistancePlayerWidths",
                    safe.automation.seaSaltTriggerDistancePlayerWidths.toDouble(),
                )
            })
            put("mcp", JSONObject().apply {
                put("enabled", safe.mcp.enabled)
                put("permissionLevel", safe.mcp.permissionLevel.name)
                put("port", safe.mcp.port)
                put("bindLocalhostOnly", safe.mcp.bindLocalhostOnly)
                put("allowDebugFrames", safe.mcp.allowDebugFrames)
                put("requireAuth", safe.mcp.requireAuth)
            })
            put("developer", JSONObject().apply {
                put("enabled", safe.developer.enabled)
                safe.developer.forceCaptureBackend?.let { put("forceCaptureBackend", it.name) }
                put("saveDebugFrames", safe.developer.saveDebugFrames)
                put("showCoordinateGrid", safe.developer.showCoordinateGrid)
                put("frameRateLimit", safe.developer.frameRateLimit)
                put("nativeBenchmarkIterations", safe.developer.nativeBenchmarkIterations)
                put("logLevel", safe.developer.logLevel.name)
            })
            put("onboarding", JSONObject().apply {
                put("completed", safe.onboarding.completed)
                put("acceptedDisclaimerVersion", safe.onboarding.acceptedDisclaimerVersion)
            })
            put("update", JSONObject().apply {
                put("channel", safe.update.channel.name)
                put("autoCheck", safe.update.autoCheck)
                put("wifiOnly", safe.update.wifiOnly)
                put("sourcePreference", safe.update.sourcePreference.name)
                safe.update.ignoredVersionCode?.let { put("ignoredVersionCode", it) }
            })
            put("algorithm", JSONObject().apply {
                put("selectionMode", safe.algorithm.selectionMode.name)
                safe.algorithm.pinnedAlgorithmId?.let { put("pinnedAlgorithmId", it) }
                put("channel", safe.algorithm.channel.name)
                put("autoCheck", safe.algorithm.autoCheck)
                put("autoDownload", safe.algorithm.autoDownload)
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
        val algorithm = root.optJSONObject("algorithm")
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
                bambooExperimentalAutoAction =
                    automation?.optBoolean("bambooExperimentalAutoAction", false) ?: false,
                allowedPackages = automation?.optJSONArray("allowedPackages").toStringSet()
                    .ifEmpty { defaults.automation.allowedPackages },
                maxActionsPerSecond = automation?.optInt("maxActionsPerSecond", 4) ?: 4,
                minimumSceneConfidence = automation?.optDouble("minimumSceneConfidence", 0.82)?.toFloat() ?: 0.82f,
                retryLimit = automation?.optInt("retryLimit", 1) ?: 1,
                sweetTriggerDistancePlayerWidths =
                    automation?.optDouble("sweetTriggerDistancePlayerWidths", 1.50)?.toFloat() ?: 1.50f,
                bambooTriggerDistancePlayerWidths =
                    automation?.optDouble("bambooTriggerDistancePlayerWidths", 1.35)?.toFloat() ?: 1.35f,
                seaSaltTriggerDistancePlayerWidths =
                    automation?.optDouble("seaSaltTriggerDistancePlayerWidths", 1.40)?.toFloat() ?: 1.40f,
            ),
            mcp = defaults.mcp.copy(
                enabled = mcp?.optBoolean("enabled", false) ?: false,
                permissionLevel = enumOr(mcp?.optString("permissionLevel"), defaults.mcp.permissionLevel),
                port = mcp?.optInt("port", defaults.mcp.port) ?: defaults.mcp.port,
                bindLocalhostOnly = mcp?.optBoolean("bindLocalhostOnly", true) ?: true,
                allowDebugFrames = mcp?.optBoolean("allowDebugFrames", false) ?: false,
                // 缺字段时保持默认 true（旧配置安全默认）；仅显式 false 才关闭。
                requireAuth = mcp?.optBoolean("requireAuth", true) ?: true,
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
                logLevel = developer?.optString("logLevel")
                    ?.takeIf(String::isNotBlank)
                    ?.let { raw -> AppLogLevel.entries.firstOrNull { it.name == raw } }
                    ?: AppLogLevel.INFO,
            ),
            onboarding = defaults.onboarding.copy(
                completed = onboarding?.optBoolean("completed", false) ?: false,
                acceptedDisclaimerVersion = onboarding?.optInt("acceptedDisclaimerVersion", 0) ?: 0,
            ),
            update = defaults.update.copy(
                channel = enumOr(update?.optString("channel"), defaults.update.channel),
                autoCheck = update?.optBoolean("autoCheck", true) ?: true,
                wifiOnly = update?.optBoolean("wifiOnly", true) ?: true,
                sourcePreference = enumOr(
                    update?.optString("sourcePreference"),
                    defaults.update.sourcePreference,
                ),
                ignoredVersionCode = update?.takeIf { it.has("ignoredVersionCode") }
                    ?.optLong("ignoredVersionCode"),
            ),
            algorithm = defaults.algorithm.copy(
                selectionMode = enumOr(
                    algorithm?.optString("selectionMode"),
                    defaults.algorithm.selectionMode,
                ),
                pinnedAlgorithmId = algorithm?.optString("pinnedAlgorithmId")
                    ?.takeIf(String::isNotBlank),
                channel = enumOr(algorithm?.optString("channel"), defaults.algorithm.channel),
                autoCheck = algorithm?.optBoolean("autoCheck", true) ?: true,
                autoDownload = algorithm?.optBoolean("autoDownload", false) ?: false,
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

/**
 * 生成两份配置的人类可读差异标签（中文）。
 * 供导入确认与 MCP 审计界面使用，不返回字段级 diff。
 */
fun AppConfig.diff(other: AppConfig): List<String> = buildList {
    if (theme != other.theme) add("外观主题")
    if (overlay != other.overlay) add("悬浮窗")
    if (selectedScene != other.selectedScene) add("赛季")
    if (captureBackend != other.captureBackend) add("截图方式")
    if (viewport != other.viewport) add("游戏画面区域")
    SceneId.entries.forEach { id ->
        if (scenes[id] != other.scenes[id]) add("${id.displayName()} 识别参数")
    }
    if (automation != other.automation) add("自动操作")
    if (mcp != other.mcp) add("MCP 服务")
    if (developer != other.developer) add("开发者设置")
    if (update != other.update) add("更新设置")
    if (algorithm != other.algorithm) add("算法与识别")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindings {
    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
