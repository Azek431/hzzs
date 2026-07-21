package top.azek431.hzzs.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内双源签名更新。
 *
 * 安全模型：
 * - 仅 HTTPS
 * - 清单签名绑定**本安装包证书**公钥；仅改 URL / 偏好无法伪造信任
 * - Debug 包故意无法认证生产发行清单
 * - 资产有体积、文件名与哈希边界
 *
 * 未正式发布索引时，[UpdateRepository.check] 失败属预期。
 */

private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
private const val MAX_ARTIFACT_BYTES = 1L * 1024L * 1024L * 1024L
private const val MAX_PATCH_MANIFEST_BYTES = 4L * 1024L * 1024L
private const val MAX_PATCH_OPERATIONS = 100_000
private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
private val SAFE_ASSET_NAME = Regex("^[A-Za-z0-9._+-]{1,160}$")
private val SAFE_TAG = Regex("^[A-Za-z0-9._+-]{1,96}$")

/** 清单中的单个可下载资产。 */
data class Artifact(val name: String, val sha256: String, val size: Long)

/** 从旧 versionCode / 旧 APK 哈希 到 差分包 的映射。 */
data class PatchArtifact(
    val fromVersionCode: Long,
    val fromApkSha256: String,
    val patch: Artifact,
)

/**
 * 已解析的更新清单。
 *
 * [signedPayload] + [signature] 用于证书绑定验签；
 * 客户端还须校验 packageName、versionCode、certificateSha256 与本安装一致策略。
 */
data class UpdateManifest(
    val schemaVersion: Int,
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val channel: String,
    val packageName: String,
    val certificateSha256: String,
    val fullApk: Artifact,
    val patches: List<PatchArtifact>,
    val releaseNotes: String,
    val signedPayload: String,
    val signatureAlgorithm: String,
    val signature: String,
)

/** 更新源标识。 */
enum class UpdateSourceId { GITEE, GITHUB }

/** 某次成功拉取的源 + 清单。 */
data class SourceResult(val source: UpdateSourceId, val manifest: UpdateManifest)

/**
 * 签名、仅 HTTPS 的双源更新仓库。
 *
 * [check] 优先 Gitee，必要时 GitHub；双源均成功时要求 signedPayload 一致。
 * 下载后校验 SHA-256，差分补丁回放通过后才可安装。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        const val GITEE_STABLE =
            "https://gitee.com/Azek431/hzzs/raw/release-index/updates/stable.json"
        const val GITEE_BETA =
            "https://gitee.com/Azek431/hzzs/raw/release-index/updates/beta.json"
        const val GITHUB_STABLE =
            "https://raw.githubusercontent.com/Azek431/hzzs/release-index/updates/stable.json"
        const val GITHUB_BETA =
            "https://raw.githubusercontent.com/Azek431/hzzs/release-index/updates/beta.json"
    }

    /**
     * 检查更新索引。
     *
     * @param beta true 读 beta 通道，否则 stable
     * @throws IllegalStateException 双源均不可用或签名无效；双源 payload 不一致
     */
    suspend fun check(beta: Boolean): SourceResult = withContext(Dispatchers.IO) {
        val primaryUrl = if (beta) GITEE_BETA else GITEE_STABLE
        val secondaryUrl = if (beta) GITHUB_BETA else GITHUB_STABLE
        val primary = runCatching { fetch(UpdateSourceId.GITEE, primaryUrl) }.getOrNull()
        val secondary = runCatching { fetch(UpdateSourceId.GITHUB, secondaryUrl) }.getOrNull()
        when {
            primary != null && secondary != null -> {
                require(primary.manifest.signedPayload == secondary.manifest.signedPayload) {
                    "Gitee 与 GitHub 的签名发行清单不一致"
                }
                primary
            }
            primary != null -> primary
            secondary != null -> secondary
            else -> error("Gitee 与 GitHub 更新源均不可用或签名无效")
        }
    }

    /**
     * 由源 + tag + 资产名拼装下载 URL。
     * 资产必须属于已签名清单，且名称/tag 通过安全正则。
     */
    fun artifactUrl(
        source: UpdateSourceId,
        manifest: UpdateManifest,
        artifact: Artifact,
    ): String {
        validateArtifact(artifact)
        require(artifact == manifest.fullApk || manifest.patches.any { it.patch == artifact }) {
            "更新文件不属于已签名清单"
        }
        require(SAFE_TAG.matches(manifest.tag)) { "发行标签格式无效" }
        return when (source) {
            UpdateSourceId.GITEE ->
                "https://gitee.com/Azek431/hzzs/releases/download/${manifest.tag}/${artifact.name}"
            UpdateSourceId.GITHUB ->
                "https://github.com/Azek431/hzzs/releases/download/${manifest.tag}/${artifact.name}"
        }
    }

    /**
     * HTTPS 下载资产到 [target]，完成后校验 SHA-256。
     * 使用 `.part` 临时文件，失败清理，避免半包残留。
     */
    suspend fun download(
        source: UpdateSourceId,
        manifest: UpdateManifest,
        artifact: Artifact,
        target: File,
    ) = withContext(Dispatchers.IO) {
        validateArtifact(artifact)
        val parent = requireNotNull(target.absoluteFile.parentFile) { "更新目标缺少父目录" }
        require(parent.isDirectory || parent.mkdirs()) { "无法创建更新目录" }
        val temp = File(parent, target.name + ".part")
        temp.delete()
        try {
            downloadHttps(
                artifactUrl(source, manifest, artifact),
                temp,
                expectedSize = artifact.size,
            )
            require(UpdateFileVerifier.sha256(temp).equals(artifact.sha256, true)) {
                "下载哈希不匹配"
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                require(temp.delete()) { "无法清理临时下载文件" }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun fetch(source: UpdateSourceId, url: String): SourceResult {
        val raw = readHttps(url, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        val manifest = parse(raw)
        require(verifySignature(manifest)) { "更新清单签名无效" }
        return SourceResult(source, manifest)
    }

    private fun parse(raw: String): UpdateManifest {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "更新清单过大" }
        val root = JSONObject(raw)
        val payload = root.getString("signedPayload")
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "签名载荷过大" }
        val data = JSONObject(payload)

        fun artifact(value: JSONObject): Artifact = Artifact(
            name = value.getString("name"),
            sha256 = value.getString("sha256"),
            size = value.getLong("size"),
        ).also(::validateArtifact)

        val patchArray = data.optJSONArray("patches")
        require((patchArray?.length() ?: 0) <= 32) { "差分包数量过多" }
        val patches = patchArray?.let { array ->
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PatchArtifact(
                    fromVersionCode = item.getLong("fromVersionCode"),
                    fromApkSha256 = item.getString("fromApkSha256"),
                    patch = artifact(item.getJSONObject("patch")),
                ).also {
                    require(it.fromVersionCode > 0) { "差分来源版本无效" }
                    require(SHA256_PATTERN.matches(it.fromApkSha256)) { "差分来源哈希无效" }
                }
            }
        }.orEmpty()

        return UpdateManifest(
            schemaVersion = data.getInt("schemaVersion"),
            tag = data.getString("tag"),
            versionName = data.getString("versionName"),
            versionCode = data.getLong("versionCode"),
            channel = data.getString("channel"),
            packageName = data.getString("packageName"),
            certificateSha256 = data.getString("certificateSha256"),
            fullApk = artifact(data.getJSONObject("fullApk")),
            patches = patches,
            releaseNotes = data.optString("releaseNotes").take(64 * 1024),
            signedPayload = payload,
            signatureAlgorithm = root.getString("signatureAlgorithm"),
            signature = root.getString("signature"),
        ).also(::validateManifest)
    }

    private fun validateManifest(manifest: UpdateManifest) {
        require(manifest.schemaVersion == 1) { "不支持的更新清单版本" }
        require(SAFE_TAG.matches(manifest.tag)) { "发行标签格式无效" }
        require(manifest.versionName.isNotBlank() && manifest.versionName.length <= 64) {
            "版本名称无效"
        }
        require(manifest.versionCode > 0) { "版本号无效" }
        require(manifest.channel == "stable" || manifest.channel == "beta") { "更新通道无效" }
        require(manifest.packageName == context.packageName) { "更新包名不匹配" }
        require(SHA256_PATTERN.matches(manifest.certificateSha256)) { "签名证书哈希无效" }
        require(manifest.certificateSha256.equals(installedCertificateSha256(), true)) {
            "更新清单证书与当前安装包不匹配"
        }
        require(manifest.signature.length in 40..16_384) { "更新签名长度无效" }
    }

    private fun verifySignature(manifest: UpdateManifest): Boolean {
        val algorithm = manifest.signatureAlgorithm
        require(algorithm == "SHA256withRSA" || algorithm == "SHA256withECDSA") {
            "不允许的签名算法"
        }
        val publicKey = installedSigningCertificate().publicKey
        require(
            (algorithm.endsWith("RSA") && publicKey.algorithm.equals("RSA", true)) ||
                (algorithm.endsWith("ECDSA") && publicKey.algorithm.equals("EC", true)),
        ) { "清单签名算法与应用证书不匹配" }
        return Signature.getInstance(algorithm).run {
            initVerify(publicKey)
            update(manifest.signedPayload.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(manifest.signature, Base64.DEFAULT))
        }
    }

    private fun installedSigningCertificate(): X509Certificate {
        val packageInfo = when {
            Build.VERSION.SDK_INT >= 33 -> context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            Build.VERSION.SDK_INT >= 28 -> {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            }
            else -> {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                )
            }
        }
        val signer = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()
        } ?: error("无法读取应用签名证书")
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(signer.toByteArray())) as X509Certificate
    }

    private fun installedCertificateSha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(installedSigningCertificate().encoded)
        .joinToString("") { "%02x".format(it) }

    private fun readHttps(url: String, maxBytes: Long): ByteArray {
        val connection = openHttps(url)
        return try {
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0) require(contentLength <= maxBytes) { "响应过大" }
            connection.inputStream.use { input -> input.readLimited(maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadHttps(url: String, target: File, expectedSize: Long) {
        val connection = openHttps(url)
        try {
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0) require(contentLength == expectedSize) { "下载大小不匹配" }
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    var written = 0L
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read.toLong()
                        require(written <= expectedSize && written <= MAX_ARTIFACT_BYTES) {
                            "下载内容超过清单大小"
                        }
                        output.write(buffer, 0, read)
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
        require(parsed.protocol.equals("https", true)) { "只允许 HTTPS 更新地址" }
        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, application/octet-stream")
            connection.setRequestProperty("User-Agent", "HZZS-Android-Update")
            connection.connect()
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            require(connection.url.protocol.equals("https", true)) {
                "更新请求被重定向到非 HTTPS 地址"
            }
            return connection
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private fun validateArtifact(artifact: Artifact) {
        require(SAFE_ASSET_NAME.matches(artifact.name)) { "更新文件名无效" }
        require(SHA256_PATTERN.matches(artifact.sha256)) { "更新文件哈希无效" }
        require(artifact.size in 1..MAX_ARTIFACT_BYTES) { "更新文件大小无效" }
    }
}

object UpdateFileVerifier {
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                update(buffer, 0, read)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyPackage(context: Context, apk: File, manifest: UpdateManifest) {
        require(apk.isFile && apk.length() == manifest.fullApk.size) { "APK 大小不匹配" }
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("无法读取 APK")
        require(info.packageName == manifest.packageName) { "包名不匹配" }
        if (Build.VERSION.SDK_INT >= 28) {
            require(info.longVersionCode == manifest.versionCode) { "版本号不匹配" }
        } else {
            @Suppress("DEPRECATION")
            require(info.versionCode.toLong() == manifest.versionCode) { "版本号不匹配" }
        }
        require(sha256(apk).equals(manifest.fullApk.sha256, true)) { "APK 哈希不匹配" }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        val certificateMatches = signatures.any { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .equals(manifest.certificateSha256, true)
        }
        require(certificateMatches) { "APK 签名证书不匹配" }
    }
}

/** Applies the deterministic HZZS block-delta format with strict bounds checks. */
object DeltaPatchApplier {
    fun apply(oldApk: File, patchZip: File, outputApk: File) {
        require(oldApk.isFile && patchZip.isFile) { "差分输入文件不存在" }
        val parent = requireNotNull(outputApk.absoluteFile.parentFile) { "差分输出缺少父目录" }
        require(parent.isDirectory || parent.mkdirs()) { "无法创建差分输出目录" }
        val temporaryOutput = File(parent, outputApk.name + ".part")
        val temporaryData = File(parent, outputApk.name + ".data.part")
        temporaryOutput.delete()
        temporaryData.delete()

        try {
            ZipFile(patchZip).use { zip ->
                val manifestEntry = requireNotNull(zip.getEntry("patch.json")) { "差分包缺少 patch.json" }
                val dataEntry = requireNotNull(zip.getEntry("data.bin")) { "差分包缺少 data.bin" }
                require(manifestEntry.size in 1..MAX_PATCH_MANIFEST_BYTES) { "差分清单大小无效" }
                require(dataEntry.size in 0..MAX_ARTIFACT_BYTES) { "差分数据大小无效" }

                val manifest = JSONObject(
                    zip.getInputStream(manifestEntry).use {
                        it.readLimited(MAX_PATCH_MANIFEST_BYTES).toString(Charsets.UTF_8)
                    },
                )
                require(manifest.getInt("formatVersion") == 1) { "不支持的差分格式" }
                require(
                    UpdateFileVerifier.sha256(oldApk)
                        .equals(manifest.getString("oldSha256"), true),
                ) { "旧 APK 与差分包不匹配" }
                val expectedSize = manifest.getLong("newSize")
                require(expectedSize in 1..MAX_ARTIFACT_BYTES) { "差分目标大小无效" }
                require(SHA256_PATTERN.matches(manifest.getString("newSha256"))) {
                    "差分目标哈希无效"
                }

                zip.getInputStream(dataEntry).use { input ->
                    temporaryData.outputStream().buffered().use { output ->
                        input.copyLimited(output, MAX_ARTIFACT_BYTES)
                    }
                }

                val operations = manifest.getJSONArray("operations")
                require(operations.length() in 1..MAX_PATCH_OPERATIONS) { "差分操作数量无效" }
                RandomAccessFile(oldApk, "r").use { old ->
                    RandomAccessFile(temporaryData, "r").use { data ->
                        temporaryOutput.outputStream().buffered().use { output ->
                            var written = 0L
                            val buffer = ByteArray(128 * 1024)
                            repeat(operations.length()) { index ->
                                val operation = operations.getJSONObject(index)
                                val offset = operation.getLong("offset")
                                val length = operation.getLong("length")
                                require(offset >= 0 && length > 0) { "差分操作范围无效" }
                                require(written + length <= expectedSize) { "差分输出越界" }
                                val source = when (operation.getString("type")) {
                                    "copy" -> old.also {
                                        require(offset + length <= old.length()) { "旧 APK 读取越界" }
                                    }
                                    "data" -> data.also {
                                        require(offset + length <= data.length()) { "差分数据读取越界" }
                                    }
                                    else -> error("未知差分操作")
                                }
                                source.seek(offset)
                                copyExactly(source, output, length, buffer)
                                written += length
                            }
                            require(written == expectedSize) { "差分输出大小不匹配" }
                        }
                    }
                }

                require(temporaryOutput.length() == expectedSize) { "差分输出大小不匹配" }
                require(
                    UpdateFileVerifier.sha256(temporaryOutput)
                        .equals(manifest.getString("newSha256"), true),
                ) { "差分输出哈希不匹配" }
            }

            if (!temporaryOutput.renameTo(outputApk)) {
                temporaryOutput.copyTo(outputApk, overwrite = true)
                require(temporaryOutput.delete()) { "无法清理差分临时文件" }
            }
        } catch (error: Throwable) {
            temporaryOutput.delete()
            throw error
        } finally {
            temporaryData.delete()
        }
    }

    private fun copyExactly(
        source: RandomAccessFile,
        output: java.io.OutputStream,
        length: Long,
        buffer: ByteArray,
    ) {
        var remaining = length
        while (remaining > 0) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = source.read(buffer, 0, requested)
            require(read > 0) { "差分源文件提前结束" }
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }
}

object ApkInstaller {
    fun launch(context: Context, apk: File) {
        require(apk.isFile) { "APK 文件不存在" }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            apk,
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun InputStream.readLimited(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
    copyLimited(output, maxBytes)
    return output.toByteArray()
}

private fun InputStream.copyLimited(output: java.io.OutputStream, maxBytes: Long): Long {
    var total = 0L
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read.toLong()
        require(total <= maxBytes) { "输入数据超过允许上限" }
        output.write(buffer, 0, read)
    }
    return total
}
