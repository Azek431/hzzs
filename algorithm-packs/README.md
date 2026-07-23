# 官方算法包源

本目录保存可复现的**官方算法包源树**，不是已签名发布物。

| 路径 | 说明 |
|---|---|
| `official-bamboo-baseline/` | 竹影书屋默认阈值示例包源；亦复制到 APK `assets/algorithms/` 作捆绑 |
| `sea-salt-living-room-v1/` | 海盐客厅多点找色示例包源（作者：酱油）；亦 APK 捆绑 |
| `official-public-keys/` | 客户端信任锚公钥 PEM / DER base64（无私钥） |

构建：

```bash
python tools/algorithm/validate_algorithm_pack.py --source algorithm-packs/official-bamboo-baseline
python tools/algorithm/build_algorithm_pack.py --source algorithm-packs/official-bamboo-baseline --output build/algorithm
python tools/algorithm/sign_algorithm_pack.py sign \
  --input build/algorithm/official-bamboo-baseline-v0.1.0.hzzsalg \
  --output build/algorithm/official-bamboo-baseline-v0.1.0.signed.hzzsalg \
  --private-key /path/to/algorithm-ed25519-private.pem \
  --key-id hzzs-algorithm-official-1 \
  --public-key-output algorithm-packs/official-public-keys/hzzs-algorithm-official-1.pem
python tools/algorithm/verify_algorithm_pack.py \
  --package build/algorithm/official-bamboo-baseline-v0.1.0.signed.hzzsalg \
  --public-key algorithm-packs/official-public-keys/hzzs-algorithm-official-1.pem
```

私钥**不得**提交到仓库。CI 使用 Secrets：

- `ALGORITHM_SIGNING_PRIVATE_KEY_B64`
- `ALGORITHM_SIGNING_KEY_ID`
