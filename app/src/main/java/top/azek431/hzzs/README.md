# Kotlin 源码导航

应用采用“稳定核心 + 平台适配 + 功能界面”的单模块分包方式，避免 Gradle 模块依赖环，同时保留清晰职责。

数据主路径：`FrameSource -> VisionRuntimeController -> NativeVisionEngine -> VisionResultValidator -> MultiObjectTracker -> OverlayController / GestureArbiter`。

配置主路径：`SettingsScreen -> SettingsRepository preview/save -> AppConfig Flow -> Theme / Runtime / MCP`。

默认 `AppConfig.selectedScene = BAMBOO_BOOKSTORE`。AI 查错时先从状态所有者开始，不要从 UI 文案反推业务状态。
