# Kotlin 修改指南

- 公开类和函数注释应描述职责、输入输出、线程和所有权，不复述语法。
- `core/model` 不依赖 Android 服务或 UI。
- `domain` 尽量保持纯 Kotlin，可直接 JVM 测试。
- `feature` 不直接执行 Root、Shell、JNI 或 WindowManager 操作，只调用注入的控制器。
- `service` 和 `platform` 负责 Android API 边界、权限和资源释放。
- `feature/settings` 设置页已拆分为独立分类子页：`McpSettingsScreen.kt`（MCP 服务，普通用户可访问）、`DeveloperSettingsScreen.kt`（开发者选项：关于页连点解锁后出现在设置首页，页内可关；关于入口复用同一屏）；旧 `McpDeveloperSettingsScreen.kt` 已删除。设置模块通过 `SettingsCategory` enum + `NavHost` 管理路由；**改动即时落盘**（短防抖），无模块级「保存并应用」草稿会话；危险项（如手动开自动操作）仍先确认再写。
