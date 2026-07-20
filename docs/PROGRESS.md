# 统一重构进度

## 已实现

- 单一 `app` Android 模块与职责分包。
- Android 7+ 低权限 MediaProjection 默认路径。
- Material 3 首次引导、主题包、悬浮窗样式、设置预览和开发者入口。
- MCP 四级权限、回环监听、随机令牌和应用内语义操作。
- 两赛季障碍类别过滤、比例坐标和三种玩家基准模式。
- C++ 类别位掩码、固定玩家快速路径、输入边界与宿主机测试。
- 项目级静态门禁、Native sanitizer 与数据集评估工具。

## 必须在设备或完整 Android SDK 环境验证

- `clean testDebugUnitTest lintDebug assembleDebug`。
- API 24 至目标版本模拟器启动与权限状态。
- 中国厂商 ROM 的后台、悬浮窗和前台服务行为。
- Root 截图性能与稳定性。
- Shizuku UserService 截图适配器尚未内置，设置中明确标记不可用。
- 444 张数据集缺少独立人工真值，不能证明 99% 准确率或全机型覆盖。
