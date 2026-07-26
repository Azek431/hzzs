# feature/runtime — 运行控制页

工具专业风操作台：**启停视觉分析、展示运行时指标**。feature 只发意图；不直接 JNI/截图/WindowManager。

## 职责

- `RuntimeViewModel`：启停 `VisionRuntimeController`；暴露 `status`/`config`。
- `RuntimeScreen`：启停按钮 + 实时指标（FPS/处理耗时/障碍数）+ 悬浮窗阻塞原因 + 自动操作最后决策 + 错误提示。

## 入口

1. `RuntimeScreen.kt` — 全部（单文件）。
2. `data/vision/VisionRuntimeController`（唯一 controller 调用者）、`platform/compat/SystemCapabilityAccess`（打开悬浮窗设置）。

## 数据流

```text
RuntimeViewModel.toggle → controller.start/stop
controller.status → RuntimeScreen（实时指标 / 阻塞原因 / 自动操作决策 / 错误）
OverlayBlockReason.PERMISSION → SystemCapabilityAccess.openOverlayPermissionSettings
```

## 不变量 / 边界

- feature 只发意图：启停经 `VisionRuntimeController`，不直接操作截图/手势。
- 状态来自 `RuntimeStatus`（running / captureReady / overlayVisible / fps / processingMs / obstacleCount / lastAutomationDecision / overlayBlockReason / lastError）。
- 自动操作决策展示：`humanizeAutomationDecision` 把 `skip:*/plan *` 决策串转用户可读文案。
- 悬浮窗阻塞：`PERMISSION` 显示打开设置；`ADD_VIEW_FAILED` 错误提示；`DISABLED` 信息提示。

## 改这个包前必读

- 改 `toggle`：同步 `VisionRuntimeController.start/stop` 与 `MainActivity` 前台服务启停。
- 改指标展示：同步 `RuntimeStatus` 字段语义（`data/vision` 写入）。
- 改阻塞原因：同步 `OverlayBlockReason` 枚举与 `OverlayController` 返回。
- 改自动操作决策文案：同步 `core/model/humanizeAutomationDecision`。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：启停、实时指标更新、悬浮窗阻塞提示、自动操作决策展示。
