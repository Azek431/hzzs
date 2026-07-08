# CLAUDE.md

本文档为 Claude Code 提供本仓库的开发指导。阅读此文件后，Claude Code 即可高效参与本项目开发。

## 项目概述

**HZZS（火崽崽助手）** 是一个早期阶段的 Android 工具项目，面向跑酷游戏画面分析。提供本地悬浮窗 HUD、基于状态机的帧分析和社区入口。当前版本 0.1.0 — UI 外壳和原生分析引擎骨架已搭建完成，真实的屏幕采集和视觉识别模块尚在规划中。

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
┌─────────────────────────────────────────────┐
│              Kotlin UI 层                     │
│  MainActivity ──→ OverlayPreviewManager      │
│  CommunityLinks ──→ NativeAnalysisBridge     │
├─────────────────────────────────────────────┤
│              JNI 桥接层                       │
│  NativeAnalysisBridge.kt ←→ libhzzs_native.so │
├─────────────────────────────────────────────┤
│           C++ 分析核心层                       │
│  NativeAnalysisEngine（协调器）                │
│    ├── SceneStateMachine                    │
│    ├── RunnerStateMachine                   │
│    ├── JumpStageEstimator                   │
│    ├── HazardEtaEstimator                   │
│    └── ActionPromptEngine                   │
└─────────────────────────────────────────────┘
```

### 目录结构

```
app/src/main/
├── java/top/azek431/hzzs/          # Kotlin UI 层（4 个文件）
│   ├── MainActivity.kt              # 首页：Edge-to-Edge 全屏、悬浮窗开关、社区链接
│   ├── OverlayPreviewManager.kt     # 系统悬浮窗生命周期管理（显示/隐藏/拖动/缩放/参数持久化）
│   ├── NativeAnalysisBridge.kt      # JNI 桥接层：加载 libhzzs_native.so，暴露 engineInfo/runSelfCheck
│   └── CommunityLinks.kt            # 单例对象：打开 QQ 群 / Telegram 链接，失败时回退到剪贴板
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
│   ├── layout/                      # XML 布局（3 个文件）
│   │   ├── activity_main.xml        # 首页布局
│   │   ├── view_overlay_preview.xml # 悬浮窗面板布局
│   │   └── view_community_footer.xml # 社区链接页脚布局
│   ├── drawable/                    # 矢量图标 + 形状选择器（~17 个文件）
│   └── values/                      # 颜色、尺寸、字符串、主题
│
└── AndroidManifest.xml              # 声明 SYSTEM_ALERT_WINDOW 权限，单一 Activity
```

### 数据流

```
FrameDetections（视觉层输入）
    → SceneStateMachine → SceneMode（稳定后的场景模式）
    → RunnerStateMachine → RunnerMotion（姿态、着地状态、速度）
    → JumpStageEstimator → jump_stage（0/1/2）
    → HazardEtaEstimator → HazardForecast[]（ETA、推荐动作）
    → ActionPromptEngine → ActionPrompt（稳定的 HUD 提示）
    → AnalysisResult（完整的分析结果，供 HUD 渲染使用）
```

### 关键设计决策

- **Edge-to-Edge 全屏**：MainActivity 启用全屏边缘到边缘显示，通过 padding 处理系统栏安全区域。折叠屏设备上 padding 初始值会被缓存，防止无限叠加增长。
- **悬浮窗**：使用 `TYPE_APPLICATION_OVERLAY`（API 26+）或 `TYPE_PHONE` 低版本回退。拖动通过 `OnTouchListener` + `MotionEvent` 实现。右下角有缩放手柄。参数通过 SharedPreferences 持久化。
- **JNI 桥接**：`NativeAnalysisBridge` Kotlin 单例在类初始化时加载 `libhzzs_native.so`。使用静态异常捕获，避免缺失 ABI 时崩溃。
- **ProGuard/R8 混淆规则**：`NativeAnalysisBridge` 类保持不被混淆，以确保 JNI 符号名匹配。详见 `proguard-rules.pro`。
- **只读不操控**：应用仅读取和分析画面，不进行触摸注入、不自动跳跃、未经用户明确授权不录屏。

## 开发规范

### 注释规范

本项目所有注释均使用中文。遵循以下约定：

- **Kotlin**：公开/半公开成员使用 KDoc（`/** ... */`）；行内逻辑解释使用 `//`
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
- **cpp/README.md**：C++ 分析引擎文档，记录算法边界和对象语义。修改分析逻辑前请先阅读。
- **已实现 vs 规划中必须分离**：不允许将屏幕采集、实时视觉识别、HUD 绘制等规划中功能描述为已完成。

### 安全红线

- **切勿提交** `.jks`、`.keystore`、`.p12`、`.pfx`、`*.properties`（含密码）、`local.properties`（含 SDK 路径）。
- Release 签名从环境变量 `AZEK431_RELEASE_*` 读取，四个字段全部非空才启用。
- 不在代码、文档或 Issue 中包含用户个人信息、设备日志脱敏前的截图、第三方平台账号凭证。

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
- **无 docs/ 目录**：CONTRIBUTING.md 引用了 `docs/architecture.md` 等文档，但目录尚未创建。
- **CHANGELOG 未同步**：README 已修正为"浅色"，但 CHANGELOG.md 仍写"深色首页界面"，待下次发布时修正。
- **悬浮窗 resize handle 占位**：`view_overlay_preview.xml` 中有 8 个 resize handle View（四边四角），但 Kotlin 代码仅在右下角实现了拖动缩放逻辑，其余 7 个是纯占位。

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
