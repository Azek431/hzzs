# Vision V3 Stage 1：海盐双算法与 Shadow 基础

## 状态

本阶段只完成并验证海盐客厅的独立 Host 核心，不接入 Android CMake、JNI、运行时或动作派发，也不修改当前 APK 默认算法。

已实现：

- `SoySauceExactEngine`：酱油脚本行为参考实现；
- `SeaSaltFastEngine`：软件内置的比例化快速扫描实现；
- `SeaSaltV3Engine`：Exact / Fast / ShadowCompare 三模式；
- `visual_bounds` 与 `action_bounds` 双几何；
- 固定玩家偏移与可调动作时序；
- C++17、固定容量 LUT、无帧级堆分配；
- 正式 ImGui HUD 使用的无分配 `HudSnapshotExchange`；
- 114 张海盐数据的逐图回归、接触表和第二版真值清单。

## AutoJS 兼容语义

公开 AutoJs6 实现显示：

1. 基准色通过 OpenCV `Core.inRange` 与 `findNonZero` 找候选，即 RGB 每通道阈值；
2. 候选按返回顺序检查；
3. 相对点通过 `DifferenceDetector` 校验；
4. 返回第一个完整匹配。

由于 AutoJS Pro 本身并非完整开源，本阶段同时保留两种相对点距离：

- `BOX_PER_CHANNEL`；
- `MEAN_L1`。

最终产品默认值必须由 AutoJS Pro 真机对照测试确认，不能仅凭开源分支推断。

## SoySauceExact

保留以下可观察行为：

- 类别顺序：大断崖、小断崖、矮沙丘、高沙丘、船锚、复活；
- 每类使用行优先首个完整匹配；
- 第一类存在匹配后不再选择后续类别；
- JavaScript `Math.round` 语义，包括负相对坐标；
- 固定玩家参考偏移；
- 跳、双跳、下滑和复活动作时序可配置；
- 搜索区与相对点均按设计分辨率比例换算。

Exact 是行为参考与回退路径，不把 `P95 ≤ 1 ms` 作为硬门禁。

## BuiltinFast

快速路径不是简单减少模板点数，而是：

1. 每个障碍使用独立的比例窄带；
2. 粗扫描步长为 `ceil(width / horizontal_samples)`，默认约等价于 360 宽工作网格；
3. RGB15 LUT 先排除绝大多数像素；
4. 从稀有扫描颜色恢复候选；
5. 候选附近才验证完整相对几何；
6. 若原始严格模板存在，优先恢复与源算法一致的行优先锚点；
7. 严格路径失败后才使用 3×3 容错；
8. 内置模式可扫描原脚本左边界之外的真实障碍。

因此：

- `require_exact_anchor_pattern=true` 可作为快速等价模式；
- `false` 为软件内置容错模式；
- 两者使用同一个核心，但验收目标不同。

## ShadowCompare

Shadow 模式同时计算 Exact 与 Fast：

- 检出状态必须一致；
- 类别必须一致；
- 前缘差异不得超过视口宽度 1%；
- 只有 Exact 本来需要动作且两者一致时才放行动作；
- 不一致时保留诊断结果，但动作 fail-closed。

## 数据集结果

测试数据：海盐客厅 114 张。

第二版真值：

- 114/114 成功读取；
- 54 张有可确认首要障碍；
- 51 张具备独立动作前缘门禁；
- 3 张通过候选并集和视觉接触表确认有真实障碍，但锚点来自检测器，因此只参加类别门禁，不参加几何误差门禁。

BuiltinFast（BOX，Host Release）：

- 类别/存在性错误：0；
- 几何门禁错误：0；
- 动作前缘 P95 误差：0.139% 屏宽；
- 最大动作前缘误差：0.556% 屏宽；
- 原始调用 P95：约 0.56 ms；
- 按帧中位数计算的 P95：约 0.54 ms。

Fast exact-compatibility：

- 与独立 Python 精确参考逐张一致：114/114；
- 最近一次 Host P95 约 0.15 ms。

性能数字仅代表当前 x86_64 容器、已准备 ARGB 缓冲后的算法调用；不代表 ARM64 手机、截图、JNI、Kotlin 或系统手势。

## 复核的重要发现

最初以酱油输出作为真值时，快速算法的新增检出被误标为“误报”。逐页检查接触表后确认，多张图中确有真实沙堡、断崖或船锚，只是位于原脚本搜索线左侧，或严格单点模板没有返回。

因此必须分开验收：

- SoySauceExact：与源行为等价；
- BuiltinFast：与视觉真值一致。

禁止使用 Exact 输出反向定义 BuiltinFast 的全部真值。

## 已知边界

- 数据集中没有已确认的大断崖正例；
- 数据集中没有复活按钮正例；
- 当前视觉复核是模型辅助接触表复核，不是独立人工标注；
- 1% 粗网格依赖目标颜色区域具有面积，不保证识别人为构造的单像素特征；
- 甜品和竹影尚未进入 V3 的同级真值门禁；
- Android、JNI、ARM64、ImGui renderer 和 API 34 目标窗口截图尚未接线。

## 运行门禁

```bash
bash tools/vision_v3/run_stage1_gate.sh /absolute/path/to/海盐客厅
```

门禁要求：

- Exact 参考差异为 0；
- Fast exact-compatibility 差异为 0；
- BuiltinFast 真值错误为 0；
- 最大动作前缘误差不超过 1%；
- BuiltinFast Host 原始 P95 与按帧中位数 P95 均不超过 1 ms；
- C++17、ASan、UBSan 和 Python compileall 通过。
