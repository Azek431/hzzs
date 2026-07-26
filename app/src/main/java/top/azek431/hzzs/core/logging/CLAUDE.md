# core/logging — 应用日志与诊断导出

进程内日志门面（Logcat + memory ring buffer）+ 脱敏诊断导出。**不写文件、不上传、不记录 Bearer Token**。

## 职责

- `AppLog`：Logcat + 内存 ring buffer（容量可调，关闭开发者时 DEBUG/VERBOSE 不入 buffer）；`revision` 在写入/清空时递增供 UI 轮询刷新；每条带稳定 `AppLogEntry.id`。
- `DiagnosticsExporter`：构建可分享纯文本诊断包（版本/机型/配置摘要/算法激活/运行态/最近日志），**不包含 MCP Bearer、签名密钥、调试帧像素**。
- `McpDiagnosticsSnapshot` / `AlgorithmDiagnosticsSnapshot`：MCP/算法状态摘要（不含 token / profile 大字段）。

## 入口

1. `AppLog.kt` — 日志门面。
2. `DiagnosticsExporter.kt` — 诊断导出。
3. `feature/settings/SettingsViewModel.buildDiagnosticsReport`、`feature/settings/screens/LogViewerScreen`（内存日志查看器）、`feature/settings/screens/DeveloperSettingsScreen`（开发者页诊断导出）。

## 不变量 / 安全

- 诊断导出**不包含** MCP Bearer、签名密钥、调试帧像素；配置仅摘要字段。
- 设备本地时区 + 真实偏移（如 `+08:00`），避免把本地时间标成假 `Z`。
- 日志级别由 `configure` 同步；关闭开发者时 DEBUG/VERBOSE 不入 buffer。
- 进程内；不依赖 Hilt；任意线程可写，快照拷贝出只读列表。

## 改这个包前必读

- 改 `AppLog`：同步 `feature/settings/screens/LogViewerScreen`（查看器）、`DeveloperSettingsScreen`（日志策略）、`feature/settings/SettingsViewModel`（日志配置）。
- 改 `DiagnosticsExporter`：同步 `feature/settings/SettingsViewModel.buildDiagnosticsReport`、MCP `export_diagnostics`。
- 改诊断字段：同步 `docs/SECURITY.md`（诊断脱敏边界）。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 真机验证：日志查看器、诊断导出、级别过滤。
