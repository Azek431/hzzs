// 火崽崽助手（HZZS）场景状态机 — 实现层。
//
// 负责将视觉层每帧输出的场景 hint 稳定化为最终场景模式。
// 核心机制：连续两帧确认（debounce），避免单帧噪声导致场景跳变。
//
// 遮挡处理是此状态机的唯一"特权"——它不需要连续确认，因为遮挡是瞬时事件。
// 遮挡消失后，从 pending_scene_ 恢复之前的场景，确保分析连续性。

#include "hzzs/analysis/SceneStateMachine.h"

namespace hzzs::analysis {
namespace {

/** 场景 hint 的最小可信度阈值。低于此值视为不可信，候选场景设为 kUnknown。 */
constexpr float kMinHintConfidence = 0.68F;

/** 遮挡检测的阈值。高于此值认为画面被外部元素遮挡。 */
constexpr float kOcclusionThreshold = 0.72F;

/** 场景切换确认所需的连续帧数。必须连续 N 帧看到相同的候选场景才正式切换。 */
constexpr int kTransitionConfirmFrames = 2;

}  // namespace

/**
 * 根据视觉层的场景观察更新场景状态机。
 *
 * 完整处理流程：
 * 1. 遮挡检测：occlusion_confidence >= 0.72 → 立即切换到 kOccluded，保留 pending 状态
 * 2. 遮挡消失：从 pending_scene_ 恢复之前的场景
 * 3. 候选场景判定：hint_confidence >= 0.68 → 使用 hint；否则设为 kUnknown
 * 4. 未知候选：保持当前场景不变，重置 pending 计数器
 * 5. 初始场景：如果当前为 kUnknown 且候选可信 → 直接切换（无需确认）
 * 6. 相同场景：候选 == 当前 → 重置 pending，返回当前
 * 7. 不同场景：候选 != 当前 → 进入 pending 状态，计数器从 1 开始
 * 8. 持续确认：候选 == pending 且计数器 >= 2 → 正式切换场景
 */
SceneMode SceneStateMachine::Update(const SceneObservation& observation) {
    // 遮挡阈值达到时，立即切换到遮挡状态。
    // 不破坏 pending 状态，以便遮挡消失后恢复到之前的场景。
    if (observation.occlusion_confidence >= kOcclusionThreshold) {
        current_scene_ = SceneMode::kOccluded;
        return current_scene_;
    }

    // 遮挡消失时恢复之前保存的场景。
    if (current_scene_ == SceneMode::kOccluded) {
        current_scene_ = pending_scene_ != SceneMode::kUnknown
            ? pending_scene_
            : SceneMode::kUnknown;
        pending_scene_ = SceneMode::kUnknown;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    const SceneMode candidate = observation.hint_confidence >= kMinHintConfidence
        ? observation.hint
        : SceneMode::kUnknown;

    if (candidate == SceneMode::kUnknown) {
        pending_scene_ = SceneMode::kUnknown;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    // 首次检测到非未知场景时直接切换。
    if (current_scene_ == SceneMode::kUnknown) {
        current_scene_ = candidate;
        pending_scene_ = candidate;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    if (candidate == current_scene_) {
        pending_scene_ = candidate;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    if (candidate != pending_scene_) {
        pending_scene_ = candidate;
        pending_frame_count_ = 1;
        return current_scene_;
    }

    pending_frame_count_++;

    if (pending_frame_count_ >= kTransitionConfirmFrames) {
        current_scene_ = candidate;
        pending_frame_count_ = 0;
    } else if (pending_frame_count_ > kTransitionConfirmFrames) {
        // 安全屏障：防止极端情况下整数溢出（UB）
        pending_frame_count_ = kTransitionConfirmFrames;
    }

    return current_scene_;
}

/** 重置所有场景状态 */
void SceneStateMachine::Reset() {
    current_scene_ = SceneMode::kUnknown;
    pending_scene_ = SceneMode::kUnknown;
    pending_frame_count_ = 0;
}

}  // namespace hzzs::analysis
