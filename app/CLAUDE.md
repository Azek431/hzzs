# app/CLAUDE.md

## 修改边界

此目录是唯一 Android 产品模块。新增产品代码时优先放入既有职责包，不新增根级 Gradle 模块。

## 必查链路

- 修改 `AppConfig`：同步 `validated()`、`ConfigJson`、设置 UI、MCP schema 和测试。
- 修改障碍类别：同步 Kotlin 枚举、场景过滤、JNI 位掩码、C++ 类别和标注工具。
- **Avoidance 枚举扩展**（CC-2）：新增 `PRESS` / `SWIPE_UP`；C++ `vision_types.h`、Kotlin `VisionModels.kt`、规划器 `planGestures`、`DisplayNames.kt` 四方同步。
- **多点找色引擎**（CC-2）：`multicolor_detector.h/.cpp` 声明式模板匹配；坐标全部视口归一化；搜索区 left/top/right/bottom；阈值 `SceneAlgorithmParams.multicolorThreshold`（酱油默认 10）。海盐模板在 `sea_salt_living_room.cpp`（设计 1272×2772、AutoJS ARGB）；**不**加找色专用绘制（仅 `Detection.bounds`）；不移植「复活」点击。
- 修改截图：检查 API 24、26、29、30、33、34+ 分支，授权失效、旋转、空帧、超时和资源释放；**AUTO 不得升权**。
- 修改悬浮窗：保证 View 持久复用、主线程调用、权限撤销后立即移除。
- 修改自动操作：保证包名白名单、窗口状态、帧时效、置信度与串行手势仲裁。
- 修改 MCP：所有写操作必须经过权限策略；服务只绑定 loopback；不得记录 Bearer Token。设置页已拆分为独立「MCP 服务」分类（普通用户可访问）。
- 修改开发者选项：需关于页连续点击版本号解锁；设置页独立「开发者选项」分类，与 MCP 页面分离。
- 修改默认赛季：只改 `AppConfig.DEFAULT_SELECTED_SCENE`，并同步迁移/单测；**不要**在 README/CLAUDE/AGENTS/PROGRESS 写死赛季中文名或枚举值。
- 修改版本号：同步 `app/build.gradle.kts` 默认值与 CHANGELOG（若用户可见）。

## 测试

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

Native 与项目门禁见根目录 `docs/TESTING.md`。
