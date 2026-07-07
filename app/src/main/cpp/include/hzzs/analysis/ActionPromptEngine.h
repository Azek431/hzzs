#pragma once

#include <vector>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 根据危险 ETA、玩家状态和二连跳余量输出只读提示。
 *
 * 核心逻辑：
 * 1. 过滤条件：场景必须为 kGroundRun，玩家姿态不能为 kUnknown，必须有有效边界
 * 2. 候选选择：遍历 Hazards 列表，按优先级选择最紧急的危险物
 *    - 滑铲提示：仅当玩家着地时输出
 *    - 跳跃提示：根据 jump_stage 决定是单次跳还是二连跳
 *    - 置信度阈值：hazard.confidence >= 0.72，ETA 在 170ms ~ 900ms 之间
 * 3. 稳定机制：候选提示需连续 kRequiredStableFrames（2）帧相同才输出，
 *    避免单帧误检造成 HUD 闪烁
 * 4. 置信度衰减：最终提示的 confidence = min(hazard.confidence, runner.confidence)，
 *    取两者较小值以确保保守估计
 *
 * 成员变量：
 * - pending_prompt_：当前待验证的提示（尚未达到稳定帧数）
 * - active_prompt_：已通过稳定验证、可显示的提示
 * - stable_frame_count_：当前提示连续稳定的帧数
 *
 * 线程安全：此类的 Update() 和 Reset() 应由单线程调用（分析引擎主线程）。
 */
class ActionPromptEngine {
public:
    /**
     * 根据当前帧信息更新动作提示。
     *
     * @param scene_mode 当前场景模式
     * @param runner 玩家运动状态
     * @param jump_stage 当前跳跃阶段（0=地面、1=首跳、2=二连跳）
     * @param hazards 按 ETA 排序的危险物列表
     * @return ActionPrompt 稳定后的动作提示（kNone 表示无提示）
     *
     * 返回值说明：
     * - 如果场景不是地面跑酷，或数据不可信，返回空提示
     * - 如果候选提示尚未稳定（< 2 帧），返回空提示
     * - 如果候选提示已稳定，返回最终的 ActionPrompt
     */
    ActionPrompt Update(
        SceneMode scene_mode,
        const RunnerMotion& runner,
        std::uint8_t jump_stage,
        const std::vector<HazardForecast>& hazards
    );

    /** 重置所有提示状态，清除 pending 和 active 提示 */
    void Reset();

private:
    ActionPrompt pending_prompt_{};
    ActionPrompt active_prompt_{};
    int stable_frame_count_{0};
};

}  // namespace hzzs::analysis
