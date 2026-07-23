# Kotlin 代码地图

Kotlin 产品源码根为 `app/src/main/java/top/azek431/hzzs/`。项目是单 Gradle 模块下的包级分层，不是严格的多模块 Clean Architecture。

## 入口与全局状态

```text
AndroidManifest.xml
→ HzzsApplication：建立 Hilt 根并记录未捕获异常
→ MainActivity.setContent
→ HzzsRoot
   ├─ onboarding 未完成：OnboardingScreen
   └─ 已完成：MainNavigation → home / runtime / settings / about
```

`AppViewModel` 收集 `SettingsRepository.config`，根界面用它驱动主题、首次引导分流和 MCP 服务同步。Feature 页面不拥有全局配置。

## 包职责

| 包 | 主要职责 | 关键入口 | 不应承担 |
| --- | --- | --- | --- |
| `core/model` | AppConfig、场景、检测、运行状态等共同模型 | `AppModels.kt` | Android 权限调用 |
| `core/preferences` | DataStore、校验、草稿会话、配置 JSON | `SettingsRepository.kt` | 直接绘制 UI |
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
| `mcp` | loopback MCP、审批和外部摄入 | `McpService.kt` | 绕过系统权限对话框 |
| `nativevision` | JNI 声明与库加载 | `NativeVision.kt` | 跨调用保存像素地址 |

## 配置状态：正式答案与草稿

持久化真相源是 `DataStoreSettingsRepository`：

```text
DataStore 中的 config_json
+ 进程内 preview
→ preview 存在时优先
→ SettingsRepository.config
```

设置模块共享一个 `SettingsViewModel`：

```text
snapshot → baseline + draft
控件修改 → UI 立即更新完整 draft
→ 防抖后 SettingsEditSession.replace
→ validated
→ 受约束 preview
保存 → DataStore.edit → 清 preview → 新 baseline
丢弃 → 清 preview → 恢复 baseline
```

截图后端、自动操作、MCP、开发者和算法更新等权限型配置在预览阶段保留 baseline；选中不等于已经启动。离开整个设置模块由 `SettingsExitCoordinator` 处理未保存草稿。

### 两种“导入”不要混淆

- 完整 `AppConfig` JSON：`ConfigJson`，当前主要由 MCP 读取/预览/保存；外部输入必须再经 `hardenedForExternalIngest(baseline)`，不能提权。
- 主题包 `.hzzstheme`：`ThemePackageCodec`，普通 UI 可导入导出，仅允许受限声明式主题/悬浮窗外观字段，保存前仍是草稿。

## 截图与帧生命周期

项目没有 `CaptureController`。真实抽象是：

- `FrameSource`
- `FrameSourceFactory`
- `VisionRuntimeController` 作为上层唯一消费者

后端包括 AUTO、MediaProjection、Accessibility、Shizuku、Root。`AUTO` 只委托 MediaProjection；高级后端必须由用户显式选择。

`CapturedFrame` 持有 `IntFramePool.Lease`：

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

1. 经过校验的免责声明和用户开关；
2. 场景置信度、帧新鲜度和场景实验锁；
3. Tracker 稳定帧、`actionable`、位置与置信度；
4. 新鲜的前台包/窗口快照和白名单；
5. generation、去重账本、空间冷却、速率和 TTL；
6. `GestureArbiter` 串行与回执；
7. `HzzsAccessibilityService` 主线程最终复核。

Root 和 Shizuku 在本项目只用于显式截图后端，不是手势后端。

## 应用内算法包

稳定主链：

```text
AlgorithmCatalogController
→ AlgorithmNetworkClient 拉取 release-index 目录
→ 同源下载包并校验 size + SHA-256
→ AlgorithmPackVerifier（ZIP 白名单 + Ed25519）
→ InstalledAlgorithmStore（应用私有目录）
→ AlgorithmActivationCoordinator
→ 安全点配置 Native profile
```

无信任锚时拒绝下载安装。分析运行中不得半途替换 profile；应记录 pending，在保存或下次启动等安全点激活。

当前网络层需特别小心：镜像回退、App 版本兼容计算、目录字段路径校验和状态阶段缺少直接测试。修改前先读 `AlgorithmNetworkClient.kt` 和 `AlgorithmCatalogController.kt`，不要只看设置页面。

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
