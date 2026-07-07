#include "hzzs/analysis/JumpStageEstimator.h"

namespace hzzs::analysis {
namespace {

/** 首次起跳的垂直速度阈值。<= -0.070 视为起跳。 */
constexpr float kNewJumpUpVelocityThreshold = -0.070F;

/** 二段跳的垂直速度阈值。<= -0.100 视为二段跳脉冲（比首次起跳更强）。 */
constexpr float kSecondJumpImpulseThreshold = -0.100F;

/** 二段跳判定：上一次垂直速度 >= -0.015 视为接近顶点（已到达最高点附近）。 */
constexpr float kPriorVelocityNearApexThreshold = -0.015F;

/** 两次跳跃脉冲之间的最小时间间隔（毫秒）。防止同一帧内重复触发。 */
constexpr std::int64_t kMinImpulseGapMs = 95;

}  // namespace

/**
 * 根据当前帧的角色运动状态更新跳跃阶段。
 *
 * 完整处理流程：
 * 1. 场景校验：非地面跑酷或姿态未知 → 重置阶段为 0
 * 2. 着地检测：grounded == true → stage = 0，标记为着地
 * 3. 首次起跳：last_grounded == true 且 vertical_velocity <= -0.070 → stage = 1
 * 4. 二段跳：全部条件满足时 stage = 2
 *    - !last_grounded（当前在空中）
 *    - stage == 1（当前为首跳阶段）
 *    - vertical_velocity <= -0.100（强向上加速度）
 *    - last_vertical_velocity >= -0.015（上一次接近顶点）
 *    - 距离上次跳跃脉冲 >= 95ms
 * 5. 安全屏障：stage_ 绝不超过 kMaxJumpStage
 * 6. 更新 last_grounded 和 last_vertical_velocity
 */
std::uint8_t JumpStageEstimator::Update(
    const RunnerMotion& motion,
    SceneMode scene_mode,
    std::int64_t timestamp_ms
) {
    // 非跑酷场景下重置跳跃阶段。
    if (scene_mode != SceneMode::kGroundRun || motion.pose == RunnerPose::kUnknown) {
        stage_ = 0;
        last_grounded_ = true;
        last_vertical_velocity_ = motion.vertical_velocity_per_second;
        return stage_;
    }

    if (motion.grounded) {
        stage_ = 0;
        last_grounded_ = true;
        last_vertical_velocity_ = motion.vertical_velocity_per_second;
        return stage_;
    }

    const bool starts_first_jump = (
        last_grounded_ &&
        motion.vertical_velocity_per_second <= kNewJumpUpVelocityThreshold
    );

    const bool gets_second_jump_impulse = (
        !last_grounded_ &&
        stage_ == 1 &&
        motion.vertical_velocity_per_second <= kSecondJumpImpulseThreshold &&
        last_vertical_velocity_ >= kPriorVelocityNearApexThreshold &&
        timestamp_ms - last_jump_impulse_ms_ >= kMinImpulseGapMs
    );

    if (starts_first_jump || (stage_ == 0 && motion.pose == RunnerPose::kJumpUp)) {
        stage_ = 1;
        last_jump_impulse_ms_ = timestamp_ms;
    } else if (gets_second_jump_impulse) {
        stage_ = kMaxJumpStage;
        last_jump_impulse_ms_ = timestamp_ms;
    }

    // 安全屏障：即使视觉误报，stage_ 也绝不能超过 kMaxJumpStage。
    if (stage_ > kMaxJumpStage) {
        stage_ = kMaxJumpStage;
    }

    last_grounded_ = false;
    last_vertical_velocity_ = motion.vertical_velocity_per_second;

    return stage_;
}

/** 重置所有状态：阶段归零，标记为着地 */
void JumpStageEstimator::Reset() {
    stage_ = 0;
    last_grounded_ = true;
    last_vertical_velocity_ = 0.0F;
    last_jump_impulse_ms_ = 0;
}

}  // namespace hzzs::analysis
