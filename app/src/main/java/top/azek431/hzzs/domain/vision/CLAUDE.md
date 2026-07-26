# domain/vision — 视觉领域模型

纯 Kotlin、可 JVM 单测、**不依赖 Android Framework**。它是整个视觉子系统（C++、Tracker、运行时、自动操作）的数据契约。

## 职责

- 定义**视口归一化坐标 [0,1]** 下的检测结果、帧输入与引擎契约（`NormalizedRect` / `Detection` / `VisionResult` / `VisionFrame`）。
- 定义**声明式算法运行时**（CC-1）：`AlgorithmRuntimeProfile` / `SceneAlgorithmParams` / `ActiveAlgorithmProvider` / `AlgorithmProfileValidator`。
- 解析算法包 `rules.json`（v1/v2）为运行时 profile：`AlgorithmRulesParser`。
- 在进入 Tracker / 自动操作之前**清洗 JNI 输出**：`VisionResultValidator.sanitize`。
- 为检测框生成仅 HUD 使用的**低点数近似显示轮廓**：`withApproximateDisplayContour`（App 呈现层增强，**不**参与跟踪/距离/动作）。

> 关键区分：算法只产出 `Detection.bounds` 等数据；**屏幕框/轮廓由 App 通用 HUD 读取检测结果呈现**（数据关联、职责分离）。详见 `data/vision` 与 `service/overlay`。

## 入口（按阅读顺序）

1. `VisionModels.kt` — 坐标、类别、结果、引擎契约。改这里等于改「视觉子系统的类型语言」。
2. `AlgorithmRuntimeProfile.kt` — CC-1 算法运行时快照与赛季参数。`init` 块是字段级校验真相源。
3. `AlgorithmRulesParser.kt` — `rules.json` 解析；v1 仅 `thresholds`、v2 为 `userThresholds + engineParams`。
4. `ApproximateContours.kt` — 近似轮廓生成。
5. `domain/automation/` 与 `data/vision/` 是主要下游消费者。

## 数据流 / 调用关系

```text
rules.json ──→ AlgorithmRulesParser.parse ──→ AlgorithmRuntimeProfile
                                                       │
                  ActiveAlgorithmProvider.activate ←───┤
                          │                           │
                          ▼                           ▼
                data/vision/NativeVisionEngine.configureAlgorithm
                          │
                          ▼
                data/vision/VisionRuntimeController（帧循环读 current()）

JNI(C++) ──→ data/vision/NativeVisionEngine.analyze ──→ VisionResult
                                                       │
                          VisionResultValidator.sanitize │
                                                       ▼
                              domain/automation 规划 + data/vision/Tracker
                                                       │
                              withApproximateDisplayContour ← 仅供 HUD
                                                       ▼
                                        service/overlay/OverlayController（呈现层）
```

## 与其他模块的关系

```text
core/algorithm/AlgorithmActivationCoordinator ──→ ActiveAlgorithmProvider（本包）
core/algorithm/InstalledAlgorithmStore        ──→ AlgorithmRulesParser（本包）
core/algorithm/AlgorithmCatalogPure           ──→ 纯函数层（不反向依赖本包）
```

## 不变量 / 安全 / 线程 / 坐标

- 坐标：除特别说明外，矩形均为**全屏归一化 [0,1]**。像素换算**只允许**发生在绘制层（`OverlayController`）与手势分发层（`service.automation`）。本包不做任何像素换算。
- 尺寸上限：`MAX_FRAME_DIMENSION = 4096`、`MAX_FRAME_PIXELS ≈ 8MP`，与 C++/JNI 一致。
- `Detection.init`：`confidence ∈ [0,1]`、`actionable 与 diagnosticOnly 不可同 true`、`actionable ⇒ avoidance ≠ NONE`。
- `NormalizedRect`：四边 finite 且 ∈[0,1]、`left<right`、`top<bottom`。外部输入走 `fromUnchecked`（非法→null），禁止把脏数据推入业务。
- 算法 profile 生命周期：**解析并校验一次 → 写入 Native 不可变快照 → 帧循环只读**。禁止每帧解析 JSON / 读文件 / 分配大型规则对象。
- `AlgorithmProfileValidator`：finite / 有序区间 / 通道 0..255 / 比例安全窗；失败必须回退 `AlgorithmRuntimeProfile.builtin()`，**不得带脏参数进 Native**。
- 线程：本包全同步、纯计算；线程安全由调用方保证。

## 算法包安全边界

算法包**不得**包含：

- 可执行代码（`.so` / Dex / Jar / 脚本 / 模型权重）
- 手势、点击、Root、包名白名单、自动化门禁字段

违反 → `AlgorithmRulesParser` 忽略；`AlgorithmProfileValidator` 拒绝；`AlgorithmPackVerifier` 拒绝。

## 改这个包前必读

- **扩展 `Avoidance` 枚举**（CC-2）：同步四方 —— 本文件 `Avoidance`、`core/model/DisplayNames.kt`、`data/vision/VisionRuntimeController.planGestures`、C++ `vision_types.h` 与 `ObstacleKind`。
- **扩展 `ObjectKind`**：同步 Kotlin 枚举、场景过滤、JNI 位掩码、C++ 类别、数据集报告（见 `app/CLAUDE.md` 「Avoidance 枚举扩展」）。
- **修改 `SceneAlgorithmParams` 字段**：必须同步 `AlgorithmRulesParser.mergeEngine` 的 key 名、`AlgorithmProfileValidator.validateScene` 的范围、`AlgorithmRuntimeProfile.builtin()` 三处赛季默认值。字段若被「算法包网络更新」使用，还需同步 `docs/ALGORITHM_SYSTEM_V1.md`。
- **算法信任锚**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 若清空，外装「官方」包须 fail-closed（私钥永不入库）。

## 测试

- 本包是 JVM 单测的黄金位置：`AlgorithmProfileValidator`（finite/区间/有序）/`AlgorithmRulesParser`（v1/v2 覆盖、缺省填洞）/`VisionResultValidator.sanitize`（类别过滤、玩家身后剔除、上限）/`NormalizedRect.fromUnchecked`。
- 相关门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。

## 文档真相源

| 用途 | 路径 |
| --- | --- |
| 算法包系统 v1 | `docs/ALGORITHM_SYSTEM_V1.md` |
| 算法切换链路 | `docs/algorithm/ALGORITHM_SWITCHING.md` |
| 多点找色（CC-2） | 根 `CLAUDE.md` |
| 视觉专项 | `docs/vision/*` |
| 算法模块分层 | `core/algorithm/CLAUDE.md` |
| 纯函数层 | `core/algorithm/logic/CLAUDE.md` |
