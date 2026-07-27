# tools/algorithm

官方算法包（`.hzzsalg`）构建、校验、目录与发布工具。

| 脚本 | 作用 |
|---|---|
| `validate_algorithm_pack.py` | 源树 schema / 白名单 / 大小校验 |
| `build_algorithm_pack.py` | 可重复构建未签名包 |
| `verify_algorithm_pack.py` | 完整性校验（清单/规则/变更日志 + 文件摘要） |
| `build_algorithm_catalog.py` | 构建 `stable.json` / `beta.json` |
| `bump_algorithm_version.py` | 升 `manifest.version`（默认 PATCH）并写 CHANGELOG；默认同步 assets |
| `prepare_algorithm_release.py` | 对比远端：同 version 同哈希则跳过；内容变则自动 PATCH 再发布 |
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

规范见 [`docs/ALGORITHM_SYSTEM_V1.md`](../../docs/ALGORITHM_SYSTEM_V1.md)。

## 自动发布（GitHub push）

`.github/workflows/algorithm-release.yml`：

| 触发 | 行为 |
| --- | --- |
| `push` 到 `main` 且路径含 `algorithm-packs/**` / `tools/algorithm/**` | 校验 → **prepare（内容变则 PATCH）** → **execute** 写 `release-index` → 若升了版本则 **commit 回 main** |
| `workflow_dispatch` | 可选手动；默认真发布 |

- 默认镜像 **`github` only**（`github.token`）；仓库若配置了 `GITEE_TOKEN` 可在手动运行时选 `github,gitee`。
- **自动 PATCH 规则**：本地签出包 sha 与远端同 `(id,version)` 不同 → `bump_algorithm_version` PATCH → 再发布；完全相同则 skip。
- 手机侧：`algorithm.autoCheck` 启动时拉 `algorithms/{channel}.json`（Gitee 优先可回退 GitHub raw）。

本地 dry-run（GitHub only）：

```powershell
python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release/bamboo `
  --channel stable `
  --mirrors github
```

本地「改完自动升 PATCH 并发布」：

```powershell
python tools/algorithm/prepare_algorithm_release.py `
  --source algorithm-packs/sea-salt-living-room-v1 `
  --mirrors github `
  --auto-bump `
  --execute
```
