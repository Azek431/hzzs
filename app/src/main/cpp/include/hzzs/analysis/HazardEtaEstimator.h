#pragma once

#include <vector>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 将已识别和追踪的危险物转换为 ETA。
 *
 * 核心计算：
 * ETA = distance / leftward_speed * 1000.0
 *
 * 其中：
 * - distance = danger_bounds.left - player.right - safety_margin
 *   （危险物左边界 - 玩家右边界 - 安全余量）
 * - leftward_speed = max(object.velocity_x, world_scroll_speed)
 *   （优先使用对象自身速度，不可信时使用背景滚动速度）
 *
 * 过滤条件：
 * 1. 对象必须是危险物类型（蛋糕断层、毒瓶、裱花袋）
 * 2. 对象必须在玩家前方（distance > 0）
 * 3. 对象置信度 >= 0.62
 * 4. 向左速度 >= 0.04（归一化坐标/秒）
 * 5. ETA <= 2200ms（超过此时间的危险物暂不提示）
 *
 * 输出按 ETA 升序排列，最近的危险物排在前面。
 */
class HazardEtaEstimator {
public:
    /**
     * 根据当前帧检测结果和玩家运动状态估算所有危险物的 ETA。
     *
     * @param frame 当前帧的检测结果
     * @param runner 当前帧的玩家运动状态
     * @return HazardForecast 列表（按 ETA 升序排列，空表示无危险）
     */
    std::vector<HazardForecast> Estimate(
        const FrameDetections& frame,
        const RunnerMotion& runner
    ) const;

private:
    /**
     * 解析危险物的向左移动速度。
     *
     * 优先级：对象自身速度 > 背景滚动速度。
     * 两者都必须 >= 0.04 才视为有效速度。
     *
     * @param frame 当前帧数据
     * @param object 待分析的对象
     * @return 向左速度的绝对值（归一化坐标/秒），无效则返回 0
     */
    static float ResolveLeftwardSpeed(
        const FrameDetections& frame,
        const DetectedObject& object
    );

    /**
     * 解析危险边界矩形。
     *
     * 如果对象提供了 danger_bounds（精确碰撞区域），使用之；
     * 否则回退到完整的 bounds。
     *
     * @param object 检测到的对象
     * @return 危险边界矩形
     */
    static RectF ResolveDangerBounds(const DetectedObject& object);

    /**
     * 根据对象类型确定默认的推荐动作。
     *
     * - 蛋糕断层 → 跳跃
     * - 悬垂裱花袋 → 滑铲
     * - 毒瓶 → 无动作（当前仅记录，等待碰撞规则校准）
     *
     * @param type 游戏对象类型
     * @return 推荐的提示动作
     */
    static PromptAction DefaultActionFor(GameObjectType type);

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
    static std::uint8_t RequiredJumpStageFor(
        GameObjectType type,
        const RectF& danger_bounds
    );
};

}  // namespace hzzs::analysis
