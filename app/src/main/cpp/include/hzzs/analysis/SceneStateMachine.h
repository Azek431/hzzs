#pragma once

#include <cstdint>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 对菜单、倒计时、地面跑酷、飞行、结算和外部遮挡做轻量稳定化。
 *
 * 核心问题：视觉层每帧输出的场景 hint 可能因画面噪声、短暂遮挡或
 * 模型不确定性而出现单帧抖动。此状态机通过"连续两帧确认"机制过滤噪声。
 *
 * 状态转换规则：
 * 1. 遮挡检测（最高优先级）：occlusion_confidence >= 0.72 → 直接返回 kOccluded
 * 2. 场景切换：候选场景 != 当前场景 → 进入 pending 状态，计数累加
 * 3. 确认切换：pending_frame_count >= 2 → 正式切换到候选场景
 * 4. 场景恢复：候选场景 == 当前场景 → 重置 pending 计数器
 * 5. 未知场景：hint_confidence < 0.68 或 hint 为 kUnknown → 保持当前场景不变
 * 6. 初始场景：current_scene 为 kUnknown 且收到可信 hint → 直接切换（无需确认）
 *
 * 设计约束：
 * - 不依赖时间戳，仅依赖帧计数（假设帧率相对稳定）
 * - pending_frame_count 在候选场景变化时重置为 1
 * - kOccluded 不进入 pending 状态，直接返回（遮挡是瞬时状态）
 */
class SceneStateMachine {
public:
    /**
     * 根据视觉层的场景观察更新场景状态。
     *
     * @param observation 当前帧的场景观察（hint + 置信度 + 遮挡检测）
     * @return SceneMode 稳定后的场景模式
     *
     * 返回值说明：
     * - 如果检测到遮挡，返回 kOccluded
     * - 如果场景切换已确认，返回新的场景模式
     * - 如果场景切换仍在 pending 中，返回当前场景模式（未确认前不切换）
     * - 如果数据不可信，返回上一个已知场景模式
     */
    SceneMode Update(const SceneObservation& observation);

    /** 返回当前已确认的场景模式（只读访问） */
    [[nodiscard]] SceneMode Current() const {
        return current_scene_;
    }

    /** 重置所有场景状态 */
    void Reset();

private:
    SceneMode current_scene_{SceneMode::kUnknown};
    SceneMode pending_scene_{SceneMode::kUnknown};
    int pending_frame_count_{0};
};

}  // namespace hzzs::analysis
