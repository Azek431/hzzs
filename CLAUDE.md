# CLAUDE.md

本文档为 Claude Code 提供本仓库的开发指导。阅读此文件后，Claude Code 即可高效参与本项目开发。

## 项目概述

**HZZS（火崽崽助手）** 是一个早期阶段的 Android 工具项目，面向跑酷游戏画面分析。提供本地悬浮窗 HUD（模拟帧驱动）、基于状态机的 C++ 分析引擎、无障碍自动操作演示与社区入口。当前版本 0.1.0 — UI 外壳、原生分析引擎骨架、HUD 模拟渲染器和自动操作服务已搭建完成，真实的屏幕采集和视觉识别模块尚在规划中。

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
┌───────────────────────────────────────────────────────┐
│              Kotlin UI 层（13 个文件）                   │
│  MainActivity ──→ OverlayPreviewManager               │
│  DisclaimerActivity ──→ FeatureFlags                  │
│  CommunityLinks ──→ NativeAnalysisBridge              │
│  OverlayHUDRenderer ──→ HUDCanvasView                 │
│  AutoOperationService ──→ AccessibilityService        │
│  OverlayNotificationService ──→ Foreground Service    │
├───────────────────────────────────────────────────────┤
│              Model 层                                   │
│  RectF / FrameAnalysisResult / HazardDetail           │
│  DetectedObject / ActionPrompt                        │
├───────────────────────────────────────────────────────┤
│              Util 层                                    │
│  ThreadSafeQueue（无锁环形缓冲区）                      │
│  ObjectPool（Paint 对象池）                            │
│  FeatureFlags（SharedPreferences 偏好管理）             │
├───────────────────────────────────────────────────────┤
│              JNI 桥接层                                 │
│  NativeAnalysisBridge.kt ←→ libhzzs_native.so         │
├───────────────────────────────────────────────────────┤
│           C++ 分析核心层（8 个类）                       │
│  NativeAnalysisEngine（协调器）                         │
│    ├── SceneStateMachine                              │
│    ├── RunnerStateMachine                             │
│    ├── JumpStageEstimator                             │
│    ├── HazardEtaEstimator                             │
│    └── ActionPromptEngine                             │
└───────────────────────────────────────────────────────┘
```

### 目录结构

```
app/src/main/
├── java/top/azek431/hzzs/          # Kotlin 层（13 个文件）
│   ├── MainActivity.kt              # 首页：Edge-to-Edge 全屏、悬浮窗开关、免责声明入口、社区链接
│   ├── NativeAnalysisBridge.kt      # JNI 桥接层：加载 libhzzs_native.so，暴露 engineInfo/runSelfCheck/analyzeFrame
│   │
│   ├── ui/
│   │   ├── overlay/
│   │   │   ├── OverlayPreviewManager.kt  # 系统悬浮窗生命周期管理（显示/隐藏/拖动/缩放/参数持久化）
│   │   │   ├── OverlayHUDRenderer.kt     # HUD 渲染器：模拟帧生成、JNI 调用、UI 更新、自动操作入队
│   │   │   └── HUDCanvasView.kt          # 自定义 Canvas 视图：双缓冲绘制、轨迹线、预测路径、热力图、置信度圆环
│   │   ├── community/
│   │   │   └── CommunityLinks.kt         # 单例对象：打开 QQ 群 / Telegram 链接，失败时回退到剪贴板
│   │   └── disclaimer/
│   │       └── DisclaimerActivity.kt     # 免责声明页面：滚动到底部才允许同意
│   │
│   ├── service/
│   │   ├── OverlayNotificationService.kt # 前台通知服务：防止悬浮窗被系统回收
│   │   └── AutoOperationService.kt       # 无障碍自动操作：AccessibilityService 触摸注入（JUMP/SLIDE/DOUBLE_JUMP）
│   │
│   ├── model/
│   │   └── FrameData.kt                    # 数据模型：RectF、FrameAnalysisResult、HazardDetail、DetectedObject
│   │
│   └── util/
│       ├── FeatureFlags.kt                 # SharedPreferences 偏好管理（免责声明/自动操作开关/延迟）
│       ├── ThreadSafeQueue.kt              # 无锁环形缓冲区（单生产者-单消费者）
│       └── ObjectPool.kt                   # Paint 对象池（模板克隆模式，避免每帧分配）
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
│           └── NativeAnalysisBridge.cpp
│
├── res/                             # 资源文件
│   ├── layout/                      # XML 布局（4 个文件）
│   │   ├── activity_main.xml        # 首页布局
│   │   ├── activity_disclaimer.xml  # 免责声明布局
│   │   ├── view_overlay_preview.xml # 悬浮窗面板布局
│   │   └── view_community_footer.xml # 社区链接页脚布局
│   ├── drawable/                    # 矢量图标 + 形状选择器（~20 个文件）
│   └── values/                      # 颜色、尺寸、字符串（129 个）、主题
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
```

### 关键设计决策

- **Edge-to-Edge 全屏**：MainActivity 启用全屏边缘到边缘显示，通过 padding 处理系统栏安全区域。折叠屏设备上 padding 初始值会被缓存，防止无限叠加增长。
- **悬浮窗**：使用 `TYPE_APPLICATION_OVERLAY`（API 26+）或 `TYPE_PHONE` 低版本回退。拖动通过 `OnTouchListener` + `MotionEvent` 实现。右下角有缩放手柄。参数通过 SharedPreferences 持久化。悬浮窗显示期间启动前台通知服务，防止被系统回收。
- **HUD 模拟渲染**：`OverlayHUDRenderer` 在后台线程以 50ms 间隔（20fps）生成模拟跑酷画面数据（正弦波玩家移动 + 周期性危险物），通过 JNI 传入 C++ 分析引擎，结果更新到悬浮窗 UI。这是演示性质，不代表真实画面采集。
- **自动操作演示**：`AutoOperationService` 继承 `AccessibilityService`，通过 `dispatchGesture()` 注入触摸事件。当 HUD 检测到动作提示且自动操作已启用时，将 JUMP/SLIDE/DOUBLE_JUMP 操作按配置延迟加入队列执行。当前仅用于技术验证，正式版本中"只读不操控"原则不变。
- **JNI 桥接**：`NativeAnalysisBridge` Kotlin 单例在类初始化时加载 `libhzzs_native.so`。使用静态异常捕获，避免缺失 ABI 时崩溃。JSON 解析不使用第三方库，通过正则表达式提取字段，减少 APK 体积。
- **ProGuard/R8 混淆规则**：`NativeAnalysisBridge` 类保持不被混淆，以确保 JNI 符号名匹配。详见 `proguard-rules.pro`。
- **只读不操控**：应用核心分析引擎仅读取和分析画面，不进行触摸注入、不自动跳跃、未经用户明确授权不录屏。自动操作服务当前仅作为技术演示存在。
- **线程安全队列**：`ThreadSafeQueue` 是无锁环形缓冲区，适用于单生产者-单消费者场景（如 HUD 后台线程 → 主线程消费分析结果）。
- **Paint 对象池**：`ObjectPool.PaintPool` 预定义画笔模板，通过 `new Paint(template)` 克隆避免状态污染，减少高频分配导致的 GC 压力。

## 开发规范

### 注释规范

本项目所有注释均使用中文。遵循以下约定：

- **Kotlin**：公开/半公开成员使用 KDoc（`/** ... */`）；行内逻辑解释使用 `//`；文件头部使用 `//` 块注释说明整体职责
- **C++**：类和成员声明使用 Doxygen 风格 `/** ... */`；行内代码注释使用 `//`
- **XML**：块级注释使用 HTML 风格 `<!-- ... -->`；复杂元素可加行内注释
- **构建文件**：注释应解释"为什么"而非仅仅"是什么"；配置原因需文档化

### 资源管理

- **strings.xml**：删除未使用的字符串而非注释掉——保持精简。新增字符串前先 grep 确认是否已有等价项。当前共 129 个字符串资源。
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
- **悬浮窗 resize handle 占位**：`view_overlay_preview.xml` 中有 8 个 resize handle View（四边四角），但 Kotlin 代码仅在右下角实现了拖动缩放逻辑，其余 7 个是纯占位。
- **HUDCanvasView 硬编码颜色**：绘制颜色使用 `Color.parseColor()` 硬编码，未与主题资源关联，深色模式适配时需改造。
- **AutoOperationService 降级分支冗余**：Android 12 以下分支代码实际也统一使用 `dispatchGesture()`（因为 minSdk=24），`injectDownEvent`/`injectUpEvent` 分支不会被触发。
- **SharedPreferences 键不一致**：`FeatureFlags` 和 `OverlayPreviewManager` 各自维护独立的 SharedPreferences（`hzzs_feature_flags` vs `hzzs_overlay_prefs`），自动操作开关存在两份存储。

## 游戏参考素材

### 素材库位置与用途

```
D:\Code\AI\火崽崽\火崽崽奇妙屋\
├── 原始素材/                          # Azek431 自己录制的游戏视频
├── 他人分享的原始素材/                 # 社区成员分享的游戏录屏与截图
├── work/                              # AI 分析处理脚本与中间产物
│   ├── *.py                           # 元数据采集、抽帧、高亮分析等脚本
│   ├── media-inventory.csv            # 全部素材元数据清单（持续更新）
│   ├── analysis-temp/                 # 抽帧快照
│   └── deep-analysis/                 # 深度分析帧
├── output/preview/                    # AI 剪辑预览输出
└── docs/                              # 分析报告文档（持续更新）
```

**用途**：所有素材均为跑酷游戏画面，用于视觉识别算法测试调优、HUD 提示逻辑验证、归一化坐标适配测试、场景状态机验证等。

**持续更新**：此素材库会随开发持续推进而不断增长——新的游戏录屏、截图、分析报告都会追加到对应目录中。Claude Code 在执行视觉识别、HUD 设计、场景分析相关任务时，应主动参考此素材库中的最新内容。

### 当前素材概况（截至首次记录）

- **总视频数**：10 个（9 个独立 + 1 个精确重复），总时长约 950 秒
- **分辨率范围**：570x1280 ~ 1746x3840（多种手机型号录制）
- **帧率**：60fps 或 90fps（竖屏录屏）
- **编码**：H.264 / H.265 (HEVC)
- **截图**：包含障碍、糖果、分数等游戏元素的清晰图像，可作为视觉层检测目标的参考基准

### 使用建议

- 开发视觉识别模块时，优先使用时长较长的视频（如 `SVID_20260707_103939_1.mp4`，149.8s，涵盖多种场景）
- 快速验证可用短视频（如 `SVID_20260707_103738_1.mp4`，27s）
- 分析引擎的 `nativeRunSelfCheck` 自检用例基于蛋糕断层场景设计，可对照素材中的断层画面验证 ETA 计算准确性
- 不同分辨率的素材可用于测试归一化坐标系（0.0~1.0）在不同设备上的表现一致性

## 与 AI 协作规则

- **执行任务前，必须先识别所有模糊与缺失的需求点，列出问题清单向用户确认后再行动。**
- **默认必须提问**：除非用户明确说"不要问我，你自己决定"或类似表述，否则遇到任何不确定的地方都必须提问确认。
- **99% 把握原则**：必须有 99% 的把握理解项目后才能开始行动。如果对项目结构、业务逻辑、技术细节有任何不清楚的地方，都可以（且应该）向用户提问。用户可以回答"不用问，你看着办"来解除提问要求，但在那之前默认都要问。
- **一次性列出所有问题**：不要分批次问，把能想到的所有疑问一次性整理成清单，让用户逐项确认。
- **与 AI 协作过程中，除本文件外其余沟通均使用中文。**
