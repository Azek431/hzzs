package top.azek431.hzzs.core.algorithm

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.azek431.hzzs.core.algorithm.CatalogRemoteEntry
import top.azek431.hzzs.core.algorithm.logic.AlgorithmCatalogPure
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.update.UpdateSourceId
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 算法目录拉取 + `.hzzsalg` 下载。
 *
 * 职责（限网络 IO）：
 * - HTTPS 目录 JSON 拉取（多源回退）
 * - `.hzzsalg` 资产下载 + size/sha256 校验 + ZIP 白名单解压
 * - 调用 [InstalledAlgorithmStore] 落盘
 *
 * 解析（纯函数）已委托 [AlgorithmCatalogPure.parseCatalog]；本类**不再**解析 JSON。
 *
 * 安全：仅 HTTPS；目录 JSON 有大小上限；资产下载校验 size/sha256 + ZIP 白名单。
 * 资产与目录同在 `release-index` 分支（**不依赖** GitHub/Gitee Release tag）：
 * `algorithms/packages/<filename>`。
 */
@Singleton
class AlgorithmNetworkClient @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val store: InstalledAlgorithmStore,
) {
    /** 目录拉取结果（含解析后的条目与运行时元数据）。 */
    data class RemoteCatalog(
        val remote: List<CatalogRemoteEntry>,
        val activeSource: UpdateSourceId,
        val usedFallback: Boolean,
        val fallbackReason: String?,
        val message: String?,
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
            val error = result.exceptionOrNull()
            val code = error?.message
                ?.substringAfter("HTTP ", missingDelimiterValue = "")
                ?.takeWhile { it.isDigit() }
                ?.toIntOrNull()
            lastError = friendlyNetworkError(url, code, error)
        }
        val source = active ?: error(
            lastError
                ?: "Gitee 与 GitHub 的 algorithms/$path 均不可用；请继续使用内置算法，或稍后在 release-index 发布目录后再检查。",
        )
        val text = body ?: error("目录为空")
        val appVersionCode = currentAppVersionCode()
        val remote = AlgorithmCatalogPure.parseCatalog(
            raw = text,
            channel = channel,
            source = source,
            appVersionCode = appVersionCode,
        )
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
        require(AlgorithmCatalogPure.SAFE_ID.matches(entry.info.id)) { "非法算法 id: ${entry.info.id}" }
        require(AlgorithmCatalogPure.SAFE_SHA256.matches(entry.sha256)) { "非法 sha256: ${entry.sha256}" }
        val safeId = entry.info.id
        val url = packageUrl(source, entry.assetPath)
        val stagingRoot = File(appContext.cacheDir, "algorithm-dl").also { it.mkdirs() }
        val part = File(stagingRoot, "$safeId.hzzsalg.part")
        val target = File(stagingRoot, "$safeId.hzzsalg")
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
            val extractDir = File(stagingRoot, "extract-$safeId").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            val entries = readZipWhitelist(target)
            entries.forEach { (name, data) ->
                File(extractDir, name).writeBytes(data)
            }
            store.installFromExtracted(
                extracted = extractDir,
                sha256 = actualSha,
                versionCode = entry.info.versionCode,
                originTag = ORIGIN_NETWORK,
            ).getOrThrow()
        } finally {
            part.delete()
            target.delete()
        }
    }

    /** 读取本应用 long versionCode，供目录兼容字段 isCompatible 使用。 */
    private fun currentAppVersionCode(): Long {
        val pm = appContext.packageManager
        val packageName = appContext.packageName
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            1L
        }
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
        require(AlgorithmCatalogPure.SAFE_ASSET_PATH.matches(assetPath)) { "非法资产路径" }
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

    /** 把 HTTP/网络异常转成用户可读中文原因，避免只显示「HTTP 404」。 */
    private fun friendlyNetworkError(url: String, code: Int?, cause: Throwable?): String {
        val host = runCatching { URL(url).host }.getOrDefault("更新源")
        return when (code) {
            404 -> "算法目录尚未发布（$host 返回 404）。当前可继续使用内置算法；完整外装需先在 release-index 发布 algorithms/{channel}.json。"
            403 -> "算法目录访问被拒绝（$host HTTP 403）。"
            in 500..599 -> "算法目录服务暂时不可用（$host HTTP $code）。"
            null -> cause?.message?.takeIf { it.isNotBlank() }?.let { "网络错误：$it" }
                ?: "网络不可用"
            else -> "拉取算法目录失败（$host HTTP $code）"
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

    /**
     * ZIP 白名单解压：仅允许 [ALLOWED] 中的文件名，强制 [REQUIRED] 必须存在。
     *
     * 安全检查：
     * - 拒绝目录条目
     * - 拒绝路径遍历（`..`、前导 `/`、`\`、嵌套 `/`）
     * - 拒绝不在白名单中的文件名
     * - 拒绝重复文件
     * - 强制 [MAX_FILES] / [MAX_FILE_BYTES] / [MAX_TOTAL_UNCOMPRESSED] / [MAX_COMPRESSED_BYTES]
     */
    private fun readZipWhitelist(file: File): Map<String, ByteArray> {
        val names = linkedSetOf<String>()
        val out = linkedMapOf<String, ByteArray>()
        var totalUncompressed = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                require(!name.contains("..") && !name.startsWith("/") && !name.startsWith("\\") && !name.contains("/")) {
                    "非法 ZIP 条目路径: $name"
                }
                require(name in ALLOWED) { "不允许的 ZIP 条目: $name" }
                require(name !in names) { "重复 ZIP 条目: $name" }
                require(out.size < MAX_FILES) { "ZIP 条目数超过上限" }
                names += name
                var bytesRead = 0L
                val baos = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    bytesRead += read
                    require(bytesRead <= MAX_FILE_BYTES) { "ZIP 单文件超过上限: $name" }
                    totalUncompressed += read
                    require(totalUncompressed <= MAX_TOTAL_UNCOMPRESSED) { "ZIP 解压总大小超过上限" }
                    baos.write(buffer, 0, read)
                }
                out[name] = baos.toByteArray()
            }
        }
        require(out.size == REQUIRED.size && out.keys.containsAll(REQUIRED)) {
            "ZIP 缺少必要文件；需要 ${REQUIRED.joinToString()}，实际 ${out.keys.joinToString()}"
        }
        return out
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 512L * 1024L
        private const val MAX_PACKAGE_BYTES = 1024L * 1024L
        private const val MAX_FILES = 16
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_TOTAL_UNCOMPRESSED = 1024 * 1024L
        private const val MAX_COMPRESSED_BYTES = 1024 * 1024L
        private val ALLOWED = setOf("manifest.json", "rules.json", "CHANGELOG.txt")
        private val REQUIRED = setOf("manifest.json", "rules.json", "CHANGELOG.txt")
        /** 网络安装来源标签；bundled 升级不得覆盖。 */
        const val ORIGIN_NETWORK = "network"
    }
}
