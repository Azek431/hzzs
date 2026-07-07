# 架构说明

本文档描述 HZZS（火崽崽助手）的当前架构与模块边界。

> **重要声明**：HZZS 是本地、只读的跑酷画面分析与 HUD 辅助工具。
> 不包含自动点击、自动跳跃、自动下滑、自动复活、代打或机器人功能。

## 整体分层

```
┌─────────────────────────────────────────────┐
│              MainActivity.kt                │  ← 唯一 Activity，首页 UI
│         OverlayPreviewManager.kt            │  ← 悬浮窗面板管理
│              CommunityLinks.kt              │  ← 社区链接跳转
├─────────────────────────────────────────────┤
│         NativeAnalysisBridge.kt             │  ← JNI 桥接（Kotlin ↔ C++）
├─────────────────────────────────────────────┤
│       NativeAnalysisEngine.cpp              │  ← 分析编排（C++）
│       ┌──────────┬──────────┬──────────┐   │
│       │ Scene    │ Runner   │ Jump     │   │
│       │ Machine  │ Machine  │ Stage    │   │
│       │          │          │ Estimator│   │
│       ├──────────┼──────────┼──────────┤   │
│       │ Hazard   │ Action   │          │   │
│       │ Eta      │ Prompt   │          │   │
│       │ Estimator│ Engine   │          │   │
│       └──────────┴──────────┴──────────┘   │
├─────────────────────────────────────────────┤
│         CMakeLists.txt                      │  ← C++ 构建
│         build.gradle.kts                    │  ← Gradle 构建
└─────────────────────────────────────────────┘
```

## 模块说明

### UI 层（Kotlin）

| 文件 | 职责 | 状态 |
| --- | --- | --- |
| `MainActivity.kt` | 首页 UI、系统栏安全区域、悬浮窗权限引导、开发计划弹窗 | 已实现 |
| `OverlayPreviewManager.kt` | 悬浮窗创建/销毁、拖动、透明度调节、执行状态切换 | 已实现 |
| `CommunityLinks.kt` | 打开 QQ 群 / Telegram 链接，失败时复制到剪贴板 | 已实现 |
| `NativeAnalysisBridge.kt` | 加载 `libhzzs_native.so`，暴露 `engineInfo()` 和 `runSelfCheck()` | 已实现 |

### 分析层（C++）

所有分析模块位于 `hzzs::analysis` 命名空间下，不依赖 Android 框架。

| 头文件 | 职责 | 状态 |
| --- | --- | --- |
| `NativeAnalysisEngine.h` | 编排 Scene/Runner/Jump/Hazard/Prompt，输出 `AnalysisResult` | 已实现 |
| `SceneStateMachine.h` | 场景模式稳定化（菜单、倒计时、地面、飞行、结算、遮挡） | 已实现 |
| `RunnerStateMachine.h` | 基于连续帧玩家矩形推断跑步、起跳、滞空、下落、下滑 | 已实现 |
| `JumpStageEstimator.h` | 从垂直运动推断跳跃阶段（0 = 地面、1 = 首跳、2 = 二连跳） | 已实现 |
| `HazardEtaEstimator.h` | 计算危险物 ETA（到达时间），按 Eta 排序 | 已实现 |
| `ActionPromptEngine.h` | 将 ETA 转换为只读提示，需连续两帧稳定才输出 | 已实现 |
| `AnalysisTypes.h` | 类型定义（SceneMode、RunnerPose、GameObjectType、PromptAction 等） | 已实现 |
| `Geometry.h` | `RectF` 归一化矩形工具 | 已实现 |

### 桥接层（JNI）

| 文件 | 职责 | 状态 |
| --- | --- | --- |
| `NativeAnalysisBridge.cpp` | JNI 入口，包含 `CreateGroundFrame()` 自检用例 | 已实现 |

### 构建层

| 文件 | 职责 | 状态 |
| --- | --- | --- |
| `CMakeLists.txt` | 定义 `hzzs_native` 共享库，链接 `log`，启用 C++17 与严格警告 | 已实现 |

## 数据流

```
FrameDetections (输入)
    │
    ▼
SceneStateMachine → SceneMode
    │
    ▼
RunnerStateMachine → RunnerMotion (pose, grounded, bounds)
    │
    ▼
JumpStageEstimator → jump_stage (0/1/2)
    │
    ▼
HazardEtaEstimator → vector<HazardForecast> (sorted by eta)
    │
    ▼
ActionPromptEngine → ActionPrompt (stable ≥ 2 frames)
    │
    ▼
AnalysisResult (输出)
```

## 坐标系统

- 所有几何运算使用 **归一化坐标**，范围 `0.0 ~ 1.0`。
- 上层视觉模块应先裁剪掉状态栏、导航栏和非游戏区域，再将游戏视口内的像素坐标转换为逻辑坐标。
- X 轴：从左到右递增。游戏世界中对象从右向左滚动，因此 `velocity_x_per_second` 通常为负数。
- Y 轴：从上到下递增。

## 模块边界

| 模块 | 不依赖 | 输入 | 输出 |
| --- | --- | --- | --- |
| 状态机 | Android、JNI、UI | 稳定后的 `FrameDetections` | `AnalysisResult` |
| JNI | 视觉识别、屏幕采集 | Kotlin 侧构造的 `FrameDetections` | `AnalysisResult` → `String` |
| OverlayPreviewManager | C++、分析引擎 | 用户手势 | 悬浮窗 View |
| MainActivity | C++、分析引擎 | 用户点击 | UI 交互 |

## 规划中的模块

以下模块属于开发规划，**尚未实现**：

| 模块 | 预期职责 | 计划里程碑 |
| --- | --- | --- |
| 屏幕采集层 | MediaProjection + ImageReader 帧采样 | Milestone 2 |
| 视觉识别层 | 模板匹配 / 像素分析 → `FrameDetections` | Milestone 2 |
| HUD 渲染层 | 将 `AnalysisResult` 渲染到悬浮窗 | Milestone 3 |
| 战报存储层 | Room 数据库，持久化事件记录 | Milestone 3 |
| 校准中心 | 设备适配参数保存/恢复 | Milestone 4 |
| 异常诊断 | 自动检测采集中断、识别置信度低等 | Milestone 4 |
