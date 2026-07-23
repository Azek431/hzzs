# app 单模块说明

`app` 是 HZZS 唯一的 Android Gradle 产品模块。它包含应用入口、Compose UI、配置、运行时、截图、悬浮窗、MCP、自动操作、JNI、C++ 视觉引擎和测试。

## 版本

- `versionName`：`0.1.0`（可用 `HZZS_VERSION_NAME` / `hzzsVersionName` 覆盖）
- `versionCode`：`1`（可用 `HZZS_VERSION_CODE` / `hzzsVersionCode` 覆盖）
- 默认赛季：`AppConfig.DEFAULT_SELECTED_SCENE`（源码唯一真相，文档不写死具体枚举值）

## 目录地图

- `core/`：稳定模型、主题、设置、更新、安全边界。
- `domain/`：与 Android 无关的识别和动作规则。
- `data/vision/`：截图帧到 Native 识别结果的运行时编排。
- `feature/`：首次引导、首页、运行、设置、关于和开发者界面。
- `platform/compat/`：Android 版本及设备能力判断。
- `service/capture/`：MediaProjection、Accessibility、Root、Shizuku 等截图实现。
- `service/overlay/`：持久 WindowManager Canvas 双层悬浮窗（穿透检测框 + 可拖 HUD）。
- `service/automation/`：Accessibility 手势分发。
- `mcp/`：仅本地回环的 MCP 服务和权限策略。
- `src/main/cpp/`：C++17 多场景障碍检测与 JNI。
- `src/test/`：JVM 与 C++ 测试。

## 配置生效模型

设置改动经短防抖即时写入 DataStore；离开设置时刷盘。手动开启自动操作等危险项须先确认；导入/MCP 外部摄入仍 harden，不得静默开启自动操作或自提权限。

## 数据主路径

`FrameSource -> VisionRuntimeController -> NativeVisionEngine -> VisionResultValidator -> MultiObjectTracker -> OverlayController / GestureArbiter`
