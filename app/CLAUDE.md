# app/CLAUDE.md

## 修改边界

此目录是唯一 Android 产品模块。新增产品代码时优先放入既有职责包，不新增根级 Gradle 模块。

## 必查链路

- 修改 `AppConfig`：同步 `validated()`、`ConfigJson`、设置 UI、MCP schema 和测试。
- 修改障碍类别：同步 Kotlin 枚举、场景过滤、JNI 位掩码、C++ 类别和标注工具。
- 修改截图：检查 API 24、26、29、30、33、34+ 分支，授权失效、旋转、空帧、超时和资源释放；**AUTO 不得升权**。
- 修改悬浮窗：保证 View 持久复用、主线程调用、权限撤销后立即移除。
- 修改自动操作：保证包名白名单、窗口状态、帧时效、置信度、会话解锁和串行手势仲裁。
- 修改 MCP：所有写操作必须经过权限策略；服务只绑定 loopback；不得记录 Bearer Token。
- 修改默认赛季：只改 `AppConfig.DEFAULT_SELECTED_SCENE`，并同步迁移/单测；**不要**在 README/CLAUDE/AGENTS/PROGRESS 写死赛季中文名或枚举值。
- 修改版本号：同步 `app/build.gradle.kts` 默认值与 CHANGELOG（若用户可见）。

## 测试

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

Native 与项目门禁见根目录 `docs/TESTING.md`。
