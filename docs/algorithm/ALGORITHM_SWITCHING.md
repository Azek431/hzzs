# 算法切换真相源（ALGORITHM_SWITCHING）

> **当前实现状态（0.1.0）**：客户端暂未启用 Ed25519 签名验签（`AlgorithmPackVerifier` / `AlgorithmTrustAnchors` 已移除）。远端 `.hzzsalg` 经 HTTPS + size/sha256 + ZIP 白名单校验后落盘；下载不再因「未配置信任锚」而 fail-closed。签名相关分支（`SecurityWarning` 相位、`trustAnchorsConfigured`、`keyId` 校验）当前均未启用，下文以「⚠ 暂不启用」标注。

本文是 **算法切换 / 激活** 的完整链路真相源，包含时序图与失败/回退边界表。
代理改算法切换相关代码前**必读**；包级导航见目录级 `CLAUDE.md`。

> 相关真相源：
> - 包级分层导航：`core/algorithm/CLAUDE.md`
> - 纯函数清单：`core/algorithm/logic/CLAUDE.md`
> - 领域层：`domain/vision/CLAUDE.md`
> - 算法包格式 / 发布：`docs/ALGORITHM_SYSTEM_V1.md`
> - 设置页：`feature/settings/CLAUDE.md`

## 一句话总结

**算法切换 = 配置 → 目录 → 下载 → 安装 → 落盘 → 钉选（草稿）→ 保存（onConfigCommitted）→ 待启用（pending）→ 启动分析（ensureConfigured）→ Native configure → generation++**

## 完整链路时序图

```text
设置页 UI（AlgorithmSettingsScreen）
   ↓ onRefresh
AlgorithmCatalogController.refreshCatalog
   ↓ fetchCatalog（HTTPS 多源回退）
AlgorithmNetworkClient.fetchCatalog
   ↓ AlgorithmCatalogPure.parseCatalog（纯函数）
   → List<CatalogRemoteEntry> 写入 StateFlow.remote

   ↓ onDownload(id)
AlgorithmCatalogController.download(id)
   ↓ 信任锚 fail-closed（无锚 → SecurityWarning）
   ↓ downloadAndInstall
AlgorithmNetworkClient.downloadAndInstall
   ↓ HTTPS 下载 + size/sha256 校验
   ↓ AlgorithmPackVerifier.verifyFile（ZIP 白名单 + Ed25519）
   ↓ store.installFromExtracted（staging rename，落盘 filesDir/algorithms/installed/<id>/current/）
   → StateFlow 更新；未分析 + MANUAL → PendingActivation

   ↓ onSelect(id) → vm.selectAlgorithm(id)
   ↓ algorithmCatalog.selectInstalled(id)
   ↓ vm.update { pinnedAlgorithmId = id, selectionMode = MANUAL }
   → 仅草稿 preview，未落盘

顶栏「保存并应用」
   ↓ vm.save → commitSave
   ↓ repository.save(toWrite)
   ↓ algorithmActivation.onConfigCommitted(saved.algorithm, saved.selectedScene)
       ├─ 未在分析 → activateCatalog → engine.configureAlgorithm → generation++
       └─ 正在分析 → pendingCatalogId + UI「待启用」

启动分析（VisionRuntimeController.start）
   ↓ ensureConfigured（消费 pending 或按 config 解析）
   ↓ activateCatalog → engine.configureAlgorithm → generation++

帧循环（analyze）
   ↓ 只读 currentActivation()（当前 generation 快照）
   → 检测到 generation 变化 → 清空 tracker / 动作缓存
```

## 关键不变量（代理必须遵守）

### 1. 两点激活（**不得**改为热切换）

| 调用点 | 触发 | 行为 |
| --- | --- | --- |
| `AlgorithmActivationCoordinator.onConfigCommitted` | `SettingsViewModel.commitSave()` | 未分析 → 立即 configure；分析中 → pending |
| `AlgorithmActivationCoordinator.ensureConfigured` | `VisionRuntimeController` 启动分析前 | 消费 pending 或按 config 解析 |

**不得**在其他位置调用 `configureAlgorithm`。

### 2. generation 单调递增

- 每次成功 `configureAlgorithm` → `generation++`（`DefaultActiveAlgorithmProvider` 用 AtomicLong）
- 回退内置也递增 generation
- 帧循环凭此检测算法变更并进入安全点

### 3. fail-closed

| 场景 | 行为 | 位置 |
| --- | --- | --- |
| 信任锚空 | 拒绝外装下载安装 | `AlgorithmCatalogController.download` / `AlgorithmPackVerifier.verifyEntries` |
| Profile 校验失败 | 回退内置 | `DefaultActiveAlgorithmProvider.activate` |
| 包内公钥 ≠ 信任锚 | 拒绝 | `AlgorithmPackVerifier.verifyEntries`（keyId 必须 = OFFICIAL_KEY_ID） |
| ZIP 白名单违规 | 拒绝 | `AlgorithmPackVerifier.readZipStream` |

### 4. 「待启用」语义

| 条件 | 结果 |
| --- | --- |
| 分析中改钉选 | `pendingCatalogId` + UI「待启用」 |
| 下次 start `ensureConfigured` | 消费 pending |
| 诊断字段 | `pendingCatalogId` / activation `id` / `analysisRunning` |

**不是**自动操作关 / 分析未开。

## 失败 / 回退边界表

| 步骤 | 失败场景 | 回退 / 处理 |
| --- | --- | --- |
| 目录拉取 | Gitee + GitHub 均不可达 | `OfflineWithCache`（有缓存）/ `Error`（无缓存） |
| 目录拉取 | 首选源不可达 | 自动回退次选源（`usedFallback=true`） |
| 目录解析 | schema ≠ 1 / id 非法 / sha256 非法 | IllegalArgumentException（目录不展示） |
| 资产下载 | 哈希不匹配 | 拒绝落盘，抛异常 |
| 验签 | ZIP 违规 / 签名失败 / keyId 未知 | 拒绝安装 |
| 落盘 | staging rename 失败 | 抛异常 |
| 激活 | Profile 校验失败 | 回退 builtin（`usingBuiltinFallback=true`, `loadError` 记录） |
| 激活 | JNI 不可用 | 仅 Kotlin 激活（`AlgorithmPipelineTrace` 标 WARNING） |

## 5 层职责速查

| 层 | 关键类 | 决策 / 职责 |
| --- | --- | --- |
| 领域层 | `AlgorithmRuntimeProfile` / `AlgorithmRulesParser` / `AlgorithmProfileValidator` | 声明式参数、解析、校验 |
| 纯函数层 | `AlgorithmCatalogPure` | resolveActive / mergeInstalled / sort / planUpgrades / computePending / catalogPhaseAfter / parseCatalog / versionToCode |
| 目录/安装层 | `AlgorithmCatalogController` / `AlgorithmNetworkClient` / `AlgorithmPackVerifier` / `InstalledAlgorithmStore` | StateFlow / HTTPS / 验签 / 落盘 |
| 激活层 | `AlgorithmActivationCoordinator` / `DefaultActiveAlgorithmProvider` | 两点激活 / generation / 回退 |
| 追踪/诊断层 | `AlgorithmPipelineTrace` / `AlgorithmRuntimeTrace` / `AlgorithmTraceSinks` | 管线阶段 / 帧 ring / 决策时间线 |

## 改代码时同步哪些文档

| 改动 | 必须同步 |
| --- | --- |
| 两点激活语义 | 本文 + `core/algorithm/CLAUDE.md` + `docs/architecture.md` |
| 纯函数签名 | `core/algorithm/logic/CLAUDE.md` |
| 安全边界（信任锚 / fail-closed） | 本文 + 根 `CLAUDE.md`「安全不变量」 |
| 设置页行为 | `feature/settings/CLAUDE.md` |
| 算法包发布协议 | `docs/ALGORITHM_SYSTEM_V1.md` |

## 测试

- 纯函数：`core/algorithm/logic/AlgorithmCatalogPureTest.kt`（JVM 直测）
- Profile / generation：`domain/vision/AlgorithmRuntimeProfileTest.kt`
- 捆绑安装：`core/algorithm/BundledAlgorithmInstallerTest.kt`
- 管线 / 帧轨迹：`AlgorithmPipelineTraceTest.kt` / `AlgorithmRuntimeTraceTest.kt`
- 门禁：`python tools/quality/check_project.py`
