# nativevision — JNI 加载边界

`NativeVision` 是 native 视觉库的 **JNI 加载边界**。职责：库加载（**失败不崩进程**）+ `analyze` / `configureAlgorithm` / `activeAlgorithmGeneration` / `reset` 的 JNI 方法声明。

## 职责

- 加载 `hzzs_vision` 库；`isAvailable` / `loadFailureMessage` 供上层 fail-closed。
- 暴露 native 方法：`analyze(...)` / `configureAlgorithm(profile)` / `activeAlgorithmGeneration()` / `reset()`。

## 入口

1. `NativeVision.kt` — 全部（单文件）。
2. `data/vision/NativeVisionEngine`（主要消费者）、`feature/settings/SettingsViewModel`（benchmark/diagnostics）、`cpp/jni_bridge.cpp`（native 侧实现）。

## 数据流

```text
NativeVisionEngine.analyze
  → NativeVision.analyze(scene, pixels, w, h, workWidth, enabledKindMask, …)
      ← cpp/jni_bridge.cpp → vision_engine.cpp / legacy_main / vision_v3
  ← NativeVision.Result（detections / timing / multicolorDiag / filteredOut / sceneConfidence / error）

安全切换点：
  AlgorithmActivationCoordinator.ensureConfigured / onConfigCommitted
      → engine.configureAlgorithm(profile) → NativeVision.configureAlgorithm
```

## 不变量 / 安全

- 库加载失败不崩进程；`isAvailable=false` 时 `NativeVisionEngine.analyze` 走 errorResult fail-closed。
- **像素缓冲仅在 JNI 调用期间借用**；native 不得缓存数组地址跨调用（`NativeVisionEngine.analyze` 注释：「JNI 仅借用 frame.argb，调用返回后不得再访问该数组地址」）。
- 算法切换在帧循环**外**的安全点完成，不得与 `analyze` 半热交错；失败回退内置 profile。
- native 坐标：视口裁剪内归一化 → 全屏归一化由 `NativeVisionEngine.toFullScreen` 映射（与 `jni_bridge` 一致）。
- `NativeVision.Detection` / `FilteredDetection` 的 JNI 构造器描述符、参数顺序与 `DetectionSource.nativeCode` 是同一协议；正常结果与过滤诊断均需透传 source，改任一侧必须同步 C++ / Kotlin / 静态门禁。

## 改这个包前必读

- 改 JNI 方法签名：同步 `cpp/jni_bridge.cpp` + `data/vision/NativeVisionEngine` + `domain/vision/VisionEngine` 契约。
- 改算法配置/激活路径：同步 `core/algorithm/*`（AlgorithmActivationCoordinator / AlgorithmRuntimeProfile / AlgorithmProfileValidator）。
- 改诊断开关：`NativeVision.analyze` 的 `enableStageTiming`/`enableMulticolorDiagnostic`/`enableFilterTrace` 由开发者选项驱动，默认全 0/空。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- Native 门禁：`tools/vision/run_native_sanitizers.sh`、`run_host_tests.py --max-representative`。
- 真机验证：库加载、analyze 调用、算法切换 fail-closed。
