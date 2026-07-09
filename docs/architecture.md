# 架构说明

本文档描述 HZZS（火崽崽助手）的当前架构与模块边界。

> **重要声明**：HZZS 是本地、只读的跑酷画面分析与 HUD 辅助工具。
> 不包含自动点击、自动跳跃、自动下滑、自动复活、代打或机器人功能。

## 整体分层

```text
┌───────────────────────────────────────────────────────────────┐
│                    UI 层（Controller 模式）                     │
│                                                               │
│  MainActivity (调度中心)                                        │
│  ├── MainViewCache (View 引用缓存)                              │
│  ├── MainInsetCache (Padding 初始值缓存)                        │
│  ├── MainInsetsController (系统栏安全区域处理)                   │
│  ├── MainActionBinder (按钮点击事件绑定)                        │
│  ├── MainDialogController (对话框显示)                          │
│  └── OverlayPermissionController (权限检查)                     │
│                                                               │
│  OverlayPreviewManager (悬浮窗生命周期)                         │
│  ├── OverlayViewFinder (视图查找)                                │
│  ├── OverlayButtonBinder (按钮绑定+状态机)                       │
│  ├── OverlayDragController (拖动)                                │
│  ├── OverlayResizeController (缩放)                               │
│  ├── OverlaySettingsBinder (设置绑定)                             │
│  └── OverlayWindowController (LayoutParams 创建)                 │
│  └── OverlayHUDRenderer (模拟帧生成)                              │
│                                                               │
│  AutoOperationService (无障碍自动操作)                           │
│  ├── AutoActionQueue (操作队列管理)                             │
│  └── GestureInjector (手势注入)                                │
│                                                               │
│  HUDCanvasView (自定义 Canvas 绘制)                              │
│  ├── HUDColorPalette (颜色常量)                                │
│  └── HUDDrawers (绘制扩展函数)                                 │
│                                                               │
│  CommunityLinks (社区链接跳转)                                   │
│  DisclaimerActivity (免责声明)                                  │
├───────────────────────────────────────────────────────────────┤
│                    Model 层                                     │
│  FrameData.kt (RectF / FrameAnalysisResult / HazardDetail...)   │
├───────────────────────────────────────────────────────────────┤
│                    Util 层                                      │
│  FeatureFlags (SharedPreferences 集中管理)                      │
│  ThreadSafeQueue (无锁环形缓冲区)                               │
│  ObjectPool (Paint 对象池)                                     │
├───────────────────────────────────────────────────────────────┤
│                    JNI 桥接层                                   │
│  NativeLibraryLoader (库加载)                                   │
│  NativeJsonParser (JSON 解析)                                   │
│  NativeAnalysisBridge (兼容门面)                                │
├───────────────────────────────────────────────────────────────┤
│              C++ 分析核心层（8 个类）                            │
│  NativeAnalysisEngine（协调器）                                 │
│    ├── SceneStateMachine                                       │
│    ├── RunnerStateMachine                                      │
│    ├── JumpStageEstimator                                      │
│    ├── HazardEtaEstimator                                      │
│    └── ActionPromptEngine                                      │
└───────────────────────────────────────────────────────────────┘
```

## 模块说明

### UI 层 — 首页 Controller（Kotlin）

首页采用 Controller 模式，将业务逻辑从 MainActivity 中完全剥离。

| 文件 | 包路径 | 职责 | 状态 |
| --- | --- | --- | --- |
| `MainActivity.kt` | `top.azek431.hzzs` | 页面生命周期、Controller 初始化、业务调度 | 已实现 |
| `MainViewCache.kt` | `ui/main` | 缓存 activity_main.xml 中所有 View 引用 | 已实现 |
| `MainInsetCache.kt` | `ui/main` | 缓存 View 的初始 padding 值，防止无限叠加 | 已实现 |
| `MainInsetsController.kt` | `ui/main` | 处理 Edge-to-Edge 系统栏安全区域 padding | 已实现 |
| `MainActionBinder.kt` | `ui/main` | 绑定三个按钮的点击事件，通过回调接口委托业务逻辑 | 已实现 |
| `MainDialogController.kt` | `ui/main` | 显示开发计划对话框、悬浮窗权限说明对话框 | 已实现 |
| `OverlayPermissionController.kt` | `ui/main` | 检查 SYSTEM_ALERT_WINDOW 权限，跳转系统设置页面 | 已实现 |
| `CommunityLinks.kt` | `ui/community` | 打开 QQ 群 / Telegram 链接，失败时复制到剪贴板 | 已实现 |
| `DisclaimerActivity.kt` | `ui/disclaimer` | 免责声明页面：滚动到底部才允许同意 | 已实现 |

### UI 层 — 悬浮窗 Controller（Kotlin）

悬浮窗同样采用 Controller 模式，每个控制器负责单一交互维度。

| 文件 | 包路径 | 职责 | 状态 |
| --- | --- | --- | --- |
| `OverlayPreviewManager.kt` | `ui/overlay` | 悬浮窗生命周期管理（显示/隐藏/状态检查） | 已实现 |
| `OverlayDragController.kt` | `ui/overlay` | 处理顶部标题栏拖动事件，更新 WindowManager.LayoutParams | 已实现 |
| `OverlayResizeController.kt` | `ui/overlay` | 处理右下角缩放手柄，计算缩放比例并持久化 | 已实现 |
| `OverlaySettingsBinder.kt` | `ui/overlay` | 绑定透明度/圆角/缩放滑块，自动操作开关委托给 FeatureFlags | 已实现 |
| `OverlayWindowController.kt` | `ui/overlay` | 创建 WindowManager.LayoutParams，计算最大 X/Y 坐标 | 已实现 |
| `OverlayHUDRenderer.kt` | `ui/overlay` | 模拟帧生成循环（20fps），驱动 C++ 分析引擎 | 已实现 |
| `HUDCanvasView.kt` | `ui/overlay` | 自定义 Canvas 视图：双缓冲绘制、玩家/危险物矩形标注 | 已实现 |
| `HUDColorPalette.kt` | `ui/overlay` | 集中管理 18+ 处颜色常量（ARGB 整数格式） | 已实现 |
| `HUDDrawers.kt` | `ui/overlay` | Canvas 绘制扩展函数（轨迹、路径、热力图、置信度等） | 已实现 |

### 自动操作层（Kotlin）

| 文件 | 包路径 | 职责 | 状态 |
| --- | --- | --- | --- |
| `AutoOperationService.kt` | `service` | 无障碍服务生命周期管理，定时调度队列操作 | 已实现 |
| `AutoActionQueue.kt` | `service` | 操作队列管理器（FIFO、线程安全、暂停/开关/延迟配置） | 已实现 |
| `GestureInjector.kt` | `service` | 手势注入器（归一化坐标 → 像素坐标 → dispatchGesture） | 已实现 |

### 数据模型层（Kotlin）

| 文件 | 包路径 | 职责 | 状态 |
| --- | --- | --- | --- |
| `FrameData.kt` | `model` | RectF、FrameAnalysisResult、HazardDetail、DetectedObject、ActionPrompt | 已实现 |

### 工具层（Kotlin）

| 文件 | 包路径 | 职责 | 状态 |
| --- | --- | --- | --- |
| `FeatureFlags.kt` | `util` | SharedPreferences 集中管理（免责声明/自动操作开关/延迟/版本码） | 已实现 |
| `ThreadSafeQueue.kt` | `util` | 无锁环形缓冲区（单生产者-单消费者场景） | 已实现 |
| `ObjectPool.kt` | `util` | Paint 对象池（模板克隆模式，减少 GC 压力） | 已实现 |

### JNI 桥接层（Kotlin）

| 文件 | 包路径 | 职责 | 状态 |
| --- | --- | --- | --- |
| `NativeLibraryLoader.kt` | `data/native` | 加载 libhzzs_native.so，暴露 isAvailable 属性 | 已实现 |
| `NativeJsonParser.kt` | `data/native` | 正则解析 C++ 返回的 JSON 字符串（无第三方库） | 已实现 |
| `NativeEngineFacade.kt` | `data/native` | JNI 调用门面：engineInfo/runSelfCheck/analyzeFrame | 已实现 |
| `NativeAnalysisBridge.kt` | `top.azek431.hzzs` | 兼容门面（向后兼容，委托给 NativeEngineFacade） | 已实现 |

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

### 构建层

| 文件 | 职责 | 状态 |
| --- | --- | --- |
| `CMakeLists.txt` | 定义 `hzzs_native` 共享库，链接 `log`，启用 C++17 与严格警告 | 已实现 |
| `settings.gradle.kts` | 定义多模块结构（:app, :core, :features:overlay, :features:service） | 已实现 |
| `build.gradle.kts` (顶层) | 统一 AGP/Kotlin 插件版本管理 | 已实现 |
| `build.gradle.kts` (:app) | APK 构建配置，依赖所有子模块，声明 AndroidManifest | 已实现 |
| `build.gradle.kts` (:core) | 纯 Kotlin 库，无 Android UI 依赖 | 已实现 |
| `build.gradle.kts` (:features:overlay) | Android 库，依赖 :core + :features:service | 已实现 |
| `build.gradle.kts` (:features/service) | Android 库，依赖 :core | 已实现 |

### Gradle 模块结构

```text
hzzs/                          ← 根项目
├── :app                       ← APK 模块（唯一含 res/ 的模块）
│   ├── MainActivity.kt        ← 入口 Activity
│   ├── HomeFragment.kt        ← 首页 Fragment
│   ├── DisclaimerActivity.kt  ← 免责声明
│   ├── ui/main/               ← 首页 Controller（5 个文件）
│   ├── ui/overlay/            ← OverlayPreviewManager + OverlaySettingsBinder
│   ├── ui/community/          ← CommunityLinks
│   ├── ui/home/               ← HomeFragment + HomeActionCallbacks
│   ├── ui/disclaimer/         ← DisclaimerActivity
│   ├── ui/settings/           ← 设置页面
│   ├── service/               ← OverlayNotificationService
│   └── data/vision/           ← 视觉识别原型（未接入主流程）
│
├── :core                      ← 纯 Kotlin 库（无 Android UI 依赖）
│   ├── model/                 ← FrameData.kt（RectF, FrameAnalysisResult 等）
│   ├── util/                  ← FeatureFlags, ThreadSafeQueue, ObjectPool
│   └── data/native/           ← NativeLibraryLoader, NativeJsonParser,
│                                NativeEngineFacade, NativeAnalysisBridge
│
└── features/
    ├── :overlay               ← 悬浮窗 UI 组件（无 R 依赖）
    │   ├── HUDCanvasView.kt
    │   ├── HUDColorPalette.kt
    │   ├── HUDDrawers.kt
    │   ├── OverlayHUDRenderer.kt
    │   ├── OverlayWindowController.kt
    │   ├── OverlayDragController.kt
    │   └── OverlayResizeController.kt
    │
    └── :service               ← 自动操作服务（无 R 依赖）
        ├── AutoOperationService.kt
        ├── AutoActionQueue.kt
        └── GestureInjector.kt
```

**设计原则：**

- `:app` 模块独占所有 `res/` 资源（布局、字符串、drawable）
- `:core` 模块为纯 Kotlin，可独立做单元测试
- `:features/overlay` 和 `:features/service` 不含 R 引用
- `OverlayPreviewManager`、`OverlaySettingsBinder` 因引用 R 留在 `:app`
- `OverlayNotificationService` 因引用 R 留在 `:app`
- JNI 库加载在 `:core` 模块，但 `Android.mk`/`CMakeLists.txt` 在 `:app` 模块

## 数据流

```text
FrameDetections（视觉层输入 / 模拟帧生成）
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
    │
    ├──→ JSON 序列化 → JNI → NativeJsonParser → FrameAnalysisResult
    │
    ├──→ HUDCanvasView (Canvas 绘制可视化)
    │
    └──→ AutoActionQueue.enqueue() (自动操作入队，需用户授权)
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
| JNI | 视觉识别、屏幕采集、UI | Kotlin 侧构造的 `FrameDetections` | `AnalysisResult` → `String` |
| OverlayPreviewManager | C++、分析引擎 | 用户手势 | 悬浮窗 View |
| MainActivity | C++、分析引擎 | 用户点击 | UI 交互调度 |
| AutoActionQueue | 任何 UI 模块 | FrameAnalysisResult 中的 prompt | QueuedAction |
| GestureInjector | 视觉识别 | QueuedAction + DisplayMetrics | dispatchGesture 调用 |
| FeatureFlags | Android 框架（仅 SharedPreferences） | 无 | 偏好设置读写 |

## 设计决策

### Controller 模式

所有 UI 模块采用 Controller 模式，将业务逻辑从 Activity/Manager 中剥离：

- **MainActivity** 只保留"组装和调度"职责，所有具体逻辑委托给 Controller
- **OverlayPreviewManager** 只管理悬浮窗生命周期，所有交互委托给 Drag/Resize/Settings Controller
- 每个 Controller 独立测试，互不依赖

### SharedPreferences 集中管理

- **FeatureFlags** 统一管理所有偏好设置（免责声明、自动操作开关/延迟、版本码）
- **OverlaySettingsBinder** 的自动操作设置委托给 FeatureFlags，避免键不一致
- **OverlayResizeController** 单独管理悬浮窗缩放系数（与自动操作无关）

### 颜色集中管理

- **HUDColorPalette** 集中管理 18+ 处颜色常量
- 使用 ARGB 整数格式（避免每次绘制时解析字符串）
- 为深色模式预留扩展点

### 绘制逻辑分离

- **HUDDrawers** 封装所有 Canvas 绘制扩展函数
- 纯函数式设计，不持有状态
- **HUDCanvasView** 只负责视图生命周期和画笔初始化

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
