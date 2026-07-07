# 测试说明

本文档描述 HZZS 当前的测试策略、已实现的验证机制，以及后续需要补充的测试项。

## 当前测试状态

### 已实现的验证

#### C++ 自检（JNI 层）

位于 [`NativeAnalysisBridge.cpp`](../app/src/main/cpp/src/jni/NativeAnalysisBridge.cpp) 的 `nativeRunSelfCheck()` 函数：

- 构造一个模拟的地面跑酷帧：玩家位于 `(0.14, 0.66, 0.24, 0.84)`，前方有一个奶油断层。
- 依次调用 `NativeAnalysisEngine.Analyze()` 两次（间隔 16ms），验证：
  - 场景模式为 `kGroundRun`
  - 角色姿态为 `kRun`
  - 跳跃阶段为 0
  - 动作提示为 `kJump`
- 全部通过则返回 `"PASS"`，任一失败则返回 `"FAIL"`。

**约束**：此自检仅在 JNI 层执行，不涉及 UI、屏幕采集或真实帧。

#### Gradle 构建验证

VS Code Tasks 提供了三种验证级别：

| Task | 命令 | 用途 |
| --- | --- | --- |
| 构建 Debug APK | `:app:assembleDebug` | 确认 Kotlin 编译 + C++ NDK 编译通过 |
| 构建 + 单元测试 | `:app:assembleDebug :app:testDebugUnitTest` | 构建 + JVM 单元测试 |
| 完整验证 | `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | 构建 + 测试 + Lint |

### 尚未实现的测试

以下测试项**尚未实现**，属于后续里程碑的工作：

| 测试类型 | 范围 | 阻塞条件 |
| --- | --- | --- |
| Kotlin 单元测试 | `MainActivity`、`OverlayPreviewManager`、`CommunityLinks` | 需解耦 Android 框架依赖 |
| C++ 单元测试 | 各状态机的边界条件（遮挡、飞行、结算场景） | 需引入 GoogleTest 或 Catch2 |
| 集成测试 | JNI 桥接端到端验证 | 需 Android Instrumented Test |
| 视觉识别测试 | 模板匹配 / 像素分析的准确率 | 需要真实游戏帧数据集 |
| 设备兼容性测试 | 不同 Android 版本、分辨率、刘海屏 | 需要多设备或模拟器矩阵 |

## 测试数据与隐私

### 禁止内容

以下数据**严禁**提交到 Git 仓库或包含在测试中：

- 用户录制的游戏视频或截图（可能包含账号、分数、隐私信息）
- 聊天记录的导出
- 包含个人信息的系统日志
- 签名文件、密钥库密码

### 推荐的测试数据格式

如果需要测试帧数据，应使用**合成数据**：

```cpp
// 示例：合成帧数据（已在 NativeAnalysisBridge.cpp 中实现）
hzzs::analysis::FrameDetections frame{};
frame.timestamp_ms = 16;
frame.scene.hint = hzzs::analysis::SceneMode::kGroundRun;
frame.scene.hint_confidence = 0.98F;
frame.player_bounds = hzzs::analysis::RectF{0.14F, 0.66F, 0.24F, 0.84F};
frame.player_confidence = 0.96F;
```

合成数据的坐标使用归一化 0.0 ~ 1.0，不依赖任何真实设备分辨率。

## 真机诊断工具

项目提供了 PowerShell 诊断工具链（`.vscode/scripts/HzzsAndroidTools.ps1`），支持以下模式：

| Mode | 行为 | 是否构建 | 是否安装 | 是否启动 |
| --- | --- | --- | --- | --- |
| `Health` | 检查 ADB、设备、SDK、应用安装路径 | 否 | 否 | 否 |
| `ClearLogs` | 清空 logcat | 否 | 否 | 否 |
| `Watch` | 实时记录日志到 `D:\Azek431-Archives\hzzs-device-diagnostics` | 否 | 否 | 否 |
| `Diagnose` | 构建 → 安装 → 启动 → 记录快照 → 监听日志 | 是 | 是 | 是 |
| `ExportSnapshot` | 导出当前 logcat、dumpsys 快照 | 否 | 否 | 否 |
| `Launch` | 启动已安装应用 | 否 | 否 | 是 |
| `BuildInstallLaunch` | 构建 → 安装 → 启动 | 是 | 是 | 是 |

诊断数据保存到 `D:\Azek431-Archives\hzzs-device-diagnostics\`，不在项目目录内。

## 运行测试的注意事项

1. **不要**在 CI 或自动化流程中启动应用、连接设备或运行 emulator。
2. **不要**在测试日志中包含用户隐私数据。
3. C++ 单元测试需要在支持 NDK 的 CI 环境中交叉编译后运行。
4. Kotlin 单元测试如果依赖 Android 框架类（如 `Context`、`View`），需要使用 Robolectric 或直接改为 Mock。
