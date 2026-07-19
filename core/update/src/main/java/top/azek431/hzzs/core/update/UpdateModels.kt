package top.azek431.hzzs.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile

data class Artifact(val name: String, val sha256: String, val size: Long)
data class PatchArtifact(val fromVersionCode: Long, val fromApkSha256: String, val patch: Artifact)

data class UpdateManifest(
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

enum class UpdateSourceId { GITEE, GITHUB }
data class SourceResult(val source: UpdateSourceId, val manifest: UpdateManifest)

class UpdateRepository(private val publicKeyDerBase64: String) {
    companion object {
        const val GITEE_STABLE = "https://gitee.com/Azek431/hzzs/raw/release-index/updates/stable.json"
        const val GITEE_BETA = "https://gitee.com/Azek431/hzzs/raw/release-index/updates/beta.json"
        const val GITHUB_STABLE = "https://raw.githubusercontent.com/Azek431/hzzs/release-index/updates/stable.json"
        const val GITHUB_BETA = "https://raw.githubusercontent.com/Azek431/hzzs/release-index/updates/beta.json"
    }

    suspend fun check(beta: Boolean): SourceResult = withContext(Dispatchers.IO) {
        val primaryUrl = if (beta) GITEE_BETA else GITEE_STABLE
        val secondaryUrl = if (beta) GITHUB_BETA else GITHUB_STABLE
        val primary = runCatching { fetch(UpdateSourceId.GITEE, primaryUrl) }.getOrNull()
        val secondary = runCatching { fetch(UpdateSourceId.GITHUB, secondaryUrl) }.getOrNull()
        when {
            primary != null && secondary != null -> {
                require(primary.manifest.versionCode == secondary.manifest.versionCode) { "发行源版本不一致" }
                require(primary.manifest.fullApk.sha256.equals(secondary.manifest.fullApk.sha256, true)) { "发行源哈希不一致" }
                primary
            }
            primary != null -> primary
            secondary != null -> secondary
            else -> error("Gitee 与 GitHub 更新源均不可用")
        }
    }

    fun artifactUrl(source: UpdateSourceId, manifest: UpdateManifest, artifact: Artifact): String = when (source) {
        UpdateSourceId.GITEE -> "https://gitee.com/Azek431/hzzs/releases/download/${manifest.tag}/${artifact.name}"
        UpdateSourceId.GITHUB -> "https://github.com/Azek431/hzzs/releases/download/${manifest.tag}/${artifact.name}"
    }

    suspend fun download(source: UpdateSourceId, manifest: UpdateManifest, artifact: Artifact, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        http(artifactUrl(source, manifest, artifact)).use { input -> temp.outputStream().buffered().use(input::copyTo) }
        require(temp.length() == artifact.size) { "下载大小不匹配" }
        require(UpdateFileVerifier.sha256(temp).equals(artifact.sha256, true)) { "下载哈希不匹配" }
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
    }

    private fun fetch(source: UpdateSourceId, url: String): SourceResult {
        val raw = http(url).bufferedReader().use { it.readText() }
        val manifest = parse(raw)
        require(verifySignature(manifest.signedPayload, manifest.signatureAlgorithm, manifest.signature)) { "更新清单签名无效" }
        return SourceResult(source, manifest)
    }

    private fun http(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json, application/octet-stream")
        connect()
        require(responseCode in 200..299) { "HTTP $responseCode" }
        inputStream
    }

    private fun parse(raw: String): UpdateManifest {
        val root = JSONObject(raw)
        val payload = root.getString("signedPayload")
        val data = JSONObject(payload)
        fun artifact(o: JSONObject) = Artifact(o.getString("name"), o.getString("sha256"), o.getLong("size"))
        val patches = data.optJSONArray("patches")?.let { arr -> List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            PatchArtifact(p.getLong("fromVersionCode"), p.getString("fromApkSha256"), artifact(p.getJSONObject("patch")))
        }}.orEmpty()
        return UpdateManifest(
            tag = data.getString("tag"), versionName = data.getString("versionName"), versionCode = data.getLong("versionCode"),
            channel = data.getString("channel"), packageName = data.getString("packageName"), certificateSha256 = data.getString("certificateSha256"),
            fullApk = artifact(data.getJSONObject("fullApk")), patches = patches, releaseNotes = data.optString("releaseNotes"),
            signedPayload = payload, signatureAlgorithm = root.getString("signatureAlgorithm"), signature = root.getString("signature"),
        )
    }

    private fun verifySignature(payload: String, algorithm: String, signatureBase64: String): Boolean {
        require(publicKeyDerBase64.isNotBlank()) { "缺少更新公钥" }
        val keyBytes = Base64.decode(publicKeyDerBase64, Base64.DEFAULT)
        val keyAlgorithm = if (algorithm.contains("RSA", true)) "RSA" else "EC"
        val key = KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(keyBytes))
        return Signature.getInstance(algorithm).run {
            initVerify(key); update(payload.toByteArray(Charsets.UTF_8)); verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }
    }
}

object UpdateFileVerifier {
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val read = input.read(buffer); if (read <= 0) break; update(buffer, 0, read) }
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyPackage(context: Context, apk: File, manifest: UpdateManifest) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("无法读取 APK")
        require(info.packageName == manifest.packageName) { "包名不匹配" }
        if (Build.VERSION.SDK_INT >= 28) require(info.longVersionCode == manifest.versionCode) else @Suppress("DEPRECATION") require(info.versionCode.toLong() == manifest.versionCode)
        require(sha256(apk).equals(manifest.fullApk.sha256, true)) { "APK 哈希不匹配" }
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else @Suppress("DEPRECATION") info.signatures.orEmpty()
        val certificateMatches = signatures.any { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }.equals(manifest.certificateSha256, true)
        }
        require(certificateMatches) { "APK 签名证书不匹配" }
    }
}

/** Applies the deterministic HZZS block-delta format and verifies exact output. */
object DeltaPatchApplier {
    fun apply(oldApk: File, patchZip: File, outputApk: File) {
        ZipFile(patchZip).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("patch.json")).bufferedReader().use { it.readText() })
            require(UpdateFileVerifier.sha256(oldApk).equals(manifest.getString("oldSha256"), true)) { "旧 APK 与差分包不匹配" }
            val data = zip.getInputStream(zip.getEntry("data.bin")).readBytes()
            val operations = manifest.getJSONArray("operations")
            RandomAccessFile(oldApk, "r").use { old -> outputApk.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                repeat(operations.length()) { index ->
                    val op = operations.getJSONObject(index)
                    when (op.getString("type")) {
                        "copy" -> {
                            old.seek(op.getLong("offset")); var left = op.getLong("length")
                            while (left > 0) { val count = old.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); require(count > 0); output.write(buffer, 0, count); left -= count }
                        }
                        "data" -> output.write(data, op.getInt("offset"), op.getInt("length"))
                        else -> error("未知差分操作")
                    }
                }
            }}
            require(outputApk.length() == manifest.getLong("newSize")) { "合成 APK 大小不匹配" }
            require(UpdateFileVerifier.sha256(outputApk).equals(manifest.getString("newSha256"), true)) { "合成 APK 哈希不匹配" }
        }
    }
}

object ApkInstaller {
    fun launch(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
