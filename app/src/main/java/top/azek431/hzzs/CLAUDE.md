# Kotlin 修改指南

本文件是 `app/src/main/java/top/azek431/hzzs/` 顶层业务包的入口级指南，统一约束 Kotlin / Compose / JNI / Hilt 产品代码。每个业务包另有独立 `CLAUDE.md`，**改代码前先读该包文档**。

## 职责

- 顶层业务包归属（`MainActivity` / `HzzsApplication` / 根导航在 `feature/` 根包）与全局修改边界。
- 跨包分层约定：`feature` → `data/service` → `domain/core`；`platform/compat` 只做能力探测。

## 入口

- 包级索引：[`feature/CLAUDE.md`](feature/CLAUDE.md)、[`core/CLAUDE.md`](core/CLAUDE.md)。
- 各业务包入口见各自 `CLAUDE.md`（列表见根 `CLAUDE.md` 与 `app/CLAUDE.md`）。

## 不变量

- 公开类和函数注释应描述职责、输入输出、线程和所有权，不复述语法。
- `core/model` 不依赖 Android 服务或 UI。
- `domain` 尽量保持纯 Kotlin，可直接 JVM 测试。
- `feature` 不直接执行 Root、Shell、JNI 或 WindowManager 操作，只调用注入的控制器。
- `service` 和 `platform` 负责 Android API 边界、权限和资源释放。
- `feature/settings` 设置页已拆分为独立分类子页：`McpSettingsScreen.kt`（MCP 服务，普通用户可访问）、`DeveloperSettingsScreen.kt`（开发者选项：关于页连点解锁后出现在设置首页，页内可关；关于入口复用同一屏）；旧 `McpDeveloperSettingsScreen.kt` 已删除。设置模块通过 `SettingsCategory` enum + `NavHost` 管理路由；**改动进入进程内 preview 草稿**，顶栏右上角「保存并应用」才 `save` 落盘；分类间切换保留草稿；离开模块若 dirty 弹窗（保存并离开 / 丢弃 / 取消）。危险项（如手动开自动操作）仍先确认再写草稿。关于页开发者入口仍可直接 `save`（旁路）。
- **算法库 / 热更 /「待启用」**（改 `core/algorithm` 或算法设置页时必读）：
  - 检查更新 → `AlgorithmCatalogController` + `AlgorithmNetworkClient`（只认 `release-index`，无 Release tag）
  - **决策逻辑纯函数化**：所有决策（resolveActive / mergeInstalled / sort / planUpgrades / computePending / catalogPhaseAfter / parseCatalog / versionToCode）已委托给 `core/algorithm/logic/AlgorithmCatalogPure`（JVM 单测直测）；Controller / Client 只做 StateFlow 持有 + HTTPS 编排
  - 下载 → `InstalledAlgorithmStore`（HTTPS + size/sha256 + ZIP 白名单；0.1.0 暂未启用 Ed25519 签名验签）
  - 「使用此版本」→ 草稿 `pinnedAlgorithmId`（MANUAL）；**保存** → `AlgorithmActivationCoordinator.onConfigCommitted`
  - 未分析：立即 Native configure；分析中：`pendingCatalogId` + UI「待启用」，下次 start `ensureConfigured`
  - 「待启用」≠ 自动操作关 / 分析未开；诊断看 activation `id` 与 `pendingCatalogId`
  - 全文：`docs/navigation/KOTLIN.md`、`docs/ALGORITHM_SYSTEM_V1.md`、`docs/algorithm/ALGORITHM_SWITCHING.md`、根 `AGENTS.md` 算法速查、根 `CLAUDE.md` 客户端检测逻辑
  - 分层导航：`core/algorithm/CLAUDE.md`、纯函数清单：`core/algorithm/logic/CLAUDE.md`、领域层：`domain/vision/CLAUDE.md`、设置页：`feature/settings/CLAUDE.md`
- 修改默认赛季：只改 `AppConfig.DEFAULT_SELECTED_SCENE`，并同步迁移/单测；**不要**在 README/CLAUDE/AGENTS/PROGRESS 写死赛季中文名或枚举值。
- 修改版本号：同步 `app/build.gradle.kts` 默认值与 CHANGELOG（若用户可见）。

## 测试

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

真机 MCP 联调（可选，代理推荐）：

```text
adb forward tcp:18765 tcp:8765
# initialize / tools/list / get_status / get_runtime_snapshot
```

Native 与项目门禁见根目录 `docs/TESTING.md`。
