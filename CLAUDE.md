# CLAUDE.md

本文档为 Claude Code 提供本仓库的开发指导。阅读此文件后，Claude Code 即可高效参与本项目开发。

## 项目概述

**HZZS（火崽崽助手）** 是一个早期阶段的 Android 工具项目，面向跑酷游戏画面分析。提供本地悬浮窗 HUD（模拟帧驱动）、基于状态机的 C++ 分析引擎、无障碍自动操作演示、视觉识别原型与社区入口。当前版本 0.1.0 — UI 外壳、原生分析引擎骨架、HUD 模拟渲染器、自动操作服务、视觉识别原型（绿瓶检测 + 截图采集）已搭建完成，真实的屏幕采集和通用视觉识别模块尚在规划中。

| 项目 | 内容 |
| --- | --- |
| 包名 | `top.azek431.hzzs` |
| 语言 | Kotlin + C++17 (JNI) |
| 最低 SDK | 24 (Android 7.0) |
| 目标/编译 SDK | 37 |
| UI 主题 | 浅色 Material 3（暖白背景 `#FFF8F6`） |
| 构建工具 | Gradle Wrapper + Kotlin DSL |
| 编辑器 | VS Code + PowerShell |
| 推荐 JDK | 17 |
| 签名 | PKCS12，从环境变量读取，不提交到仓库 |
| GitHub | [Azek431/hzzs](https://github.com/Azek431/hzzs) |

## 快速开始

### 日常开发流程

```powershell
# 1. 一键构建 + 安装 + 启动 + 日志监听（VS Code Tasks）
#    在 VS Code 中按 Ctrl+Shift+P → "Tasks: Run Task" → 选择 "🧪 [HZZS] 真机诊断一条龙"

# 2. 仅构建验证
#    VS Code Tasks → "🔨 [HZZS] 构建 Debug APK"

# 3. 提交前完整验证
#    VS Code Tasks → "🛡️ [HZZS] 提交前完整验证"
```

### 命令行构建

```powershell
# 构建 Debug APK
.\gradlew.bat :app:assembleDebug

# 构建 Release APK（需要 AZEK431_RELEASE_* 环境变量）
# 日常开发不需要 Release 构建；如需验证，手动执行：
# .\gradlew.bat :app:clean && .\gradlew.bat :app:assembleRelease

# 清理构建产物（仅在故障时使用，日常不要频繁执行）
.\gradlew.bat clean

# 停止 Gradle Daemon 释放内存
.\gradlew.bat --stop
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

> **注意**：本项目目前**没有测试代码**（`src/test` 和 `src/androidTest` 目录不存在）。CLAUDE.md 中列出的测试命令（`testDebugUnitTest` 等）会返回零结果或失败，不要依赖它们做提交验证。

## 架构说明

### 分层结构

```
┌───────────────────────────────────────────────────────────────┐
│              Kotlin UI 层（35 个文件）                          │
│                                                               │
│  MainActivity ──→ ui/main/ (6 个 Controller)                  │
│    ├── MainViewCache          # View 引用缓存                   │
│    ├── MainInsetCache         # Padding 初始值缓存              │
│    ├── MainInsetsController   # 系统栏适配                     │
│    ├── MainActionBinder       # 按钮/链接事件绑定               │
│    ├── MainDialogController   # 对话框管理                      │
│    └── OverlayPermissionController # 悬浮窗权限引导             │
│                                                               │
│  ui/overlay/ (8 个文件)                                       │
│    ├── OverlayPreviewManager      # 悬浮窗生命周期入口          │
│    ├── OverlayWindowController    # WindowManager 封装         │
│    ├── OverlayDragController      # 拖动逻辑                   │
│    ├── OverlayResizeController    # 缩放逻辑                   │
│    ├── OverlaySettingsBinder      # 透明度/自动操作设置         │
│    ├── OverlayHUDRenderer         # HUD 渲染器（模拟帧生成）     │
│    ├── HUDCanvasView              # Canvas 自定义视图           │
│    ├── HUDDrawers                 # Canvas 绘制扩展函数         │
│    └── HUDColorPalette            # 颜色常量集中管理           │
│                                                               │
│  ui/settings/ (2 个文件)                                      │
│    ├── VisionSettingsActivity   # 视觉识别设置页面             │
│    └── VisionSettingsKeys       # 设置参数键名与默认值          │
│                                                               │
│  ui/disclaimer/                                               │
│    └── DisclaimerActivity       # 免责声明页面                │
│                                                               │
│  ui/community                                                 │
│    └── CommunityLinks           # QQ/Telegram 社区链接         │
│                                                               │
│  service/ (3 个文件)                                         │
│    ├── AutoOperationService     # 无障碍服务入口               │
│    ├── AutoActionQueue          # 操作队列管理                  │
│    ├── GestureInjector          # 触摸事件注入                 │
│    └── OverlayNotificationService # 前台通知服务               │
│                                                               │
│  data/                                                        │
│    ├── native/ (3 个文件)                                     │
│    │   ├── NativeLibraryLoader  # JNI 库加载器                 │
│    │   ├── NativeJsonParser     # JSON 解析器                  │
│    │   └── NativeEngineFacade   # 分析引擎统一门面             │
│    └── vision/ (2 个文件)                                     │
│        ├── ScreenshotCapture    # 屏幕截图采集器               │
│        └── VisionAnalysisBridge # 视觉识别 JNI 桥接            │
│                                                               │
│  model/                                                       │
│    └── FrameData.kt                                           │
│                                                               │
│  util/                                                        │
│    ├── FeatureFlags           # SharedPreferences 偏好管理     │
│    ├── ThreadSafeQueue        # 无锁环形缓冲区                 │
│    └── ObjectPool             # Paint 对象池                  │
├───────────────────────────────────────────────────────────────┤
│              JNI 桥接层                                       │
│  NativeAnalysisBridge.kt ←→ libhzzs_native.so                 │
├───────────────────────────────────────────────────────────────┤
│           C++ 分析核心层（8 个类）                              │
│  NativeAnalysisEngine（协调器）                                 │
│    ├── SceneStateMachine      # 场景模式稳定化                 │
│    ├── RunnerStateMachine     # 玩家姿态推断                   │
│    ├── JumpStageEstimator     # 跳跃阶段跟踪                   │
│    ├── HazardEtaEstimator     # 障碍物 ETA 计算                │
│    └── ActionPromptEngine     # HUD 提示生成                   │
└───────────────────────────────────────────────────────────────┘
```

### 目录结构

```
app/src/main/
├── java/top/azek431/hzzs/          # Kotlin 层（35 个文件）
│   ├── MainActivity.kt              # 首页：Edge-to-Edge 全屏、悬浮窗开关、免责声明入口、社区链接
│   ├── NativeAnalysisBridge.kt      # JNI 桥接层：加载 libhzzs_native.so，暴露 engineInfo/runSelfCheck/analyzeFrame
│   │
│   ├── ui/
│   │   ├── main/                    # 首页 Controller 组（6 个文件）
│   │   │   ├── MainViewCache.kt           # View 引用缓存（防止 findViewById 散落）
│   │   │   ├── MainInsetCache.kt          # Padding 初始值缓存（折叠屏防护）
│   │   │   ├── MainInsetsController.kt    # Edge-to-Edge 系统栏适配
│   │   │   ├── MainActionBinder.kt         # 首页按钮/链接事件绑定
│   │   │   ├── MainDialogController.kt     # 对话框管理
│   │   │   └── OverlayPermissionController.kt # 悬浮窗权限引导
│   │   ├── overlay/                 # 悬浮窗相关（8 个文件）
│   │   │   ├── OverlayPreviewManager.kt   # 悬浮窗生命周期入口（显示/隐藏/参数持久化）
│   │   │   ├── OverlayWindowController.kt # WindowManager.LayoutParams 封装
│   │   │   ├── OverlayDragController.kt   # 拖动逻辑（OnTouchListener）
│   │   │   ├── OverlayResizeController.kt # 缩放逻辑（右下角手柄）
│   │   │   ├── OverlaySettingsBinder.kt   # 透明度/自动操作设置绑定
│   │   │   ├── OverlayHUDRenderer.kt      # HUD 渲染器（模拟帧生成、JNI 调用、UI 更新）
│   │   │   ├── HUDCanvasView.kt           # 自定义 Canvas 视图（双缓冲绘制）
│   │   │   ├── HUDDrawers.kt            # Canvas 绘制扩展函数（纯函数式）
│   │   │   └── HUDColorPalette.kt        # HUD 颜色常量集中管理
│   │   │   └── VisionDebugOverlayView.kt  # 视觉识别调试叠加视图
│   │   ├── settings/                # 视觉识别设置（2 个文件）
│   │   │   ├── VisionSettingsActivity.kt  # 设置页面（ViewPager2 + Tabs）
│   │   │   └── VisionSettingsKeys.kt      # 设置参数键名与默认值
│   │   ├── community/
│   │   │   └── CommunityLinks.kt         # 单例对象：打开 QQ 群 / Telegram 链接
│   │   └── disclaimer/
│   │       └── DisclaimerActivity.kt     # 免责声明页面：滚动到底部才允许同意
│   │
│   ├── service/                     # 服务层（4 个文件）
│   │   ├── OverlayNotificationService.kt # 前台通知服务：防止悬浮窗被系统回收
│   │   ├── AutoOperationService.kt       # 无障碍服务入口
│   │   ├── AutoActionQueue.kt            # 操作队列管理（线程安全）
│   │   └── GestureInjector.kt            # 触摸事件注入（dispatchGesture）
│   │
│   ├── data/                        # 数据层（5 个文件）
│   │   ├── native/                  # JNI 调用封装（3 个文件）
│   │   │   ├── NativeLibraryLoader.kt     # 库加载器（System.loadLibrary）
│   │   │   ├── NativeJsonParser.kt        # JSON 解析器（正则提取）
│   │   │   └── NativeEngineFacade.kt      # 分析引擎统一门面
│   │   └── vision/                  # 视觉识别（2 个文件）
│   │       ├── ScreenshotCapture.kt       # 屏幕截图采集器（API 31+）
│   │       └── VisionAnalysisBridge.kt    # 视觉识别 JNI 桥接
│   │
│   ├── model/
│   │   └── FrameData.kt                    # 数据模型：RectF、FrameAnalysisResult 等
│   │
│   └── util/
│       ├── FeatureFlags.kt                 # SharedPreferences 偏好管理
│       ├── ThreadSafeQueue.kt              # 无锁环形缓冲区（单生产者-单消费者）
│       └── ObjectPool.kt                   # Paint 对象池（模板克隆模式）
│
├── cpp/                             # C++ 原生分析核心
│   ├── CMakeLists.txt               # 将所有源文件编译为 libhzzs_native.so
│   ├── README.md                    # C++ 模块文档：算法边界、对象语义、下一步计划
│   ├── include/hzzs/analysis/       # 头文件（8 个类）
│   │   ├── AnalysisTypes.h          # 所有数据结构：FrameDetections、AnalysisResult、枚举类型
│   │   ├── Geometry.h               # RectF 矩形结构体，含相交检测、边界约束工具
│   │   ├── SceneStateMachine.h      # 场景模式稳定化（连续两帧确认机制）
│   │   ├── RunnerStateMachine.h     # 玩家姿态推断：基于矩形边界和垂直速度
│   │   ├── JumpStageEstimator.h     # 跳跃阶段跟踪：地面→首跳→二连跳
│   │   ├── HazardEtaEstimator.h     # 障碍物 ETA 计算
│   │   ├── ActionPromptEngine.h     # HUD 提示生成，带稳定性过滤
│   │   └── NativeAnalysisEngine.h   # 总协调器：串联所有子模块
│   └── src/                         # 实现文件（7 个 .cpp）
│       ├── analysis/
│       │   ├── SceneStateMachine.cpp
│       │   ├── RunnerStateMachine.cpp
│       │   ├── JumpStageEstimator.cpp
│       │   ├── HazardEtaEstimator.cpp
│       │   ├── ActionPromptEngine.cpp
│       │   └── NativeAnalysisEngine.cpp
│       └── jni/
│           ├── NativeAnalysisBridge.cpp  # JNI 导出函数
│           └── VisionAnalysisBridge.cpp  # 视觉识别 JNI 导出
│
├── res/                             # 资源文件
│   ├── layout/                      # XML 布局（5 个文件）
│   │   ├── activity_main.xml        # 首页布局
│   │   ├── activity_disclaimer.xml  # 免责声明布局
│   │   ├── view_overlay_preview.xml # 悬浮窗面板布局
│   │   ├── view_community_footer.xml # 社区链接页脚布局
│   │   └── activity_vision_settings.xml # 视觉识别设置页面布局
│   ├── drawable/                    # 矢量图标 + 形状选择器（~25 个文件）
│   └── values/                      # 颜色、尺寸、字符串（129+ 个）、主题
│
└── AndroidManifest.xml              # 声明 SYSTEM_ALERT_WINDOW、FOREGROUND_SERVICE、ACCESSIBILITY_SERVICE 权限
```

### 数据流

```
FrameDetections（视觉层输入 / 模拟帧生成）
    → SceneStateMachine → SceneMode（稳定后的场景模式）
    → RunnerStateMachine → RunnerMotion（姿态、着地状态、速度）
    → JumpStageEstimator → jump_stage（0/1/2）
    → HazardEtaEstimator → HazardForecast[]（ETA、推荐动作）
    → ActionPromptEngine → ActionPrompt（稳定的 HUD 提示）
    → AnalysisResult（完整的分析结果，供 HUD 渲染使用）
    → FrameAnalysisResult（Kotlin model 层解析 JSON 得到）
    → HUDCanvasView（Canvas 绘制） / TextView（文本标签）
    → AutoOperationService（如果自动操作已启用，将动作入队）

视觉识别数据流（原型）：
ScreenshotCapture.takeScreenshot() → CaptureResult(pixels, w, h)
    → VisionAnalysisBridge.scanGreenBottle() → VisionGreenBottleResult
    → VisionDebugOverlayView（调试可视化）
```

### 关键设计决策

- **Edge-to-Edge 全屏**：MainActivity 启用全屏边缘到边缘显示，通过 MainInsetsController 处理系统栏安全区域。折叠屏设备上 padding 初始值会被缓存（MainInsetCache），防止无限叠加增长。
- **Controller 模式**：MainActivity 的职责已拆分为 6 个 Controller 类（ui/main/），每个负责单一关注点。View 缓存（MainViewCache）与业务逻辑（MainActionBinder）分离。
- **悬浮窗模块化**：OverlayPreviewManager 是入口，实际职责委托给 OverlayWindowController（窗口管理）、OverlayDragController（拖动）、OverlayResizeController（缩放）、OverlaySettingsBinder（设置绑定）。
- **悬浮窗**：使用 `TYPE_APPLICATION_OVERLAY`（API 26+）或 `TYPE_PHONE` 低版本回退。拖动通过 OverlayDragController 封装。右下角有缩放手柄（OverlayResizeController）。参数通过 SharedPreferences 持久化。悬浮窗显示期间启动前台通知服务（OverlayNotificationService），防止被系统回收。
- **HUD 模拟渲染**：`OverlayHUDRenderer` 在后台线程以 50ms 间隔（20fps）生成模拟跑酷画面数据（正弦波玩家移动 + 周期性危险物），通过 NativeEngineFacade → JNI 传入 C++ 分析引擎，结果更新到悬浮窗 UI。这是演示性质，不代表真实画面采集。
- **HUD 绘制模块化**：HUDCanvasView 只负责视图生命周期和属性 setter。所有绘制逻辑提取为 HUDDrawers.kt 中的扩展函数（纯函数式，无状态）。颜色常量集中在 HUDColorPalette 中。
- **自动操作模块化**：AutoOperationService 是入口，队列管理（AutoActionQueue）和触摸注入（GestureInjector）已分离。通过 dispatchGesture() 注入 JUMP/SLIDE/DOUBLE_JUMP 操作。当前仅用于技术验证。
- **JNI 三层架构**：
  - NativeLibraryLoader：库加载（System.loadLibrary），isAvailable 状态检测
  - NativeJsonParser：JSON 解析（正则提取），容错策略
  - NativeEngineFacade：统一门面，封装所有 JNI 调用
  - 主 NativeAnalysisBridge：兼容门面，保持向后兼容
- **视觉识别原型**：data/vision/ 包提供截图采集（ScreenshotCapture，API 31+）和绿瓶检测（VisionAnalysisBridge）。VisionSettingsActivity 提供参数调节界面。
- **ProGuard/R8 混淆规则**：`NativeAnalysisBridge` 和 `VisionAnalysisBridge` 类保持不被混淆，以确保 JNI 符号名匹配。详见 `proguard-rules.pro`。
- **只读不操控**：应用核心分析引擎仅读取和分析画面，不进行触摸注入、不自动跳跃、未经用户明确授权不录屏。自动操作服务当前仅作为技术演示存在。
- **线程安全队列**：`ThreadSafeQueue` 是无锁环形缓冲区（CAS 循环实现），适用于单生产者-单消费者场景（如 HUD 后台线程 → 主线程消费分析结果）。
- **Paint 对象池**：`ObjectPool.PaintPool` 预定义画笔模板，通过 `new Paint(template)` 克隆避免状态污染，减少高频分配导致的 GC 压力。
- **SharedPreferences 统一管理**：
  - `hzzs_feature_flags`：免责声明、自动操作开关/延迟、版本记录
  - `hzzs_overlay_prefs`：悬浮窗透明度、圆角
  - `hzzs_vision_settings`：视觉识别参数（25+ 个键）

## 开发规范

### 注释规范

本项目所有注释均使用中文。遵循以下约定：

- **Kotlin**：公开/半公开成员使用 KDoc（`/** ... */`）；行内逻辑解释使用 `//`；文件头部使用 `//` 块注释说明整体职责、设计原因、线程模型等
- **C++**：类和成员声明使用 Doxygen 风格 `/** ... */`；行内代码注释使用 `//`
- **XML**：块级注释使用 HTML 风格 `<!-- ... -->`；复杂元素可加行内注释
- **构建文件**：注释应解释"为什么"而非仅仅"是什么"；配置原因需文档化

### 资源管理

- **strings.xml**：删除未使用的字符串而非注释掉——保持精简。新增字符串前先 grep 确认是否已有等价项。
- **drawable**：每个 drawable XML 应包含用途说明、颜色参数、圆角大小和引用位置。矢量图标应标注对应的 Material Design 图标名称。
- **主题一致性**：README、CHANGELOG 中的 UI 描述必须与实际主题一致（本项目为**浅色**主题）。

### 文档维护

- **CHANGELOG.md**：遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式。每次发布前更新。
- **CONTRIBUTING.md**：贡献者须知，包含隐私与安全红线。
- **README.md**：项目对外文档，包含路线图、功能边界、技术信息。
- **docs/** 目录：
  - `architecture.md` — 当前架构与模块边界（含各模块状态标记）
  - `analysis-algorithm.md` — 分析算法详细说明
  - `testing.md` — 测试策略
  - `privacy-and-boundaries.md` — 隐私政策与合规边界
- **cpp/README.md**：C++ 分析引擎文档，记录算法边界和对象语义。修改分析逻辑前请先阅读。
- **已实现 vs 规划中必须分离**：不允许将屏幕采集、实时视觉识别、HUD 绘制等规划中功能描述为已完成。HUD 模拟渲染器是演示性质，需在注释和文档中明确标注。

### 安全红线

- **切勿提交** `.jks`、`.keystore`、`.p12`、`.pfx`、`*.properties`（含密码）、`local.properties`（含 SDK 路径）。
- Release 签名从环境变量 `AZEK431_RELEASE_*` 读取，四个字段全部非空才启用。
- 不在代码、文档或 Issue 中包含用户个人信息、设备日志脱敏前的截图、第三方平台账号凭证。
- 无障碍服务的触摸注入功能不得用于自动化操控游戏——这是合规底线。

## VS Code 任务速查

项目配置了 20+ 个 VS Code Tasks（定义在 `.vscode/tasks.json`），覆盖日常开发全流程。常用任务如下：

### 构建与安装

| 任务 | 说明 |
| --- | --- |
| 🔨 构建 Debug APK | 仅构建，不安装 |
| 📲 安装 Debug APK | 构建并安装到已连接设备 |
| ▶️ 启动已安装应用 | 通过 adb monkey 启动应用 |
| 🚀 构建、安装并启动 | 一条龙任务（安装 + 启动） |

### 调试

| 任务 | 说明 |
| --- | --- |
| 🔌 调试前置 | 安装 + 启动 + JDWP 端口转发（VS Code 调试按钮的前置任务） |
| 🔌 调试一条龙 | 同上，独立运行 |
| 🔌 移除 JDWP 转发 | 清理调试环境 |

### 日志与诊断

| 任务 | 说明 |
| --- | --- |
| 🩺 检查设备与应用状态 | 只读：查看 ADB 设备列表、应用安装状态、进程状态 |
| 🧽 清空设备运行日志 | 清空 logcat 缓冲区 |
| 🐞 监听核心运行日志 | 持续监听 HZZS、崩溃、Activity、窗口相关日志 |
| 🧪 真机诊断一条龙 | 完整流程：清日志 → 构建安装 → 启动 → 监听日志 |
| 📤 导出诊断快照 | 导出设备信息、logcat、截图到 `D:\Azek431-Archives\hzzs-device-diagnostics` |

### 提交验证

| 任务 | 说明 |
| --- | --- |
| ✅ 验证 Debug | 构建 + 单元测试 |
| 🛡️ 提交前完整验证 | 构建 + 单元测试 + lint |

### 其他

| 任务 | 说明 |
| --- | --- |
| 🗑️ 卸载应用 | 从设备卸载（会清除应用数据） |
| 🛑 停止 Gradle Daemon | 释放内存 |
| 🧹 清理构建产物 | 删除构建产物（仅在故障时使用） |
| 🔎 查看 Git 状态 | 只读：显示未提交改动和最近 10 条提交 |

## 已知限制与待办

- **无测试代码**：`src/test` 和 `src/androidTest` 目录不存在。接入真实功能后需补充单元测试和仪器测试。
- **HUD 模拟数据**：`OverlayHUDRenderer` 使用正弦波玩家移动和周期性危险物生成作为演示数据，不代表真实画面分析。`MAX_FRAMES = 600`（30 秒自动停止）是当前限制。
- **自动操作合规性**：`AutoOperationService` 的触摸注入功能仅用于技术演示，正式版本中不应作为默认功能启用。
- **视觉识别原型**：`ScreenshotCapture` 需要 API 31+（Android 12）且依赖无障碍服务。`VisionAnalysisBridge` 当前仅支持绿瓶单行扫描检测，通用视觉识别尚未实现。
- **VisionSettingsActivity**：设置页面使用 ViewPager2 + 手动 RecyclerView.Adapter 实现 Fragment 管理（因项目环境中 FragmentStateAdapter 编译受限）。
- **HUDCanvasView 颜色**：绘制颜色已从 `HUDColorPalette` 集中管理，但仍未与主题资源关联，深色模式适配时需改造。
- **SharedPreferences 三分离**：`FeatureFlags`（`hzzs_feature_flags`）、`OverlayPreviewManager`（`hzzs_overlay_prefs`）、`VisionSettingsKeys`（`hzzs_vision_settings`）各自维护独立的 SharedPreferences，自动操作开关存在三份存储。

## 游戏参考素材

### 素材库位置与用途

```
D:\Code\AI\火崽崽\火崽崽奇妙屋\
├── 原始素材/                          # Azek431 自己录制的游戏视频（4 个，共 11 分 47 秒）
├── 他人分享的原始素材/                 # 社区成员分享的游戏录屏与截图（13 个视频 + 5 张图片）
├── 图片/                              # 截图与抽帧快照
├── 素材库/                            # 整理好的素材分类文件（CSV/JSON 索引）
├── work/                              # AI 分析处理脚本与中间产物
│   ├── frame_extract/                 # 视频抽帧与全景深度报告（主要工作成果）
│   │   ├── README.md                  # 全景深度报告（17 视频 / 16575 帧 / 2.19 GB）
│   │   ├── VIDEO_INDEX.md             # 每个视频的完整技术参数索引
│   │   ├── GAME_INSIGHTS.md           # 游戏关卡洞察报告（美术风格 / 内容分类 / 时间线）
│   │   ├── TECH_DOCS.md               # 技术文档（抽帧参数 / 分析算法说明）
│   │   └── {17 个视频文件夹}/         # 每个视频的抽帧图片 + contact sheet + 分析 JSON
│   ├── library-index/                 # 媒体索引（all-media-current.csv/json）
│   ├── privacy-review/                # 隐私审查文件
│   ├── *.py                           # 元数据采集、抽帧、高亮分析等脚本
│   ├── media-inventory.csv            # 全部素材元数据清单（含 SHA256）
│   └── highlight-candidate-timeline.csv # 高光候选片段时间线
├── output/preview/                    # AI 剪辑预览输出
├── 安全候选片段/                      # 隐私审查通过的素材
└── 待入库/                            # 待分类的新素材
```

**用途**：所有素材均为跑酷游戏画面，用于视觉识别算法测试调优、HUD 提示逻辑验证、归一化坐标适配测试、场景状态机验证等。

**持续更新**：此素材库会随开发持续推进而不断增长——新的游戏录屏、截图、分析报告都会追加到对应目录中。Claude Code 在执行视觉识别、HUD 设计、场景分析相关任务时，应主动参考此素材库中的最新内容。

### 当前素材概况（截至 2026-07-09 全景深度报告）

**总览**：

| 指标 | 数值 |
| --- | --- |
| 视频总数 | 17 个（原始 4 + 他人分享 13） |
| 总时长 | 27 分 38 秒（1,657.7 秒） |
| 总抽帧数 | 16,575 帧（10fps 抽帧） |
| 抽帧总大小 | 2.19 GB |
| 场景变化总数 | 174 处 |
| 图片 | 5 张分享图片 + 若干抽帧快照 |

**分辨率分布**：

- **576×1280（竖屏 9:20）** — 10 个视频，最常见分辨率（推测三星手机录屏）
- **858×1920（竖屏 9:20）** — 3 个视频，Record_ 开头的手机录屏
- **1080×2376~2400（竖屏 9:20）** — 2 个视频，Screenrecording_ 开头，最高清
- **1746×3840（竖屏 9:20）** — 1 个视频，HEVC 编码，唯一异常值

**帧率分布**：

- 52~60 fps — 大多数三星录屏（SVID_）
- 28~30 fps — 导出视频（301c/4fb8/7662）
- 60~88 fps — Screenrecording_（最高 88fps，极速录制）

**编码分布**：

- H.264 — 16 个视频
- HEVC (H.265) — 1 个（video_20260707_191624，唯一异常值）

**美术风格分析**（基于全景深度报告）：

- 统一暖粉/米色系调色板（#E0E0E0, #E0A0A0, #E0E0C0, #E0A080 等）
- 高亮度（avg 176-214）、低饱和度（avg 18-22%）
- 13/17 个视频以"缓慢"运动为主导，符合休闲/治愈系游戏特征
- 16/17 个视频共享相同配色方案，表明来自同一游戏或同一关卡系列

**内容分类**（基于场景变化密度）：

| 类别 | 视频数 | 特征 | 代表视频 |
| --- | --- | --- | --- |
| A. 静态界面/讲解 | 4 | 场景变化 ≤ 1 | video_20260707_191624（0 处） |
| B. 慢节奏玩法 | 6 | 场景变化 2~5 | SVID_20260707_103738_1（2 处） |
| C. 中等节奏玩法 | 4 | 场景变化 9~32 | SVID_20260707_103939_1（32 处） |
| D. 高内容密度 | 3 | 场景变化 4~43 | SVID_20260707_120208_1（43 处） |

### 使用建议

- **视觉识别模块开发**：优先使用 SVID_20260708_152941_1（7:33 最长，4,533 帧，38 处场景变化）或 SVID_20260707_120208_1（4:15，43 处场景变化，变化最丰富）
- **快速验证**：使用 SVID_20260707_103738_1（27 秒）或 4fb87702（10 秒最短）
- **HUD 提示逻辑验证**：SVID_20260707_103939_1（2:30，32 处场景变化，均匀分布）
- **归一化坐标适配测试**：使用 576×1280、858×1920、1080×2376、1746×3840 四种分辨率的素材交叉验证
- **分析引擎自检**：`nativeRunSelfCheck` 基于蛋糕断层场景设计，可对照素材中的断层画面验证 ETA 计算准确性
- **场景状态机验证**：优先使用 Record_2026-07-07-19-11-44（13 处场景变化，涵盖菜单→玩法→结算多种场景）
- **隐私合规**：所有素材已通过隐私审查（`privacy-risk=safe`），SHA256 校验可追溯

### 相关分析文档

- `work/frame_extract/README.md` — 视频素材全景深度报告
- `work/frame_extract/VIDEO_INDEX.md` — 每个视频的详细技术参数
- `work/frame_extract/GAME_INSIGHTS.md` — 游戏关卡洞察（美术风格/内容分类/时间线）
- `work/frame_extract/TECH_DOCS.md` — 抽帧与分析技术文档
- `素材库/all-media-current.csv` — 全部媒体文件完整索引（含 SHA256）

## 与 AI 协作规则

- **执行任务前，必须先识别所有模糊与缺失的需求点，列出问题清单向用户确认后再行动。**
- **默认必须提问**：除非用户明确说"不要问我，你自己决定"或类似表述，否则遇到任何不确定的地方都必须提问确认。
- **99% 把握原则**：必须有 99% 的把握理解项目后才能开始行动。如果对项目结构、业务逻辑、技术细节有任何不清楚的地方，都可以（且应该）向用户提问。用户可以回答"不用问，你看着办"来解除提问要求，但在那之前默认都要问。
- **一次性列出所有问题**：不要分批次问，把能想到的所有疑问一次性整理成清单，让用户逐项确认。
- **与 AI 协作过程中，除本文件外其余沟通均使用中文。**
