# app/CLAUDE.md

## 模块文档索引

`app/` 每个业务包现都有独立 `CLAUDE.md`（职责/入口/不变量/测试），**改代码前先读该包文档**：

| 包 | 文档 |
| --- | --- |
| `core/model`、`core/preferences`、`core/algorithm`、`core/theme`、`core/update`、`core/logging`、`core/designsystem`、`core/platform`、`core`（顶索引） | `core/*/CLAUDE.md` |
| `data/vision` | `data/vision/CLAUDE.md` |
| `domain/vision`、`domain/automation` | `domain/*/CLAUDE.md` |
| `feature/home`、`feature/runtime`、`feature/settings`、`feature/onboarding`、`feature/about`、`feature`（根索引） | `feature/*/CLAUDE.md` |
| `service/capture`、`service/overlay`、`service/automation`、`service/vision` | `service/*/CLAUDE.md` |
| `mcp`、`nativevision`、`platform/compat` | 各包 `CLAUDE.md` |

另见 `core/algorithm/logic/CLAUDE.md`、`core/algorithm/CLAUDE.md`（算法包系统真相源）。

## 修改边界

此目录是唯一 Android 产品模块。新增产品代码时优先放入既有职责包，不新增根级 Gradle 模块。

## 必查链路

- 修改 `AppConfig`：同步 `validated()`、`ConfigJson`、设置 UI、MCP schema 和测试。
- 修改障碍类别：同步 Kotlin 枚举、场景过滤、JNI 位掩码、C++ 类别和标注工具。
- **Avoidance 枚举扩展**（CC-2）：新增 `PRESS` / `SWIPE_UP`；C++ `vision_types.h`、Kotlin `VisionModels.kt`、规划器 `planGestures`、`DisplayNames.kt` 四方同步。
- **多点找色引擎**（CC-2）：`multicolor_detector.h/.cpp` 声明式模板匹配；坐标全部视口归一化；搜索区 left/top/right/bottom；阈值 `SceneAlgorithmParams.multicolorThreshold`（酱油默认 10）。海盐模板在 `sea_salt_living_room.cpp`（设计 1272×2772、AutoJS ARGB）。**算法只算数据**（`Detection`/`bounds`），**不**自带绘制；屏幕框/轮廓由 App 通用 HUD 读取检测结果呈现（数据关联、职责分离）；禁止找色专用绘制通道；不移植「复活」点击。
- 修改截图：检查 API 24、26、29、30、33、34+ 分支，授权失效、旋转、空帧、超时和资源释放；**AUTO 不得升权**。
- 修改悬浮窗：保证 View 持久复用、主线程调用、权限撤销后立即移除。
- 修改自动操作：保证可选包名限制（默认关）、`GestureBackend` 选择与前台探测（无障碍 / dumpsys）、帧时效、置信度与串行手势仲裁；勿恢复强制白名单求交；AUTO 手势永不升 Root。
- 修改 MCP：所有写操作必须经过权限策略；默认 loopback，用户可显式 LAN（`bindLocalhostOnly=false`）；不得记录 Bearer Token。访问日志（`McpAccessLog` / `accessLogEnabled`）只记 method/工具/状态/耗时/远端，**永不**记 Token 与 arguments。绑定/启停跟 `savedConfig`；策略与 `tools/list` 跟 `current()`。设置页「可选主机」始终含 `127.0.0.1`。设置页已拆分为独立「MCP 服务」分类（普通用户可访问）。**代理自测**：真机 MCP 开着时用 `adb forward tcp:18765 tcp:8765` + `http://127.0.0.1:18765/mcp` 调 `get_runtime_snapshot` / `get_status` / `get_mcp_access_log` 等，见根 `CLAUDE.md`「代理用 MCP 自测」。
- 修改开发者选项：关于页连点版本号 7 次开启后，设置首页才显示「开发者选项」分类；页内开关可关闭。关于入口与设置入口共用 `DeveloperSettingsScreen`，与 MCP 页面分离。系统指针位置经 `SystemCapabilityAccess`（`WRITE_SETTINGS` 优先，已授权 Shizuku / Root 可 `settings put`），不进 AppConfig、不静默要权。
- 修改默认赛季：只改 `AppConfig.DEFAULT_SELECTED_SCENE`，并同步迁移/单测；**不要**在 README/CLAUDE/AGENTS/PROGRESS 写死赛季中文名或枚举值。
- 修改版本号：同步 `app/build.gradle.kts` 默认值与 CHANGELOG（若用户可见）。

## 测试

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

真机 MCP 联调（可选，代理推荐）：

```text
adb forward tcp:18765 tcp:8765
# initialize / tools/list / get_status / get_runtime_snapshot
```

Native 与项目门禁见根目录 `docs/TESTING.md`。
