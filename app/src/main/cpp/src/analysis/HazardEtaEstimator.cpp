#include "hzzs/analysis/HazardEtaEstimator.h"

#include <algorithm>
#include <cmath>

namespace hzzs::analysis {
namespace {

/** 危险对象的最小可信度阈值。低于此值的检测不参与 ETA 计算。 */
constexpr float kMinHazardConfidence = 0.62F;

/** 最小有效向左速度（归一化坐标/秒）。低于此值视为静止，不参与 ETA 计算。 */
constexpr float kMinLeftwardSpeed = 0.04F;

/** 玩家安全余量。计算距离时在 danger_bounds.left - player.right 的基础上再减去此值，
 * 确保提示在玩家真正接触到危险物之前就发出。 */
constexpr float kPlayerSafetyMargin = 0.014F;

/** 最大有用的 ETA（毫秒）。超过此时间的危险物暂不提示，避免信息过载。 */
constexpr float kMaxUsefulEtaMs = 2200.0F;

/** 宽断层阈值（归一化坐标宽度）。>= 此值的蛋糕断层需要二连跳。 */
constexpr float kWideGapThreshold = 0.18F;

}  // namespace

/**
 * 根据当前帧检测结果和玩家运动状态估算所有危险物的 ETA。
 *
 * 完整处理流程：
 * 1. 校验玩家边界是否有效
 * 2. 遍历所有检测对象，过滤非危险物、无效边界、低置信度对象
 * 3. 解析危险边界和向左速度
 * 4. 过滤速度不可信的对象
 * 5. 计算距离（danger_bounds.left - player.right - safety_margin）
 * 6. 过滤不在玩家前方或距离 <= 0 的对象
 * 7. 计算 ETA = distance / speed * 1000
 * 8. 过滤 ETA > 2200ms 的对象
 * 9. 填充 HazardForecast 并加入结果列表
 * 10. 按 ETA 升序排序
 *
 * @param frame 当前帧检测结果
 * @param runner 当前帧玩家运动状态
 * @return 按 ETA 升序排列的危险预测列表
 */
std::vector<HazardForecast> HazardEtaEstimator::Estimate(
    const FrameDetections& frame,
    const RunnerMotion& runner
) const {
    std::vector<HazardForecast> forecasts{};

    // 玩家边界无效时无法计算距离，直接返回空列表
    if (!runner.bounds.has_value() || !runner.bounds->IsValid()) {
        return forecasts;
    }

    // 玩家碰撞点：玩家矩形的右边界（世界向左滚动，玩家从右侧接近危险物）
    const float player_collision_x = runner.bounds->right;

    for (const DetectedObject& object : frame.objects) {
        // 过滤：非危险物或边界无效
        if (!IsHazard(object.type) || !object.bounds.IsValid()) {
            continue;
        }

        // 过滤：置信度不足
        if (object.confidence < kMinHazardConfidence) {
            continue;
        }

        const RectF danger_bounds = ResolveDangerBounds(object);
        const float leftward_speed = ResolveLeftwardSpeed(frame, object);

        // 过滤：向左速度不可信
        if (leftward_speed < kMinLeftwardSpeed) {
            continue;
        }

        // 计算危险物与玩家之间的距离（含安全余量）
        const float distance = danger_bounds.left - player_collision_x - kPlayerSafetyMargin;

        // 过滤：危险物不在玩家前方
        if (distance <= 0.0F) {
            continue;
        }

        // 计算 ETA（毫秒）
        const float eta_ms = distance / leftward_speed * 1000.0F;

        // 过滤：ETA 过长，暂不提示
        if (eta_ms > kMaxUsefulEtaMs) {
            continue;
        }

        HazardForecast forecast{};
        forecast.type = object.type;
        forecast.danger_bounds = danger_bounds;
        forecast.eta_ms = eta_ms;
        forecast.confidence = object.confidence;
        forecast.preferred_action = DefaultActionFor(object.type);
        forecast.required_jump_stage = RequiredJumpStageFor(
            object.type,
            danger_bounds
        );

        forecasts.push_back(forecast);
    }

    // 按 ETA 升序排序，最近的危险物排在前面
    std::sort(
        forecasts.begin(),
        forecasts.end(),
        [](const HazardForecast& left, const HazardForecast& right) {
            return left.eta_ms < right.eta_ms;
        }
    );

    return forecasts;
}

/**
 * 解析危险物的向左移动速度。
 *
 * 优先级：对象自身速度 > 背景滚动速度。
 * 两者都必须 < -0.04 才视为有效向左速度。
 *
 * @param frame 当前帧数据
 * @param object 待分析的对象
 * @return 向左速度的绝对值（归一化坐标/秒），无效则返回 0
 */
float HazardEtaEstimator::ResolveLeftwardSpeed(
    const FrameDetections& frame,
    const DetectedObject& object
) {
    // 优先使用对象自身的 X 方向速度
    if (object.velocity_x_per_second < -kMinLeftwardSpeed) {
        return std::abs(object.velocity_x_per_second);
    }

    // 回退到背景滚动速度
    if (frame.world_scroll_speed_x_per_second < -kMinLeftwardSpeed) {
        return std::abs(frame.world_scroll_speed_x_per_second);
    }

    return 0.0F;
}

/**
 * 解析危险边界矩形。
 *
 * 如果对象提供了 danger_bounds（精确碰撞区域），使用之；
 * 否则回退到完整的 bounds。
 *
 * @param object 检测到的对象
 * @return 危险边界矩形
 */
RectF HazardEtaEstimator::ResolveDangerBounds(const DetectedObject& object) {
    return object.danger_bounds.value_or(object.bounds);
}

/**
 * 根据对象类型确定默认的推荐动作。
 *
 * - 蛋糕断层 → 跳跃
 * - 悬垂裱花袋 → 滑铲
 * - 毒瓶 → 跳跃（当前建议，等真实回放完成碰撞规则校准后可调整）
 * - 其他 → 无动作
 */
PromptAction HazardEtaEstimator::DefaultActionFor(GameObjectType type) {
    switch (type) {
        case GameObjectType::kCakeGap:
            return PromptAction::kJump;

        case GameObjectType::kHangingPipingBag:
            return PromptAction::kSlide;

        case GameObjectType::kPoisonBottle:
            // 毒瓶是地面障碍，建议跳跃避开。
            // 等真实回放完成碰撞规则校准后，可调整为更精确的动作。
            return PromptAction::kJump;

        default:
            return PromptAction::kNone;
    }
}

/**
 * 根据对象类型和危险边界宽度确定所需的跳跃阶段。
 *
 * - 非蛋糕断层 → 不需要跳跃（返回 0）
 * - 蛋糕断层宽度 < 0.18 → 首跳即可（返回 1）
 * - 蛋糕断层宽度 >= 0.18 → 需要二连跳（返回 kMaxJumpStage = 2）
 *
 * @param type 游戏对象类型
 * @param danger_bounds 危险边界矩形
 * @return 需要的跳跃阶段
 */
std::uint8_t HazardEtaEstimator::RequiredJumpStageFor(
    GameObjectType type,
    const RectF& danger_bounds
) {
    if (type != GameObjectType::kCakeGap) {
        return 0;
    }

    return danger_bounds.Width() >= kWideGapThreshold
        ? kMaxJumpStage
        : 1;
}

}  // namespace hzzs::analysis
