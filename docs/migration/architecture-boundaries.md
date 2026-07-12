# HZZS 实时视觉迁移后的职责边界

- `runtime/capture`：权限、截图来源、Viewport、rowStride/pixelStride 与 Android 版本回退。
- `vision2` C++：只负责单帧图色与几何检测；不处理系统权限和手势。
- `runtime/vision` Kotlin：固定数组解析、轻量坐标映射、多帧跟踪和 1.50P 动作规划。
- `runtime/action`：原子组入队、到期调度、去重与取消重试。
- 现有 `AutoOperationService`：唯一无障碍服务；负责截图代理、前台包名守卫和手势执行。
- `runtime/overlay`：单快照持久绘制；使用安全色，不保存像素缓冲区，也不积累旧 Canvas。
- `runtime/settings`：首次启动能力检测、AUTO 模式、权限入口、画面校准、Root 显式确认。

C++ 不负责系统权限、前台服务和动作状态；Kotlin 不重复逐像素检测；不注册第二个无障碍服务。
