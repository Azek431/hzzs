# service/overlay — 悬浮窗（呈现层）

主线程持有的持久悬浮窗控制器。**只消费算法计算结果（`VisionResult`/`Detection`），不执行算法**。算法包与找色引擎不绘制；是否画框由 `OverlayConfig.showBoxes` 等配置决定。

## 职责

- `OverlayController`：双层悬浮窗（穿透全屏检测框层 + 交互 HUD 层）的 show/hide/suspend/resume。
- `VisionOverlayView`：按 `role` 只承担一层职责；内容签名去重避免无变化 invalidate。
- 坐标换算：检测框为视口归一化 [0,1]，**仅在绘制层换算像素**。

## 入口

1. `OverlayController.kt` — 全部（单文件）。
2. `data/vision/VisionRuntimeController.publishOverlay`（唯一调用者）、`core/model/OverlayConfig`（配置模型）。

## 数据流

```text
VisionRuntimeController.publishOverlay
  → OverlayController.show（Dispatchers.Main.immediate）
      ├─ 穿透层：FLAG_NOT_TOUCHABLE，绘制检测框/坐标网格/残留框/找色命中点/过滤虚线/搜索区
      └─ HUD 层：WRAP_CONTENT 可拖拽贴边，展示状态文字（MINIMAL/COMPACT/DEBUG_HUD）

取帧前：suspendForCapture → INVISIBLE + 等一次显示提交 → resumeAfterCapture
```

## 不变量 / 安全 / 线程 / 坐标

- **线程不变量**：所有 WindowManager.add/update/remove 与 View 更新必须在主线程（统一 `Dispatchers.Main.immediate`）。
- 安全：无悬浮窗权限或配置关闭时立即移除视图；add/update 失败 fail-closed 隐藏；`OverlayBlockReason` 写入 `RuntimeStatus.overlayBlockReason`，分析循环不中断。
- 双窗架构：穿透层不挡游戏手势；HUD 层可拖拽贴边，不绘制全屏框。
- 坐标：归一化 [0,1] 仅在绘制层换算像素；`displayContour` 参与绘制，**不参与规划**。
- 残留框：`persistBoxes` 开启时丢检后仍绘制 700ms（淡出），硬上限 24。
- 诊断叠加层（找色命中点/过滤虚线/搜索区）：仅 `DEBUG_HUD` 且开发者诊断开关开启时绘制（默认空）。
- 内容签名：粗粒度 hash 抑制重复 overlay 刷新，非密码学。

## 改这个包前必读

- 改绘制内容：同步 `VisionResult` 字段语义（`detections`/`filteredOut`/`multicolorDiag`/`timing`/`displayContour`）。
- 改 `show`：唯一调用者是 `VisionRuntimeController.publishOverlay`，返回 `OverlayShowResult` 用于写 `RuntimeStatus`。
- 改 `suspendForCapture` / `resumeAfterCapture`：与 `VisionRuntimeController.runLoop` 的「HUD 显示时临时隐身 + 排空一帧」配合。
- 改 `OverlayConfig`：同步 `core/model/OverlayConfig`、设置页 `OverlaySettingsScreen`、MCP `patch_settings`。
- 改主题/颜色：`accentColor` / `panelColor` / `readableTextColor` 与 `OverlayTheme` 枚举对应；外观设置页可共用 `HzzsColorContrast`。
- 多点找色命中点坐标：base 为全帧像素，需 ÷frameWidth/frameHeight 换算到 View 像素；未设置时不绘制。

## 测试

- 相关门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：权限授予/撤销、自摄入（HUD 不摄入自身）、旋转、拖拽贴边、残留框淡出。
- 当前缺口：悬浮窗缺 UI/instrumentation 测试，改动以真机验证为主。
