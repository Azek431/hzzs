#pragma once

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 根据连续帧的玩家边界推断跑步、起跳、滞空、下落和下滑。
 *
 * 核心逻辑：
 * 1. 初始化阶段：第一帧有效数据建立基线（baseline），记录角色底部位置和高度
 * 2. 速度计算：基于帧间时间差和底部位置变化，计算垂直速度（归一化坐标/秒）
 * 3. 姿态判定：
 *    - 着地（near_ground）：角色底部接近基线 → 区分奔跑和滑铲
 *    - 上升（upward）：垂直速度 < -0.070 → kJumpUp
 *    - 下落（downward）：垂直速度 > 0.070 → kJumpDown
 *    - 顶点（top）：垂直速度接近 0 → kJumpTop
 * 4. 滑铲检测：角色高度显著降低（< 基线高度的 76%）且接近地面 → kSlide
 * 5. 基线平滑：使用指数移动平均（系数 0.08）更新基线，避免单帧跳动
 *
 * 约束条件：
 * - 仅在 SceneMode::kGroundRun 下执行姿态推断
 * - SceneMode::kFlightRun 直接返回 kFlight 姿态
 * - 玩家置信度 < 0.35 时跳过分析
 * - 不关心屏幕采集、模板匹配或 UI，只消费稳定后的玩家矩形
 *
 * 成员变量：
 * - has_baseline_：是否已完成基线初始化
 * - baseline_bottom_：基线底部位置（归一化坐标），持续平滑更新
 * - baseline_height_：基线高度（归一化坐标），用于滑铲检测
 * - last_player_bottom_：上一帧玩家底部位置，用于速度计算
 * - last_timestamp_ms_：上一帧时间戳，用于计算帧间隔
 */
class RunnerStateMachine {
public:
    /**
     * 根据当前帧数据和场景模式更新角色运动状态。
     *
     * 这是核心分析函数，每帧调用一次。
     *
     * @param frame 当前帧的检测结果（包含玩家边界、置信度、时间戳等）
     * @param scene_mode 当前场景模式
     * @return RunnerMotion 包含姿态、边界、速度、是否着地等信息
     *
     * 返回值说明：
     * - 如果数据不可信（player_bounds 无效或置信度过低），返回空的 RunnerMotion
     * - 如果场景不是地面跑酷，仅返回基本边界信息
     * - 如果场景是飞行模式，直接返回 kFlight 姿态
     */
    RunnerMotion Update(const FrameDetections& frame, SceneMode scene_mode);

    /**
     * 重置所有状态。
     *
     * 在场景切换、分析中断或初始化时调用。
     * 清除基线、速度缓存和时间戳，使下一帧重新建立基线。
     */
    void Reset();

private:
    bool has_baseline_{false};
    float baseline_bottom_{0.0F};
    float baseline_height_{0.0F};
    float last_player_bottom_{0.0F};
    std::int64_t last_timestamp_ms_{0};
};

}  // namespace hzzs::analysis
