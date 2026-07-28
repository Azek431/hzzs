package top.azek431.hzzs.core.algorithm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile
import top.azek431.hzzs.domain.vision.AlgorithmRulesParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已安装算法包磁盘索引。
 *
 * 布局 v2（多版本支持）：
 * filesDir/algorithms/installed/<catalogId>/
 *   ├── current.json                { "versionCode": 123456, ... }
 *   ├── versions/
 *   │   ├── 123456/                 (versionCode 目录)
 *   │   │   ├── manifest.json
 *   │   │   ├── rules.json
 *   │   │   ├── profile.json
 *   │   │   ├── meta.json
 *   │   │   └── CHANGELOG.txt (可选)
 *   │   └── 987654/
 *   │       ├── ...
 *   │   └── ...
 *   └── staging/                  (临时目录，安装时临时使用)
 *
 * 兼容旧格式：若 `current.json` 不存在但 `current/` 目录存在，视为旧版，首次使用时自动迁移。
 */
@Singleton
class InstalledAlgorithmStore @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {

    private val root: File
        get() = File(appContext.filesDir, "algorithms").also { it.mkdirs() }

    private val installedDir: File
        get() = File(root, "installed").also { it.mkdirs() }

    /** current.json 指向的当前版本，按 catalogId 索引。 */
    private val cache = ConcurrentHashMap<String, InstalledAlgorithmRecord>()
    /** 全部已安装版本，key 为 `catalogId@versionCode`。 */
    private val allVersions = ConcurrentHashMap<String, InstalledAlgorithmRecord>()

    /** 已扫描并迁移过的旧版目录缓存，避免每次启动重复迁移。 */
    private var legacyMigrated: Boolean = false

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

    /** 获取指定 catalogId 下的所有版本，按 versionCode 降序排列。 */
    fun listAllVersions(catalogId: String): List<InstalledAlgorithmRecord> {
        refreshFromDisk()
        return allVersions.values
            .filter { it.catalogId == catalogId }
            .sortedByDescending { it.versionCode }
    }

    /** 获取当前激活版本（指向 current.json 中记录的版本）。 */
    fun getCurrentVersion(catalogId: String): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return cache[catalogId]
    }

    /** 获取每个 catalogId 的 current.json 指向版本。 */
    fun listInstalled(): List<InstalledAlgorithmRecord> {
        refreshFromDisk()
        return cache.values.sortedByDescending { it.versionCode }
    }

    /** 获取磁盘中的全部版本，供版本管理与回滚列表使用。 */
    fun listAllInstalledVersions(): List<InstalledAlgorithmRecord> {
        refreshFromDisk()
        return allVersions.values.sortedWith(
            compareBy<InstalledAlgorithmRecord> { it.catalogId }
                .thenByDescending { it.versionCode },
        )
    }

    /** 将 v1 的 `<catalogId>/current/` 目录迁入同一 catalog 的多版本布局。 */
    private fun ensureLegacyMigration() {
        if (legacyMigrated) return
        legacyMigrated = true
        val candidates = installedDir.listDirectories()
            .mapNotNull { catalogDir ->
                File(catalogDir, "current").takeIf { it.isDirectory }
            }
            .toMutableList()
        // 兼容短暂存在过的错误全局 current/ 布局；必须由 meta 提供 catalogId。
        File(installedDir, "current").takeIf { it.isDirectory }?.let(candidates::add)
        candidates.forEach(::migrateLegacyDirectory)

        // 兼容旧错误实现写出的全局 installed/versions/<versionCode>/。
        File(installedDir, "versions").listDirectories().forEach { oldVersionDir ->
            migrateGlobalVersionDirectory(oldVersionDir)
        }
        File(installedDir, "versions").takeIf { it.listDirectories().isEmpty() }?.delete()
    }

    private fun migrateGlobalVersionDirectory(oldVersionDir: File) {
        val metaFile = File(oldVersionDir, "meta.json")
        if (!metaFile.isFile) return
        runCatching {
            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
            val catalogId = meta.getString("catalogId")
            require(catalogId.isNotBlank() && !catalogId.contains('/') && !catalogId.contains('\\'))
            val versionCode = meta.optLong("versionCode", oldVersionDir.name.toLongOrNull() ?: 1L)
            val catalogDir = File(installedDir, catalogId).also { it.mkdirs() }
            val target = File(File(catalogDir, "versions"), versionCode.toString())
            if (!target.exists()) {
                require(oldVersionDir.copyRecursively(target, overwrite = false)) {
                    "无法复制全局旧版本目录"
                }
            }
            val record = loadRecordFromMeta(meta, target)
            val currentFile = File(catalogDir, "current.json")
            if (!currentFile.isFile) writeCurrent(catalogDir, record)
            oldVersionDir.deleteRecursively()
            AppLog.i("algorithm", "迁移全局旧版本：$catalogId@$versionCode")
        }.onFailure { error ->
            AppLog.e("algorithm", "全局旧版本迁移失败: ${error.message}", error)
        }
    }

    private fun migrateLegacyDirectory(oldCurrentDir: File) {
        val metaFile = File(oldCurrentDir, "meta.json")
        if (!metaFile.isFile) {
            AppLog.w("algorithm", "跳过缺少 meta.json 的旧算法目录：$oldCurrentDir")
            return
        }
        runCatching {
            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
            val catalogId = meta.optString("catalogId").takeIf { it.isNotBlank() }
                ?: oldCurrentDir.parentFile?.name?.takeIf { it != "installed" }
                ?: error("旧算法目录缺少 catalogId")
            val catalogDir = File(installedDir, catalogId).also { it.mkdirs() }
            val versionCode = meta.optLong("versionCode", 1L)
            val versionDir = File(File(catalogDir, "versions"), versionCode.toString())
            if (versionDir.exists()) versionDir.deleteRecursively()
            require(
                oldCurrentDir.copyRecursively(versionDir, overwrite = true),
            ) { "无法复制旧算法版本" }
            val record = loadRecordFromMeta(meta, versionDir)
            require(record.catalogId == catalogId) { "旧算法 catalogId 不匹配" }
            writeCurrent(catalogDir, record)
            oldCurrentDir.deleteRecursively()
            AppLog.i("algorithm", "旧版格式迁移完成：$catalogId/current → versions/$versionCode")
        }.onFailure { error ->
            AppLog.e("algorithm", "旧版格式迁移失败: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    /**
     * 从已解压的包目录安装（staging 内容须含 manifest.json + rules.json）。
     * 负责网络下载与验签；调用方先完成安全校验。
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
        ensureLegacyMigration() // 启动时检测旧版，首次安装时触发迁移

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

        val catalogDir = File(installedDir, catalogId).also { it.mkdirs() }
        val versionsDir = File(catalogDir, "versions").also { it.mkdirs() }
        val versionDir = File(versionsDir, versionCode.toString())
        val staging = File(catalogDir, "staging-$versionCode-${System.nanoTime()}")
            .also { it.mkdirs() }

        try {
            extracted.listFiles()?.forEach { src ->
                val dest = File(staging, src.name)
                if (src.isDirectory) {
                    src.copyRecursively(dest, overwrite = true)
                } else {
                    src.copyTo(dest, overwrite = true)
                }
            }

            // 写入 meta.json（记录版本、origin、source 等）
            val installedAt = System.currentTimeMillis()
            val meta = JSONObject()
                .put("catalogId", catalogId)
                .put("runtimeId", runtimeId)
                .put("version", version)
                .put("versionCode", versionCode)
                .put("displayName", displayName)
                .put("installedAtEpochMs", installedAt)
                .put("sha256", sha256 ?: JSONObject.NULL)
                .put("author", resolvedAuthor ?: JSONObject.NULL)
                .put("summary", resolvedSummary ?: JSONObject.NULL)
                .put("channel", resolvedChannel ?: JSONObject.NULL)
                .put("originTag", originTag ?: JSONObject.NULL)
                .put(
                    "supportedScenes",
                    JSONArray().also { arr -> scenes.forEach { arr.put(it.name) } },
                )
            File(staging, "meta.json").writeText(meta.toString(2), Charsets.UTF_8)

            File(staging, "profile.json").writeText(
                encodeProfileStub(parsed.profile),
                Charsets.UTF_8,
            )

            if (versionDir.exists()) versionDir.deleteRecursively()
            require(staging.renameTo(versionDir) || staging.copyRecursively(versionDir, overwrite = true)) {
                "无法晋升算法版本目录"
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
                directory = versionDir,
                installedAtEpochMs = installedAt,
                sha256 = sha256,
                author = resolvedAuthor,
                summary = resolvedSummary,
                channelName = resolvedChannel,
                originTag = originTag,
            )

            writeCurrent(catalogDir, record)
            allVersions[versionKey(catalogId, versionCode)] = record
            cache[catalogId] = record
            record
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun uninstall(catalogId: String, versionCode: Long? = null): Boolean {
        if (AlgorithmIds.isBuiltinCatalog(catalogId)) return false

        refreshFromDisk()
        val catalogDir = File(installedDir, catalogId)
        val target = versionCode?.let { allVersions[versionKey(catalogId, it)] }
        if (versionCode == null) {
            val existed = catalogDir.exists()
            val deleted = catalogDir.deleteRecursively()
            cache.remove(catalogId)
            allVersions.entries.removeIf { it.value.catalogId == catalogId }
            return existed && deleted
        }
        target ?: return false
        val deleted = target.directory.deleteRecursively()
        if (!deleted) return false
        allVersions.remove(versionKey(catalogId, versionCode))

        val current = cache[catalogId]
        if (current?.versionCode == versionCode) {
            val replacement = allVersions.values
                .filter { it.catalogId == catalogId }
                .maxByOrNull { it.versionCode }
            if (replacement == null) {
                cache.remove(catalogId)
                File(catalogDir, "current.json").delete()
            } else {
                cache[catalogId] = replacement
                writeCurrent(catalogDir, replacement)
            }
        }
        return true
    }

    /** 获取 catalogId 对应的当前激活版本（current.json 指向的）。 */
    fun getActiveVersion(catalogId: String): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return cache[catalogId]
    }

    fun setActiveVersion(catalogId: String, versionCode: Long): Boolean {
        refreshFromDisk()
        val catalogDir = File(installedDir, catalogId)
        val versionDir = File(File(catalogDir, "versions"), versionCode.toString())
        if (!versionDir.isDirectory) return false

        return runCatching {
            val metaFile = File(versionDir, "meta.json")
            if (!metaFile.isFile) return false
            val record = loadRecordFromMeta(
                JSONObject(metaFile.readText(Charsets.UTF_8)),
                versionDir,
            )
            if (record.catalogId != catalogId) return false

            writeCurrent(catalogDir, record)
            cache[catalogId] = record
            allVersions[versionKey(catalogId, versionCode)] = record
            true
        }.getOrElse { error ->
            AppLog.e("algorithm", "SetActiveVersion 失败: ${error.message}", error)
            false
        }
    }

    /** 从磁盘刷新全部版本，并按 current.json 建立当前版本索引。 */
    private fun refreshFromDisk() {
        ensureLegacyMigration()
        cache.clear()
        allVersions.clear()
        val catalogDirs = installedDir.listDirectories()
            .filter { it.name != "staging" && it.name != "current" }
        catalogDirs.forEach { catalogDir ->
            val versionDirs = File(catalogDir, "versions").listDirectories()
            versionDirs.forEach { versionDir ->
                runCatching {
                    val record = File(versionDir, "meta.json").takeIf(File::isFile)?.let { metaFile ->
                        loadRecordFromMeta(JSONObject(metaFile.readText(Charsets.UTF_8)), versionDir)
                    } ?: loadLegacyRecord(versionDir)
                    if (record != null && record.catalogId == catalogDir.name) {
                        allVersions[versionKey(record.catalogId, record.versionCode)] = record
                    }
                }.onFailure { error ->
                    AppLog.w("algorithm", "跳过损坏的版本目录 $versionDir: ${error.message}", error)
                }
            }

            val currentVersionCode = runCatching {
                JSONObject(File(catalogDir, "current.json").readText(Charsets.UTF_8))
                    .optLong("versionCode", -1L)
            }.getOrDefault(-1L)
            val current = allVersions[versionKey(catalogDir.name, currentVersionCode)]
                ?: allVersions.values
                    .filter { it.catalogId == catalogDir.name }
                    .maxByOrNull { it.versionCode }
            if (current != null) {
                cache[catalogDir.name] = current
                if (current.versionCode != currentVersionCode) {
                    runCatching { writeCurrent(catalogDir, current) }
                        .onFailure { error ->
                            AppLog.w("algorithm", "修复 current.json 失败：${error.message}", error)
                        }
                }
            }
        }
    }

    /** 从 meta.json 加载记录。 */
    private fun loadRecordFromMeta(meta: JSONObject, directory: File): InstalledAlgorithmRecord {
        val rulesText = File(directory, "rules.json").readText(Charsets.UTF_8)
        val runtimeId = meta.optString("runtimeId", AlgorithmIds.runtimeIdForCatalog(meta.optString("catalogId", "")))
        val scenes = meta.optJSONArray("supportedScenes").toSceneSet()
        val parsed = AlgorithmRulesParser.parse(
            rulesJson = rulesText,
            algorithmId = runtimeId,
            version = meta.getString("version"),
            supportedScenes = scenes,
        ).getOrThrow()

        return InstalledAlgorithmRecord(
            catalogId = meta.getString("catalogId"),
            runtimeId = runtimeId,
            version = meta.getString("version"),
            versionCode = meta.optLong("versionCode", directory.name.toLongOrNull() ?: 1L),
            displayName = meta.optString("displayName", meta.getString("catalogId")),
            supportedScenes = scenes,
            profile = parsed.profile,
            directory = directory,
            installedAtEpochMs = meta.optLong("installedAtEpochMs", 0),
            sha256 = meta.optString("sha256").takeIf { it.isNotBlank() },
            author = meta.optString("author").takeIf { it.isNotBlank() },
            summary = meta.optString("summary").takeIf { it.isNotBlank() },
            channelName = meta.optString("channel").takeIf { it.isNotBlank() },
            originTag = meta.optString("originTag").takeIf { it.isNotBlank() },
        )
    }

    /** 从旧版 manifest.json 加载兼容记录。 */
    private fun loadLegacyRecord(dir: File): InstalledAlgorithmRecord? {
        try {
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return null
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val catalogId = manifest.getString("id")
            val version = manifest.getString("version")
            val displayName = manifest.optString("displayName", catalogId)
            val scenes = manifest.getJSONArray("supportedScenes").toSceneSet()
            val rulesFile = File(dir, "rules.json")
            if (!rulesFile.exists()) return null
            val rulesText = rulesFile.readText(Charsets.UTF_8)
            val runtimeId = AlgorithmIds.runtimeIdForCatalog(catalogId)
            val parsed = AlgorithmRulesParser.parse(
                rulesJson = rulesText,
                algorithmId = runtimeId,
                version = version,
                supportedScenes = scenes,
            ).getOrThrow()

            return InstalledAlgorithmRecord(
                catalogId = catalogId,
                runtimeId = runtimeId,
                version = version,
                versionCode = 1L, // 旧版没有 versionCode，默认 1
                displayName = displayName,
                supportedScenes = scenes,
                profile = parsed.profile,
                directory = dir,
                installedAtEpochMs = System.currentTimeMillis(),
                sha256 = null,
                author = manifest.optString("author").takeIf { it.isNotBlank() },
                summary = manifest.optString("description").takeIf { it.isNotBlank() },
                channelName = null,
                originTag = null,
            )
        } catch (e: Exception) {
            AppLog.w("algorithm", "旧版记录加载失败: ${e.message}", e)
            return null
        }
    }

    /** 获取已安装的所有 catalogId 列表。 */
    fun getAllCatalogIds(): Set<String> {
        refreshFromDisk()
        return cache.keys.toSet()
    }

    /** 获取指定 catalogId 的总版本数。 */
    fun getVersionCount(catalogId: String): Int {
        refreshFromDisk()
        return allVersions.values.count { it.catalogId == catalogId }
    }

    /** 扩展：读取指定版本的 CHANGELOG.txt。 */
    fun readChangelog(catalogId: String, versionCode: Long): String? {
        val record = getVersion(catalogId, versionCode) ?: return null
        return runCatching {
            File(record.directory, "CHANGELOG.txt").let {
                if (it.exists()) it.readText(Charsets.UTF_8) else ""
            }
        }.getOrNull()
    }

    /** 扩展：读取指定版本的 rules.json 摘要。 */
    fun readRulesSummary(catalogId: String, versionCode: Long): String? {
        val record = getVersion(catalogId, versionCode) ?: return null
        return runCatching {
            File(record.directory, "rules.json").readText(Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 根据 catalogId 获取记录（返回当前激活版本）。
     * 与 listInstalled() 不同，此方法返回单个记录，指向 current.json。
     */
    fun get(catalogId: String): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return cache[catalogId]
    }

    private fun getVersion(catalogId: String, versionCode: Long): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return allVersions[versionKey(catalogId, versionCode)]
    }

    fun uninstallAll(catalogId: String): Boolean = uninstall(catalogId)

    private fun versionKey(catalogId: String, versionCode: Long): String = "$catalogId@$versionCode"

    private fun writeCurrent(catalogDir: File, record: InstalledAlgorithmRecord) {
        catalogDir.mkdirs()
        File(catalogDir, "current.json").writeText(
            JSONObject()
                .put("versionCode", record.versionCode)
                .put("installedAtEpochMs", record.installedAtEpochMs)
                .toString(2),
            Charsets.UTF_8,
        )
    }

    /** 编码 AlgorithmRuntimeProfile 为轻量 JSON 快照（用于 profile.json）。 */
    private fun encodeProfileStub(profile: AlgorithmRuntimeProfile): String =
        JSONObject()
            .put("algorithmId", profile.algorithmId)
            .put("version", profile.version)
            .put("schemaVersion", profile.schemaVersion)
            .put("isBuiltin", profile.isBuiltin)
            .toString(2)

    /** 将 SceneId 数组转换为 Set<SceneId>（JSON 解析辅助）。 */
    private fun JSONArray?.toSceneSet(): Set<SceneId> {
        if (this == null) return emptySet()
        val out = linkedSetOf<SceneId>()
        for (i in 0 until length()) {
            runCatching { SceneId.valueOf(getString(i)) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun File.listDirectories(): List<File> =
        listFiles()?.filter(File::isDirectory).orEmpty()
}
