#include "hzzs/analysis/RunnerStateMachine.h"

#include <algorithm>

namespace hzzs::analysis {
namespace {

/** 玩家边界的最小可信度阈值。低于此值视为数据不可信，跳过分析。 */
constexpr float kMinPlayerConfidence = 0.35F;

/** 判定角色"接近地面"的阈值。基线底部与角色底部的差距 <= 此值视为着地（归一化坐标）。 */
constexpr float kAirborneThreshold = 0.018F;

/** 滑铲检测的高度比例。角色高度 < 基线高度 * 此值且接近地面时判定为滑铲。 */
constexpr float kSlideHeightRatio = 0.76F;

/** 起跳上升的垂直速度阈值。垂直速度 < -0.070 视为起跳上升。 */
constexpr float kUpwardVelocityThreshold = -0.070F;

/** 下落阶段的垂直速度阈值。垂直速度 > 0.070 视为下落。 */
constexpr float kDownwardVelocityThreshold = 0.070F;

/** 基线平滑系数（指数移动平均）。0.08 表示每帧基线向当前值移动 8%，避免单帧跳动。 */
constexpr float kBaselineSmoothing = 0.08F;

/** 简单的线性插值工具函数 */
float Lerp(float from, float to, float amount) {
    return from + (to - from) * amount;
}

}  // namespace

/**
 * 根据当前帧数据和场景模式更新角色运动状态。
 *
 * 完整处理流程：
 * 1. 数据校验：player_bounds 无效或置信度 < 0.35 → 返回空结果
 * 2. 飞行模式：场景为 kFlightRun → 直接返回 kFlight 姿态
 * 3. 非地面模式：场景不是 kGroundRun → 返回基本边界信息
 * 4. 基线初始化：首次有效帧 → 建立基线底部和高度，返回 kRun
 * 5. 速度计算：(当前底部 - 上一帧底部) / 帧间隔 → 垂直速度
 * 6. 着地判定：基线底部 - 当前底部 <= 0.018 → near_ground
 * 7. 姿态判定：
 *    - near_ground + 高度显著降低 → kSlide（滑铲）
 *    - near_ground + 高度正常 → kRun（奔跑），平滑更新基线
 *    - 速度 < -0.070 → kJumpUp（起跳上升）
 *    - 速度 > 0.070 → kJumpDown（下落）
 *    - 其他 → kJumpTop（滞空顶点）
 */
RunnerMotion RunnerStateMachine::Update(
    const FrameDetections& frame,
    SceneMode scene_mode
) {
    RunnerMotion result{};

    // 数据校验：玩家边界无效或置信度过低，返回空结果
    if (
        !frame.player_bounds.has_value() ||
        !frame.player_bounds->IsValid() ||
        frame.player_confidence < kMinPlayerConfidence
    ) {
        return result;
    }

    result.bounds = frame.player_bounds;
    result.confidence = frame.player_confidence;

    // 飞行模式：直接返回飞行姿态，不进行地面姿态推断
    if (scene_mode == SceneMode::kFlightRun) {
        result.pose = RunnerPose::kFlight;
        result.grounded = false;
        return result;
    }

    // 非地面模式：不执行姿态推断
    if (scene_mode != SceneMode::kGroundRun) {
        return result;
    }

    const RectF& player = *frame.player_bounds;
    const float player_bottom = player.bottom;
    const float player_height = player.Height();

    // 基线初始化：第一帧有效数据建立基准
    if (!has_baseline_) {
        has_baseline_ = true;
        baseline_bottom_ = player_bottom;
        baseline_height_ = player_height;
        last_player_bottom_ = player_bottom;
        last_timestamp_ms_ = frame.timestamp_ms;

        result.pose = RunnerPose::kRun;
        result.grounded = true;
        return result;
    }

    // 计算帧间时间差（默认 60fps → 1/60 秒）
    float delta_seconds = 1.0F / 60.0F;

    if (frame.timestamp_ms > last_timestamp_ms_ && last_timestamp_ms_ > 0) {
        delta_seconds = std::max(
            0.001F,
            static_cast<float>(frame.timestamp_ms - last_timestamp_ms_) / 1000.0F
        );
    }

    // 计算垂直速度（归一化坐标/秒）
    const float vertical_velocity = (
        player_bottom - last_player_bottom_
    ) / delta_seconds;

    // 计算与基线的垂直差距，用于判断是否接近地面
    const float airborne_gap = baseline_bottom_ - player_bottom;
    const bool near_ground = airborne_gap <= kAirborneThreshold;

    result.vertical_velocity_per_second = vertical_velocity;

    // 接近地面时的姿态判定
    if (near_ground) {
        // 滑铲检测：角色高度显著降低（< 基线高度的 76%）
        const bool looks_like_slide = (
            baseline_height_ > 0.0F &&
            player_height < baseline_height_ * kSlideHeightRatio
        );

        if (looks_like_slide) {
            result.pose = RunnerPose::kSlide;
            result.grounded = true;
        } else {
            // 奔跑模式：使用指数移动平均平滑更新基线
            baseline_bottom_ = Lerp(
                baseline_bottom_,
                player_bottom,
                kBaselineSmoothing
            );

            baseline_height_ = Lerp(
                baseline_height_,
                player_height,
                kBaselineSmoothing
            );

            result.pose = RunnerPose::kRun;
            result.grounded = true;
        }
    } else if (vertical_velocity < kUpwardVelocityThreshold) {
        // 向上运动：起跳上升阶段
        result.pose = RunnerPose::kJumpUp;
    } else if (vertical_velocity > kDownwardVelocityThreshold) {
        // 向下运动：下落阶段
        result.pose = RunnerPose::kJumpDown;
    } else {
        // 垂直速度接近 0：滞空顶点
        result.pose = RunnerPose::kJumpTop;
    }

    // 更新上一帧的玩家底部位置和时间戳
    last_player_bottom_ = player_bottom;
    last_timestamp_ms_ = frame.timestamp_ms;

    return result;
}

/** 重置所有状态，清除基线、速度缓存和时间戳 */
void RunnerStateMachine::Reset() {
    has_baseline_ = false;
    baseline_bottom_ = 0.0F;
    baseline_height_ = 0.0F;
    last_player_bottom_ = 0.0F;
    last_timestamp_ms_ = 0;
}

}  // namespace hzzs::analysis
