package top.azek431.hzzs.core.algorithm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.update.UpdateSourceId
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 算法目录拉取 + `.hzzsalg` 下载。
 *
 * 安全：仅 HTTPS；目录 JSON 有大小上限；资产下载校验 size/sha256。
 * 资产与目录同在 `release-index` 分支（**不依赖** GitHub/Gitee Release tag）：
 * `algorithms/packages/<filename>`。
 * 目录签名（catalogSignature）在信任锚未配置时只做结构校验并标记 UNKNOWN。
 */
@Singleton
class AlgorithmNetworkClient @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val verifier: AlgorithmPackVerifier,
    private val store: InstalledAlgorithmStore,
) {
    data class RemoteCatalog(
        val remote: List<CatalogRemoteEntry>,
        val activeSource: UpdateSourceId,
        val usedFallback: Boolean,
        val fallbackReason: String?,
        val message: String?,
    )

    data class CatalogRemoteEntry(
        val info: AlgorithmPackageInfo,
        /** release-index 下相对路径，如 algorithms/packages/foo-v1.0.0.hzzsalg */
        val assetPath: String,
        val filename: String,
        val sha256: String,
    )

    suspend fun fetchCatalog(
        channel: AlgorithmChannel,
        preference: UpdateSourcePreference,
    ): RemoteCatalog = withContext(Dispatchers.IO) {
        require(isNetworkAvailable()) { "网络不可用" }
        val path = if (channel == AlgorithmChannel.BETA) "beta.json" else "stable.json"
        val order = sourceOrder(preference)
        var active: UpdateSourceId? = null
        var usedFallback = false
        var fallbackReason: String? = null
        var lastError: String? = null
        var body: String? = null
        for ((index, source) in order.withIndex()) {
            val url = catalogUrl(source, path)
            val result = runCatching { readHttps(url, MAX_CATALOG_BYTES) }
            if (result.isSuccess) {
                body = result.getOrThrow()
                active = source
                if (index > 0) {
                    usedFallback = true
                    fallbackReason = "首选源不可达，已切换到 ${source.name}"
                }
                break
            }
            lastError = result.exceptionOrNull()?.message
        }
        val source = active ?: error(lastError ?: "Gitee 与 GitHub 算法目录均不可用")
        val text = body ?: error("目录为空")
        val remote = parseCatalog(text, channel, source)
        RemoteCatalog(
            remote = remote,
            activeSource = source,
            usedFallback = usedFallback,
            fallbackReason = fallbackReason,
            message = if (usedFallback) fallbackReason else "已从 ${source.name} 刷新算法目录",
        )
    }

    /**
     * 下载并验签安装。返回已安装 catalogId。
     * @param onProgress 0f..1f
     */
    suspend fun downloadAndInstall(
        entry: CatalogRemoteEntry,
        source: UpdateSourceId,
        wifiOnly: Boolean,
        onProgress: (Float) -> Unit,
    ): InstalledAlgorithmStore.InstalledAlgorithmRecord = withContext(Dispatchers.IO) {
        if (wifiOnly && !isOnUnmeteredNetwork()) {
            error("当前设置要求仅在 Wi‑Fi 下下载算法包")
        }
        val url = packageUrl(source, entry.assetPath)
        val stagingRoot = File(appContext.cacheDir, "algorithm-dl").also { it.mkdirs() }
        val part = File(stagingRoot, "${entry.info.id}.hzzsalg.part")
        val target = File(stagingRoot, "${entry.info.id}.hzzsalg")
        part.delete()
        target.delete()
        try {
            downloadHttps(url, part, entry.info.sizeBytes, onProgress)
            val actualSha = sha256(part)
            require(actualSha.equals(entry.sha256, ignoreCase = true)) { "下载哈希不匹配" }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(1f)
            val verified = verifier.verifyFile(target)
            val extractDir = File(stagingRoot, "extract-${entry.info.id}").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            verified.entries.forEach { (name, data) ->
                File(extractDir, name).writeBytes(data)
            }
            store.installFromExtracted(
                extracted = extractDir,
                sha256 = verified.sha256,
                versionCode = entry.info.versionCode,
            ).getOrThrow()
        } finally {
            part.delete()
            target.delete()
        }
    }

    private fun parseCatalog(
        raw: String,
        channel: AlgorithmChannel,
        source: UpdateSourceId,
    ): List<CatalogRemoteEntry> {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion") == 1) { "不支持的算法目录 schema" }
        val algorithms = root.optJSONArray("algorithms") ?: return emptyList()
        val downloadSource = when (source) {
            UpdateSourceId.GITEE -> AlgorithmDownloadSource.GITEE
            UpdateSourceId.GITHUB -> AlgorithmDownloadSource.GITHUB
        }
        val appCode = 1L // 兼容字段；精确 versionCode 由 Controller 再过滤
        val out = ArrayList<CatalogRemoteEntry>(algorithms.length())
        for (i in 0 until algorithms.length()) {
            val item = algorithms.getJSONObject(i)
            if (item.optBoolean("revoked", false)) continue
            val id = item.getString("id")
            val version = item.getString("version")
            val filename = item.getString("filename")
            require(SAFE_NAME.matches(filename)) { "非法文件名: $filename" }
            // 优先 assetPath；兼容旧目录里的 tag 字段（忽略，仅用于人工阅读）
            val assetPath = item.optString("assetPath").ifBlank {
                "algorithms/packages/$filename"
            }
            require(SAFE_ASSET_PATH.matches(assetPath)) { "非法资产路径: $assetPath" }
            val size = item.getLong("size")
            val sha256 = item.getString("sha256")
            val scenes = item.optJSONArray("supportedScenes").toSceneSet()
            val minApp = item.optLong("minimumAppVersionCode", 1L)
            val versionCode = versionToCode(version)
            val info = AlgorithmPackageInfo(
                id = id,
                name = item.optString("displayName", id),
                versionName = version,
                versionCode = versionCode,
                channel = channel,
                summary = item.optString("description").ifBlank { item.optString("changelog") },
                supportedScenes = scenes,
                minAppVersionCode = minApp,
                publishedAtEpochMs = 0L,
                sizeBytes = size,
                origin = AlgorithmOrigin.REMOTE,
                signature = if (AlgorithmTrustAnchors.hasOfficialAnchors()) {
                    AlgorithmSignatureState.OFFICIAL
                } else {
                    AlgorithmSignatureState.UNKNOWN
                },
                downloadSource = downloadSource,
                releaseNotes = item.optString("changelog"),
                isCompatible = appCode >= minApp,
            )
            out += CatalogRemoteEntry(info, assetPath, filename, sha256)
        }
        return out
    }

    private fun versionToCode(version: String): Long {
        val parts = version.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val minor = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val patch = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        return major * 1_000_000L + minor * 1_000L + patch
    }

    private fun catalogUrl(source: UpdateSourceId, file: String): String = when (source) {
        UpdateSourceId.GITEE ->
            "https://gitee.com/Azek431/hzzs/raw/release-index/algorithms/$file"
        UpdateSourceId.GITHUB ->
            "https://raw.githubusercontent.com/Azek431/hzzs/release-index/algorithms/$file"
    }

    /**
     * 包体与目录同分支：release-index 上的 raw 路径。
     * 不使用 GitHub/Gitee Releases tag。
     */
    private fun packageUrl(source: UpdateSourceId, assetPath: String): String {
        require(SAFE_ASSET_PATH.matches(assetPath)) { "非法资产路径" }
        return when (source) {
            UpdateSourceId.GITEE ->
                "https://gitee.com/Azek431/hzzs/raw/release-index/$assetPath"
            UpdateSourceId.GITHUB ->
                "https://raw.githubusercontent.com/Azek431/hzzs/release-index/$assetPath"
        }
    }

    private fun sourceOrder(preference: UpdateSourcePreference): List<UpdateSourceId> =
        when (preference) {
            UpdateSourcePreference.AUTO, UpdateSourcePreference.PREFER_GITEE ->
                listOf(UpdateSourceId.GITEE, UpdateSourceId.GITHUB)
            UpdateSourcePreference.PREFER_GITHUB ->
                listOf(UpdateSourceId.GITHUB, UpdateSourceId.GITEE)
        }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun readHttps(url: String, maxBytes: Long): String {
        val connection = openHttps(url)
        return try {
            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= maxBytes) { "目录过大" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadHttps(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ) {
        require(expectedSize in 1..MAX_PACKAGE_BYTES) { "资产大小无效" }
        val connection = openHttps(url)
        try {
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    var written = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        require(written <= expectedSize && written <= MAX_PACKAGE_BYTES) {
                            "下载超过清单大小"
                        }
                        output.write(buffer, 0, read)
                        onProgress((written.toFloat() / expectedSize).coerceIn(0f, 0.99f))
                    }
                    require(written == expectedSize) { "下载大小不匹配" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", true)) { "只允许 HTTPS" }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "HZZS-Android-Algorithm")
        connection.connect()
        require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        require(connection.url.protocol.equals("https", true)) { "重定向到非 HTTPS" }
        return connection
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                update(buffer, 0, n)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    private fun org.json.JSONArray?.toSceneSet(): Set<SceneId> {
        if (this == null) return emptySet()
        val out = linkedSetOf<SceneId>()
        for (i in 0 until length()) {
            runCatching { SceneId.valueOf(getString(i)) }.getOrNull()?.let(out::add)
        }
        return out
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 512L * 1024L
        private const val MAX_PACKAGE_BYTES = 1024L * 1024L
        private val SAFE_NAME = Regex("^[A-Za-z0-9._+-]{1,160}$")
        /** 仅允许 algorithms/packages/ 下单层安全文件名。 */
        private val SAFE_ASSET_PATH = Regex("^algorithms/packages/[A-Za-z0-9._+-]{1,160}$")
    }
}
