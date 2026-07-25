# ImGui 正式 HUD 与无 HUD 截图契约

## 目标

- Dear ImGui 作为正式 HUD，而非只用于调试；
- HUD Surface 常驻，不因每帧截图而销毁或重建；
- 算法截图不包含 HZZS 自己的 HUD；
- 绘制结果不参与动作几何；
- 不依赖隐藏 API作为默认功能。

## ImGui 组合

使用官方 Dear ImGui 主源码，加：

- Android platform backend；
- OpenGL ES 3 renderer backend；
- EGL + `ANativeWindow`；
- 固定版本与 MIT 许可证；
- 不打包 demo、Vulkan 和无关平台 backend。

算法线程不直接调用 ImGui。它只发布固定容量 `HudFrameSnapshot`；渲染线程通过 `HudSnapshotExchange` 读取稳定副本。

## Android 14+

首选路径：

1. 无障碍服务查找目标游戏 `AccessibilityWindowInfo.id`；
2. 调用 `takeScreenshotOfWindow(windowId, ...)`；
3. 直接获得目标窗口像素，不包含覆盖其上的 accessibility overlay；
4. HUD 使用 `SurfaceControlViewHost` / `SurfaceControl`；
5. 通过 `attachAccessibilityOverlayToDisplay` 或 `attachAccessibilityOverlayToWindow` 挂载；
6. ImGui Surface 全程保持可见。

官方参考：

- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#attachAccessibilityOverlayToDisplay(int,android.view.SurfaceControl)
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#attachAccessibilityOverlayToWindow(int,android.view.SurfaceControl)

## Android 11–13

公开 API 没有通用的 MediaProjection 参数能够“排除指定 overlay 并还原其后方游戏像素”。`FLAG_SECURE` 可能产生空白/黑区，不应被当作背景恢复方案。

回退顺序：

1. 用户明确选择的高级截图后端若能输出不含 overlay 的游戏帧，则保持常驻 HUD；
2. 否则 MediaProjection 只切换 ImGui 渲染 Surface 的可见性，不移除 Window、不销毁 EGL、不重新 addView；
3. 切换通过单次 `SurfaceControl.Transaction`，并记录隐藏到有效帧的延迟；
4. 无法确认得到干净帧时 fail-closed，不把含 HUD 的帧送入算法。

Root/隐藏 API 的 layer-exclusion 只能作为显式高级实验功能，不能成为普通用户默认路径。

## 渲染策略

- 障碍运动时最多 60 FPS；
- 只有文字变化时 15–30 FPS；
- snapshot generation 未变化时不提交新帧；
- 后台或 HUD 关闭时停止渲染循环但保留可恢复资源；
- 屏幕旋转时重建 EGL Surface，不重建 ImGui context；
- 全屏穿透层和交互 HUD 分为两个 Surface；
- 穿透层永不接收触摸；
- 交互层只在实际 HUD bounds 内接收输入。

## 性能指标

分别测量：

- ImGui CPU 构帧；
- OpenGL ES draw submission；
- GPU/Surface present；
- 截图回调；
- 像素拷贝；
- Native 算法。

算法 `P95 ≤ 1 ms` 不包含截图和 HUD；HUD 目标为 CPU 构帧 P95 ≤ 0.5 ms，且静止状态不持续满刷新。

## 安全边界

- 不尝试隐藏第三方游戏本身；
- 不承诺防 Root、Xposed 或外部摄像机；
- 只保证 HZZS 自己的目标窗口截图路径不包含 HZZS HUD；
- 无障碍、Shizuku、Root 均必须由用户明确开启；
- ImGui 只是呈现层，不得绕过现有动作与前台门控。
