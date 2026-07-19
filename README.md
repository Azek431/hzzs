# 火崽崽助手 HZZS

HZZS 是一个本地运行的 Android 跑酷画面分析工具。此重构版以清晰的模块边界、可回滚配置、C++ 多目标视觉协议、Material 3 界面和安全的手势串行执行为核心。

## 当前架构

- Jetpack Compose + Material 3
- Hilt 依赖注入
- DataStore 永久配置 + 会话草稿预览
- C++17 多目标视觉引擎
- MediaProjection / Accessibility 截图后端
- 串行 GestureArbiter，只有系统回调完成后才提交动作
- Gitee 优先、GitHub 校验的应用内更新架构

详见 `docs/ARCHITECTURE.md`、`docs/SECURITY.md` 和 `docs/TESTING.md`。

## 构建

需要 JDK 17、Android SDK 37、NDK 28.2.13676358、CMake。

```bash
./gradlew clean test lint assembleDebug
```

正式 Release 必须提供完整签名环境变量。缺失时构建任务会直接失败，不会产生未签名或误签名的发布包。
