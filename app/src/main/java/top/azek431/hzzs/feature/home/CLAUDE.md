# feature/home — 首页

工具专业风首页：**只读**已保存配置速览 + 进入运行/设置的单一主路径。**不启动分析、不编辑草稿、不触碰权限型运行时能力**。

## 职责

- `HomeViewModel`：只读 `SettingsRepository.config` 与 `VisionRuntimeController.status`。
- `HomeScreen`：就绪摘要 + 状态带 + 配置速览 + 安全提示。

## 入口

1. `HomeScreen.kt` — 全部（单文件）。
2. `MainActivity.AppNavHost`（入口）、`data/vision/VisionRuntimeController`（运行时状态）、`core/preferences/SettingsRepository`（配置）。

## 数据流

```text
HomeViewModel.config / status → HomeScreen（只读展示）
  → onOpenRuntime → MainActivity.navigate(RUNTIME)
  → onOpenSettings → MainActivity.navigate(SETTINGS)
```

## 不变量 / 边界

- **只读**：首页不持有草稿；配置编辑去设置页。
- 不启动分析（启停在 `feature/runtime`）；不直接 JNI/截图/WindowManager。
- 状态带使用 `RuntimeStatus.running` / `activeScene` / `activeBackend`；自动操作状态仅展示 enabled。

## 改这个包前必读

- 改展示字段：同步 `core/model/AppConfig`、`core/model/RuntimeStatus`、`core/model/SceneId/CaptureBackend/McpConfig` 的 `displayName()`。
- 改主路径：唯一跳转点是 `onOpenRuntime` / `onOpenSettings`，由 `MainActivity.AppNavHost` 驱动。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：配置变化后首页速览更新、状态芯片切换。
