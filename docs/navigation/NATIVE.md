# JNI 与 C++ 代码地图

本页解释一张截图怎样跨过 Kotlin/JNI/C++ 边界。具体算法参数与安全上限仍以源码、`app/src/main/cpp/README.md` 和测试为准。

## 完整调用链

```text
FrameSource.nextFrame
→ VisionRuntimeController.runLoop / frame.use
→ VisionEngine.analyze
→ NativeVisionEngine.analyze
→ NativeVision.analyze（external）
→ jni_bridge.cpp：Java_top_..._NativeVision_analyze
→ vision_engine.cpp：hzzs::analyze
→ AlgorithmRuntime 当前 profile 快照
→ analyze_with_profile
→ 甜品 / 竹影 / 海盐场景检测器
→ finalize_result
→ JNI make_result
→ NativeVision.Result
→ NativeVisionEngine 坐标回映射与 Validator
→ MultiObjectTracker
→ Kotlin 生成 displayContour
```

## 跨语言协议表

| 项 | 约定 | 修改时必须同步 |
| --- | --- | --- |
| 像素 | Kotlin `IntArray`，每项为 `0xAARRGGBB` | 截图复制、JNI、C++ 位移和测试 |
| Scene | `SceneId.ordinal` 对应 C++ scene | Kotlin enum、`kSceneCount`、JNI 校验、host 测试 |
| Kind | `ObjectKind.ordinal` 对应 C++ `Kind` | 两侧 enum、位掩码、Validator、host ABI |
| Avoidance | Kotlin 与 C++ ordinal 一致 | 两侧 enum、JNI 构造器、动作规划 |
| Detection ctor | JNI 描述符与 `NativeVision.Detection` 字段顺序完全一致 | Kotlin 数据类 + `jni_bridge.cpp` |
| Result ctor | JNI 描述符与 `NativeVision.Result` 一致 | Kotlin + JNI |
| 尺寸 | 单边与总像素均有上限 | Kotlin、JNI、C++、质量测试 |

不要把“RGBA 字节缓冲”与打包整数 `0xAARRGGBB` 混为一谈。修改枚举时不能只改一侧，也不能在中间插入值后忘记位掩码。

## 坐标的四个阶段

```text
全屏像素
→ JNI 按 viewport 裁剪出的局部像素
→ C++ 裁剪区归一化 [0,1]
→ Kotlin 映射回全屏归一化 [0,1]
```

C++ 检测器的像素框右/下边界是闭区间，因此归一化时使用：

```text
(right + 1) / width
(bottom + 1) / height
```

这个 `+1` 是把闭区间像素框变成连续边界，不是多余的偏移。

最终领域 `Detection.bounds` 才是全屏归一化几何真相源。它用于 Tracker、距离、动作与 HUD 基础框。

`displayContour`：

- 不经过 JNI；
- 在 Tracker 平滑 bounds 后由 Kotlin 近似生成；
- 仅供 HUD；
- 玩家不生成该轮廓；
- 不得参与 Tracker、距离或动作规划。

## 内存与所有权

```text
CapturedFrame 持有池化像素与释放回调
→ close 时由回调归还对应 IntFramePool.Lease
→ VisionFrame 临时借用同一 IntArray
→ JNI CriticalIntArray 在当前调用中 pin
→ C++ FrameView 临时借用
→ 返回前全部结束
→ frame.use 退出后租约归池
```

- Native 不缓存 Java 数组地址。
- `ReleasePrimitiveArrayCritical(..., JNI_ABORT)` 表示只读借用，无需回写，不表示异常。
- 非全视口时 JNI 创建局部 C++ vector，分析返回前销毁。
- Tracker 只保存 Detection 值，不保存像素。
- 异步调试记录必须先复制像素。

## Engine、Runtime 与 Tracker

- `NativeVisionEngine` 是 Hilt 单例适配器。
- `VisionRuntimeController` 是帧循环唯一运行时所有者，并私有持有非线程安全 Tracker。
- C++ `AlgorithmRuntime` 是进程单例，用互斥锁保护整份不可变 profile 快照。
- JNI 层串行化 analyze/configure/reset。
- Native `reset()` 当前不回退内置算法，也不推进算法 generation；检测器没有跨帧状态。
- 场景、算法和会话切换仍必须由 Kotlin 在安全点同时重置 Tracker、动作账本和玩家参考。

## 场景主路径

- 甜品、竹影：先走各自 `legacy_main` 主检测，弱结果时走场景回退实现。
- 海盐：`sea_salt_living_room.cpp` 提供当前参数驱动路径。
- `vision_engine.cpp` 统一分发、类别掩码与结果收敛。
- `algorithm_runtime.cpp` 管理外部算法 profile 快照。

## 多点找色当前状态

**当前不能宣称多点找色已进入产品热路径。**

| 环节 | 当前状态 |
| --- | --- |
| 模板和相关类型 | 已存在 |
| 海盐调用占位 | 已存在 |
| `append_multicolor_detections` | stub，不产生结果 |
| `multicolor_detector.cpp` 进入 CMake | 否 |
| 进入 host 构建源列表 | 否 |
| Kotlin 三个找色参数映射到 JNI/profile | 否 |
| Native 对三个参数做完整校验 | 否 |
| 自动化测试 | 无 |

接入前还必须核对头/实现函数签名、ARGB 通道提取、类别掩码、CMake/host 源列表和参数协议。不能只把 `.cpp` 加入 CMake 就宣布完成。

## 测试地图

| 层 | 入口 | 能证明什么 | 不能证明什么 |
| --- | --- | --- | --- |
| JVM | `app/src/test/java/.../domain/vision`、`service/capture` | Validator、近似轮廓、帧租约 | JNI 描述符与设备内存边界 |
| Native direct | `app/src/test/cpp/native_tests.cpp` | profile、scene 边界、输入约束、基本并发 | Java/JNI round-trip |
| Host ABI | `tools/vision/run_host_tests.py`、`host_api.cpp` | 宿主 ABI、bounds、mask、代表帧稳定性 | Android Image/JNI/Tracker |
| 设备 | 当前没有完整 `androidTest` 链 | 真机权限、JNI、厂商行为 | 尚待补充 |

host 测试的 scene 和 Kind 范围必须跟 `kSceneCount` 与当前枚举同步；不要沿用旧的双场景或低 8 位假设。

## 修改检查表

跨语言协议变化时，逐项核对：

1. Kotlin 模型和 ordinal；
2. C++ enum 与位掩码；
3. JNI 参数、校验与构造器描述符；
4. profile 字段读取与 Native 校验；
5. CMake 与 Linux/Windows host 构建源列表；
6. JVM、native direct、host ABI 测试；
7. 坐标仍只在绘制层/手势层转像素；
8. Native 未保存 Java 缓冲；
9. `displayContour` 未进入动作；
10. stub 没有被文档包装成已上线功能。
