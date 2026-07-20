# HZZS 火崽崽数据分析

<p align="center">
  <strong>面向《火崽崽奇妙屋》的第三方 Android 本地画面分析工具</strong><br>
  截图采集 · C++ 障碍识别 · 可配置悬浮窗 · 受控自动操作 · 本地 MCP
</p>

<p align="center">
  <a href="https://github.com/Azek431/hzzs/stargazers">
    <img src="https://img.shields.io/github/stars/Azek431/hzzs?style=social" alt="GitHub stars">
  </a>
  <a href="https://github.com/Azek431/hzzs/releases">
    <img src="https://img.shields.io/github/v/release/Azek431/hzzs?include_prereleases" alt="Release">
  </a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-green" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/Kotlin%20%2B%20C%2B%2B17-blue" alt="Kotlin + C++17">
  <img src="https://img.shields.io/badge/version-0.1.0-orange" alt="version 0.1.0">
</p>

<p align="center">
  <a href="#主要能力">主要能力</a> ·
  <a href="#权限与截图后端">截图后端</a> ·
  <a href="#mcp">MCP</a> ·
  <a href="#代码结构">结构</a> ·
  <a href="#构建">构建</a> ·
  <a href="#文档">文档</a> ·
  <a href="#star-趋势">Star 趋势</a>
</p>

> **免责声明**
>
> HZZS 与游戏、平台及其运营方没有隶属或授权关系。本项目用于技术研究与数据分析，不承诺识别结果绝对准确。自动操作属于高风险可选功能，可能因画面变化、网络延迟、系统卡顿或识别误差产生错误操作。使用者应遵守游戏规则、平台条款和当地法律，并自行承担使用风险。

## 当前版本

| 项 | 值 |
|---|---|
| 版本 | **0.1.0**（首发目标版本，尚未正式打 tag 发布） |
| versionCode | **1** |
| 包名 | `top.azek431.hzzs` |
| 最低系统 | Android 7.0（API 24） |
| 目标 / 编译 SDK | 37 |
| 默认赛季 | **竹影书屋**（`BAMBOO_BOOKSTORE`） |
| 模块形态 | 单一 `app` Gradle 模块 |
| 配置 schema | DataStore v5 |

首要目标：低权限默认、Android 7+ 兼容、比例坐标适配、设置可回滚、算法结果可测试，以及让开发者和 AI 能快速理解并安全修改代码。

## 主要能力

- 单一 `app` 模块，业务代码按职责分包（Compose + Hilt）。
- 甜甜圈、竹影书屋两个赛季配置，共用比例坐标体系；**首次安装默认竹影书屋**。
- 默认识别全部障碍类别，可按赛季关闭具体类别。
- 固定比例、启动检测一次、持续检测三种玩家基准策略。
- **MediaProjection 默认截图**；`CaptureBackend.AUTO` 只选低权限公开接口，**不探测 Root / Shizuku / 无障碍**。
- 无障碍、Shizuku、Root 仅由用户主动选择（Shizuku 适配器见进度文档）。
- 极简、紧凑、调试 HUD 三种悬浮窗。
- Material 3 内置主题、动态取色、AMOLED、高对比、自定义颜色与 `.hzzstheme` 主题包。
- 设置修改可临时预览；取消或离开恢复；点击保存后永久生效。权限型设置（截图后端、MCP、自动操作等）不在预览阶段启动。
- 首次启动引导、简体中文免责声明、自动操作风险等待确认与**会话解锁（arm）**。
- 关于页连续点击版本号 7 次解锁开发者设置。
- MCP 本地服务：只读 / 每次确认 / 会话信任 / 完整访问四级权限。
- C++ 输入边界、JNI 失败隔离、宿主机 Sanitizer、数据集回归与发布门禁。
- Gitee 优先的双源签名更新与增量补丁工具链（应用内检查入口见设置/关于）。

## 权限与截图后端

| 后端 | 适用范围 | 默认 | 说明 |
|---|---|---:|---|
| 自动 | API 24+ | 是 | **只选择低权限 MediaProjection**，不探测 Root 或 Shell |
| 屏幕录制 | API 24+ | 推荐 | 用户通过系统界面授权，适合连续分析 |
| 无障碍截图 | API 30+ | 否 | 仅在用户已开启无障碍服务后使用，存在系统调用频率限制 |
| Shizuku | 高级 | 否 | 用户显式选择；经 Shizuku 执行 `screencap -p`（需安装/启动/授权） |
| Root | Root 设备 | 否 | 每帧执行受超时和大小限制的 `screencap`，兼容性和风险最高 |

应用不会自动调用 `su`、自动启动 Shizuku、自动开启无障碍，也不能绕过 Android 系统授权界面。

## MCP

MCP 服务默认关闭，只绑定设备回环地址，并在每次启动时生成随机 Bearer Token。电脑端可通过 ADB 端口转发连接。应用内页面、状态、设置、分析和悬浮窗操作通过语义工具暴露，不依赖屏幕坐标点击。

即使选择“完整访问”，MCP 也只能执行应用本身有权执行的操作，无法替代系统录屏授权、悬浮窗授权、无障碍开关或安装确认。

## 代码结构

```text
app/
├─ src/main/java/top/azek431/hzzs/
│  ├─ core/         配置、主题、设计系统、更新模型
│  ├─ domain/       视觉与自动操作领域规则
│  ├─ data/vision/  运行时编排、追踪、JNI 适配
│  ├─ feature/      引导、首页、运行、设置、关于
│  ├─ platform/     Android 版本与能力探测
│  ├─ service/      截图、无障碍、悬浮窗
│  ├─ mcp/          本地 MCP 传输和权限仲裁
│  └─ nativevision/ JNI 加载边界
├─ src/main/cpp/    C++ 视觉算法、JNI 与宿主机 ABI
└─ src/test/        JVM 与 C++ 测试

docs/               架构、安全、测试和进度
tools/              质量检查、视觉回归和发布工具
.github/workflows/  构建与发布门禁
```

每个重要目录的 `README.md` 与 `CLAUDE.md` 说明职责、数据流、线程模型、修改入口和验证命令。

主数据路径：

```text
FrameSource → VisionRuntimeController → NativeVisionEngine
  → VisionResultValidator → MultiObjectTracker
  → OverlayController / GestureArbiter
```

## 构建

需要：

- JDK 17
- Android SDK 37
- Android NDK `28.2.13676358`
- CMake `3.22.1`
- Python 3.11+（质量与视觉回归工具）

```bash
python3 -m compileall -q tools
python3 tools/quality/check_resources.py
python3 tools/quality/check_project.py
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Windows：

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

Native 宿主机验证：

```bash
bash tools/vision/run_native_sanitizers.sh
python3 tools/vision/run_host_tests.py --dataset /path/to/screenshots --max-representative 48
python3 tools/vision/evaluate_dataset.py --dataset /path/to/screenshots --output build/vision-results
```

## 发布

正式 Release 必须提供完整签名环境变量（`ANDROID_KEYSTORE_PATH` 等）。缺少任何签名项时任务会失败，不会生成误签名发布包。发布工作流负责 APK 验签、更新清单签名、增量补丁验证、GitHub/Gitee 资产同步与匿名下载校验。

当前仓库尚未打出正式 `v0.1.0` Release 时，应用内更新检查会因源不可用而失败，这是预期行为。

## 测试边界

宿主机数据集运行可以证明崩溃安全、输出约束和性能分布，但 **不能替代人工真值标注**。在没有独立标注前，项目不会声称达到 99% 准确率、所有机型覆盖或固定像素误差目标。

中国厂商 ROM 的后台限制、悬浮窗、Root、Shizuku 和真实游戏操作仍需要对应真机报告。详见 [`docs/TESTING.md`](docs/TESTING.md) 与 [`docs/PROGRESS.md`](docs/PROGRESS.md)。

## 文档

| 文档 | 说明 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | AI / Claude Code 协作硬约束 |
| [`AGENTS.md`](AGENTS.md) | Codex / 通用 AI 代理导航（与当前架构同步） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构与数据流 |
| [`docs/SECURITY.md`](docs/SECURITY.md) | 安全与权限模型 |
| [`docs/TESTING.md`](docs/TESTING.md) | 测试与门禁 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 进度与未完成项 |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更日志 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献说明 |

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=Azek431/hzzs&type=Date)](https://star-history.com/#Azek431/hzzs&Date)

仓库地址：

- GitHub：<https://github.com/Azek431/hzzs>
- Gitee：<https://gitee.com/Azek431/hzzs>

## 许可证

见 [`LICENSE`](LICENSE)。
