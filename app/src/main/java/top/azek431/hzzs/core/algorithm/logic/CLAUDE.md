# core/algorithm/logic/CLAUDE.md

`core/algorithm/logic/` 是算法模块的**纯函数层**。

## 契约

**本目录的所有方法均为纯函数**（输入 → 输出，无副作用、无 Android 框架依赖、无 StateFlow / 无单例状态）。
违反此契约 = 必须迁出本目录。

- 单一对象：[AlgorithmCatalogPure](AlgorithmCatalogPure.kt)
- 测试：[AlgorithmCatalogPureTest.kt](../logic/AlgorithmCatalogPureTest.kt)（JVM 直测）

## 纯函数清单

| 函数 | 输入 | 输出 | 用途 |
| --- | --- | --- | --- |
| `parseCatalog(raw, channel, source, appVersionCode, trustAnchorsConfigured)` | 目录 JSON 文本 + 元数据 | `List<CatalogRemoteEntry>` | 目录 JSON → 模型；校验 schema/id/文件名/sha256 |
| `resolveActive(installed, pinned, mode, scene)` | 已装列表 + 钉选 ID + 模式 + 场景 | `AlgorithmPackageInfo?` | MANUAL 钉选优先 → builtin；AUTO 场景兼容最新 |
| `previousOf(installed, activeId)` | 已装列表 + 当前激活 ID | `AlgorithmPackageInfo?` | 回滚按钮候选（最高 version 非内置非当前） |
| `mergeInstalled(current, extras)` | 两个列表 | `List<AlgorithmPackageInfo>` | 按 id 去重，bundled/已安装覆盖远端 |
| `mergeDiskInstalled(records)` | `List<InstalledAlgorithmRecord>` | `List<AlgorithmPackageInfo>` | 磁盘记录 → UI 模型（纯映射） |
| `planUpgrades(installed, remote, trustAnchorsConfigured)` | 已装 + 远端 + 锚 | `UpgradePlan` | 可升级包（纯计划，不触发下载） |
| `computePending(pendingFromUi, activeId, pinnedId, mode, analysisRunning)` | pending 上下文 | `AlgorithmPackageInfo?` | 推导「待启用」包 |
| `catalogPhaseAfter(current, remoteInfos, installed, catalog)` | 相位上下文 | `AlgorithmCatalogPhase` | 由远端/已装列表推导目录相位 |
| `sortInstalled(installed, activeId, scene)` | 列表 + 当前 ID + 场景 | `Comparator<AlgorithmPackageInfo>` | 已安装排序 |
| `sortRemote(remote, scene)` | 列表 + 场景 | `Comparator<AlgorithmPackageInfo>` | 远端排序 |
| `versionToCode(version)` | 语义化版本 | `Long` | `1.2.3` → 1_002_003 |
| `builtinPackages()` | — | `List<AlgorithmPackageInfo>` | 内置算法种子（仅一条） |

## 安全常量（单一真相源）

| 常量 | 用途 |
| --- | --- |
| `SAFE_ID` | 算法 id（与 `tools/algorithm/common.py` 对齐） |
| `SAFE_NAME` | 文件名 |
| `SAFE_SHA256` | 64 hex chars |
| `SAFE_ASSET_PATH` | `algorithms/packages/<safe-name>` |

下游（`AlgorithmNetworkClient` / `AlgorithmCatalogController`）**不再各自维护正则**，统一消费本对象。

## 内部数据类

- `CatalogRemoteEntry`（通用模型在 `core/algorithm/AlgorithmModels.kt`）
- `RemoteCatalogMeta`（目录元数据，用于相位推导）
- `UpgradePlan` / `UpgradeResult`（升级计划 / 执行结果）

## 测试约定

每个纯函数必须有对应单测，覆盖：

- 主路径
- 边界（空列表 / 缺失 / 不兼容）
- 优先级（bundled > installed > remote；active > pending > current）

## 与其他层的关系

```text
AlgorithmCatalogController ──→ AlgorithmCatalogPure（委托决策）
AlgorithmNetworkClient     ──→ AlgorithmCatalogPure（委托解析 + 安全常量）
AlgorithmCatalogPureTest   ──→ AlgorithmCatalogPure（JVM 直测，无 Android）
```
