# 架构说明

HZZS 重构版采用分层、单向依赖的多模块结构。应用界面使用 Jetpack Compose 与 Material 3，视觉热路径保留在 C++，Android 系统能力通过独立服务模块封装。

## 模块边界

- `app`：应用入口、导航与 Hilt 组装。
- `core:model`：跨模块不可变模型。
- `core:designsystem`：Material 3 主题、组件与悬浮窗视觉令牌。
- `core:preferences`：DataStore 永久配置、编辑草稿和配置迁移。
- `core:update`：稳定版/测试版、完整包和差分包模型。
- `domain:vision`：视觉领域协议、场景与对象语义。
- `domain:automation`：动作、手势状态机和安全门控。
- `data:vision`：JNI 适配、目标追踪与视觉运行时。
- `feature:*`：首页、运行、设置与关于页面。
- `service:capture`：MediaProjection、Accessibility 与 Root 截图后端。
- `service:automation`：唯一的无障碍手势提交入口。
- `service:overlay`：持久悬浮窗与调试绘制。
- `native:vision`：C++17 多目标视觉引擎与 JNI 边界。

## 关键数据流

```text
FrameSource
  -> viewport normalization
  -> NativeVisionEngine
  -> immutable detections
  -> MultiObjectTracker
  -> automation policy
  -> GestureArbiter / Overlay
```

设置页使用三层状态：永久配置、编辑草稿、运行时预览。草稿可立即作用于当前算法，但只有用户点击保存后才原子写入 DataStore。
