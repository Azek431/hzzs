# AGENTS.md

本文档为 AI 代理（Codex / Claude / 其他）提供**当前仓库**的开发导航。  
**以源码与本文件、`CLAUDE.md`、`docs/*` 为准。** 若与任何旧备份、旧 Issue 摘录冲突，以当前分支源码为准。

## 项目概述

| 项 | 内容 |
| --- | --- |
| 名称 | HZZS（火崽崽数据分析） |
| 定位 | 《火崽崽奇妙屋》第三方 **本地** Android 画面分析工具 |
| 包名 | `top.azek431.hzzs` |
| 语言 | Kotlin + Jetpack Compose + Hilt + C++17 (JNI) |
| 最低 SDK | 24（Android 7.0） |
| 目标 / 编译 SDK | 37 |
| 版本 | **0.1.0** / `versionCode = 1`（首发目标，尚未正式 release） |
| 默认赛季 | **竹影书屋** `SceneId.BAMBOO_BOOKSTORE` |
| 模块 | **仅** `:app` |
| 仓库 | [Azek431/hzzs](https://github.com/Azek431/hzzs) |

主要能力：MediaProjection 截图、C++ 双赛季障碍识别、可配置悬浮窗、受控自动操作、本地 MCP、主题包、双源更新库。

## 快速开始

```powershell
python tools/quality/check_resources.py
python tools/quality/check_project.py
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

## 架构（当前）

```text
feature → data/service → domain/core
platform/compat 只做能力探测
```

```text
top.azek431.hzzs/
├─ MainActivity.kt / HzzsApplication.kt
├─ core/           AppConfig、SettingsRepository、主题、更新
├─ domain/         VisionModels、GestureArbiter
├─ data/vision/    VisionRuntimeController、NativeVisionEngine、Tracker
├─ feature/        onboarding / home / runtime / settings / about
├─ service/        capture / overlay / automation
├─ platform/compat CaptureCapabilities
├─ mcp/            McpService、ActionRegistry
└─ nativevision/   NativeVision JNI 边界

app/src/main/cpp/  jni_bridge、vision_engine、legacy_main/{vision2,vision_bamboo}（主路径）、sweet_factory/bamboo_bookstore（回退）、scene_geometry/color_components
app/src/test/      JVM 单测 + cpp/native_tests.cpp
```

### 主数据流

```text
FrameSource → VisionRuntimeController → NativeVisionEngine (JNI)
  → VisionResultValidator → MultiObjectTracker
  → OverlayController
  → (arm 后，独立 actionJob) GestureArbiter → HzzsAccessibilityService
```

### 配置流

```text
SettingsScreen 预览/保存 → SettingsRepository → AppConfig Flow
  → Theme / Runtime / MCP
```

权限型字段（截图后端、automation、mcp、developer、update）**预览时不生效**。

## 安全不变量（修改时必须保持）

1. `CaptureBackend.AUTO` **只**走 MediaProjection，不探测 Root/Shizuku/无障碍。
2. 自动操作默认关；导入/迁移不得静默开启；需免责声明版本 + 会话 arm。
3. MCP 仅 loopback + 随机 Bearer；默认写操作需确认。
4. 主题包声明式 JSON，无脚本/远程资源。
5. 帧缓冲有 `close()` 租约；Native 不持有 Java 数组地址。
6. 视觉坐标归一化 `[0,1]`，仅绘制/手势层转像素。

## 与历史 main 的关系

- 历史 `main`：多模块（`app`/`core`/`features/*`）、Views、叠层 `runtime/*` 视觉、`libhzzs_native.so`（analysis 状态机 + vision2 + bamboo）。
- 当前分支：单模块 Compose 统一架构；算法与动作阈值正从 main **有选择回迁**。
- 对照行为时可用 `git show main:<path>`，**不要**把 main 的包路径当当前代码路径修改。

## 测试

存在 JVM 测试，例如：

- `SettingsSessionTest`、`ThemePackageTest`
- `GestureArbiterTest`、`VisionResultValidatorTest`
- `FrameSequenceTest`

Native：`tools/vision/run_native_sanitizers.sh`、`run_host_tests.py --max-representative`。

## 文档地图

| 文件 | 用途 |
| --- | --- |
| `README.md` | 对外说明 + Star 趋势图 |
| `CLAUDE.md` | Claude 硬约束与修改流程 |
| `docs/ARCHITECTURE.md` | 架构 |
| `docs/SECURITY.md` | 安全 |
| `docs/TESTING.md` | 测试 |
| `docs/PROGRESS.md` | 进度与未完成 |
| `CHANGELOG.md` | 变更 |
| `CONTRIBUTING.md` | 贡献 |

## 协作规则

- 与用户沟通默认使用**中文**。
- 执行任务前识别模糊需求；不确定处先提问（用户明确授权自行决定除外）。
- 改代码后同步相关 README/CLAUDE/docs，并跑质量门禁。
- 不提交密钥、签名文件、`local.properties`、本地备份与生成二进制。
- **不要**根据过期的“无测试 / 仅模拟 HUD / MediaProjection 未接入”描述做决策。

## 游戏素材（可选参考）

视觉调参可参考本机素材库（若存在）：

`D:\Code\AI\火崽崽\火崽崽奇妙屋\`

仅用于算法验证；注意隐私与版权，勿把未脱敏截图提交进仓库。
