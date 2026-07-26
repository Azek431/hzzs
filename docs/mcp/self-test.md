# MCP 自测 / 排障指南

真机已装 debug APK 且 MCP 已启用时，**优先应用 MCP 做运行时自检与调参验证**，不要只改代码不连设备。详见根 `CLAUDE.md` 与 `docs/SECURITY.md`。

## 连接（优先 ADB，最稳）

```powershell
adb devices
adb forward tcp:18765 tcp:8765
# Claude Code / 本机 HTTP 客户端：
# url = http://127.0.0.1:18765/mcp   type = http（Claude Code）或 streamable_http（RikkaHub）
```

- 手机设置 → MCP：**启用**；默认免鉴权即可。
- **Wi‑Fi 直连** `http://<lan-ip>:8765/mcp` 仅当已开「允许局域网」且路由**无 AP 隔离**时可用；很多环境（含华为/荣耀 + 有线 PC）会超时——**失败时改 ADB，不要当协议坏了**。
- 可选主机常含 `127.0.0.1`（同机/ADB）以及蜂窝 `10.x`、Tailscale `100.x`、Wi‑Fi `192.168.x`；**同网电脑优先 `192.168.x`**，勿误用蜂窝 IP。
- 进程若在后台 **D（disk sleep）** 导致超时：先 `am start` / 用户点亮 HZZS 再测。

## 自测顺序（只读优先）

1. `initialize` → 记下 `Mcp-Session-Id`（若有）→ `notifications/initialized`
2. `tools/list` / `resources/list`
3. **排障只读**：优先 `inspect`（可 `include`）；或 `get_status`、`get_runtime_snapshot`、`get_automation_gates`、`get_settings`（脱敏 Token）、`get_events`、`get_version` / `get_metrics`、`get_mcp_status` / `get_mcp_access_log`
4. 调参：`patch_settings`（`patches` 与/或 `operations`：set/add/remove/toggle）/ `set_scene` / `set_threshold` / profile CRUD；写操作受权限级与 `toolPolicies`，**ASK** 时须用户在手机确认
5. 运行时：`start_analysis` / `stop_analysis` / `restart_analysis` / `cancel_actions`；调试帧 `capture_debug_frame` / `get_debug_frame`（HIGH_RISK + `allowDebugFrames`）；算法 `upgrade_algorithms(dryRun)`（HIGH_RISK 或需确认时勿连点）

## 工具面速查

| 类别 | 工具 |
| --- | --- |
| 只读诊断 | `inspect`、`get_status`、`get_runtime_snapshot`、`get_automation_gates`、`get_settings`、`get_events`、`get_version`、`get_metrics`、`get_mcp_status`、`get_mcp_access_log`、`get_algorithm_pipeline`、`list_debug_frames`、`run_diagnostics` |
| 配置写入 | `patch_settings`、`preview_settings`、`save_settings`、`reset_preview`、`set_scene`、`set_threshold`、`set_overlay`、`set_theme`、`set_developer_*`、`set_automation_enabled`、`set_active_algorithm`、`set_capture_backend`、`set_gesture_backend` |
| 运行时 | `start_analysis`、`stop_analysis`、`restart_analysis`、`cancel_actions`、`navigate` |
| 算法 | `list_algorithms`、`get_active_algorithm`、`set_active_algorithm`、`refresh_algorithm_catalog`、`download_algorithm`、`upgrade_algorithms` |
| 开发者/高危 | `get_debug_frame`、`capture_debug_frame`、`set_mcp_enabled`、`set_mcp_permission_level`、`set_mcp_auth`、`set_mcp_tool_policy`、`download_algorithm`、`set_developer_enabled`、`set_automation_enabled` |

> 完整工具清单与严格 JSON Schema 见 `mcp/McpToolCatalog.kt`。

## 权限与鉴权

- 服务启停/端口/鉴权/LAN 跟 **`savedConfig`**；`permissionLevel`/`toolPolicies`/工具列表跟 **`current()`**（可含设置草稿）。
- 改 `permissionLevel` / `toolPolicies` **不**重启服务；改绑定/端口会重启并清空会话（RikkaHub 易 -32003）。
- 默认免 Bearer；`requireAuth=true` 时用持久化 `authToken`（恒时比较），不在每次启动轮换，仅设置页「轮换 Token」时更新。
- 访问日志（`accessLogEnabled`，默认开）：进程内 ring `McpAccessLog`，记 method/工具/状态/耗时/远端摘要；**永不**记 Bearer、`authToken`、请求参数体。

## 排障常见问题

| 现象 | 先查 |
| --- | --- |
| tools/list 返回空 / 工具消失 | `toolPolicies` 是否置 DISABLED；服务是否重启清空会话 |
| 手机点确认但操作不生效 | 权限级是 ASK 但无会话 / HIGH_RISK 在 TRUSTED_SESSION 下被拒绝 |
| Wi‑Fi 直连超时 | 改 ADB（AP 隔离） |
| 连接被拒 / 立即断 | 服务是否启停指纹未变但进程死掉；`adb forward` 是否生效 |

## 硬规则

- **不得**把 Bearer / 完整 `authToken` 写入仓库、提交说明或对话日志；访问日志摘要同样不得含 Token/参数体。
- 外部导入 / `save_settings` 仍经 `hardenedForExternalIngest`；不得静默开自动操作或局域网。
- 设置页「可选主机」始终含 `127.0.0.1`（未开局域网也可复制回环 URL）；LAN IP 仅在允许局域网后追加。
- 门禁：`python tools/quality/check_project.py` 校验 MCP 安全字面量（含 LAN 门控）。

## 关联文档

- `mcp/McpForegroundService.kt` — 服务主循环 + 连接/鉴权/Origin 门禁
- `mcp/McpActionRegistry.kt` — 四级权限 + 工具策略仲裁
- `mcp/McpToolCatalog.kt` — 工具/资源目录
- `mcp/McpAccessLog.kt` — 访问日志 ring
- `mcp/CLAUDE.md` — 模块级真相源
- `docs/SECURITY.md` — 安全边界全文
