# 算法包网络发布（无 Release tag）

> **当前实现状态（0.1.0）**：为简化首发，客户端**暂未启用 Ed25519 官方签名验签**。`AlgorithmPackVerifier` / `AlgorithmTrustAnchors` 已移除；远端 `.hzzsalg` 仅走 HTTPS + size/sha256 + ZIP 白名单（`manifest.json` / `rules.json` / `CHANGELOG.txt`，无签名文件）校验后落盘。`release-index` 协议、目录 schema、发布流程、`tools/algorithm/` 脚本**保持原样**，待签名私钥就位后重新启用验签分支即可回滚。

应用**检测算法更新**只读 `release-index` 分支上的目录 JSON，**不**扫 GitHub Release、**不**要求 `alg-…` tag。完整流程见根 `CLAUDE.md`（历史真相源）。

## 真相源与路径

| 项 | 值 |
| --- | --- |
| 规范全文 | `docs/ALGORITHM_SYSTEM_V1.md` |
| 工具 | `tools/algorithm/*`、`tools/algorithm/README.md` |
| 示例源树 | `algorithm-packs/official-bamboo-baseline/` |
| 客户端目录/下载 | `core/algorithm/AlgorithmNetworkClient.kt` |
| 验签 | `AlgorithmPackVerifier.kt` + BouncyCastle Ed25519 |
| 信任锚 | `AlgorithmTrustAnchors.kt`（`officialPublicKeyDerB64`，当前含 official-1 公钥） |
| 安装落盘 | `InstalledAlgorithmStore.kt` → `filesDir/algorithms/installed/` |
| 激活 | `AlgorithmActivationCoordinator.kt`（save / start 安全点） |

分支 `release-index` 布局：

```text
algorithms/stable.json          # 或 beta.json — 检查更新读这个
algorithms/packages/<filename>  # .hzzsalg 包体 — 下载读这个
```

公开 raw（双源）：

```text
# 目录
https://gitee.com/Azek431/hzzs/raw/release-index/algorithms/stable.json
https://raw.githubusercontent.com/Azek431/hzzs/release-index/algorithms/stable.json

# 包体（assetPath 或默认 packages/<filename>）
…/raw/release-index/algorithms/packages/<id>-v<version>.hzzsalg
…/raw.githubusercontent.com/…/release-index/algorithms/packages/<id>-v<version>.hzzsalg
```

目录条目关键字段：`id` / `version` / `filename` / `assetPath` / `size` / `sha256` / 兼容与场景等。  
**禁止**在目录里写任意外链 URL。旧字段 `tag` 若仍存在，客户端**忽略**，一律走 packages 路径。

## 安全硬规则

1. **私钥永不入库、不进对话日志、不进 commit**：仅 CI Secret `ALGORITHM_SIGNING_PRIVATE_KEY_B64` 或本机用户提供的安全路径；与 APK keystore **分离**。
2. **公钥**可进仓库：写入 `AlgorithmTrustAnchors.officialPublicKeyDerB64`（DER SubjectPublicKeyInfo 的 base64）。列表为空时下载安装必须 fail-closed。
3. 包内仅声明式 JSON/文本；禁止 `.so`/Dex/脚本/模型权重；验签失败不得安装。
4. 算法包**不得**控制手势、Root、包白名单、automation、截图升权。
5. 发布顺序：**先** packages 资产并双侧 raw 校验，**最后**才更新 `algorithms/{channel}.json`（避免「检测到了却下不下来」）。
6. 默认 `publish_algorithm_release.py` 为 **dry-run**；真正上传须用户明确要求并带 `--execute` 与有效 token。

## 客户端检测与下载逻辑（代理改代码时勿回退）

**纯函数下沉**：所有决策（resolveActive / mergeInstalled / sort / planUpgrades / computePending / catalogPhaseAfter / parseCatalog / versionToCode / builtinPackages）已抽到 `core/algorithm/logic/AlgorithmCatalogPure`（纯函数 object，命名即契约）。Controller / Client 只做 StateFlow 持有 + HTTPS 编排，新增决策逻辑必须放 Pure。

```text
设置通道 STABLE|BETA + sourcePreference
  → HTTPS 拉 algorithms/{channel}.json（Gitee 优先可回退 GitHub）
  → AlgorithmCatalogPure.parseCatalog（纯函数）
  → 列表展示；点下载（或 autoDownload）
  → raw 下 packages 资产；校验 size + sha256
  → AlgorithmPackVerifier（ZIP 白名单 + Ed25519 信任锚）
  → InstalledAlgorithmStore 落盘
  → MANUAL「使用此版本」写草稿 pinned → 保存 save
  → ActivationCoordinator.onConfigCommitted：
       未分析 → 立即 configure Native
       分析中 → pendingCatalogId，UI 可「待启用」；下次 start ensureConfigured
  → AUTO：未分析可下载后 activateCatalog；分析中仍 pending
```

- 目录检查：小 JSON，**不**受「仅 Wi‑Fi 下大文件」限制。  
- 包下载：遵守 `UpdateConfig.wifiOnly`。  
- `algorithm.autoCheck`：启动时可刷新目录；`autoDownload`：仅在有信任锚时尝试下最新兼容包。
- **「待启用」**：仅表示引擎尚未切到新钉选（分析中改包或 Catalog pending），**不是**自动操作关。诊断看 `pinned` / activation `id` / `pendingCatalogId`。导航全文：`docs/navigation/KOTLIN.md`、`docs/ALGORITHM_SYSTEM_V1.md`、`docs/algorithm/ALGORITHM_SWITCHING.md`。

## 改包内容后自动升版本并发布（CI / 本机）

顺序仍遵守：**先验证再 bump**（L0 校验在 prepare 内；单测在 workflow 前置）。

| 方式 | 命令 / 触发 |
| --- | --- |
| **CI（推荐）** | push `main` 且改动 `algorithm-packs/**` 或 `tools/algorithm/**` → `algorithm-release.yml` 跑 `prepare_algorithm_release.py --auto-bump --execute`：内容与远端同 `(id,version)` 哈希不同则 **PATCH+1** 并发布，再 **commit 源树 version** 回 main |
| **本机** | `python tools/algorithm/bump_algorithm_version.py --source algorithm-packs/<id>` 只升版本；或 `prepare_algorithm_release.py --auto-bump --execute` 对比远端后升版并上传 |
| **仅升版本** | `bump_algorithm_version.py --level patch\|minor|major`；默认同步 `assets/algorithms/<id>/` |

- 默认 **PATCH**；大改需人工 `--level minor/major` 或先手改 manifest。  
- 同 version 同 sha → **skip**（不重复发）。  
- 同 version 不同 sha 且未开 auto-bump → fail（保护不可变目录）。  
- 私钥仍只走 Secret / 本机路径；**禁止**把私钥贴进聊天或入库。

## 版本号与通道（算法包语义化版本）

算法包版本写在 **`algorithm-packs/<id>/manifest.json` 的 `version`**，与 **App 的 `0.1.0` / versionCode 相互独立**。  
格式：`MAJOR.MINOR.PATCH`（可加预发布后缀，如 `0.2.0-beta.1`，仅建议上 **beta** 通道）。

| 变更类型 | 版本怎么加 | 典型场景 |
| --- | --- | --- |
| **首版** | 固定从 **`0.1.0`** 起（产品约定；官方示例包同此） | 首次对外可检测/可安装的算法包 |
| **补丁** | `PATCH +1`（`x.y.z` → `x.y.(z+1)`） | 修阈值/文案/小回归、兼容修复、changelog 勘误 |
| **次要** | `MINOR +1`，`PATCH` 归零（`x.y.z` → `x.(y+1).0`） | 完整一波识别改进、新场景参数段、行为可感知的一轮交付 |
| **主要** | `MAJOR +1`，`MINOR/PATCH` 归零 | 破坏性 schema、不兼容旧引擎 API、强制抬 `minimumAppVersionCode` / `engineApiVersion` |

**硬规则：**

1. **禁止「改完就先 bump」**。顺序永远是：改内容 → **验证全过** → 再改 `manifest.version`（及 `CHANGELOG.txt`）→ 再打包/发布。  
2. **验证未通过不得升高版本号、不得 `--execute` 上传**。  
3. 用户未指定时：默认按上表推断；有歧义先问（补丁 vs 次要）。  
4. **通道**（与版本独立，用户可选）：`beta`（测试）/ `stable`（稳定）；用户设置 `AlgorithmConfig.channel` 自选订阅；未验证勿上 stable。  
5. 同一 `(id, version)` 不可重复；修正已发布错误应 **升 PATCH 再发**，禁止静默改同版本包体哈希。

## 发布前验证门禁（通过后才允许 bump + 发布）

| 级别 | 命令 / 动作 | 何时必须 |
| --- | --- | --- |
| L0 包结构 | `python tools/algorithm/validate_algorithm_pack.py --source <源树>`（或等价） | 任何 pack 改动 |
| L1 工具链单测 | `python -m unittest discover -s tools/algorithm/tests -v` | 任何 pack/tools 改动 |
| L2 项目门禁 | `python tools/quality/check_resources.py` 与 `check_project.py` | 默认真发前；仅改 packs 也建议 |
| L3 引擎相关 | 动 `engineParams` 或识别行为时：相关 JVM 单测；有条件 native/代表帧/批跑 | 识别行为变更 |
| L4 真机 | 装包激活无崩溃、无明显误动作（与用户确认） | 上 **stable** 前强烈建议 |

失败时修问题并**保持原 version**，不得为过门禁跳过断言。

## 代理如何帮用户「发布算法更新」（推荐流程）

用户说「发算法包 / 更新算法目录 / 让应用能检查到 / 修完发一版 / **全部算法都发布**」时执行下列步骤；**不得**再创建 `alg-…` Release tag（产品 B：无 tag）。

### 常驻约定（用户偏好 · 代理必须记住）

1. **改了算法包内容且任务目标是让用户/手机拿到新包时**：门禁通过后**自动**升 `manifest.version`（+ `CHANGELOG.txt`），再走发布脚本。
2. **用户说「发布 / 全部发布 / 你发布」**：视为授权 `--execute` 真上传（仍须先 L0–L3；缺密钥/token 时停下说明）。
3. **多包**：默认**每个**可发布源树各跑一遍 `publish_algorithm_release.py`；通道优先用该包 `manifest.channel`（缺省：实验 → beta，正式 → stable）。
4. **首次目录为空**（`release-index` 404）：仍可按上表首发 `0.1.0`。
5. **密钥与 token**：只从环境变量或本机路径读取；禁止要求用户把私钥贴进聊天；禁止写入仓库或 commit。
6. 发布成功后：用匿名 raw 抽查目录/包体；同步相关 docs/`CHANGELOG` 若用户可见；**记得 git commit** 源树 version/changelog 变更。
7. 同步 **assets 捆绑树**（`app/src/main/assets/algorithms/<id>/`）与 `algorithm-packs/<id>/` 内容一致。bundled 在更高 `versionCode` 时可覆盖同 origin 的已装种子；网络外装 `originTag=network` 不被冲。
8. **CI 自动发布**：push `algorithm-packs/**` 时 `algorithm-release.yml` 签包并写 `release-index`（默认 GitHub-only）。

### 0. 确认前置

- [ ] 真上传：CI Secrets 或本机 `GH_TOKEN`/`GITHUB_TOKEN`、算法签名私钥；**禁止**用户把私钥贴进聊天  
- [ ] 通道：各包 `manifest.channel` 或用户指定；实验默认 **beta**；明确「正式/稳定」才 stable  
- [ ] 版本意图：内容未变且远端尚无该 version → 可发当前 version；内容已变 → 门禁后 **bump**  
- [ ] 源树：用户说「全部」→ 枚举 `algorithm-packs/*/` 中含 `manifest.json`+`rules.json` 的目录  
- [ ] `AlgorithmTrustAnchors` 是否已配置；若空须告知「目录可更新，手机仍装不上外装包」  
- [ ] 缺密钥：**立即停止 `--execute`**，列出缺失项与设置方式，不假装已发布  

### 1. 改包内容（若需要）— **先不改 version**

- 改 `rules.json`（schema v2：`userThresholds` + `engineParams`）等  
- **不要**先改 `manifest.version`（等门禁过后再 bump）

### 2. 跑验证门禁（L0–L3；stable 确认 L4）

- 全过 → 步骤 3；失败 → 修复重跑，**不 bump、不上传**

### 3. 验证通过后：递增版本 + changelog（内容有变时）

- 按上表改 `manifest.json` 的 `version`（及 assets 镜像树同 version）  
- 更新 `CHANGELOG.txt`（禁止准确率吹嘘）  
- 再跑 L0 确认版本合法  
- 内容未变且作**目录首发**：可保持现有 version  

### 4. 本地 dry-run 发布

```powershell
python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release `
  --channel stable `
  --mirrors github `
  --private-key <用户本机密钥路径> `
  --key-id hzzs-algorithm-official-1
```

多包时对每个源树重复；通道与该包一致。  
期望：`would upload algorithms/packages/…`、`would publish catalog … algorithms/{channel}.json`；**不得**出现 create GitHub release / `alg-` tag。

### 5. 真发布（CI 自动 或 用户已说发布 + 密钥齐）

**优先**：commit + push `main`（含 `algorithm-packs/**`）→ Actions 自动 `--execute`（GitHub-only）。

本机手动（GitHub only）：

```powershell
# 本机 shell 预先设置（勿贴进聊天）：
# $env:GH_TOKEN / $env:GITHUB_TOKEN
# $env:ALGORITHM_SIGNING_PRIVATE_KEY_B64  或  --private-key 路径
# $env:ALGORITHM_SIGNING_KEY_ID = "hzzs-algorithm-official-1"

python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release/bamboo `
  --channel stable `
  --mirrors github `
  --key-id hzzs-algorithm-official-1 `
  --execute
```

成功：packages 有新 filename；所选镜像 raw hash 一致且验签过；**最后**目录 JSON 含对应 version。

### 6. 首次「手机能装」

1. 公钥 DER base64 写入 `AlgorithmTrustAnchors.officialPublicKeyDerB64`（当前仓库通常已配置）  
2. 用户装的 APK 须含该锚；之后只更 `release-index` 即可检查到算法  
3. 私钥只留 CI/本机  

### 7. 用户侧验证

- 设置算法通道与发布 `--channel` 一致（海盐当前默认 **beta**，竹影基线 **stable**）  
- 检查到新 version；有锚可装，无锚拒绝  
- 激活后 `activeAlgorithmId` 为 `pack.<id>` 或内置  

## 代理禁止事项

- 为算法更新建 **Release tag**（除非用户改回旧协议）  
- **验证未通过就 bump 或 `--execute`**  
- 未验证包发 **stable**  
- 私钥/token/keystore 入库或进对话日志  
- 目录 JSON 写任意外链 URL  
- 宣称「push main 即算法更新」  
- 跳过验签 / 只信包内公钥  
- 算法发布与无关 UI 混提交  
- 同 `(id, version)` 改包体哈希

## 与 APK 更新的区别（勿混）

| | APK 更新 | 算法包 |
| --- | --- | --- |
| 索引 | `updates/{channel}.json` | `algorithms/{channel}.json` |
| 资产 | Release / 差分 APK | **`release-index` packages 文件** |
| 签名 | 安装证书 | 独立 Ed25519 |
| 是否可执行 | 是 | 否（声明式参数） |

## 关联文档

- `docs/ALGORITHM_SYSTEM_V1.md` — 算法包格式 / 发布 / 运行时（CC-1）规范全文
- `docs/algorithm/ALGORITHM_SWITCHING.md` — 算法切换完整链路时序图 + 失败/回退边界表
- `core/algorithm/CLAUDE.md` — 模块级真相源（5 层分层）
- `core/algorithm/logic/CLAUDE.md` — 纯函数清单
- `domain/vision/CLAUDE.md` — 领域层导航
- `tools/algorithm/README.md` — 发布工具用法
