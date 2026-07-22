# tools/algorithm

官方算法包（`.hzzsalg`）构建、签名、校验、目录与发布工具。

| 脚本 | 作用 |
|---|---|
| `validate_algorithm_pack.py` | 源树 schema / 白名单 / 大小校验 |
| `build_algorithm_pack.py` | 可重复构建未签名包 |
| `sign_algorithm_pack.py` | 独立 Ed25519 签名与密钥生成 |
| `verify_algorithm_pack.py` | 验签与完整性 |
| `build_algorithm_catalog.py` | 构建并签名 `stable.json` / `beta.json` |
| `publish_algorithm_release.py` | 端到端发布到 `release-index`（默认 dry-run，**无 Release tag**） |
| `common.py` | 共享限制与 ZIP 规则 |

## 发布形态（无 tag）

资产与目录都在分支 `release-index`：

```text
algorithms/stable.json | beta.json
algorithms/packages/<id>-v<version>.hzzsalg
```

客户端读目录 JSON，再按 `assetPath`（或默认 `algorithms/packages/<filename>`）从 raw URL 下载。  
**不需要**每次创建 `alg-…` GitHub/Gitee Release。

测试：

```bash
python -m unittest discover -s tools/algorithm/tests -v
```

密钥使用 Secrets `ALGORITHM_SIGNING_PRIVATE_KEY_B64` / `ALGORITHM_SIGNING_KEY_ID`，**不要**使用 APK keystore。  
规范见 [`docs/ALGORITHM_SYSTEM_V1.md`](../../docs/ALGORITHM_SYSTEM_V1.md)。
