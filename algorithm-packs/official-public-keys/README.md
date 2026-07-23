# 官方算法签名公钥

本目录存放可被 Android 客户端内置的 **Ed25519** 公钥（PEM 与 DER base64）。

| 文件 | 说明 |
| --- | --- |
| `hzzs-algorithm-official-1.pem` | SubjectPublicKeyInfo PEM |
| `hzzs-algorithm-official-1.der.b64` | 同钥 DER 的 base64（与 `AlgorithmTrustAnchors.officialPublicKeyDerB64` 一致） |

- 算法包签名密钥**独立于** Android APK keystore。
- **私钥永不入库**；仅 CI Secret `ALGORITHM_SIGNING_PRIVATE_KEY_B64` 或本机安全路径。
- 客户端生产验签只认 `AlgorithmTrustAnchors`；包内公钥仅调试对照。
- APK 捆绑声明式包（`assets/algorithms/*`）不经本目录验签；远端 `.hzzsalg` 必须过信任锚。

生成新密钥（仅在安全环境）：

```bash
python tools/algorithm/sign_algorithm_pack.py generate-key \
  --private-out /secure/hzzs-algorithm-official-1.pem \
  --public-out algorithm-packs/official-public-keys/hzzs-algorithm-official-1.pem
```

将私钥 PEM 做 base64 后写入 `ALGORITHM_SIGNING_PRIVATE_KEY_B64`，
`ALGORITHM_SIGNING_KEY_ID` 设为 `hzzs-algorithm-official-1`，并把公钥 DER base64
同步写入 `AlgorithmTrustAnchors.officialPublicKeyDerB64`。
