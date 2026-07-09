# 火崽崽助手（HZZS）

<p align="center">
  <strong>面向跑酷画面分析的 Android 本地图像识别与悬浮窗 HUD 工具。</strong><br>
  使用 Kotlin 与 C++/JNI 技术开发，优先追求清晰、稳定、可维护的本地工具体验。<br>
  支持绿瓶识别、坑位检测、自动跳跃/点击/滑动等基础辅助能力。
</p>

<p align="center">
  <a href="#项目简介">项目简介</a> ·
  <a href="#当前进度">当前进度</a> ·
  <a href="#功能边界">功能边界</a> ·
  <a href="#开发路线图">开发路线图</a> ·
  <a href="#开发与构建">开发与构建</a> ·
  <a href="#文档">文档</a> ·
  <a href="#交流与反馈">交流与反馈</a>
</p>

> [!IMPORTANT]
> HZZS 仍处于早期开发阶段。README 中标注为“计划中”“后续”或“待接入”的能力，不代表当前版本已经实现、可用或已发布。

## 项目简介

火崽崽助手（HZZS）是一个独立开发的 Android 工具项目，目标是为跑酷类游戏提供**本地画面分析、悬浮窗 HUD 展示、图像识别辅助**和**参数校准**能力。

### 核心技术栈

- **Kotlin + Android Views** — 原生 UI 层
- **C++17 + JNI** — 高性能分析引擎（场景状态机、跳跃阶段估算、障碍物 ETA 计算）
- **本地图像识别** — 绿瓶 RGB 检测、坑位地面断裂检测
- **悬浮窗 HUD** — 实时画面分析可视化展示
- **无障碍服务** — 自动操作注入（跳跃/点击/滑动）
- **MediaProjection / takeScreenshot** — 本地屏幕采集

### 开源目的

本项目作为开源学习参考作品发布，欢迎开发者：

- 研究 Android 无障碍服务截图技术
- 学习本地图像识别与颜色检测算法
- 了解 C++/JNI 在 Android 中的性能优化
- 参考悬浮窗 HUD 设计与绘制技术
- 探索基于图像识别的辅助操作方案

HZZS 不隶属于、不代表、也未获得任何第三方平台、品牌、游戏或服务的官方授权。

## 当前进度

当前版本：

```text
0.1.0
```

当前版本的重点是建立稳定的 Android 工程基础、视觉识别原型、悬浮窗 HUD 和自动操作能力。

| 模块 | 当前状态 | 说明 |
| --- | --- | --- |
| Android 原生工程基础 | 已完成 | Kotlin、Gradle、Android Views 与 Material Components 基础已建立。 |
| 浅色首页界面 | 已完成 | 已具备状态卡片、功能入口、开发信息与社区入口。 |
| 悬浮窗权限与预览 | 已完成 | 支持显示、拖动、缩放、关闭和重新打开。 |
| 悬浮窗执行状态 | 已完成 | 支持”循环执行”、”单次执行”与”停止运行”交互。 |
| QQ 群与 Telegram 入口 | 已完成 | 首页和悬浮窗都可打开对应社区链接。 |
| C++ 分析引擎 | 已完成 | 场景状态机、跳跃阶段估算、障碍物 ETA 计算。 |
| JNI 桥接层 | 已完成 | NativeLibraryLoader / NativeEngineFacade / VisionAnalysisBridge。 |
| HUD 模拟渲染 | 已完成 | 20fps 模拟帧驱动 C++ 引擎，结果输出到悬浮窗 Canvas。 |
| 无障碍自动操作 | 已完成 | AutoOperationService + AutoActionQueue + GestureInjector。 |
| 截图采集原型 | 已完成 | AccessibilityService.takeScreenshot() 截图 + 绿瓶检测。 |
| 视觉识别设置 | 已完成 | 5 个分区 Tab 设置页面，支持参数实时调节。 |
| 屏幕采集授权 | 计划中 | 后续将通过 MediaProjection 系统授权流程接入。 |
| 实时帧分析 | 计划中 | 尚未接入真实游戏画面采集与实时识别。 |
| 本局战报与历史记录 | 计划中 | 尚未建立正式数据模型与本地数据库。 |
| 参数校准与异常诊断 | 计划中 | 后续将作为独立能力逐步实现。 |

> 当前”循环执行”驱动的是 C++ 分析引擎的模拟帧数据，不代表真实采集屏幕或识别游戏画面。

## 当前界面与交互

### 首页

当前首页采用浅色 Material 风格（暖白背景 #FFF8F6），作为后续功能、状态和反馈信息的统一入口。

目前包含：

- 应用状态概览
- 开发计划入口
- 悬浮窗入口
- 开发者与版本信息
- HZZS QQ 交流群与 Azek431 主频道入口

### 悬浮窗

当前悬浮窗采用深色科技风设计，已具备完整交互能力：

- 用户授权后显示悬浮窗
- 拖动顶部标题栏移动位置
- 拖动右下角缩放手柄调整大小
- 点击右上角 `×` 关闭
- 点击”循环执行”启动模拟帧循环，按钮变为”停止运行”
- 点击”停止运行”停止循环，按钮恢复”循环执行”
- 点击”单次执行”执行一次分析，结果停留 400ms 后自动清空
- 调节透明度滑块实时改变悬浮窗透明度
- 切换自动操作开关控制自动操作启用/禁用
- 调节自动操作延迟滑块（0~500ms）
- 打开 HZZS QQ 交流群和 Azek431 主频道
- 关闭后可从首页再次打开

## 功能边界

HZZS 的核心定位是**本地画面分析与辅助提示**，同时提供可选的自动操作能力。

| 范围 | 说明 |
| --- | --- |
| 允许方向 | 本地屏幕画面分析、状态识别、悬浮窗 HUD 提示、参数校准与异常诊断。 |
| 自动操作 | 已实现自动跳跃、自动点击、自动滑动（基于图像识别结果），可通过设置界面开关。 |
| 截图采集 | 已实现 AccessibilityService.takeScreenshot() 截图原型，MediaProjection 计划中。 |
| 不上传数据 | 默认不上传截图、日志、运行记录、设备信息或本地分析数据。 |
| 不伪装官方 | 不宣称为任何游戏、平台、品牌或服务的官方工具。 |

## 当前分析流程

当前已实现两条分析链路：

### 模拟帧分析（已完成）

```text
OverlayHUDRenderer 生成模拟帧（正弦波玩家 + 周期危险物）
        ↓
NativeEngineFacade → JNI → C++ 分析引擎
        ↓
JSON 序列化 → NativeJsonParser → FrameAnalysisResult
        ↓
HUDCanvasView（Canvas 绘制可视化）
```

### 截图识别分析（原型已搭建）

```text
ScreenshotCapture.takeScreenshot() → CaptureResult(pixels, w, h)
        ↓
VisionAnalysisBridge.scanGreenBottle() → VisionGreenBottleResult
        ↓
VisionDebugOverlayView（调试可视化，计划中）
```

### 自动操作链路（已完成）

```text
识别结果（绿瓶/坑位/障碍）
        ↓
AutoActionQueue.enqueue(action)
        ↓
AutoOperationService 定时调度
        ↓
GestureInjector.dispatchGesture() 注入触摸事件
```

为提高稳定性，分析流程不会只依赖单帧结果做判断，而是逐步引入：

- 多帧确认与置信度判断
- 逻辑坐标与不同屏幕比例适配
- 不同皮肤和画面风格的视觉配置
- 低置信度降级与可解释提示
- 本地事件摘要记录，而非无意义地保存全部原始帧

## 计划中的页面结构

以下页面属于开发规划，不代表当前版本已经全部实现。

```text
火崽崽助手
│
├─ 首页
│  ├─ 当前状态概览
│  ├─ 权限与设备状态
│  ├─ 快速操作入口
│  ├─ 最近提示
│  └─ 社区与开发动态
│
├─ 运行
│  ├─ 执行状态
│  ├─ 屏幕采集授权
│  ├─ HUD 设置
│  ├─ 实时事件提示
│  └─ 最近运行结果
│
├─ 分析
│  ├─ 本局战报
│  ├─ 历史记录
│  ├─ 事件时间线
│  ├─ 数据趋势
│  └─ 本地导出与清理
│
└─ 我的
   ├─ 权限管理
   ├─ 校准中心
   ├─ 异常诊断
   ├─ 本地数据管理
   ├─ 关于应用
   └─ 更新与反馈
```

## 开发路线图

路线图表示当前优先级，会根据真实设备表现、稳定性和用户反馈持续调整。

### Milestone 0：工程基础与发布身份

**目标：建立可长期维护的 Android 工程基础。**

- [x] 创建 Android 原生项目
- [x] 确定项目名称、包名、主题与基础资源
- [x] 配置 Kotlin、Gradle 与 Android SDK 基础环境
- [x] 建立本地 Release 签名流程
- [x] 添加 README 与 MIT License
- [ ] 完善签名文件与本地配置保护规则
- [ ] 建立 GitHub Issue 模板与协作规范

### Milestone 1：首页与悬浮窗交互基础

**目标：建立统一、稳定、可扩展的应用壳层。**

- [x] 建立浅色首页基础界面
- [x] 建立悬浮窗权限入口
- [x] 实现悬浮窗显示、拖动、缩放、关闭和重新打开
- [x] 建立循环执行 / 单次执行 / 停止运行 的状态切换
- [x] 接入 QQ 群与 Telegram 社区入口
- [x] 建立底部导航栏（首页 / 设置）
- [ ] 完善空状态、加载状态、错误状态与无障碍标签
- [ ] 完善应用图标、启动页与关于页面

### Milestone 2：屏幕采集与画面分析基础

**目标：在明确授权的前提下，建立稳定的本地帧分析能力。**

- [x] 搭建 C++ 分析引擎（场景/姿态/跳跃/ETA/提示）
- [x] 搭建 JNI 桥接层（NativeLibraryLoader / NativeEngineFacade / VisionAnalysisBridge）
- [x] 实现 HUD 模拟帧渲染循环（20fps，正弦波驱动）
- [x] 实现 AccessibilityService.takeScreenshot() 截图原型
- [x] 实现绿瓶 RGB 单行扫描检测
- [x] 实现无障碍自动操作服务（AutoOperationService + GestureInjector）
- [ ] 接入 MediaProjection 屏幕采集授权流程
- [ ] 建立帧采样与图像预处理模块
- [ ] 建立逻辑坐标与游戏画面区域适配
- [ ] 识别玩家区域与基础姿态
- [ ] 识别地面障碍与顶部障碍
- [ ] 识别糖果、分数和生命状态
- [ ] 建立多帧确认与置信度模型
- [ ] 在异常或低置信度时提供明确提示

### Milestone 3：HUD、记录与战报

**目标：把识别结果转化为可理解、可回看的本地数据。**

- [ ] 建立实时 HUD 提示模型
- [ ] 建立跑酷事件时间线
- [ ] 记录本局关键事件
- [ ] 建立本局战报页面
- [ ] 增加历史记录与数据趋势
- [ ] 支持本地数据导出、清理与恢复
- [ ] 默认关闭任何未明确说明的数据上传行为

### Milestone 4：校准与异常诊断

**目标：让不同设备和画面环境下的问题更容易被定位和解决。**

- [ ] 建立校准中心
- [ ] 保存、恢复和重置视觉参数
- [ ] 建立设备环境检查
- [ ] 增加异常原因分类
- [ ] 增加本地日志与诊断快照导出
- [ ] 提供问题定位与恢复建议
- [ ] 优化不同 Android 版本和设备的适配体验

### Milestone 5：测试、发布与长期维护

**目标：形成可重复的测试、发布和反馈闭环。**

- [ ] 完善 Debug 与 Release 构建检查清单
- [ ] 建立 GitHub Releases 发布流程
- [ ] 编写版本更新日志与升级说明
- [ ] 编写用户使用说明与常见问题
- [ ] 建立基础兼容性测试流程
- [ ] 发布首个可测试版本
- [ ] 根据反馈持续优化稳定性、性能与界面体验

## 技术信息

| 项目 | 内容 |
| --- | --- |
| 应用名称 | 火崽崽助手 |
| 项目代号 | HZZS |
| 当前版本 | `0.1.0` |
| Package / Namespace | `top.azek431.hzzs` |
| 开发语言 | Kotlin + C++17 (JNI) |
| 当前 UI 基础 | Android Views + Material Components |
| UI 方向 | 浅色 Material Design 3 风格（暖白背景 #FFF8F6）+ 深色悬浮窗科技风 |
| 最低 Android 版本 | API 24（Android 7.0） |
| 编译 SDK | API 37 |
| 目标 SDK | API 37 |
| 构建工具 | Gradle Wrapper |
| 推荐 JDK | JDK 17 |
| Release 签名 | PKCS12，本地私有保存，不提交到仓库 |

## 当前项目结构

当前目录会随功能增加继续拆分。以下是目前较核心的文件与后续方向：

```text
hzzs/
├─ app/                          ← APK 模块（含 res/ 资源）
│  ├─ src/main/java/top/azek431/hzzs/
│  │  ├─ MainActivity.kt         ← 入口 Activity（底部导航调度）
│  │  ├─ ui/home/               ← 首页 Fragment + 回调接口
│  │  ├─ ui/main/               ← 首页 Controller（对话框/权限等）
│  │  ├─ ui/overlay/            ← 悬浮窗管理器、按钮绑定、设置绑定
│  │  ├─ ui/settings/           ← 视觉识别设置页面（5 个分区 Tab）
│  │  ├─ ui/disclaimer/         ← 免责声明
│  │  ├─ ui/community/          ← 社区链接
│  │  ├─ service/               ← 前台通知服务、无障碍自动操作
│  │  ├─ data/vision/           ← 截图采集 + 视觉识别 JNI 桥接
│  │  └─ NativeAnalysisBridge.kt ← JNI 兼容门面
│  ├─ cpp/                       ← C++ 原生分析核心（8 个类）
│  └─ res/                       ← 布局、样式、字符串资源
├─ core/                         ← 纯 Kotlin 库（无 UI 依赖）
│  ├─ model/                     ← FrameData.kt（数据模型）
│  ├─ util/                      ← FeatureFlags / ThreadSafeQueue / ObjectPool
│  └─ data/native/               ← NativeLibraryLoader / NativeEngineFacade
├─ features/
│  ├─ overlay/                   ← 悬浮窗 UI 组件（HUD 渲染/绘制/缩放）
│  └─ service/                   ← 自动操作服务（队列/手势注入）
├─ docs/                         ← 项目文档
├─ gradle/
├─ .vscode/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradlew / gradlew.bat
├─ LICENSE
├─ README.md
├─ CONTRIBUTING.md
└─ CHANGELOG.md
├─ LICENSE
├─ README.md
├─ CONTRIBUTING.md
└─ CHANGELOG.md
```

后续预计会逐步形成更清晰的模块边界：

```text
app/src/main/java/top/azek431/hzzs/
├─ ui/
│  ├─ home/
│  ├─ run/
│  ├─ analysis/
│  ├─ profile/
│  ├─ calibration/
│  └─ diagnostics/
├─ capture/
├─ vision/
├─ model/
├─ data/
├─ service/
└─ util/
```

## 开发与构建

推荐开发环境：

```text
操作系统：Windows 10 / Windows 11
编辑器：Visual Studio Code
Java：JDK 17
Android SDK Platform：API 37
Android SDK Build-Tools：37.0.0
构建工具：Gradle Wrapper
```

项目使用 Gradle Wrapper，通常不需要额外全局安装 Gradle。

在项目根目录打开 PowerShell 后，构建 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

> 注意：上述命令在 PowerShell 中也可写作 `.\gradlew.bat :app:assembleDebug`。
> 如果你使用的是 CMD，可以直接运行 `gradlew.bat :app:assembleDebug`（无需 `.\` 前缀）。

Debug APK 默认输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 使用 Android 自动生成的调试签名，仅适合本地开发与测试。

## Release 构建与签名

正式 Release APK 使用开发者本机保存的私有签名。

以下信息必须保留在本地安全环境中，不应提交到 Git 仓库：

```text
发布签名文件
签名密码
私钥密码
本地密钥库路径
个人环境变量
本地签名配置
```

请勿将以下类型文件提交到 GitHub：

```text
*.jks
*.keystore
*.p12
*.pfx
keystore.properties
signing.properties
包含密码的 local.properties
```

## 数据与隐私

HZZS 后续会优先采用本地数据管理策略。

项目计划遵循以下原则：

- 默认优先在设备本地保存记录、配置和诊断信息。
- 不主动上传运行记录、截图、日志或设备信息。
- 涉及屏幕采集或系统权限时，明确说明用途并要求用户主动授权。
- 用户应能够查看、导出或清理本地数据。
- 不在未说明的情况下收集个人信息。
- 不在仓库中保存用户隐私数据、签名文件或敏感配置。

## 使用与合规说明

本项目是独立开发的本地工具应用。

请在遵守当地法律法规、设备系统规则、服务条款与平台规则的前提下使用。

请勿将本项目用于：

- 违反法律法规的行为
- 违反平台规则或服务条款的行为
- 自动化操控、代打、绕过限制或破坏公平性的行为
- 损害他人权益的行为
- 传播恶意软件、隐私数据或未经授权的内容

## 交流与反馈

HZZS 目前处于持续开发阶段，欢迎通过以下入口参与测试、反馈和交流。

- HZZS 项目 QQ 交流群（测试、反馈与问题讨论）：[130330601](https://qm.qq.com/q/5T5fjwRgVq)
- Azek431 主频道（同步 HZZS 与更多项目动态）：[@AzekMain](https://t.me/AzekMain)
- GitHub Issues：适合提交可复现的问题、构建错误、设备兼容问题和功能建议。

反馈问题时，建议尽量附上：

```text
设备型号
Android 版本
应用版本
问题描述
复现步骤
相关截图
错误日志
```

请不要在 Issue、Discussion、Pull Request、日志或截图中上传：

```text
签名文件
密钥库密码
私钥密码
个人账号密码
手机号
身份证明
真实住址
其他隐私信息
```

## 贡献说明

提交代码前，请确保：

- 不包含密码、私钥、签名文件或个人配置。
- 不复制来源不明或许可证不兼容的代码与资源。
- 不把规划中的功能描述成已经实现。
- 提交说明能够准确描述改动目的。
- 新增能力应保留清晰的边界，避免把页面、分析、存储和系统服务逻辑堆进单一文件。

## 关键词

Android · Kotlin · C++ · JNI · Computer Vision · Image Processing · Screen Capture ·
Accessibility Screenshot · MediaProjection · HUD Overlay · Game Analysis · Local First ·
Native Android · Vision Algorithm · 跑酷画面分析 · 安卓悬浮窗 · 本地图像识别 ·
障碍检测 · 绿瓶识别 · 坑位识别 · 参数校准 · 火崽崽助手 · HZZS ·
火崽崽基础辅助 · 基础助手 · 数据分析 · 开源学习 · Azek431

## 开源许可证

本项目采用 [MIT License](./LICENSE) 开源。

MIT 协议允许他人使用、复制、修改、合并、发布、分发、再授权和销售本项目的软件副本；使用或分发时需保留原始版权声明与许可证文本。第三方依赖、素材或资源仍可能适用其各自的许可证，使用前应自行核对。

---

Built with ❤️ by [Azek431](https://github.com/Azek431)
