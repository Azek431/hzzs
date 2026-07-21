# 官方算法签名公钥

本目录用于存放可被 Android 客户端内置的 **Ed25519** 公钥（PEM 与 DER base64）。

- 算法包签名密钥**独立于** Android APK keystore。
- 生产私钥仅保存在 CI Secrets：`ALGORITHM_SIGNING_PRIVATE_KEY_B64`。
- 对应公钥在算法包发布流程中写出，并随 Release 附件 `algorithm-public-key.der.b64` 分发。
- 在首次真实算法发布前，此目录可能仅有说明文件；请勿提交私钥或 PKCS#12。

生成新密钥（仅在安全环境）：

```bash
python tools/algorithm/sign_algorithm_pack.py generate-key \
  --private-out /secure/hzzs-algorithm-official-1.pem \
  --public-out algorithm-packs/official-public-keys/hzzs-algorithm-official-1.pem
```

然后将私钥 PEM 做 base64 后写入 GitHub Secret `ALGORITHM_SIGNING_PRIVATE_KEY_B64`，
`ALGORITHM_SIGNING_KEY_ID` 设为 `hzzs-algorithm-official-1`。
