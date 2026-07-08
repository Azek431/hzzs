// 火崽崽助手（HZZS）动作提示引擎 — 实现层。
//
// 综合危险物 ETA、玩家姿态和跳跃阶段，输出最终 HUD 动作提示。
//
// 核心机制：
// 1. FindCandidate：从按 ETA 排序的 hazards 列表中选出最紧急的候选提示
//    - 滑铲提示仅在着地时输出（空中无法滑铲）
//    - 跳跃提示根据 jump_stage 决定单次跳还是二连跳
//    - 飞行模式下抑制所有提示
// 2. 稳定验证：候选提示需连续 2 帧相同才激活，避免 HUD 闪烁
// 3. 置信度衰减：最终 confidence = min(hazard.confidence, runner.confidence)
// 4. 遮挡增强：遮挡状态下将置信度阈值从 0.72 提升到 0.85

#include "hzzs/analysis/ActionPromptEngine.h"

#include <algorithm>
#include <cmath>

namespace hzzs::analysis {
namespace {

/** 最小有用 ETA（毫秒）。ETA < 170ms 的提示太紧急，可能来不及反应。 */
constexpr float kMinPromptEtaMs = 170.0F;

/** 最大有用 ETA（毫秒）。ETA > 900ms 的提示太早，可能造成干扰。 */
constexpr float kMaxPromptEtaMs = 900.0F;

/** 提示的最小可信度阈值。低于此值的危险物不生成 HUD 提示。 */
constexpr float kMinPromptConfidence = 0.72F;

/** 提示稳定所需的连续帧数。候选提示必须连续 N 帧相同才输出。 */
constexpr int kRequiredStableFrames = 2;

/**
 * 判断两个提示是否完全相同。
 *
 * 比较 action、target 和 required_jump_stage 三个关键字段。
 * 如果全部相同则认为提示一致，stable_frame_count_ 累加。
 * 如果任一不同则认为提示变化，重置 stable_frame_count_ 为 1。
 */
bool IsSamePrompt(
    const ActionPrompt& left,
    const ActionPrompt& right
) {
    return left.action == right.action &&
        left.target == right.target &&
        left.required_jump_stage == right.required_jump_stage;
}

/**
 * 从危险物列表中查找最紧急的候选提示。
 *
 * 遍历 Hazards 列表（已按 ETA 升序排列），找到第一个满足所有条件的危险物：
 * 1. 有明确的推荐动作（不是 kNone）
 * 2. ETA 在合理范围内（170ms ~ 900ms）
 * 3. 置信度 >= 0.72
 * 4. 飞行模式下返回空（抑制所有提示）
 * 5. 滑铲提示仅在玩家着地时有效
 * 6. 跳跃提示根据 jump_stage 决定是 kJump 还是 kJumpAgain
 *
 * 返回的 candidate.confidence = min(hazard.confidence, runner.confidence)，
 * 取两者较小值确保保守估计。
 */
ActionPrompt FindCandidate(
    SceneMode scene_mode,
    const RunnerMotion& runner,
    std::uint8_t jump_stage,
    const std::vector<HazardForecast>& hazards
) {
    // 飞行模式下抑制所有跳跃/滑铲提示。
    if (IsFlightMode(scene_mode)) {
        return {};
    }

    for (const HazardForecast& hazard : hazards) {
        if (
            hazard.preferred_action == PromptAction::kNone ||
            hazard.eta_ms < kMinPromptEtaMs ||
            hazard.eta_ms > kMaxPromptEtaMs ||
            hazard.confidence < kMinPromptConfidence
        ) {
            continue;
        }

        ActionPrompt candidate{};
        candidate.target = hazard.type;
        candidate.eta_ms = hazard.eta_ms;
        candidate.confidence = std::min(
            hazard.confidence,
            runner.confidence
        );
        candidate.required_jump_stage = hazard.required_jump_stage;

        if (hazard.preferred_action == PromptAction::kSlide) {
            // 滑铲提示仅在玩家着地时有效（空中无法滑铲）
            if (!runner.grounded) {
                continue;
            }

            candidate.action = PromptAction::kSlide;
            return candidate;
        }

        if (hazard.preferred_action == PromptAction::kJump) {
            // 地面状态 → 单次跳跃
            if (jump_stage == 0) {
                candidate.action = PromptAction::kJump;
                return candidate;
            }

            // 首跳状态 + 需要二段跳 → 二连跳提示
            if (
                jump_stage == 1 &&
                hazard.required_jump_stage >= kMaxJumpStage
            ) {
                candidate.action = PromptAction::kJumpAgain;
                return candidate;
            }
        }
    }

    return {};
}

}  // namespace

/**
 * 根据当前帧信息更新动作提示。
 *
 * 完整处理流程：
 * 1. 场景校验：非可分析场景、姿态未知或边界无效 → 重置并返回空
 * 2. 遮挡检测：遮挡状态下提高置信度阈值（0.85 vs 0.72）
 * 3. 查找候选提示：调用 FindCandidate 从 hazards 中选择最紧急的一个
 * 4. 无候选 → 重置状态，返回空
 * 5. 遮挡 + 低置信度 → 重置状态，返回空
 * 6. 候选与 pending 不同 → 更新 pending，重置计数器为 1
 * 7. 候选与 pending 相同 → 计数器累加
 * 8. 计数器 >= 2 → 标记为 active，可输出到 HUD
 *
 * @param scene_mode 当前场景模式
 * @param runner 玩家运动状态
 * @param jump_stage 当前跳跃阶段
 * @param hazards 按 ETA 排序的危险物列表
 * @return 稳定后的动作提示
 */
ActionPrompt ActionPromptEngine::Update(
    SceneMode scene_mode,
    const RunnerMotion& runner,
    std::uint8_t jump_stage,
    const std::vector<HazardForecast>& hazards
) {
    // 非跑酷场景不输出提示。
    if (
        !IsAnalyzableScene(scene_mode) ||
        runner.pose == RunnerPose::kUnknown ||
        !runner.bounds.has_value()
    ) {
        Reset();
        return {};
    }

    // 遮挡状态下提高置信度阈值，避免低置信度误触发 HUD 提示。
    // 只有高置信度（>= 0.85）的提示才会在遮挡下输出。
    const float effective_min_confidence = IsOccluded(scene_mode)
        ? 0.85F
        : kMinPromptConfidence;

    const ActionPrompt candidate = FindCandidate(
        scene_mode,
        runner,
        jump_stage,
        hazards
    );

    if (candidate.action == PromptAction::kNone) {
        Reset();
        return {};
    }

    // 遮挡模式下额外校验置信度。
    if (IsOccluded(scene_mode) && candidate.confidence < effective_min_confidence) {
        Reset();
        return {};
    }

    // 候选提示与 pending 不同 → 视为新提示，重置稳定计数器
    if (!IsSamePrompt(candidate, pending_prompt_)) {
        pending_prompt_ = candidate;
        stable_frame_count_ = 1;
        active_prompt_ = {};
        return {};
    }

    // 候选提示与 pending 相同 → 累加稳定帧数
    stable_frame_count_++;

    // 达到稳定帧数要求 → 激活提示
    if (stable_frame_count_ >= kRequiredStableFrames) {
        active_prompt_ = candidate;
    }

    return active_prompt_;
}

/** 重置所有提示状态，清除 pending 和 active 提示 */
void ActionPromptEngine::Reset() {
    pending_prompt_ = {};
    active_prompt_ = {};
    stable_frame_count_ = 0;
}

}  // namespace hzzs::analysis
