#include "vision_engine.h"

#include "HzzsVisionCore.h"
#include "BambooVisionCore.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace hzzs {
namespace {

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

/**
 * 使用历史 main 的 vision2 甜品工厂检测器，并映射到统一 Detection 协议。
 * 保留 sweet_factory.cpp 作为后备启发式（CMake 仍编译，便于对照）。
 */
Result analyze_sweet_main(
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio) {
    Result out;
    const int step = coarse_step_for(frame.width, work_width);
    const hzzs::vision2::FrameView view{frame.pixels, frame.width, frame.height, frame.width};
    const auto raw = hzzs::vision2::analyze(view, step, std::max(1, step - 1));

    const int player_left = raw.playerLeft;
    const int player_right = raw.playerRight;
    const int player_top = std::max(0, raw.playerCenterY - raw.playerWidth);
    const int player_bottom = std::min(frame.height - 1, raw.playerCenterY + raw.playerWidth);
    out.scene_confidence = 0.92f;

    {
        Detection player{};
        player.track_hint = 1;
        player.kind = Kind::PLAYER;
        if (detect_player) {
            player.bounds = normalize_px(
                player_left, player_top, player_right, player_bottom, frame.width, frame.height);
            player.confidence = 1.0f;
        } else {
            const int fixed_right = static_cast<int>(frame.width * fixed_player_x_ratio);
            const int fixed_left = std::max(0, fixed_right - std::max(8, frame.width / 20));
            player.bounds = normalize_px(
                fixed_left,
                static_cast<int>(frame.height * 0.72f),
                fixed_right,
                static_cast<int>(frame.height * 0.94f),
                frame.width,
                frame.height);
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
        // 宽 cake 语义上更接近断层/坑：优先 PIT；否则输出蛋糕结构。避免同一框双写导致双动作。
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
    int /*work_width*/,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio) {
    Result out;
    const hzzs::vision_bamboo::FrameView view{frame.pixels, frame.width, frame.height, frame.width};
    const auto raw = hzzs::vision_bamboo::analyze(view);

    const float player_conf = raw.playerConfidencePermille / 1000.0f;
    if (raw.sceneState != 1 /* HZZS_SCENE_RUNNING */) {
        out.scene_confidence = finite_confidence(player_conf * 0.35f);
        return out;
    }
    if (player_conf < 0.45f && detect_player) {
        out.scene_confidence = finite_confidence(player_conf);
        return out;
    }

    out.scene_confidence = finite_confidence(std::max(0.82f, player_conf));

    const int player_left = raw.playerLeft;
    const int player_right = raw.playerRight;
    const int player_top = std::max(0, raw.playerCenterY - raw.playerWidth);
    const int player_bottom = std::min(frame.height - 1, raw.playerCenterY + raw.playerWidth);

    if (detect_player || player_conf >= 0.45f) {
        Detection player{};
        player.track_hint = 1;
        player.kind = Kind::PLAYER;
        player.bounds = normalize_px(
            player_left, player_top, player_right, player_bottom, frame.width, frame.height);
        player.confidence = finite_confidence(std::max(player_conf, 0.88f));
        out.detections.push_back(player);
    } else {
        const int fixed_right = static_cast<int>(frame.width * fixed_player_x_ratio);
        const int fixed_left = std::max(0, fixed_right - std::max(8, frame.width / 20));
        Detection player{};
        player.track_hint = 1;
        player.kind = Kind::PLAYER;
        player.bounds = normalize_px(
            fixed_left,
            static_cast<int>(frame.height * 0.72f),
            fixed_right,
            static_cast<int>(frame.height * 0.94f),
            frame.width,
            frame.height);
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
        // 竹隙为主语义；仅当 BAMBOO_GAP 被用户关闭时才退化为 PIT，避免双写。
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

}  // namespace

Result analyze(
    int scene,
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio) {
    if (!valid_frame(frame)) {
        Result invalid;
        invalid.error = "invalid frame";
        return invalid;
    }
    if (scene < 0 || scene > 1) {
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

    Result result = scene == 1
        ? analyze_bamboo_main(frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio)
        : analyze_sweet_main(frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio);

    // 若 main 引擎未给出可用结果，回退到统一重构期的启发式检测器。
    if (result.error.empty() && result.detections.size() <= 1 && result.scene_confidence < 0.2f) {
        result = scene == 1
            ? analyze_bamboo(frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio)
            : analyze_sweet(frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio);
    }

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

void reset() {}

}  // namespace hzzs
