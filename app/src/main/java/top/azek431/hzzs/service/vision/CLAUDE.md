# service/vision — 视觉分析前台服务

**仅**在分析运行期间提升进程优先级，降低 OEM 后台杀进程概率。不持有任何业务状态；帧循环、截图、手势注入仍由 `data.vision.VisionRuntimeController` 在协程里跑。

## 职责

- `VisionAnalysisForegroundService`：前台服务壳，`START` 提优先级 / `STOP` 移除通知。
- 进程被杀重启后**不**自动恢复分析（防后台偷跑），仅 alive 期间提优先级。

## 入口

1. `VisionAnalysisForegroundService.kt` — 全部（单文件）。
2. `data/vision/VisionRuntimeController.start/stop`（启停调用者）。

## 数据流

```text
VisionRuntimeController.start  → VisionAnalysisForegroundService.start（startForeground）
VisionRuntimeController.stop   → VisionAnalysisForegroundService.stop（stopForeground + stopSelf）
```

## 不变量 / 安全 / 线程

- 默认开：每次 `VisionRuntimeController.start` 同步启前台服务，`stop` 同步停。
- API 34+ 前台服务须带 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`。
- 「免责声明版本不足 / 自动操作关闭」等合规门控仍在运行时控制器内，**不在**前台服务。
- 失败只打日志（`AppLog.w`），不打断帧循环。

## 改这个包前必读

- 改启停时机：同步 `VisionRuntimeController.start/stop` 与 `algorithmCatalog.setAnalysisRunning`。
- 改通知渠道/类型：API 分支（26+ 渠道、34+ type）已封装在 `startForegroundCompat` / `createVisionAnalysisChannel`。
- 前台服务与 `mcp/McpForegroundService` 是**两个独立服务**，不要混为一谈。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：分析启停时通知显示/隐藏、进程优先级、后台杀进程恢复行为。
