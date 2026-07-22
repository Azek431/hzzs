/**
 * 视觉引擎实现：调度主路径检测器，映射为统一 Detection 协议，
 * 并在主路径过弱时启用启发式回退。
 *
 * 像素 → 归一化坐标在本文件完成；业务阈值优先读 AlgorithmRuntime 快照。
 */
#include "vision_engine.h"

#include "HzzsVisionCore.h"
#include "BambooVisionCore.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace hzzs {
namespace {

/** 帧指针与尺寸边界，与 JNI / Kotlin FrameMeta 上限一致。 */
bool valid_frame(const FrameView& frame) {
    constexpr int kMaxDimension = 4096;
    constexpr int64_t kMaxPixels = 8'388'608;
    const int64_t pixels = static_cast<int64_t>(frame.width) * frame.height;
    return frame.pixels != nullptr && frame.width > 0 && frame.height > 0 &&
           frame.width <= kMaxDimension && frame.height <= kMaxDimension &&
           pixels > 0 && pixels <= kMaxPixels;
}

float finite_confidence(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

/** 像素包围盒（含 right/bottom 闭区间）→ 归一化 [0,1]。 */
Rect normalize_px(int left, int top, int right, int bottom, int width, int height) {
    if (width <= 0 || height <= 0) return {};
    const float w = static_cast<float>(width);
    const float h = static_cast<float>(height);
    Rect rect{
        static_cast<float>(left) / w,
        static_cast<float>(top) / h,
        static_cast<float>(right + 1) / w,
        static_cast<float>(bottom + 1) / h,
    };
    rect.left = std::clamp(rect.left, 0.0f, 1.0f);
    rect.top = std::clamp(rect.top, 0.0f, 1.0f);
    rect.right = std::clamp(rect.right, rect.left, 1.0f);
    rect.bottom = std::clamp(rect.bottom, rect.top, 1.0f);
    return rect;
}

/**
 * 若类别启用则压入检测；非法矩形降级为 diagnostic_only。
 * 坐标在写入前归一化。
 */
void push_if_enabled(
    Result& out,
    int enabled_kind_mask,
    Kind kind,
    Avoidance avoidance,
    int track_hint,
    int left,
    int top,
    int right,
    int bottom,
    int width,
    int height,
    float confidence,
    bool actionable) {
    if (!kind_enabled(enabled_kind_mask, kind)) return;
    if (right < left || bottom < top) return;
    Detection detection{};
    detection.track_hint = track_hint;
    detection.kind = kind;
    detection.bounds = normalize_px(left, top, right, bottom, width, height);
    detection.confidence = finite_confidence(confidence);
    detection.actionable = actionable && avoidance != Avoidance::NONE;
    detection.diagnostic_only = false;
    detection.avoidance = avoidance;
    if (detection.bounds.right <= detection.bounds.left ||
        detection.bounds.bottom <= detection.bounds.top) {
        detection.actionable = false;
        detection.diagnostic_only = true;
    }
    out.detections.push_back(detection);
}

int coarse_step_for(int frame_width, int work_width) {
    if (frame_width <= 0 || work_width <= 0) return 3;
    const int step = std::max(1, (frame_width + work_width - 1) / work_width);
    return std::clamp(step, 1, 8);
}

Rect fixed_player_bounds(
    int width,
    int height,
    float fixed_player_x_ratio,
    const SceneAlgorithmParamsNative& params) {
    const int divisor = std::max(8, params.fixed_player_width_divisor);
    const int fixed_right = static_cast<int>(width * fixed_player_x_ratio);
    const int fixed_left = std::max(0, fixed_right - std::max(8, width / divisor));
    return normalize_px(
        fixed_left,
        static_cast<int>(height * params.fixed_player_top),
        fixed_right,
        static_cast<int>(height * params.fixed_player_bottom),
        width,
        height);
}

Result analyze_sweet_main(
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio,
    const SceneAlgorithmParamsNative& params) {
    Result out;
    const int step = coarse_step_for(frame.width, work_width);
    const hzzs::vision2::FrameView view{frame.pixels, frame.width, frame.height, frame.width};
    const auto raw = hzzs::vision2::analyze(view, step, std::max(1, step - 1));

    const int player_left = raw.playerLeft;
    const int player_right = raw.playerRight;
    const int player_top = std::max(0, raw.playerCenterY - raw.playerWidth);
    const int player_bottom = std::min(frame.height - 1, raw.playerCenterY + raw.playerWidth);
    // 场景置信度取检测质量（有障碍则抬升），floor 仅作下限，不再直接写常数。
    float quality = 0.35f;
    if (raw.bottle.found) quality = std::max(quality, raw.bottle.scorePermille / 1000.0f);
    if (raw.cake.found) quality = std::max(quality, raw.cake.scorePermille / 1000.0f);
    if (raw.spike.found) quality = std::max(quality, raw.spike.scorePermille / 1000.0f);
    if (detect_player && raw.playerWidth > 0) quality = std::max(quality, 0.72f);
    out.scene_confidence = finite_confidence(std::max(params.scene_confidence_floor * 0.25f, quality));

    {
        Detection player{};
        player.track_hint = 1;
        player.kind = Kind::PLAYER;
        if (detect_player) {
            player.bounds = normalize_px(
                player_left, player_top, player_right, player_bottom, frame.width, frame.height);
            player.confidence = 1.0f;
        } else {
            player.bounds = fixed_player_bounds(
                frame.width, frame.height, fixed_player_x_ratio, params);
            player.confidence = 1.0f;
        }
        out.detections.push_back(player);
    }

    int hint = 100;
    if (raw.bottle.found) {
        push_if_enabled(
            out,
            enabled_kind_mask,
            Kind::POISON_BOTTLE,
            Avoidance::JUMP,
            hint++,
            raw.bottle.left,
            raw.bottle.top,
            raw.bottle.right,
            raw.bottle.bottom,
            frame.width,
            frame.height,
            raw.bottle.scorePermille / 1000.0f,
            true);
    }
    if (raw.cake.found) {
        const bool wide = raw.cake.sizeClass == hzzs::vision2::kSizeLargeOrWide;
        const Kind cake_kind = wide ? Kind::PIT : Kind::CAKE_STRUCTURE;
        push_if_enabled(
            out,
            enabled_kind_mask,
            cake_kind,
            wide ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP,
            hint++,
            raw.cake.left,
            raw.cake.top,
            raw.cake.right,
            raw.cake.bottom,
            frame.width,
            frame.height,
            raw.cake.scorePermille / 1000.0f,
            true);
    }
    if (raw.spike.found) {
        push_if_enabled(
            out,
            enabled_kind_mask,
            Kind::HANGING_SPIKE,
            Avoidance::SLIDE,
            hint++,
            raw.spike.left,
            raw.spike.top,
            raw.spike.right,
            raw.spike.bottom,
            frame.width,
            frame.height,
            raw.spike.scorePermille / 1000.0f,
            true);
    }
    return out;
}

Result analyze_bamboo_main(
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio,
    const SceneAlgorithmParamsNative& params) {
    Result out;
    // work_width 保留供未来主路径降采样；当前 bamboo 引擎全分辨率，至少校验合法。
    if (work_width < 160 || work_width > 960) {
        out.error = "invalid work width";
        return out;
    }
    const hzzs::vision_bamboo::FrameView view{frame.pixels, frame.width, frame.height, frame.width};
    const auto raw = hzzs::vision_bamboo::analyze(view, params.player_confidence_floor);

    const float player_conf = raw.playerConfidencePermille / 1000.0f;
    if (raw.sceneState != 1 /* HZZS_SCENE_RUNNING */) {
        out.scene_confidence = finite_confidence(player_conf * 0.35f);
        return out;
    }
    if (player_conf < params.player_confidence_floor && detect_player) {
        out.scene_confidence = finite_confidence(player_conf);
        return out;
    }

    out.scene_confidence =
        finite_confidence(std::max(params.scene_confidence_floor, player_conf));

    const int player_left = raw.playerLeft;
    const int player_right = raw.playerRight;
    const int player_top = std::max(0, raw.playerCenterY - raw.playerWidth);
    const int player_bottom = std::min(frame.height - 1, raw.playerCenterY + raw.playerWidth);

    if (detect_player || player_conf >= params.player_confidence_floor) {
        Detection player{};
        player.track_hint = 1;
        player.kind = Kind::PLAYER;
        player.bounds = normalize_px(
            player_left, player_top, player_right, player_bottom, frame.width, frame.height);
        player.confidence = finite_confidence(std::max(player_conf, 0.88f));
        out.detections.push_back(player);
    } else {
        Detection player{};
        player.track_hint = 1;
        player.kind = Kind::PLAYER;
        player.bounds =
            fixed_player_bounds(frame.width, frame.height, fixed_player_x_ratio, params);
        player.confidence = 1.0f;
        out.detections.push_back(player);
    }

    int hint = 200;
    if (raw.ground.found) {
        const bool large = raw.ground.sizeClass == hzzs::vision_bamboo::kSizeLargeOrWide;
        push_if_enabled(
            out,
            enabled_kind_mask,
            Kind::PANDA_STATUE,
            large ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP,
            hint++,
            raw.ground.left,
            raw.ground.top,
            raw.ground.right,
            raw.ground.bottom,
            frame.width,
            frame.height,
            raw.ground.scorePermille / 1000.0f,
            true);
    }
    if (raw.gap.found) {
        const bool wide = raw.gap.sizeClass == hzzs::vision_bamboo::kSizeLargeOrWide;
        const Kind gap_kind = kind_enabled(enabled_kind_mask, Kind::BAMBOO_GAP)
            ? Kind::BAMBOO_GAP
            : Kind::PIT;
        push_if_enabled(
            out,
            enabled_kind_mask,
            gap_kind,
            wide ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP,
            hint++,
            raw.gap.left,
            raw.gap.top,
            raw.gap.right,
            raw.gap.bottom,
            frame.width,
            frame.height,
            raw.gap.scorePermille / 1000.0f,
            true);
    }
    if (raw.overhead.found) {
        push_if_enabled(
            out,
            enabled_kind_mask,
            Kind::HANGING_BRUSH,
            Avoidance::SLIDE,
            hint++,
            raw.overhead.left,
            raw.overhead.top,
            raw.overhead.right,
            raw.overhead.bottom,
            frame.width,
            frame.height,
            raw.overhead.scorePermille / 1000.0f,
            true);
    }
    return out;
}

Result finalize_result(
    Result result,
    bool detect_player) {
    result.scene_confidence = finite_confidence(result.scene_confidence);
    for (auto& detection : result.detections) {
        detection.bounds.left =
            std::isfinite(detection.bounds.left) ? std::clamp(detection.bounds.left, 0.0f, 1.0f) : 0.0f;
        detection.bounds.top =
            std::isfinite(detection.bounds.top) ? std::clamp(detection.bounds.top, 0.0f, 1.0f) : 0.0f;
        detection.bounds.right = std::isfinite(detection.bounds.right)
            ? std::clamp(detection.bounds.right, detection.bounds.left, 1.0f)
            : detection.bounds.left;
        detection.bounds.bottom = std::isfinite(detection.bounds.bottom)
            ? std::clamp(detection.bounds.bottom, detection.bounds.top, 1.0f)
            : detection.bounds.top;
        detection.confidence = finite_confidence(detection.confidence);
        if (detection.bounds.right <= detection.bounds.left ||
            detection.bounds.bottom <= detection.bounds.top) {
            detection.actionable = false;
            detection.diagnostic_only = true;
        }
        if (detection.kind != Kind::PLAYER && detection.avoidance == Avoidance::NONE) {
            detection.actionable = false;
        }
    }

    const auto player = std::find_if(
        result.detections.begin(),
        result.detections.end(),
        [](const Detection& detection) { return detection.kind == Kind::PLAYER; });
    if (player == result.detections.end()) {
        if (detect_player) {
            for (auto& detection : result.detections) detection.actionable = false;
        }
        return result;
    }
    for (auto& detection : result.detections) {
        if (detection.kind == Kind::PLAYER) continue;
        const bool behind = detection.bounds.right <= player->bounds.left;
        if (behind || detection.diagnostic_only) detection.actionable = false;
    }
    return result;
}

}  // namespace

Result analyze_with_profile(
    int scene,
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio,
    const AlgorithmRuntimeProfileNative& profile) {
    if (!valid_frame(frame)) {
        Result invalid;
        invalid.error = "invalid frame";
        return invalid;
    }
    if (scene < 0 || scene >= kSceneCount) {
        Result invalid;
        invalid.error = "invalid scene";
        return invalid;
    }
    if (work_width < 160 || work_width > 960) {
        Result invalid;
        invalid.error = "invalid work width";
        return invalid;
    }
    if (!std::isfinite(fixed_player_x_ratio) || fixed_player_x_ratio < 0.0f ||
        fixed_player_x_ratio > 1.0f) {
        Result invalid;
        invalid.error = "invalid player ratio";
        return invalid;
    }

    const SceneAlgorithmParamsNative& params = profile.scenes[scene];
    Result result = scene == 1
        ? analyze_bamboo_main(
              frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params)
        : analyze_sweet_main(
              frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params);

    // 主路径过弱时回退启发式；阈值来自运行时 profile。
    if (result.error.empty() &&
        static_cast<int>(result.detections.size()) <= params.fallback_max_detections &&
        result.scene_confidence < params.fallback_scene_confidence_max) {
        result = scene == 1
            ? analyze_bamboo(
                  frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params)
            : analyze_sweet(
                  frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params);
    }

    return finalize_result(std::move(result), detect_player);
}

Result analyze(
    int scene,
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio) {
    const auto profile = AlgorithmRuntime::instance().current();
    return analyze_with_profile(
        scene,
        frame,
        work_width,
        enabled_kind_mask,
        detect_player,
        fixed_player_x_ratio,
        profile);
}

void reset() {
    AlgorithmRuntime::instance().reset();
}

}  // namespace hzzs
