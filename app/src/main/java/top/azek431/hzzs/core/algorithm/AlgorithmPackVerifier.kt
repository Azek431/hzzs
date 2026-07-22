package top.azek431.hzzs.core.algorithm

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 校验 `.hzzsalg`：ZIP 白名单、大小、路径穿越、Ed25519 签名（内置信任锚）。
 *
 * 与 tools/algorithm 规则对齐；失败抛 [IllegalArgumentException]。
 */
@Singleton
class AlgorithmPackVerifier @Inject constructor() {
    data class VerifiedPack(
        val catalogId: String,
        val version: String,
        val keyId: String,
        val entries: Map<String, ByteArray>,
        val sha256: String,
    )

    fun verifyFile(packageFile: File): VerifiedPack {
        require(packageFile.isFile) { "算法包不存在" }
        require(packageFile.length() in 1..MAX_COMPRESSED_BYTES) { "算法包大小无效" }
        val sha256 = sha256(packageFile)
        val entries = readZip(packageFile)
        return verifyEntries(entries, sha256)
    }

    fun verifyBytes(bytes: ByteArray): VerifiedPack {
        require(bytes.size in 1..MAX_COMPRESSED_BYTES.toInt()) { "算法包大小无效" }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val entries = readZipBytes(bytes)
        return verifyEntries(entries, sha256)
    }

    private fun verifyEntries(entries: Map<String, ByteArray>, sha256: String): VerifiedPack {
        require(entries.keys.containsAll(REQUIRED)) { "算法包缺少必需文件" }
        require(entries.keys.all { it in ALLOWED }) { "算法包含非法文件名" }
        require(entries.values.sumOf { it.size.toLong() } <= MAX_TOTAL_UNCOMPRESSED) {
            "未压缩合计过大"
        }
        entries.forEach { (name, data) ->
            require(data.size <= MAX_FILE_BYTES) { "文件过大: $name" }
        }

        val signatureRaw = entries[SIGNATURE_FILE]
            ?: error("缺少 signature.json")
        val signatureDoc = JSONObject(String(signatureRaw, Charsets.UTF_8))
        require(signatureDoc.optInt("schemaVersion") == 1) { "不支持的 signature schema" }
        require(signatureDoc.optString("signatureAlgorithm") == "Ed25519") {
            "不支持的签名算法"
        }
        val keyId = signatureDoc.getString("keyId")
        require(keyId == AlgorithmTrustAnchors.OFFICIAL_KEY_ID) { "未知 keyId: $keyId" }
        require(AlgorithmTrustAnchors.hasOfficialAnchors()) {
            "客户端尚未配置官方算法公钥；拒绝安装外部包"
        }

        val unsigned = entries.filterKeys { it != SIGNATURE_FILE }
        val signedPayloadStr = signatureDoc.getString("signedPayload")
        val signedPayload = JSONObject(signedPayloadStr)
        val files = signedPayload.getJSONArray("files")
        require(files.length() == unsigned.size) { "签名文件列表长度不匹配" }
        val digestMap = unsigned.mapValues { (_, data) ->
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        }
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val name = item.getString("name")
            val expectedSha = item.getString("sha256").lowercase()
            val expectedSize = item.getLong("size")
            val actual = unsigned[name] ?: error("签名列出未知文件: $name")
            require(actual.size.toLong() == expectedSize) { "文件大小不匹配: $name" }
            require(digestMap[name] == expectedSha) { "文件哈希不匹配: $name" }
        }

        val manifest = JSONObject(String(unsigned.getValue("manifest.json"), Charsets.UTF_8))
        val catalogId = manifest.getString("id")
        val version = manifest.getString("version")
        require(signedPayload.optString("algorithmId") == catalogId) { "algorithmId 不一致" }
        require(signedPayload.optString("version") == version) { "version 不一致" }

        val signatureB64 = signatureDoc.getString("signature")
        val signature = Base64.getDecoder().decode(signatureB64)
        val message = signedPayloadStr.toByteArray(Charsets.UTF_8)
        // 规范化：与 tools 一致使用 signedPayload 字符串原文验签
        val ok = AlgorithmTrustAnchors.officialPublicKeyDerB64.any { b64 ->
            verifyEd25519(b64, message, signature)
        }
        require(ok) { "算法包签名校验失败" }

        // rules 基础解析
        val rules = JSONObject(String(unsigned.getValue("rules.json"), Charsets.UTF_8))
        require(rules.optInt("schemaVersion") in 1..2) { "rules schema 不支持" }

        return VerifiedPack(
            catalogId = catalogId,
            version = version,
            keyId = keyId,
            entries = unsigned,
            sha256 = sha256,
        )
    }

    private fun verifyEd25519(publicKeyDerB64: String, message: ByteArray, signature: ByteArray): Boolean {
        return runCatching {
            val der = Base64.getDecoder().decode(publicKeyDerB64)
            // SPKI DER for Ed25519 is 44 bytes: header + 32-byte raw key
            val raw = if (der.size == 32) {
                der
            } else if (der.size >= 44) {
                der.copyOfRange(der.size - 32, der.size)
            } else {
                return false
            }
            val params = Ed25519PublicKeyParameters(raw, 0)
            val signer = Ed25519Signer()
            signer.init(false, params)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        }.getOrDefault(false)
    }

    private fun readZip(file: File): Map<String, ByteArray> =
        file.inputStream().use { readZipStream(it) }

    private fun readZipBytes(bytes: ByteArray): Map<String, ByteArray> =
        ByteArrayInputStream(bytes).use { readZipStream(it) }

    private fun readZipStream(input: java.io.InputStream): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                count++
                require(count <= MAX_FILES) { "算法包文件过多" }
                require(!entry.isDirectory) { "拒绝目录条目" }
                val name = entry.name.replace('\\', '/')
                require(!name.contains("..") && !name.startsWith("/") && !name.contains("/")) {
                    "非法路径: $name"
                }
                require(name in ALLOWED) { "非白名单文件: $name" }
                require(!out.containsKey(name)) { "重复文件: $name" }
                val data = zis.readBytes()
                require(data.size <= MAX_FILE_BYTES) { "文件过大: $name" }
                out[name] = data
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
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

    companion object {
        private const val SIGNATURE_FILE = "signature.json"
        private val ALLOWED = setOf("manifest.json", "rules.json", "CHANGELOG.txt", SIGNATURE_FILE)
        private val REQUIRED = setOf("manifest.json", "rules.json", "CHANGELOG.txt", SIGNATURE_FILE)
        private const val MAX_FILES = 16
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_TOTAL_UNCOMPRESSED = 1024 * 1024L
        private const val MAX_COMPRESSED_BYTES = 1024 * 1024L
    }
}
