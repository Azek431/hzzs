package top.azek431.hzzs.core.algorithm

/**
 * 算法包官方信任锚（Ed25519 SubjectPublicKeyInfo DER 的 base64）。
 *
 * - 生产验签**只**认本列表；包内 `publicKeyDerB64` 仅调试对照，不得单独建立信任。
 * - 列表为空时外装下载/安装 fail-closed；内置引擎与 APK 捆绑声明式包仍可用。
 * - 发布流程：`tools/algorithm/sign_algorithm_pack.py generate-key` 生成密钥后，
 *   将公钥 DER base64 写入本列表，私钥仅放 CI Secret。
 */
object AlgorithmTrustAnchors {
    const val OFFICIAL_KEY_ID = "hzzs-algorithm-official-1"

    /**
     * 官方公钥（SubjectPublicKeyInfo DER 的 base64）。
     * 与 `algorithm-packs/official-public-keys/hzzs-algorithm-official-1.*` 及
     * keyId [OFFICIAL_KEY_ID] 对齐。私钥仅本机/CI，永不入库。
     * 列表为空时外装下载安装 fail-closed；APK 捆绑声明式包不经本列表。
     */
    val officialPublicKeyDerB64: List<String> = listOf(
        // hzzs-algorithm-official-1 — Ed25519 SPKI DER
        "MCowBQYDK2VwAyEA5GCJRq1MTKvh7TJYLR1O/JhjoyXirlhAGwdryYSj17s=",
    )

    fun hasOfficialAnchors(): Boolean = officialPublicKeyDerB64.isNotEmpty()
}
