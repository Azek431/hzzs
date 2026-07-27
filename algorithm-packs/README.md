# 官方算法包源

本目录保存可复现的**官方算法包源树**，不是发布物。

| 路径 | 说明 |
|---|---|
| `official-bamboo-baseline/` | 竹影书屋默认阈值示例包源；亦复制到 APK `assets/algorithms/` 作捆绑 |
| `sea-salt-living-room-v1/` | 海盐客厅多点找色示例包源（作者：酱油）；亦 APK 捆绑 |
| `official-public-keys/` | （保留占位）0.1.0 暂不使用签名，目录留作未来扩展 |

构建：

```bash
python tools/algorithm/validate_algorithm_pack.py --source algorithm-packs/official-bamboo-baseline
python tools/algorithm/build_algorithm_pack.py --source algorithm-packs/official-bamboo-baseline --output build/algorithm
python tools/algorithm/verify_algorithm_pack.py --package build/algorithm/official-bamboo-baseline-v0.1.0.hzzsalg
```

0.1.0 版本不使用 Ed25519 签名，仅做完整性校验（清单/规则/变更日志 + 文件摘要）。
