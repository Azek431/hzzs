# HZZS Native Analysis Core

这套 C++ 基础层只负责把“稳定后的视觉结果”变成可解释的游戏状态、危险 ETA 和 HUD 提示。

## 当前规则边界

- 最大跳跃阶段固定为 `2`：首跳为 `1`，二连跳为 `2`。
- 地面模式支持：跑步、上跳、滞空、下落、下滑。
- 飞行模式会停止跳跃/下滑提示，仅保留状态和可收集物数据。
- 不包含屏幕采集、OpenCV、模板匹配、自动点击、自动滑铲或任何游戏操作。

## 当前对象语义

| 内部类型 | 用途 | 当前动作策略 |
| --- | --- | --- |
| `CakeGap` | 奶油断层 / 地面缺口 | 跳跃；宽断层可提示二连跳。 |
| `HangingPipingBag` | 悬垂裱花袋 / 顶部风险 | 下滑。 |
| `PoisonBottle` | 毒液瓶 | 当前仅绘制和记录，等待回放校准后再启用动作提示。 |
| `StripedCandy` | 普通条纹糖果 | 绘制与记录。 |
| `DoubleCandy` | X2 糖果 | 重点绘制与记录。 |
| `ShieldToken` | 护盾类增益候选 | 绘制与状态记录。 |
| `FlightTrigger` | 飞行器触发物候选 | 绘制与场景切换辅助。 |

## 数据流

```text
Android 屏幕采集 / 离线回放
        ↓
视觉检测与追踪层（后续实现）
        ↓
FrameDetections
        ↓
NativeAnalysisEngine
        ↓
AnalysisResult
        ↓
Kotlin HUD、调试绘制、本局战报
```

## 下一步

优先实现离线视频回放的视觉层，而不是直接接入实时控制：

1. `GameViewportDetector`：确认真实游戏画面区域。
2. `SceneClassifier`：区分菜单、倒计时、地面跑酷、飞行、结算、遮挡。
3. `PlayerTracker`：只在固定左侧区域跟踪火崽崽的 Y 轴变化。
4. `CakeGapDetector`、`HangingPipingBagDetector`：先做高价值危险物。
5. `CandyDetector`、`ScoreReader`、`HeartReader`：最后再做数据与绘制优化。
