# core/algorithm/CLAUDE.md

面向 Claude / Codex 等代理的**算法模块真相源**。

## 模块职责

算法包系统 = 声明式视觉参数（`.hzzsalg`）的下载、ZIP 白名单校验、落盘、选择与激活。
**算法只算数据（`Detection`/`bounds`），不控制手势、Root、包名白名单、自动化门禁**。

## 分层（5 层，依赖方向向下）

```text
feature/settings (UI)                  ← AlgorithmSettingsScreen / AlgorithmPipelineScreen
    ↓ 注入
core/algorithm (StateFlow + 激活)      ← AlgorithmCatalogController / AlgorithmActivationCoordinator
    ↓ 委托
core/algorithm/logic (纯函数)          ← AlgorithmCatalogPure（JVM 单测直测）
core/algorithm (网络 + ZIP 白名单 + 落盘)    ← AlgorithmNetworkClient / InstalledAlgorithmStore
domain/vision (领域模型)               ← AlgorithmRuntimeProfile / AlgorithmRulesParser / AlgorithmProfileValidator
```

| 层 | 关键文件 | 职责 |
|---|---|---|
| 领域层 | `domain/vision/AlgorithmRuntimeProfile.kt`、`AlgorithmRulesParser.kt`、`VisionModels.kt` | 声明式参数模型、rules.json 解析、Profile 校验（fail-closed 回退内置） |
| 目录/安装层 | `AlgorithmCatalogController.kt`、`AlgorithmNetworkClient.kt`、`InstalledAlgorithmStore.kt`、`BundledAlgorithmInstaller.kt` | StateFlow 目录、HTTPS 下载、ZIP 白名单校验、磁盘落盘、APK 捆绑预装 |
| 纯函数层 | `logic/AlgorithmCatalogPure.kt` | 所有决策逻辑（resolveActive / mergeInstalled / sort / planUpgrades / computePending / catalogPhaseAfter / parseCatalog / versionToCode / builtinPackages） |
| 激活层 | `AlgorithmActivationCoordinator.kt`、`data/vision/DefaultActiveAlgorithmProvider.kt` | 配置提交/启动两点安全切换；analysisRunning 时只 pending；generation 单调递增 |
| 追踪/诊断层 | `AlgorithmPipelineTrace.kt`、`AlgorithmRuntimeTrace.kt`、`AlgorithmTraceSinks.kt` | 管线阶段、帧级 ring、决策时间线；sink 接口供 ViewModel 注入 |

## 关键不变量（代理必须遵守）

### 1. 两点激活（**不得**改为热切换）

```text
保存并应用 → AlgorithmActivationCoordinator.onConfigCommitted
               · 未在分析 → 立即 configure Native
               · 正在分析 → pendingCatalogId + UI「待启用」
启动分析前  → AlgorithmActivationCoordinator.ensureConfigured（消费 pending 或按 config 解析）
```

- `onConfigCommitted` 仅在 `SettingsViewModel.commitSave()` 调用
- `ensureConfigured` 仅在 `VisionRuntimeController` 启动分析前调用
- 分析中改钉选 → 只 pending，**不**半热切换

### 2. generation 单调递增

- 每次成功 `configureAlgorithm` → `generation++`
- 帧循环凭此检测算法变更并进入安全点
- 回退内置也递增 generation

### 3. 完整性校验

- Profile 校验失败 → 回退内置（`DefaultActiveAlgorithmProvider.activate`）
- 远端 `.hzzsalg` 经 HTTPS + size/sha256 + ZIP 白名单校验后落盘（0.1.0 暂未启用 Ed25519 签名验签；签名约束将来启用时以「fail-closed」规则回滚）

### 4. 「待启用」语义

- 仅 `AlgorithmCatalogPhase.PendingActivation` 与 `pendingCatalogId`
- 分析中改钉选 → pending；下次 start `ensureConfigured` 消费
- **不是**自动操作关 / 分析未开；诊断看 activation `id` 与 `pendingCatalogId`

## 文件清单

| 文件 | 大小 | 改动边界 |
|---|---|---|
| `AlgorithmActivationCoordinator.kt` | 11KB | **慎改**：两点激活语义 |
| `AlgorithmCatalogController.kt` | 16KB | StateFlow 持有 + Android 边界 |
| `AlgorithmNetworkClient.kt` | 10KB | HTTPS 编排 + ZIP 白名单解压 |
| `logic/AlgorithmCatalogPure.kt` | 15KB | 纯函数集（JVM 单测覆盖） |
| `AlgorithmTraceSinks.kt` | 3KB | 追踪层注入适配器 |
| `AlgorithmModels.kt` | 8KB | UI 模型 + 状态 |
| `AlgorithmIds.kt` | 1.4KB | Catalog/Runtime ID 映射 |
| `AlgorithmPipelineTrace.kt` | 9KB | 管线阶段 object |
| `AlgorithmRuntimeTrace.kt` | 12KB | 帧轨迹 object |
| `InstalledAlgorithmStore.kt` | 11KB | 磁盘落盘 |
| `BundledAlgorithmInstaller.kt` | 7.6KB | APK 捆绑预装（不经外装验签） |

## 测试

```text
:app:testDebugUnitTest
```

相关单测：
- `core/algorithm/logic/AlgorithmCatalogPureTest.kt`（纯函数，覆盖 resolveActive / mergeInstalled / sort / planUpgrades / computePending / versionToCode / parseCatalog / statusAgainst）
- `domain/vision/AlgorithmRuntimeProfileTest.kt`（Profile 校验 + generation）
- `core/algorithm/BundledAlgorithmInstallerTest.kt`（versionCode / decideBundledAction）
- `core/algorithm/AlgorithmPipelineTraceTest.kt` / `AlgorithmRuntimeTraceTest.kt`

## 文档真相源

| 用途 | 路径 |
|---|---|
| 算法包格式 / 发布 | `docs/ALGORITHM_SYSTEM_V1.md` |
| 算法切换完整链路 | `docs/algorithm/ALGORITHM_SWITCHING.md` |
| 代理导航 | `docs/navigation/KOTLIN.md` |
| 本模块导航 | `core/algorithm/CLAUDE.md`（本文件） |
| 纯函数清单 | `core/algorithm/logic/CLAUDE.md` |
| 领域层导航 | `domain/vision/CLAUDE.md` |
| 设置页导航 | `feature/settings/CLAUDE.md` |
