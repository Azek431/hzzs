# 跑酷分析算法

本文档描述 HZZS 当前已实现的 C++ 分析算法，以及规划中的算法边界。

## 概述

跑酷分析引擎的目标是：在已知画面帧检测结果的前提下，输出稳定的跑酷状态判断与只读动作提示。

**引擎本身不执行任何屏幕采集、图像识别或游戏操作。** 它只消费由上层视觉模块填充的 `FrameDetections` 结构体，并输出 `AnalysisResult`。

## 输入：FrameDetections

```cpp
struct FrameDetections {
    int64_t          timestamp_ms;        // 帧时间戳（毫秒）
    SceneObservation scene;               // 场景观察
    optional<RectF>  player_bounds;       // 玩家边界（归一化 0~1）
    float            player_confidence;   // 玩家置信度
    float            world_scroll_speed_x_per_second;  // 世界水平滚动速度
    vector<DetectedObject> objects;       // 已识别的对象列表
    optional<int>    score;               // 分数（可选）
    optional<int>    heart_count;         // 生命值（可选）
    bool             shield_active;       // 护盾是否激活
};
```

`DetectedObject` 包含类型、边界框、置信度、TrackID 和速度。类型枚举见 [AnalysisTypes.h](../app/src/main/cpp/include/hzzs/analysis/AnalysisTypes.h)。

## 坐标系统

- 所有坐标使用 **归一化 0.0 ~ 1.0**，已去除状态栏、导航栏和非游戏区域。
- 对象从右向左滚动（X 方向速度通常为负数）。
- 角色 X 基本固定在屏幕左侧约 14% ~ 24% 处，算法主要追踪 Y 方向变化。

## 场景状态机（SceneStateMachine）

对菜单、倒计时、地面跑酷、飞行、结算和外部遮挡做轻量稳定化。

- **遮挡判定**：当 `occlusion_confidence ≥ 0.72` 时，直接返回 `kOccluded`。
- **场景切换**：需要连续 2 帧观察到相同的新场景 hint（`hint_confidence ≥ 0.68`）才切换。
- 目的：避免单帧噪声导致的场景抖动。

## 角色状态机（RunnerStateMachine）

基于连续帧的玩家边界推断当前姿态：

| 姿态 | 条件 |
| --- | --- |
| `kRun` | 贴近基线高度，高度未明显缩减 |
| `kSlide` | 贴近基线高度，高度 < 基线高度的 76% |
| `kJumpUp` | 向下速度 > 70% 每帧（归一化坐标，Y 向下为正） |
| `kJumpTop` | 垂直速度接近零（滞空顶点） |
| `kJumpDown` | 向下速度 > 70% 每帧 |
| `kFlight` | 场景为 `kFlightRun` |

基线通过指数平滑（系数 0.08）自适应更新。

## 跳跃阶段估算（JumpStageEstimator）

**当前游戏规则固定为：最大二连跳，不存在三连跳。**

| 阶段 | 含义 |
| --- | --- |
| 0 | 地面 |
| 1 | 首跳 |
| 2 | 二连跳 |

判定逻辑：

1. **首跳**：上一帧接地，当前帧垂直向上速度 ≤ -0.070。
2. **二连跳**：首跳后到达顶点附近（垂直速度 ≥ -0.015），再次出现强向上脉冲（≤ -0.100），且距上次起跳 ≥ 95ms。
3. 接地时重置为 0。

**注意**：此模块只识别视觉运动特征，不执行任何触摸或自动操作。

## 危险 ETA 估算（HazardEtaEstimator）

对每个已识别的危险物（奶油断层、毒液瓶、悬垂裱花袋）计算到达时间：

```
ETA = distance / leftward_speed × 1000ms
```

- `distance` = 危险物左边界 - 玩家右边界 - 安全边距（0.014）
- `leftward_speed` = max(|object.velocity_x|, |world_scroll_speed_x|)，要求 > 0.04
- 置信度 < 0.62 的危险物被过滤
- 有效 ETA 范围：0 ~ 2200ms

**奶油断层宽度阈值**：宽度 ≥ 18% 屏幕时，要求二连跳；否则只需首跳。

**毒液瓶**：当前仅记录和绘制，不输出动作提示（碰撞规则待校准）。

**悬垂裱花袋**：默认提示下滑。

## 动作提示引擎（ActionPromptEngine）

将 ETA 排序后的危险物列表转换为只读 HUD 提示：

1. 仅在 `SceneMode::kGroundRun` 且玩家姿态已知时工作。
2. 对每个候选危险物，检查 ETA 范围（170ms ~ 900ms）、置信度（≥ 0.72）。
3. 滑行提示要求玩家当前贴地。
4. 跳跃提示要求 `jump_stage == 0`。
5. 二连跳提示要求 `jump_stage == 1` 且危险物需要第 2 跳。
6. **稳定化**：同一提示需连续 2 帧稳定才输出，避免 HUD 闪烁。
7. 非地面场景（飞行、菜单、遮挡等）自动重置提示。

## 飞行模式行为

- 飞行模式下，`RunnerStateMachine` 直接返回 `kFlight` 姿态。
- `ActionPromptEngine` 仅在 `kGroundRun` 时输出提示，飞行模式下不输出跳跃或下滑提示。
- `JumpStageEstimator` 在非 `kGroundRun` 时重置阶段。

## 外部通知与遮挡

当 `SceneObservation.occlusion_confidence ≥ 0.72` 时，场景机返回 `kOccluded`。此时：

- `RunnerStateMachine` 不改变姿态（保持上次已知状态）。
- `ActionPromptEngine` 被重置（`scene_mode != kGroundRun`）。
- 置信度降低时应通过 UI 提示用户，而非盲目输出动作建议。

## 算法常量速查

| 常量 | 值 | 模块 |
| --- | --- | --- |
| `kMinHintConfidence` | 0.68 | SceneStateMachine |
| `kOcclusionThreshold` | 0.72 | SceneStateMachine |
| `kTransitionConfirmFrames` | 2 | SceneStateMachine |
| `kMinPlayerConfidence` | 0.35 | RunnerStateMachine |
| `kAirborneThreshold` | 0.018 | RunnerStateMachine |
| `kSlideHeightRatio` | 0.76 | RunnerStateMachine |
| `kUpwardVelocityThreshold` | -0.070 | RunnerStateMachine / JumpStageEstimator |
| `kBaselineSmoothing` | 0.08 | RunnerStateMachine |
| `kSecondJumpImpulseThreshold` | -0.100 | JumpStageEstimator |
| `kMinImpulseGapMs` | 95 | JumpStageEstimator |
| `kWideGapThreshold` | 0.18 | HazardEtaEstimator |
| `kMinHazardConfidence` | 0.62 | HazardEtaEstimator |
| `kMinPromptEtaMs` | 170 | ActionPromptEngine |
| `kMaxPromptEtaMs` | 900 | ActionPromptEngine |
| `kMinPromptConfidence` | 0.72 | ActionPromptEngine |
| `kRequiredStableFrames` | 2 | ActionPromptEngine |

## 规划中的算法扩展

以下算法**尚未实现**：

| 能力 | 预期行为 | 阻塞条件 |
| --- | --- | --- |
| 糖果识别 | 普通糖果、X2 糖果、护盾、飞行模式的检测与分类 | 需要视觉识别层 |
| 分数识别 | 从屏幕 OCR 或模板匹配提取当前分数 | 需要视觉识别层 |
| 生命值识别 | 从 HUD 提取心形图标数量 | 需要视觉识别层 |
| 视频回放分析 | 保存帧片段供事后调试 | 需要 MediaProjection |
| 多目标跟踪 | TrackID 生命周期管理、穿越遮挡恢复 | 需要视觉识别层 |
| 自适应基线 | 不同设备分辨率下的动态基线校准 | 需要校准中心 |
