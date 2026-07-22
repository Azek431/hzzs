package top.azek431.hzzs.core.algorithm

/**
 * 算法包官方信任锚（Ed25519 SubjectPublicKeyInfo DER 的 base64）。
 *
 * - 生产验签**只**认本列表；包内 `publicKeyDerB64` 仅调试对照，不得单独建立信任。
 * - 首次真实算法发布前，可为空列表：此时客户端拒绝任何“官方签名”激活，
 *   仅允许内置 builtin（及显式开发者本地安装路径若另行开放）。
 * - 发布流程：`tools/algorithm/sign_algorithm_pack.py generate-key` 生成密钥后，
 *   将公钥 DER base64 写入本列表，私钥仅放 CI Secret。
 */
object AlgorithmTrustAnchors {
    const val OFFICIAL_KEY_ID = "hzzs-algorithm-official-1"

    /**
     * 占位：与工具链 keyId 对齐。空列表 = 尚未发布官方公钥，验签一律失败（fail-closed）。
     * 真实公钥发布后替换此数组（可多锚轮换）。
     */
    val officialPublicKeyDerB64: List<String> = emptyList()

    fun hasOfficialAnchors(): Boolean = officialPublicKeyDerB64.isNotEmpty()
}
