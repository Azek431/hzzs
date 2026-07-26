# feature — 顶层包（MainActivity / 根导航）

> 注意：`feature/` 下各业务包（home/runtime/settings/about/onboarding）各有独立 `CLAUDE.md`。本包级仅承载跨包共享的根级 Composable。

根级 UI 宿主：`MainActivity` 是**唯一导出的 Activity**，承载 Compose 根树、底部/侧栏导航、首次引导分流、MCP 审批 UI。`HzzsApplication` 是进程级 Application 入口（启用 Hilt + 诊断未捕获异常钩子）。

## 子包导航（核心索引）

| 子包 | 职责 | 入口 |
| --- | --- | --- |
| `feature/home` | 主页（入口分流、状态卡片） | `feature/home/CLAUDE.md` |
| `feature/runtime` | 视觉运行时悬浮/预览/HUD | `feature/runtime/CLAUDE.md` |
| `feature/settings` | 设置页（草稿+保存；算法/MCP/开发者分类） | `feature/settings/CLAUDE.md` |
| `feature/about` | 关于页（连点解锁开发者） | `feature/about/CLAUDE.md` |
| `feature/onboarding` | 首次引导 | `feature/onboarding/CLAUDE.md` |
| `feature/algorithm` | **已下沉**：算法设置页现位于 `feature/settings/screens/` | 见 `feature/settings/CLAUDE.md` |

## 职责（仅根级，业务已下沉到子包）

- `MainActivity`：应用导航壳 + MCP 服务启停（`syncMcpService`）+ MCP 审批对话框宿主。
- `AppViewModel`（MainActivity 内）：聚合全局配置、MCP 审批/导航桥、静默更新检查；把进程级生命周期留在根包，不下沉到 feature。
- `HzzsApplication`：Hilt 入口 + 未捕获异常钩子 + 创建分析前台服务通知渠道。

## 入口

- `MainActivity.kt` — `HzzsRoot` / `AppNavHost` / `syncMcpService` / `McpApprovalDialog`。
- `HzzsApplication.kt` — 进程入口。
- 业务入口见各子包 `CLAUDE.md`。

## 数据流

```text
HzzsRoot
  ├─ onboarding.completed ? OnboardingScreen : MainNavigation
  ├─ 主题/悬浮窗可跟 preview；自动操作与截图后端只跟已落盘 saved
  ├─ syncMcpService（savedConfig.mcp 指纹变化 → STOP/START 前台服务）
  └─ McpApprovalDialog（McpUiBridge.approval → 回写 resolveApproval）

MainNavigation：窄屏底部栏 / 宽屏侧栏 + NavHost（home/runtime/settings/about）
  └─ 离开设置：SettingsExitCoordinator.request → 可能弹未保存对话框
```

## 不变量 / 安全

- `syncMcpService` 只跟已保存配置：MCP 开关/端口/鉴权/LAN 的指纹变化才重启服务；指纹**不含** `permissionLevel`/`toolPolicies`（二者在 `tools/call` 时读 `current()`，变更无需重启 socket，重启会清会话表）。
- 自动操作 / 截图后端只跟 `saved`，避免设置草稿未保存就派发手势或换源。
- 捐赠图片保存：API ≤ 28 需 WRITE_EXTERNAL_STORAGE；经 MediaStore 写入「相册/HZZS」。
- 根包不直接 JNI/Root/WindowManager/无障碍；经注入的 data/service 控制器。

## 改这个包前必读

- 改根导航：同步 `feature/settings/SettingsScreen`（嵌套导航）、`feature/onboarding/OnboardingScreen`（引导分流）。
- 改 MCP 启停：同步 `mcp/McpForegroundService` 与 `MainActivity.syncMcpService` 指纹逻辑。
- 改审批 UI：同步 `mcp/McpUiBridge`、`mcp/McpActionRegistry`（四级权限）。
- 改主导航入口表：`Destination` 枚举（home/runtime/settings/about）。
- 改算法设置入口：位于 `feature/settings/screens/AlgorithmSettingsScreen.kt`，详见 `feature/settings/CLAUDE.md`。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 当前缺口：根导航缺 UI/instrumentation 测试。
