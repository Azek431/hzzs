package top.azek431.hzzs.core.algorithm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.domain.vision.AlgorithmRulesParser
import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已安装算法包磁盘索引。
 *
 * 布局：`filesDir/algorithms/installed/<catalogId>/current/` 含
 * manifest.json、rules.json、profile.json、meta.json。
 * 写入经 staging 目录再 rename，避免半包。
 */
@Singleton
class InstalledAlgorithmStore @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    private val root: File
        get() = File(appContext.filesDir, "algorithms").also { it.mkdirs() }

    private val installedDir: File
        get() = File(root, "installed").also { it.mkdirs() }

    private val stagingDir: File
        get() = File(root, "staging").also { it.mkdirs() }

    private val cache = ConcurrentHashMap<String, InstalledAlgorithmRecord>()

    data class InstalledAlgorithmRecord(
        val catalogId: String,
        val runtimeId: String,
        val version: String,
        val versionCode: Long,
        val displayName: String,
        val supportedScenes: Set<SceneId>,
        val profile: AlgorithmRuntimeProfile,
        val directory: File,
        val installedAtEpochMs: Long,
        val sha256: String? = null,
        /** 作者展示名（manifest.author）；可空。 */
        val author: String? = null,
        /** 一句话说明（manifest.description）；可空。 */
        val summary: String? = null,
        /** 发布通道名 stable/beta；可空。 */
        val channelName: String? = null,
        /**
         * 安装来源标签：
         * - null / network：网络验签安装
         * - [BundledAlgorithmInstaller.ORIGIN_BUNDLED]：APK assets 预装
         */
        val originTag: String? = null,
    )

    fun listInstalled(): List<InstalledAlgorithmRecord> {
        refreshFromDisk()
        return cache.values.sortedByDescending { it.versionCode }
    }

    fun get(catalogId: String): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return cache[catalogId]
    }

    fun getProfile(catalogId: String): AlgorithmRuntimeProfile? =
        if (AlgorithmIds.isBuiltinCatalog(catalogId)) {
            AlgorithmRuntimeProfile.builtin()
        } else {
            get(catalogId)?.profile
        }

    /**
     * 从已解压的包目录安装（staging 内容须含 manifest.json + rules.json）。
     * 不负责网络下载与验签；调用方先完成安全校验（捆绑包除外）。
     */
    fun installFromExtracted(
        extracted: File,
        sha256: String? = null,
        versionCode: Long = 1L,
        originTag: String? = null,
        author: String? = null,
        summary: String? = null,
        channelName: String? = null,
    ): Result<InstalledAlgorithmRecord> = runCatching {
        val manifest = JSONObject(File(extracted, "manifest.json").readText(Charsets.UTF_8))
        val catalogId = manifest.getString("id")
        require(catalogId.isNotBlank() && !catalogId.contains('/') && !catalogId.contains('\\')) {
            "invalid algorithm id"
        }
        val version = manifest.getString("version")
        val displayName = manifest.optString("displayName", catalogId)
        val resolvedAuthor = author ?: manifest.optString("author").takeIf { it.isNotBlank() }
        val resolvedSummary = summary
            ?: manifest.optString("description").takeIf { it.isNotBlank() }
        val resolvedChannel = channelName
            ?: manifest.optString("channel").takeIf { it.isNotBlank() }
        val scenes = manifest.getJSONArray("supportedScenes").toSceneSet()
        val rulesText = File(extracted, "rules.json").readText(Charsets.UTF_8)
        val runtimeId = AlgorithmIds.runtimeIdForCatalog(catalogId)
        val parsed = AlgorithmRulesParser.parse(
            rulesJson = rulesText,
            algorithmId = runtimeId,
            version = version,
            supportedScenes = scenes,
        ).getOrThrow()

        val targetParent = File(installedDir, catalogId).also { it.mkdirs() }
        val staging = File(stagingDir, "$catalogId-${System.nanoTime()}").also { it.mkdirs() }
        try {
            File(extracted, "manifest.json").copyTo(File(staging, "manifest.json"), overwrite = true)
            File(extracted, "rules.json").copyTo(File(staging, "rules.json"), overwrite = true)
            File(extracted, "CHANGELOG.txt").takeIf { it.isFile }?.copyTo(
                File(staging, "CHANGELOG.txt"),
                overwrite = true,
            )
            File(staging, "profile.json").writeText(
                encodeProfileStub(parsed.profile),
                Charsets.UTF_8,
            )
            val installedAt = System.currentTimeMillis()
            File(staging, "meta.json").writeText(
                JSONObject()
                    .put("catalogId", catalogId)
                    .put("runtimeId", runtimeId)
                    .put("version", version)
                    .put("versionCode", versionCode)
                    .put("displayName", displayName)
                    .put("installedAtEpochMs", installedAt)
                    .put("sha256", sha256)
                    .put("author", resolvedAuthor)
                    .put("summary", resolvedSummary)
                    .put("channel", resolvedChannel)
                    .put("originTag", originTag)
                    .put(
                        "supportedScenes",
                        JSONArray().also { arr -> scenes.forEach { arr.put(it.name) } },
                    )
                    .toString(2),
                Charsets.UTF_8,
            )
            val current = File(targetParent, "current")
            if (current.exists()) {
                current.deleteRecursively()
            }
            require(staging.renameTo(current) || staging.copyRecursively(current, overwrite = true)) {
                "无法晋升安装目录"
            }
            if (staging.exists()) staging.deleteRecursively()
            val record = InstalledAlgorithmRecord(
                catalogId = catalogId,
                runtimeId = runtimeId,
                version = version,
                versionCode = versionCode,
                displayName = displayName,
                supportedScenes = scenes,
                profile = parsed.profile,
                directory = current,
                installedAtEpochMs = installedAt,
                sha256 = sha256,
                author = resolvedAuthor,
                summary = resolvedSummary,
                channelName = resolvedChannel,
                originTag = originTag,
            )
            cache[catalogId] = record
            record
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun uninstall(catalogId: String): Boolean {
        if (AlgorithmIds.isBuiltinCatalog(catalogId)) return false
        cache.remove(catalogId)
        val dir = File(installedDir, catalogId)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    private fun refreshFromDisk() {
        installedDir.listFiles()?.forEach { idDir ->
            if (!idDir.isDirectory) return@forEach
            val catalogId = idDir.name
            if (cache.containsKey(catalogId)) return@forEach
            val current = File(idDir, "current")
            if (!current.isDirectory) return@forEach
            runCatching { loadRecord(catalogId, current) }.getOrNull()?.let { cache[catalogId] = it }
        }
    }

    private fun loadRecord(catalogId: String, current: File): InstalledAlgorithmRecord {
        val meta = JSONObject(File(current, "meta.json").readText(Charsets.UTF_8))
        val rulesText = File(current, "rules.json").readText(Charsets.UTF_8)
        val runtimeId = meta.optString("runtimeId", AlgorithmIds.runtimeIdForCatalog(catalogId))
        val version = meta.getString("version")
        val scenes = meta.optJSONArray("supportedScenes").toSceneSet()
            .ifEmpty {
                JSONObject(File(current, "manifest.json").readText(Charsets.UTF_8))
                    .getJSONArray("supportedScenes")
                    .toSceneSet()
            }
        val profile = AlgorithmRulesParser.parse(
            rulesJson = rulesText,
            algorithmId = runtimeId,
            version = version,
            supportedScenes = scenes,
        ).getOrThrow().profile
        val manifestAuthor = runCatching {
            JSONObject(File(current, "manifest.json").readText(Charsets.UTF_8))
                .optString("author")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
        val manifestSummary = runCatching {
            JSONObject(File(current, "manifest.json").readText(Charsets.UTF_8))
                .optString("description")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
        return InstalledAlgorithmRecord(
            catalogId = catalogId,
            runtimeId = runtimeId,
            version = version,
            versionCode = meta.optLong("versionCode", 1L),
            displayName = meta.optString("displayName", catalogId),
            supportedScenes = scenes,
            profile = profile,
            directory = current,
            installedAtEpochMs = meta.optLong("installedAtEpochMs", 0L),
            sha256 = meta.optString("sha256").takeIf { it.isNotBlank() },
            author = meta.optString("author").takeIf { it.isNotBlank() } ?: manifestAuthor,
            summary = meta.optString("summary").takeIf { it.isNotBlank() } ?: manifestSummary,
            channelName = meta.optString("channel").takeIf { it.isNotBlank() },
            originTag = meta.optString("originTag").takeIf { it.isNotBlank() },
        )
    }

    /** 轻量审计快照：只写 id/version，完整参数以 rules.json 为准。 */
    private fun encodeProfileStub(profile: AlgorithmRuntimeProfile): String =
        JSONObject()
            .put("algorithmId", profile.algorithmId)
            .put("version", profile.version)
            .put("schemaVersion", profile.schemaVersion)
            .put("isBuiltin", profile.isBuiltin)
            .toString(2)

    private fun JSONArray?.toSceneSet(): Set<SceneId> {
        if (this == null) return emptySet()
        val out = linkedSetOf<SceneId>()
        for (i in 0 until length()) {
            runCatching { SceneId.valueOf(getString(i)) }.getOrNull()?.let(out::add)
        }
        return out
    }
}
