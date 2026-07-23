# 按任务找代码

本页回答“我想改一件事，第一步打开哪里”。它是导航，不是新的架构真相源。

## 最短心智模型

```text
Compose 页面发出意图
→ ViewModel/Controller 持有流程状态
→ Repository/Domain 负责配置与规则
→ Service/Platform 执行 Android 能力
→ JNI/C++ 处理单帧像素
```

目录名只是提示，**状态所有者和真实调用链更重要**。例如 `data/vision/VisionRuntimeController` 实际是应用运行时编排层，不能只按 “data” 名称套用教科书分层。

## 我想改什么？

| 任务 | 第一入口 | 关键下一跳 | 先看测试/门禁 |
| --- | --- | --- | --- |
| 应用启动或主导航 | `app/.../MainActivity.kt`：`HzzsRoot`、`AppNavHost` | `HzzsApplication.kt`、各 `feature/*Screen.kt` | 根导航目前缺 UI/instrumentation 测试 |
| 首页展示 | `feature/home/HomeScreen.kt` | `HomeViewModel` → 配置与运行状态 Flow | 相关纯逻辑测试 |
| 开始/停止分析 | `feature/runtime/RuntimeScreen.kt`：`RuntimeViewModel.toggle` | `data/vision/VisionRuntimeController.start/stop` | 运行时端到端测试仍是缺口 |
| 设置分类或即时落盘 | `feature/settings/SettingsScreen.kt`、`SettingsViewModel.kt` | `core/preferences/SettingsRepository.kt`、`SettingsExitCoordinator` | `SettingsSessionTest`、`SettingsExitCoordinatorTest`、`SettingsUiLogicTest` |
| 主题包 | `feature/settings/screens/AppearanceSettingsScreen.kt` | `core/theme/ThemePackage.kt` | `ThemePackageTest` |
| 完整配置 JSON | `core/preferences/SettingsRepository.kt`：`ConfigJson` | `mcp/McpService.kt` 的外部摄入路径 | `SettingsSessionTest`；普通 UI 当前不提供完整配置导入 |
| 截图后端 | `service/capture/FrameCapture.kt` | `CaptureSources.kt`、`platform/compat/CaptureCapabilities.kt` | `CaptureBackendResolutionTest`、`FrameSequenceTest` |
| 帧循环与 Tracker | `data/vision/VisionRuntimeController.kt` | `MultiObjectTracker.kt`、`NativeVisionEngine.kt` | [Kotlin 地图](KOTLIN.md) 中的测试缺口 |
| C++ 检测 | `data/vision/NativeVisionEngine.kt` | `nativevision/NativeVision.kt` → `cpp/jni_bridge.cpp` → `vision_engine.cpp` | [Native 地图](NATIVE.md) |
| HUD 悬浮窗 | `service/overlay/OverlayController.kt` | `VisionRuntimeController.publishOverlay` | 真机权限与自摄入验证 |
| 自动操作 | `domain/automation/AutomationModels.kt` | `VisionRuntimeController.maybeDispatch` → `HzzsAccessibilityService` | `GestureArbiterTest` + 真机前台窗口验证 |
| MCP | `mcp/McpService.kt` | 审批、配置外部摄入、前台服务 | `SECURITY.md` 和 MCP 相关质量门禁 |
| 算法库切换 / 远端更新 | `feature/settings/screens/AlgorithmSettingsScreen.kt` | `AlgorithmCatalogController` → Network/Verifier/Store/Activation；捆绑见 `BundledAlgorithmInstaller` | 算法客户端直接测试目前不足 |
| 检测参数 | `feature/settings/screens/DetectionSettingsScreen.kt` | `AppModels.VisionThresholds`、`SettingsRepository.validated` | `SettingsUiLogicTest` |
| 算法包内容 | `algorithm-packs/<id>/` 与 `app/src/main/assets/algorithms/` | `tools/algorithm/common.py` | 算法工具 unittest + 源树校验 |
| APK 发布 | `.github/workflows/release.yml` | `tools/release/` | `testing.md` 与 Release 签名说明 |
| 算法发布 | `tools/algorithm/publish_algorithm_release.py` | `.github/workflows/algorithm-release.yml` | [工具、测试与 CI](TOOLS_TESTS_CI.md) |

`app/...` 表示 `app/src/main/java/top/azek431/hzzs/`。

## 三条真实数据流

### 配置

```text
设置控件
→ SettingsViewModel.update（乐观 UI）
→ 短防抖后 SettingsRepository.save（validated）
→ SettingsRepository.config Flow
→ Theme / Runtime / MCP 等订阅者
→ 离开设置：flushNow 刷掉挂起写
```

危险项（如手动开自动操作）先确认再写；导入 / MCP 外部摄入经 `hardenedForExternalIngest`，不得静默开启自动操作或自提权限。

### 视觉

```text
FrameSource
→ VisionRuntimeController
→ NativeVisionEngine
→ JNI / C++
→ VisionResultValidator
→ MultiObjectTracker
→ HUD / 自动操作规划
```

`VisionRuntimeController` 是帧循环和 Tracker 的唯一所有者。项目中没有 `CaptureController` 符号。

### 算法包

```text
首次 bind / 刷新
→ BundledAlgorithmInstaller 预装 assets 声明式包（可选）
→ 通道与镜像偏好
→ 拉取 release-index 目录
→ 下载包并校验 size + SHA-256
→ ZIP 白名单 + Ed25519 信任锚验签（远端）
→ 应用私有目录安装
→ 非分析安全点立即激活，否则 pending
→ Native profile 配置
```

无官方信任锚时，外装“官方”包必须 fail-closed。

## 修改前的五问

1. 谁拥有这份状态？
2. 数据是否跨线程或跨帧？谁负责 `close()`？
3. 这是普通配置，还是会触发系统权限/外部输入的安全配置？
4. 文件只是存在，还是已经进入构建与调用链？
5. 哪个测试能证明行为没有退化？

答不出时，先继续读调用者和测试，不要直接重构。
