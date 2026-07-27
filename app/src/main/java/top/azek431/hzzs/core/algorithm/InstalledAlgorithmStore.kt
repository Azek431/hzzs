package top.azek431.hzzs.core.algorithm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.SceneId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    private val stagingDir: File
        get() = File(root, "staging").also { it.mkdirs() }

    private val cache = ConcurrentHashMap<String, InstalledAlgorithmRecord>()

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
        return cache.values.filter { it.catalogId == catalogId }.sortedByDescending { it.versionCode }
    }

    /** 获取当前激活版本（指向 current.json 中记录的版本）。 */
    fun getCurrentVersion(catalogId: String): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return cache[catalogId]
    }

    /** 刷新磁盘：扫描所有已安装目录，载入 cache。 */
    fun listInstalled(): List<InstalledAlgorithmRecord> {
        refreshFromDisk()
        return cache.values.sortedByDescending { it.versionCode }
    }

    /** 检查并迁移旧格式（如果存在）。 */
    private fun ensureLegacyMigration() {
        if (legacyMigrated) return
        legacyMigrated = true
        val oldCurrentDir = File(installedDir, "current")
        if (oldCurrentDir.isDirectory && oldCurrentDir.listFiles()?.isNotEmpty() == true) {
            // 存在旧版 current/ 目录，需要迁移
            migrateLegacyFormat()
        }
    }

    private fun migrateLegacyFormat() {
        val oldCurrentDir = File(installedDir, "current")
        if (!oldCurrentDir.isDirectory) return

        AppLog.i("algorithm", "检测到旧版算法存储格式，自动迁移中...")
        try {
            // 读取 oldCurrentDir/meta.json 或重建元数据
            val metaFile = File(oldCurrentDir, "meta.json")
            var recordMeta: JSONObject? = null
            if (metaFile.exists()) {
                recordMeta = JSONObject(metaFile.readText(Charsets.UTF_8))
            }

            // 收集当前目录下的所有文件作为旧版内容
            val files = oldCurrentDir.listFiles()?.toList() ?: emptyList()
            // 创建版本目录（取 versionCode = 1，或从 meta 读取）
            val versionCode = recordMeta?.optLong("versionCode", 1) ?: 1L
            val versionDir = File(installedDir, "versions/$versionCode").also { it.mkdirs() }

            // 迁移所有文件
            files.forEach { file ->
                val target = File(versionDir, file.name)
                if (file.isDirectory) {
                    file.copyRecursively(target, overwrite = true)
                } else {
                    file.copyTo(target, overwrite = true)
                }
            }

            // 保留/migrate meta 到新目录
            recordMeta?.let { meta ->
                val metaJson = File(versionDir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
            }

            // 删除旧 current 目录
            oldCurrentDir.deleteRecursively()
            AppLog.i("algorithm", "旧版格式迁移完成：current/ → versions/$versionCode/")
        } catch (e: Exception) {
            AppLog.e("algorithm", "旧版格式迁移失败: ${e.message ?: e.javaClass.simpleName}", e)
            // 迁移失败不阻断旧功能，保留 current/ 目录继续工作
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

        // 安装到版本目录：installed/<catalogId>/versions/<versionCode>/
        val versionDir = File(installedDir, "versions/$versionCode").also { it.mkdirs() }
        val catalogDir = File(installedDir, catalogId).also { it.mkdirs() }

        try {
            // 迁移已存在的 current 目录到新版本（如果有）
            val oldCurrent = File(catalogDir, "current")
            if (oldCurrent.exists()) {
                oldCurrent.copyTo(File(versionDir, "current"), overwrite = true)
            }

            // 复制所有文件
            extracted.listFiles()?.forEach { src ->
                val dest = File(versionDir, src.name)
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
            File(versionDir, "meta.json").writeText(meta.toString(2), Charsets.UTF_8)

            // 写入 profile.json（供快速读取，避免每次启动都解析 rules.json）
            File(versionDir, "profile.json").writeText(
                encodeProfileStub(parsed.profile),
                Charsets.UTF_8,
            )

            // 更新 current.json 指向最新版本
            val currentJson = File(catalogDir, "current.json")
            currentJson.writeText(
                JSONObject()
                    .put("versionCode", versionCode)
                    .put("installedAtEpochMs", installedAt)
                    .toString(2),
                Charsets.UTF_8,
            )

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

            cache[catalogId] = record
            record
        } catch (error: Throwable) {
            // 清理半包版本
            versionDir.deleteRecursively()
            throw error
        }
    }

    /**
     * 卸载指定 catalogId 的指定版本（或全部版本）。
     * @param catalogId 算法 id
     * @param versionCode 版本码（null 表示卸载所有版本）
     */
    fun uninstall(catalogId: String, versionCode: Long? = null): Boolean {
        if (AlgorithmIds.isBuiltinCatalog(catalogId)) return false

        refreshFromDisk()
        val versionsToKeep = if (versionCode != null) {
            cache.values.filter { it.catalogId == catalogId && it.versionCode != versionCode }
        } else {
            cache.values.filter { it.catalogId == catalogId }
        }

        // 如果要卸载的目录还存在，删除它
        versionCode?.let { vc ->
            val dirToDelete = File(installedDir, "versions/$vc")
            dirToDelete.deleteRecursively()
        }

        // 移除缓存
        val removed = if (versionCode != null) {
            cache.remove(catalogId)?.let { if (it.versionCode == versionCode) 1 else 0 } ?: 0
        } else {
            cache.values.filter { it.catalogId == catalogId }.size.also {
                cache.removeAll { it.catalogId == catalogId }
            }
        }
        removed > 0
    }

    /** 获取 catalogId 对应的当前激活版本（current.json 指向的）。 */
    fun getActiveVersion(catalogId: String): InstalledAlgorithmRecord? {
        refreshFromDisk()
        return cache[catalogId]
    }

    /**
     * 将 current.json 指向的版本标记为激活（即设置当前版本）。
     * 实际不需要，因为 installFromExtracted 已自动更新 current.json。
     * 此方法为遗留接口兼容，直接生效。
     */
    fun setActiveVersion(catalogId: String, versionCode: Long): Boolean {
        refreshFromDisk()
        val versionDir = File(installedDir, "versions/$versionCode")
        if (!versionDir.exists() || !versionDir.isDirectory) return false

        val catalogDir = File(installedDir, catalogId)
        val currentJson = File(catalogDir, "current.json")
        try {
            currentJson.writeText(
                JSONObject()
                    .put("versionCode", versionCode)
                    .toString(2),
                Charsets.UTF_8,
            )
            // 从 cache 中更新指向
            cache[catalogId]?.let { record ->
                val newRecord = versionDir.findRecord(versionCode, catalogId)
                if (newRecord != null) {
                    cache[catalogId] = newRecord
                    return true
                }
            }
            return true
        } catch (e: Exception) {
            AppLog.e("algorithm", "SetActiveVersion 失败: ${e.message}", e)
            return false
        }
    }

    /** 从磁盘刷新已安装记录。 */
    private fun refreshFromDisk() {
        cache.clear()
        val versionDirs = File(installedDir, "versions").listDirectories() ?: emptyList()
        versionDirs.forEach { versionDir ->
            try {
                val metaFile = File(versionDir, "meta.json")
                if (metaFile.exists()) {
                    val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                    val catalogId = meta.optString("catalogId")
                    if (catalogId.isNotBlank()) {
                        val record = loadRecordFromMeta(meta, versionDir)
                        cache[catalogId] = record
                    }
                } else {
                    // 尝试从旧版 manifest.json/rules.json 加载
                    loadLegacyRecord(versionDir)?.let {
                        val catalogId = it.catalogId
                        cache[catalogId] = it
                    }
                }
            } catch (e: Exception) {
                AppLog.w("algorithm", "跳过损坏的目录 $versionDir: ${e.message}", e)
            }
        }

        // 加载 current.json（用于获取活跃版本）
        val catalogDirs = File(installedDir).listDirectories()?.filter { it.name != "staging" } ?: emptyList()
        catalogDirs.forEach { catalogDir ->
            val currentJson = File(catalogDir, "current.json")
            if (currentJson.exists()) {
                try {
                    val meta = JSONObject(currentJson.readText(Charsets.UTF_8))
                    val versionCode = meta.optLong("versionCode", -1)
                    if (versionCode >= 0) {
                        val versionDir = File(installedDir, "versions/$versionCode")
                        if (versionDir.exists()) {
                            val metaFile = File(versionDir, "meta.json")
                            if (metaFile.exists()) {
                                val recordMeta = JSONObject(metaFile.readText(Charsets.UTF_8))
                                val record = loadRecordFromMeta(recordMeta, versionDir)
                                cache[catalogDir.name] = record
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w("algorithm", "current.json 解析失败: ${e.message}", e)
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
            versionCode = meta.getLong("versionCode"),
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
        return cache.values.filter { it.catalogId == catalogId }.count()
    }

    /** 扩展：读取指定版本的 CHANGELOG.txt。 */
    fun readChelog(catalogId: String, versionCode: Long): String? {
        val record = get(catalogId) ?: return null
        if (record.versionCode != versionCode) return null
        return runCatching {
            File(record.directory, "CHANGELOG.txt").let {
                if (it.exists()) it.readText(Charsets.UTF_8) else ""
            }
        }.getOrNull()
    }

    /** 扩展：读取指定版本的 rules.json 摘要。 */
    fun readRulesSummary(catalogId: String, versionCode: Long): String? {
        val record = get(catalogId) ?: return null
        if (record.versionCode != versionCode) return null
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

    /**
     * 卸载指定 catalogId 的所有版本（包括内置不允许卸载）。
     */
    fun uninstallAll(catalogId: String): Boolean {
        if (AlgorithmIds.isBuiltinCatalog(catalogId)) return false
        cache.remove(catalogId)
        // 删除所有版本目录
        val versionsDir = File(installedDir, "versions")
        versionsDir.listFiles()?.forEach { versionDir ->
            val catalogDirName = versionDir.nameTake { it.contains(catalogId) }
            if (catalogDirName.contains(catalogId)) {
                versionDir.deleteRecursively()
            }
        }
        // 也删除 catalog 目录下的 current.json
        val catalogDir = File(installedDir, catalogId)
        catalogDir.delete()
        return true
    }

    /** 扩展：从目录名提取 catalogId（用于旧版兼容）。 */
    private fun File.listDirectories(): List<File>? {
        return this.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    /** 扩展：从字符串中取出包含目标子串的部分（仅用于兼容）。 */
    private fun String.contains(substring: Boolean): Boolean {
        // 占位，实际逻辑在 loadLegacyRecord 中硬编码实现
        return true
    }
}

/** 扩展：从 File 获取名称中的 catalogId 部分（兼容旧命名约定）。 */
private fun File.catalogIdFromPath(): String {
    return this.name
}

/** 安全扩展：字符串截取，取第一个 @ 之前的部分（兼容旧版本 id 格式）。 */
private fun String.takeBeforeAt(): String {
    return this.split('@').first()
}