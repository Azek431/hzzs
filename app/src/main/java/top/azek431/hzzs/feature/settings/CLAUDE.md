# feature/settings — 设置模块（草稿预览 + 显式保存）

设置首页 + 分类子页共享同一 `SettingsViewModel`。改动进入**进程内草稿 preview**，顶栏右上角「保存并应用」才 `save` 落盘；分类间切换保留草稿；离开模块若 dirty 弹窗（保存并离开 / 丢弃 / 取消）。

> 这是 `app/` 里文件最密的包（20 文件 + components/model/screens 三个子目录）。目录名只是提示，**状态所有者和调用链更重要**。

## 职责

- `SettingsViewModel`：草稿预览 + 显式保存；订阅配置；危险项由子页先确认再 `update`。
- `SettingsScreen`：嵌套 NavHost；宽屏左目录右内容；MCP 深链可打开指定子页。
- `SettingsExitCoordinator`：应用级导航壳与设置模块之间转发「离开设置」意图。
- `SettingsCategory` / `SettingsRoutes`：分类路由、首页摘要、搜索匹配。
- 各分类屏：Appearance / Algorithm / Detection / Capture / Overlay / Automation / Network / Mcp / Developer + LogViewer / AlgorithmPipeline / McpAccessLog。

## 入口（按阅读顺序）

1. `SettingsViewModel.kt` — 核心。改草稿/保存/算法激活必读。
2. `SettingsScreen.kt` — 嵌套导航 + dirty 弹窗。
3. `SettingsExitCoordinator.kt` — 离开拦截。
4. `model/SettingsCategory.kt` — 路由与摘要。
5. `screens/SettingsHomeScreen.kt` — 设置首页。
6. `screens/*SettingsScreen.kt` — 各分类屏。
7. `components/SettingsComponents.kt` — 跨屏复用组件。
8. `core/preferences/SettingsRepository`（配置仓库）、`core/algorithm/*`（算法目录/激活）、`core/update/*`（应用更新）。

## 数据流

```text
子页控件
  → vm.update { validated().also(repository.preview) }   ← 乐观 UI + 草稿（不落盘）
  → 顶栏「保存并应用」→ vm.save → repository.save → AppConfig Flow
      → AlgorithmActivationCoordinator.onConfigCommitted（未分析立即 configure；分析中 pending）
  → 离开设置 / 切走主导航：dirty 则弹窗（保存并离开 / 丢弃 / 取消）
  → Theme / Runtime / MCP（preview 优先于已保存）

算法页：bindSettings(draft) 刷新列表 active；selectAlgorithm 写 pinned 草稿
网络页：即时任务（updateState / algorithmState），与草稿字段无关
```

### 草稿与远端回流的互斥

本地有未保存草稿时，`repository.config` 的远端/预览回流**不覆盖**编辑中的 UI（避免 MCP 自动操作等外部写入冲掉草稿）。`mergeRuntimeOwnedAutomationFields`：草稿未改、磁盘已自调的触发距离字段取磁盘值，避免保存盖回旧倍数。

## 与其他模块的关系

```text
AlgorithmSettingsScreen ← algorithmState: StateFlow<AlgorithmCatalogState>
         ↓ onRefresh / onDownload / onSelect
AlgorithmCatalogController ──→ AlgorithmCatalogPure（委托决策）
         ↓ activateCatalog（AUTO 下载后 / 手动保存后）
AlgorithmActivationCoordinator（两点激活）

AlgorithmPipelineScreen ← AlgorithmPipelineTrace.snapshot()（直接读 object，轮询 revision）

SettingsViewModel ──→ UpdateRepository（APK 更新）
SettingsViewModel ──→ McpUiBridge（MCP 服务状态）
SettingsViewModel ──→ VisionRuntimeController / VisionEngine
```

## 不变量 / 安全 / 线程

- 草稿与仓库 preview/save 同源 `validated()`，避免 UI 显示 `enabled=true` 而 preview 因免责版本被洗回 `false`（「确认后开关又弹」）。
- 危险项（开自动操作等）由子页对话框确认后再 `update`；`onLeaveComposition` 卸载时丢弃未保存 preview，避免残留。
- 外部写入（导入/MCP `save_settings`）经 `config` 回流，本地有草稿时不覆盖；仍走 `hardenedForExternalIngest`，不得静默开自动操作或自提权限。
- 开发者选项：关于页连点版本号 7 次开启后设置首页出现「开发者选项」；页内开关可关闭；关于与设置共用 `DeveloperSettingsScreen`。
- 自动操作默认关；算法切换：`selectAlgorithm` 仅支持单赛季且与当前不一致时自动切赛季。
- 线程：`editMutex` 保护保存/丢弃临界区；`update` 经 `editMutex.withLock` 写 preview。

## 改这个包前必读

- 改 `SettingsViewModel.update` / `save`：同步 `SettingsRepository`、`validated()`、`AlgorithmActivationCoordinator.onConfigCommitted`、`McpSettingsPatch`（preview/save 收敛）。
- 改 `SettingsScreen`：同步 `MainActivity.AppNavHost`（主导航）与 `McpUiBridge.settingsSubRoute`（MCP 深链打开子页）。
- 改分类屏：经 `vm.update` 写草稿，不要直接调 `repository.save`；危险项先确认再写。
- 改 `SettingsCategory` 枚举：同步 `SettingsScreen` 的 when 分支、`summary()`、`matchesQuery()`、首页 IA。
- 改 `SettingsExitCoordinator`：唯一调用者是 `MainActivity.MainNavigation`（底部栏/侧栏/返回/MCP 路由）。
- 改开发者页：关于页与设置共用 `DeveloperSettingsScreen`，不要维护第二套开发者 UI。
- 算法「待启用」≠ 自动操作关；诊断看 `pinned` / activation `id` / `pendingCatalogId`。

## 测试

- 相关测试：`SettingsSessionTest`、`SettingsExitCoordinatorTest`、`SettingsUiLogicTest`、`ThemePackageTest`。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 真机验证：草稿保存/离开弹窗/MCP 深链/开发者开关/算法切换。

## 文档真相源

| 用途 | 路径 |
| --- | --- |
| 算法模块分层 | `core/algorithm/CLAUDE.md` |
| 算法切换链路 | `docs/algorithm/ALGORITHM_SWITCHING.md` |
| MCP 自测 | 根 `CLAUDE.md`「代理用 MCP 自测」 |
| 代理导航 | `docs/navigation/KOTLIN.md` |
