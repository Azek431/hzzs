# core/model — 应用级稳定配置模型

DataStore / 设置草稿 / 运行时快照的**共享类型**。`core` 包不依赖 Android 服务或 UI（仅 `ColorInt` 注解）。

## 职责

- 主题、悬浮窗、截图、场景、自动操作、MCP、开发者、更新、算法等配置结构（`AppConfig` 及其子树）。
- 枚举：`SceneId` / `CaptureBackend` / `GestureBackend` / `OverlayStyle/Theme` / `PlayerReferenceMode` / `AlgorithmSelectionMode` 等 + `displayName()`。
- `RuntimeStatus` / `RuntimeStatusUtils`：运行时状态结构。
- 坐标与几何：`ViewportConfig` / `VisionThresholds` / `ObstacleKind` 等。

## 入口

1. `AppModels.kt` — 配置主干 + 枚举 + `displayName()`。
2. `DisplayNames.kt` — 枚举中文名。
3. `RuntimeStatusUtils.kt` — 运行时状态工具。
4. `core/preferences/SettingsRepository`（消费）/`core/algorithm/*`（算法配置）/`mcp/*`（MCP schema）。

## 不变量

- 默认值必须**安全**：自动操作关、MCP 关、截图 AUTO 不升权。
- **修改字段时同步四方**：`validated()`、JSON 编解码（`ConfigJson`）、设置 UI、MCP schema、单测（见 `app/CLAUDE.md`）。
- 枚举序与 C++ 一致：`SWEET_FACTORY=0`、`BAMBOO_BOOKSTORE=1`、`SEA_SALT_LIVING_ROOM=2`。
- 赛季名是产品默认，文档不写死；取 `AppConfig.DEFAULT_SELECTED_SCENE`。

## 改这个包前必读

- 改 `SceneId` / `ObstacleKind` / `Avoidance`：同步 C++ `vision_types.h`、Kotlin 枚举、JNI 位掩码、数据集报告（见 `app/CLAUDE.md` 「Avoidance 枚举扩展」）。
- 改 MCP 相关字段：同步 `mcp/McpConfig`、`mcp/McpSettingsPatch`、MCP schema。
- 改默认赛季：只改 `AppConfig.DEFAULT_SELECTED_SCENE`，并同步迁移/单测；**不要**在 README/CLAUDE/AGENTS/PROGRESS 写死赛季中文名。
- 改 `displayName()`：同步 `DisplayNames.kt` 与设置页/MCP 工具描述。

## 测试

- 相关测试：`SettingsSessionTest`、`ThemePackageTest`。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
