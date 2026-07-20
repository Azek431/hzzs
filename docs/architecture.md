# HZZS 架构

## 单模块、强分层

HZZS 使用单一 `app` Gradle 模块，减少 AI 和开发者跨模块追踪成本。包级依赖方向为：

`feature -> data/service -> domain/core`，平台层只通过接口向运行时暴露能力。

## 运行时

1. `FrameSourceFactory` 根据已保存后端创建截图源。
2. `VisionRuntimeController` 获取帧、校验视口与配置并调用 JNI。
3. `NativeVisionEngine` 将 C++ 结果转换成领域模型。
4. `VisionResultValidator` 应用类别过滤、置信度与坐标不变量。
5. `MultiObjectTracker` 做跨帧稳定。
6. 结果进入持久 Canvas 悬浮窗；自动操作只有通过全部门控后才进入 `GestureArbiter`。

## 配置

DataStore 存储 schema v5。`SettingsRepository` 合并持久配置和内存预览。权限型配置只在保存后生效，避免浏览设置页时意外启动敏感能力。

## MCP

MCP 服务仅监听 `127.0.0.1`，每次启动生成令牌。权限分为只读、每次确认、会话信任、完整访问。工具调用进入统一动作注册表，不能直接触摸 Compose 内部状态。
