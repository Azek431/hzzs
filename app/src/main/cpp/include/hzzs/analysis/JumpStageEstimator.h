#pragma once

#include <cstdint>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 从玩家的垂直运动中估计跳跃段数。
 *
 * 当前游戏规则按用户确认固定为：地面 0 段、首跳 1 段、二连跳 2 段。
 * 这里只识别视觉上的再次向上加速，不执行任何触摸或自动操作。
 *
 * 二段跳检测逻辑：
 * 1. 首次起跳：last_grounded=true 且 vertical_velocity <= -0.070 → stage = 1
 * 2. 二段跳触发条件（全部满足）：
 *    - 当前不在地面（!last_grounded）
 *    - 当前处于首跳阶段（stage == 1）
 *    - 垂直速度 <= -0.100（比首次起跳更强的向上加速度）
 *    - 上一次垂直速度接近顶点（>= -0.015，说明已在最高点附近）
 *    - 距离上次跳跃脉冲 >= 95ms（防止同一帧内重复触发）
 * 3. 着地重置：当 grounded=true 时，stage 归零
 *
 * 成员变量：
 * - stage_：当前跳跃阶段（0/1/2）
 * - last_grounded_：上一帧是否着地，用于检测"从地面到空中"的转换
 * - last_vertical_velocity_：上一帧的垂直速度，用于判断是否在顶点附近
 * - last_jump_impulse_ms_：上次跳跃脉冲的时间戳，用于二段跳的最小间隔检查
 */
class JumpStageEstimator {
public:
    /**
     * 根据当前帧的角色运动状态更新跳跃阶段。
     *
     * @param motion 当前帧的角色运动数据
     * @param scene_mode 当前场景模式
     * @param timestamp_ms 当前帧时间戳（毫秒）
     * @return 更新后的跳跃阶段（0/1/2）
     *
     * 返回值说明：
     * - 如果场景不是地面跑酷，或姿态未知，返回当前阶段（不清零）
     * - 如果角色着地，返回 0
     * - 否则根据垂直速度和阶段转换返回 1 或 2
     */
    std::uint8_t Update(
        const RunnerMotion& motion,
        SceneMode scene_mode,
        std::int64_t timestamp_ms
    );

    /** 重置所有状态：阶段归零，标记为着地 */
    void Reset();

private:
    std::uint8_t stage_{0};
    bool last_grounded_{true};
    float last_vertical_velocity_{0.0F};
    std::int64_t last_jump_impulse_ms_{0};
};

}  // namespace hzzs::analysis
