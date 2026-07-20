# Kotlin 修改指南

- 公开类和函数注释应描述职责、输入输出、线程和所有权，不复述语法。
- `core/model` 不依赖 Android 服务或 UI。
- `domain` 尽量保持纯 Kotlin，可直接 JVM 测试。
- `feature` 不直接执行 Root、Shell、JNI 或 WindowManager 操作，只调用注入的控制器。
- `service` 和 `platform` 负责 Android API 边界、权限和资源释放。
- 状态流必须有单一所有者；避免 UI、本地服务和仓库同时修改同一状态。
