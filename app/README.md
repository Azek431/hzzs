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
- `service/overlay/`：持久 WindowManager Canvas 悬浮窗。
- `service/automation/`：Accessibility 手势分发。
- `mcp/`：仅本地回环的 MCP 服务和权限策略。
- `src/main/cpp/`：C++17 多场景障碍检测与 JNI。
- `src/test/`：JVM 与 C++ 测试。

## 配置生效模型

主题、悬浮窗和视觉参数可临时预览。离开设置页时清除预览；点击“保存并应用”后写入 DataStore。截图后端、MCP、Root/无障碍和自动操作属于权限型设置，不在未保存预览阶段启动。

## 数据主路径

`FrameSource -> VisionRuntimeController -> NativeVisionEngine -> VisionResultValidator -> MultiObjectTracker -> OverlayController / GestureArbiter`
