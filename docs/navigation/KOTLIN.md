# Kotlin 代码地图

Kotlin 产品源码根为 `app/src/main/java/top/azek431/hzzs/`。项目是单 Gradle 模块下的包级分层，不是严格的多模块 Clean Architecture。

## 入口与全局状态

```text
AndroidManifest.xml
→ HzzsApplication：建立 Hilt 根并记录未捕获异常
→ MainActivity.setContent
→ HzzsRoot
   ├─ onboarding 未完成：OnboardingScreen（5 步：欢迎/赛季/截图/权限/完成；截图能力来自 CaptureCapabilityResolver）
   └─ 已完成：MainNavigation → home / runtime / settings / about
```

`AppViewModel` 收集 `SettingsRepository.config`，根界面用它驱动主题、首次引导分流和 MCP 服务同步。Feature 页面不拥有全局配置。引导完成前只 `preview`，点「完成」才 `save` 并写入 `onboarding.completed` + 免责声明版本。

## 包职责

| 包 | 主要职责 | 关键入口 | 不应承担 |
| --- | --- | --- | --- |
| `core/model` | AppConfig、场景、检测、运行状态等共同模型 | `AppModels.kt` | Android 权限调用 |
| `core/preferences` | DataStore、校验、配置 JSON、可选 preview 层 | `SettingsRepository.kt` | 直接绘制 UI |
| `core/algorithm` | 目录、下载、验签、安装、激活 | `AlgorithmCatalogController.kt` | 手势与截图升权 |
| `core/designsystem` | 主题、组件、尺寸和动效 | `HzzsTheme.kt` | 业务状态所有权 |
| `core/theme` | 声明式 `.hzzstheme` | `ThemePackage.kt` | 完整 AppConfig 或脚本 |
| `domain/vision` | 视觉模型、结果清洗、profile 解析 | `VisionModels.kt` | 持有 Android 帧缓冲 |
| `domain/automation` | 手势计划、仲裁、提交账本 | `AutomationModels.kt` | 直接调用 Accessibility API |
| `data/vision` | 完整帧循环与 Native 适配 | `VisionRuntimeController.kt` | 被多个并行循环共同持有 Tracker |
| `feature/*` | Compose 页面和 ViewModel | 各 `*Screen.kt` | Root/Shell/JNI/WindowManager |
| `platform/compat` | API 与能力判断、设置跳转 | `CaptureCapabilities.kt` | 静默授予权限 |
| `service/capture` | 截图源、帧租约、系统授权 | `FrameCapture.kt`、`CaptureSources.kt` | 自动选择高级权限 |
| `service/overlay` | 双层悬浮窗与 HUD | `OverlayController.kt` | 参与动作几何判断 |
| `service/automation` | 无障碍手势和最终前台校验 | `HzzsAccessibilityService.kt` | 从 Root/Shizuku 注入手势 |
| `mcp` | IPv4 loopback MCP、会话握手、审批和外部摄入 | `McpService.kt` / `McpHttp.kt` / `McpProtocol.kt` / `McpToolCatalog.kt` | 绕过系统权限对话框；把 TRUSTED_SESSION 当持久特权；绑 `0.0.0.0` |
| `nativevision` | JNI 声明与库加载 | `NativeVision.kt` | 跨调用保存像素地址 |

## 配置状态：即时落盘

持久化真相源是 `DataStoreSettingsRepository`：

```text
DataStore 中的 config_json
+ 进程内 preview（引导/外部预览仍可使用）
→ preview 存在时优先
→ SettingsRepository.config
```

设置模块共享一个 `SettingsViewModel`：

```text
snapshot / config 回流 → 界面展示
控件修改 → 乐观更新 UI
→ 短防抖后 repository.save（validated）
→ 清 preview → algorithmActivation.onConfigCommitted
离开设置 / 切走主导航 → SettingsExitCoordinator → flushNow
```

手动开启自动操作等危险项由子页对话框确认后再调用 `update`。导入配置 / MCP 外部摄入仍走 `hardenedForExternalIngest`，不得静默开自动操作或自提权限。

### 两种“导入”不要混淆

- 完整 `AppConfig` JSON：`ConfigJson`，当前主要由 MCP 读取/预览/保存；外部输入必须再经 `hardenedForExternalIngest(baseline)`，不能提权。
- 主题包 `.hzzstheme`：`ThemePackageCodec`，普通 UI 可导入导出，仅允许受限声明式主题/悬浮窗外观字段，导入后即时写入主题相关字段。

## 截图与帧生命周期

项目没有 `CaptureController`。真实抽象是：

- `FrameSource`
- `FrameSourceFactory`
- `VisionRuntimeController` 作为上层唯一消费者

后端包括 AUTO、MediaProjection、Accessibility、Shizuku、Root。`AUTO` 只委托 MediaProjection；高级后端必须由用户显式选择。

`CapturedFrame` 持有池化像素和释放回调；关闭时由回调归还对应的 `IntFramePool.Lease`：

```text
nextFrame
→ frame.use
→ VisionEngine.analyze
→ 离开 use 自动 close
→ 像素数组归还池中并可能被下一帧覆盖
```

异步记录帧必须在 `close()` 前复制。不要把 `CapturedFrame` 改成 data class，也不要跨 `frame.use` 保存 `pixels`。

## 完成驱动运行时

`VisionRuntimeController.runLoop` 每轮做：

```text
检查 generation 与配置安全点
→ HUD 隐身并等待一帧提交
→ MediaProjection/AUTO 排掉可能含旧 HUD 的一帧
→ 取得干净帧
→ Native 分析
→ Validator
→ Tracker
→ HUD
→ 自动操作评估
→ close 帧
→ 再取下一帧
```

没有按固定 FPS 主动取帧。截图源序号可能因 conflated Channel 和 HUD 排帧跳号，所以 Tracker 使用独立连续的 `trackingSequence`。

Tracker 非线程安全，只由当前帧循环拥有；场景、算法 generation 和会话切换时必须 reset。分析前后都要检查 `generation`，避免停止后的旧结果写回。

## HUD 与自动操作

`OverlayController` 使用两层 Window：全屏不可触摸绘制层 + 小型可拖交互 HUD。截图前切为不可见而不是每帧 remove/add。

自动操作至少经过：

1. 经过校验的免责声明和用户开关（全场景共用，无赛季实验硬锁）；
2. 场景置信度与帧新鲜度；
3. Tracker 稳定帧、`actionable`、位置与置信度；
4. 有效 `GestureBackend` 同源前台快照（无障碍事件 / Shell dumpsys）与可选包门控；
5. generation、去重账本、空间冷却、速率和 TTL；
6. `GestureArbiter` 串行与回执；
7. `GestureDispatcherFactory` 按后端注入（无障碍 `dispatchGesture` 或 Shizuku/Root `input`）。

手势后端与截图后端正交：Shizuku/Root 可选手势注入，AUTO 永不升 Root。

## 应用内算法包（更新 · 选择 · 「待启用」）

### 网络热更主链

```text
设置「检查更新」/ autoCheck
→ AlgorithmCatalogController.refreshCatalog
→ AlgorithmNetworkClient 拉 release-index：algorithms/{stable|beta}.json
→ 列表展示；点下载
→ packages 资产 raw 下载 + size/sha256
→ AlgorithmPackVerifier（ZIP 白名单 + Ed25519 + AlgorithmTrustAnchors）
→ InstalledAlgorithmStore（filesDir/algorithms/installed/）
→ （可选）AUTO 模式未分析时可 activateCatalog 立即 configure
```

- **无 tag**：不读 GitHub Release；只认 `release-index` 分支文件。
- **信任锚空**：目录可展示，**拒绝**远端下载安装；内置 + `assets/algorithms/*` 捆绑仍可用。
- **通道**：`AlgorithmConfig.channel` = STABLE / BETA；海盐远端默认 beta。
- **捆绑**：`BundledAlgorithmInstaller` 按更高 `versionCode` 覆盖同 origin 的 bundled；**不**冲掉 `originTag=network`。

### 激活与「待启用」语义（必读）

UI 徽章 **「待启用」** = `AlgorithmCatalogPhase.PendingActivation` / 卡片 `PENDING_ACTIVATION`，**不是**自动操作未开，也不是分析未启动。

| 概念 | 真相源 | 含义 |
| --- | --- | --- |
| 钉选 / 草稿选择 | `AlgorithmConfig.pinnedAlgorithmId` + MANUAL 模式 | 用户想用的 catalog id |
| UI `active` | `AlgorithmCatalogController` ← `resolveActive(draft/saved)` | **配置解析出的**当前包展示 |
| 引擎真实 profile | `AlgorithmActivationCoordinator` + Native `configureAlgorithm` | 真正参与分析的参数 |
| UI `pendingActivation` | Catalog 状态 | 分析**运行中**已改钉选，引擎尚未切换 |
| 协调器 `pendingCatalogId` | ActivationCoordinator | save 时若在分析中，记一笔，**下次 start** `ensureConfigured` 消费 |

```text
点「使用此版本」
→ Catalog.selectInstalled + ViewModel 写草稿 pinned（可自动切单赛季包场景）
→ 顶栏「保存并应用」
→ repository.save
→ algorithmActivation.onConfigCommitted
     ├─ 未分析 → 立即 activateCatalog → Native configure
     └─ 分析中 → 只写 pendingCatalogId，引擎仍用旧 profile
→ 下次「开始分析」VisionRuntimeController.start
→ ensureConfigured：消费 pending 或按 saved 解析 → configure
```

**禁止**分析帧循环中途半热替换 Native profile（generation / Tracker 安全点）。

排障对照诊断导出：

- `algorithm.pinned` / `selectionMode` / `channel`
- `Algorithm activation id=`（引擎侧）
- `pendingCatalogId` / `analysisRunning`
- pipeline 阶段与 `Last frame`

常见误解：诊断里 `vision.running=false` 且 `usingBuiltinFallback=true`、钉选 `builtin-…` → 当前就是内置，**不应**再显示待启用；若仍显示，多半是 Catalog `pendingActivation` 未在 save/bind 后清除（见 `bindSettings` 清理条件）。

修改前读：`AlgorithmCatalogController.kt`、`AlgorithmActivationCoordinator.kt`、`AlgorithmNetworkClient.kt`、设置页 `AlgorithmSettingsScreen.kt`。

## 对应测试与明显缺口

| 范围 | 已有测试示例 | 仍缺少 |
| --- | --- | --- |
| 设置与外部摄入 | `SettingsSessionTest`、`SettingsExitCoordinatorTest` | DataStore/Hilt/导航端到端 |
| 主题包 | `ThemePackageTest` | 完整 UI 文档契约 |
| 截图解析 | `CaptureBackendResolutionTest`、`FrameSequenceTest` | 各真实后端 stop/异常清理 |
| 视觉结果 | `VisionResultValidatorTest`、`ApproximateContoursTest` | Tracker 与运行时端到端 |
| 自动操作 | `GestureArbiterTest` | 真实无障碍前台窗口集成 |
| 算法客户端 | 少量设置 round-trip/流程追踪 | 网络、Verifier、Store、Activation 的直接测试 |
| Compose | 纯逻辑为主 | `src/androidTest` UI/instrumentation |

## AI 修改检查表

- 是否从状态所有者开始，而不是从文案反推？
- 是否让 Feature 直接碰了平台能力？
- 是否保持 `AUTO` 不升权？
- 是否跨帧保存了缓冲引用？
- 是否删掉了 generation 或前台二次校验？
- 是否把 `displayContour` 用于 Tracker/动作？
- 是否把“字段存在”误写成“运行时已消费”？
- 是否为配置、网络和权限变化补了对应测试？
