# core/preferences — 配置持久化与校验

应用配置的**唯一真相源**：DataStore 读写 `AppConfig` + 内存预览层 + 导入导出 JSON + 旧版 SharedPreferences 安全迁移。所有写入前执行 `AppConfig.validated()`。

> DataStore 文件名仍为 `hzzs_settings_v5`（历史命名）；schema 版本见 `AppConfig.CURRENT_SCHEMA`。

## 职责

- `SettingsRepository` 接口：`config`（preview 优先）/ `savedConfig`（仅已保存）/ `snapshot`/`current`/`preview`/`clearPreview`/`save`/`updateSavedPreservingPreview`/`importJson`/`exportJson`/`exportJsonRedacted`。
- `DataStoreSettingsRepository`：单例 DataStore 实现；预览用 `MutableStateFlow` 即时覆盖；`current`/`snapshot` 优先读解码缓存避免每次走 DataStore map。

## 入口

1. `SettingsRepository.kt` — 全部（单文件）。
2. `core/model/AppConfig`（被持久化的类型）、`feature/settings/SettingsViewModel`（主消费者）、`mcp/*`（MCP 服务/权限仲裁读 current/saved）。

## 数据流

```text
设置页 vm.update → repository.preview(config)      ← 内存预览（不落盘，即时作用于主题/悬浮窗）
顶栏「保存并应用」→ repository.save(config)        ← validated + 落盘 + 清空 preview
MCP 服务启停 → repository.savedConfig              ← 仅已保存才生效
权限仲裁/tools/list → repository.current()         ← preview 优先，避免「页面已改、服务仍读磁盘旧值」
```

## 不变量 / 安全

- 迁移与导入**不得**静默开启自动操作 / Root；MCP 强制 loopback。
- `updateSavedPreservingPreview`：供运行时自调触发距离等后台写盘路径，**不**清空 preview；若有 preview 会把 automation 触发距离字段合并进 preview，避免「保存并应用」用过期草稿盖回自调结果。
- `exportJsonRedacted`：MCP/诊断通道导出时脱敏 `McpConfig.authToken`（有值写 `***`）；用户备份导出仍用 `exportJson` 完整写入。
- 生效配置 = preview（若有）否则已保存快照。

## 改这个包前必读

- 改接口：同步 `feature/settings/SettingsViewModel`、`MainActivity`（MCP 启停读 savedConfig）、`mcp/*`（MCP 读 current/saved）、`feature/onboarding`（引导用 preview/save）。
- 改 DataStore 键/迁移：同步 `AppConfig.CURRENT_SCHEMA`、`ConfigJson` 编解码、旧版 SharedPreferences 迁移。
- 改 `validated()`：同步 `core/model/AppConfig`、设置 UI、MCP schema、单测。

## 测试

- 相关测试：`SettingsSessionTest`。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
