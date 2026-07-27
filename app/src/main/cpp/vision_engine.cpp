/**
 * 视觉引擎实现：调度主路径检测器，映射为统一 Detection 协议，
 * 并在主路径过弱时启用启发式回退。
 *
 * 像素 → 归一化坐标在本文件完成；业务阈值优先读 AlgorithmRuntime 快照。
 */
#include "vision_engine.h"

#include "HzzsVisionCore.h"
#include "BambooVisionCore.h"
#include "sea_salt_v3.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>

namespace hzzs {
namespace {

/** 单调时钟（纳秒），用于阶段计时；相比 wall-clock 不受 NTP 跳变影响。 */
int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

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
            Kind::GREEN_BOTTLE,
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
    if (work_width < 160 || work_width > 960) {
        out.error = "invalid work width";
        return out;
    }
    const hzzs::vision_bamboo::FrameView view{frame.pixels, frame.width, frame.height, frame.width};
    // work_width 驱动竹影主路径降采样，再映射回原图像素框。
    const auto raw = hzzs::vision_bamboo::analyze(
        view, params.player_confidence_floor, work_width);

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
    const AlgorithmRuntimeProfileNative& profile,
    bool enable_stage_timing,
    bool enable_multicolor_diag,
    bool enable_filter_trace) {
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
    StageTiming timing;
    int64_t t0 = enable_stage_timing ? now_ns() : 0;
    Result result;
    const VisionBackend backend = backend_from_profile(profile);
    if (scene == 2 && backend == VisionBackend::NATIVE_VISION) {
        // HZZS Native Vision 1.0.0 后端：海盐走 vision_v3 SeaSaltV3Engine。
        result = analyze_sea_salt_sparse(
            frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params,
            enable_multicolor_diag ? &result.multicolor_diag : nullptr);
    } else if (scene == 2) {
        // 仅诊断开关开时采样多点找色明细；关则传 nullptr，避免每帧分配。
        result = analyze_sea_salt(
            frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params,
            enable_multicolor_diag ? &result.multicolor_diag : nullptr);
    } else if (scene == 1) {
        result = analyze_bamboo_main(
            frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params);
    } else {
        result = analyze_sweet_main(
            frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params);
    }
    int64_t t1 = enable_stage_timing ? now_ns() : 0;
    if (enable_stage_timing) timing.detect_ns = t1 - t0;

    // 后过滤：用 profile 尺寸窗剔除明显越界障碍（viewport 归一化），不改核心扫描。
    // 被剔除项在 enable_filter_trace 时写入 result.filtered_out，供开发者诊断（不参与规划）。
    if (result.error.empty()) {
        std::vector<Detection> kept;
        for (const auto& d : result.detections) {
            if (d.kind == Kind::PLAYER) {
                kept.push_back(d);
                continue;
            }
            const float w = d.bounds.right - d.bounds.left;
            const float h = d.bounds.bottom - d.bounds.top;
            FilterReason reason = FilterReason::SIZE_WIDTH_MIN;
            bool drop = false;
            switch (d.kind) {
                case Kind::GREEN_BOTTLE:
                    if (w < params.bottle_width_min) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > params.bottle_width_max) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < params.bottle_height_min) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > params.bottle_height_max) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                case Kind::CAKE_STRUCTURE:
                case Kind::PIT:
                    if (w < params.cake_width_min) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > params.cake_width_max) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < params.cake_height_min) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    break;
                case Kind::SEA_PIT:
                    if (w < 0.07f) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > 0.90f) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < 0.05f) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > 0.70f) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                case Kind::HANGING_SPIKE:
                    if (w < params.spike_width_min) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > params.spike_width_max) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < params.spike_height_min) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > params.spike_height_max) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                case Kind::PANDA_STATUE:
                    if (w < params.statue_width_min) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > params.statue_width_max) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < params.statue_height_min) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > params.statue_height_max) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                case Kind::SAND_CASTLE:
                    if (w < 0.035f) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > 0.48f) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < 0.04f) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > 0.50f) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                case Kind::BAMBOO_GAP:
                    if (w < params.gap_width_min) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > params.gap_width_max) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < params.gap_height_min) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    break;
                case Kind::HANGING_BRUSH:
                    if (w < params.brush_width_min) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > params.brush_width_max) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < params.brush_height_min) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > params.brush_height_max) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                case Kind::HANGING_ANCHOR:
                    if (w < 0.025f) { drop = true; reason = FilterReason::SIZE_WIDTH_MIN; }
                    else if (w > 0.35f) { drop = true; reason = FilterReason::SIZE_WIDTH_MAX; }
                    else if (h < 0.08f) { drop = true; reason = FilterReason::SIZE_HEIGHT_MIN; }
                    else if (h > 0.65f) { drop = true; reason = FilterReason::SIZE_HEIGHT_MAX; }
                    break;
                default:
                    break;
            }
            if (drop) {
                if (enable_filter_trace) {
                    result.filtered_out.push_back(FilteredDetection{d, reason});
                }
            } else {
                kept.push_back(d);
            }
        }
        result.detections = std::move(kept);
    }
    int64_t t2 = enable_stage_timing ? now_ns() : 0;
    if (enable_stage_timing) timing.postfilter_ns = t2 - t1;

    // 主路径过弱时回退启发式（海盐无独立回退路径）。
    if (scene != 2 && result.error.empty() &&
        static_cast<int>(result.detections.size()) <= params.fallback_max_detections &&
        result.scene_confidence < params.fallback_scene_confidence_max) {
        int64_t t_fb0 = enable_stage_timing ? now_ns() : 0;
        result = scene == 1
            ? analyze_bamboo(
                  frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params)
            : analyze_sweet(
                  frame, work_width, enabled_kind_mask, detect_player, fixed_player_x_ratio, params);
        if (enable_stage_timing) {
            int64_t t_fb1 = now_ns();
            timing.detect_ns += (t_fb1 - t_fb0);
        }
    }

    int64_t t_fin0 = enable_stage_timing ? now_ns() : 0;
    result = finalize_result(std::move(result), detect_player);
    if (enable_stage_timing) {
        int64_t t_fin1 = now_ns();
        timing.finalize_ns = t_fin1 - t_fin0;
        result.timing = timing;
    } else {
        result.timing = StageTiming{};
    }
    // 诊断关时清空，避免上游误读占位数据。
    if (!enable_multicolor_diag) result.multicolor_diag.clear();
    if (!enable_filter_trace) result.filtered_out.clear();
    return result;
}

Result analyze(
    int scene,
    const FrameView& frame,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio,
    bool enable_stage_timing,
    bool enable_multicolor_diag,
    bool enable_filter_trace) {
    const auto profile = AlgorithmRuntime::instance().current();
    return analyze_with_profile(
        scene,
        frame,
        work_width,
        enabled_kind_mask,
        detect_player,
        fixed_player_x_ratio,
        profile,
        enable_stage_timing,
        enable_multicolor_diag,
        enable_filter_trace);
}

void reset() {
    AlgorithmRuntime::instance().reset();
}

namespace {

hzzs::vision_v3::SeaV3Config sparse_config_from_params(const SceneAlgorithmParamsNative& params) {
    hzzs::vision_v3::SeaV3Config config{};
    config.mode = hzzs::vision_v3::SeaAlgorithmMode::SHADOW_COMPARE;
    config.exact.threshold = static_cast<std::uint8_t>(
        std::clamp(static_cast<int>(params.multicolor_threshold), 0, 255));
    config.exact.verification_metric = hzzs::vision_v3::VerifyMetric::BOX_PER_CHANNEL;
    config.exact.visual_margin_permille = 10;
    config.fast.anchor_threshold = static_cast<std::uint8_t>(
        std::clamp(static_cast<int>(params.multicolor_threshold), 0, 255));
    config.fast.verify_threshold = config.fast.anchor_threshold;
    config.fast.verification_metric = hzzs::vision_v3::VerifyMetric::BOX_PER_CHANNEL;
    config.fast.neighborhood_radius = 1;
    config.fast.enforce_source_anchor_region = false;
    config.fast.require_exact_anchor_pattern = false;
    config.fast.horizontal_samples = 360;
    config.fast.minimum_scan_x_permille = 150;
    config.shadow_edge_tolerance_permille = 10;
    return config;
}

struct SparseEngineCache {
    hzzs::vision_v3::SeaSaltV3Engine engine{};
    float last_multicolor_threshold{-1.0f};
    bool initialized{false};

    void ensure(const hzzs::vision_v3::SeaV3Config& cfg, float threshold) {
        if (!initialized || last_multicolor_threshold != threshold) {
            engine.set_config(cfg);
            last_multicolor_threshold = threshold;
            initialized = true;
        }
    }
};

Kind soy_kind_to_object(hzzs::vision_v3::SoyObstacleKind kind) {
    switch (kind) {
        case hzzs::vision_v3::SoyObstacleKind::LARGE_CLIFF: return Kind::SEA_PIT;
        case hzzs::vision_v3::SoyObstacleKind::SMALL_CLIFF: return Kind::PIT;
        case hzzs::vision_v3::SoyObstacleKind::LOW_SANDCASTLE:
        case hzzs::vision_v3::SoyObstacleKind::HIGH_SANDCASTLE: return Kind::SAND_CASTLE;
        case hzzs::vision_v3::SoyObstacleKind::ANCHOR: return Kind::HANGING_ANCHOR;
        case hzzs::vision_v3::SoyObstacleKind::REVIVE: return Kind::HANGING_ANCHOR;
        default: return Kind::SAND_CASTLE;
    }
}

Avoidance soy_action_to_avoidance(hzzs::vision_v3::SoyActionType action) {
    switch (action) {
        case hzzs::vision_v3::SoyActionType::JUMP: return Avoidance::JUMP;
        case hzzs::vision_v3::SoyActionType::DOUBLE_JUMP: return Avoidance::DOUBLE_JUMP;
        case hzzs::vision_v3::SoyActionType::SLIDE: return Avoidance::SLIDE;
        case hzzs::vision_v3::SoyActionType::REVIVE: return Avoidance::PRESS;
        default: return Avoidance::NONE;
    }
}

float soy_confidence(bool found, bool action_allowed) {
    if (found && action_allowed) return 0.88f;
    if (found) return 0.72f;
    return 0.0f;
}

void push_soy_detection(Result& out, const hzzs::vision_v3::SoyDetection& det, int track_hint) {
    if (!det.found) return;
    const float vl = std::clamp(det.visual_bounds.left, 0.0f, 1.0f);
    const float vt = std::clamp(det.visual_bounds.top, 0.0f, 1.0f);
    const float vr = std::clamp(det.visual_bounds.right, vl, 1.0f);
    const float vb = std::clamp(det.visual_bounds.bottom, vt, 1.0f);
    out.detections.push_back({
        track_hint,
        soy_kind_to_object(det.kind),
        Rect{vl, vt, vr, vb},
        soy_confidence(det.found, det.action_triggered),
        det.action_triggered, false,
        soy_action_to_avoidance(det.action)});
}

}  // namespace

Result analyze_sea_salt_sparse(
    const FrameView& frame,
    [[maybe_unused]] int work_width,
    int enabled_kind_mask,
    [[maybe_unused]] bool detect_player,
    float fixed_player_x_ratio,
    const SceneAlgorithmParamsNative& params,
    std::vector<MulticolorDiag>* detail_out) {
    Result out;
    if (frame.pixels == nullptr || frame.width < 32 || frame.height < 64) {
        out.error = "invalid frame";
        return out;
    }

    // 帧路径零堆分配：thread_local 缓存引擎 + dirty check 避免每帧重建 LUT。
    static thread_local SparseEngineCache cache;
    hzzs::vision_v3::SeaV3Config cfg = sparse_config_from_params(params);
    cache.ensure(cfg, params.multicolor_threshold);

    hzzs::vision_v3::ArgbFrameView argb_view{
        frame.pixels, frame.width, frame.height, frame.width};

    const auto sea_result = cache.engine.detect(argb_view);
    if (!sea_result.primary.found) {
        out.scene_confidence = 0.55f;
    } else {
        out.scene_confidence = 0.82f;
    }

    // 玩家：检测器不产出 PLAYER；用固定参考框。
    {
        const int fixed_right = static_cast<int>(frame.width *
            std::clamp(fixed_player_x_ratio, 0.05f, 0.45f));
        const int divisor = std::max(8, params.fixed_player_width_divisor);
        const int fixed_left = std::max(0, fixed_right - std::max(8, frame.width / divisor));
        const int top = static_cast<int>(frame.height * params.fixed_player_top);
        const int bottom = static_cast<int>(frame.height * params.fixed_player_bottom);
        out.detections.push_back({
            1, Kind::PLAYER,
            normalize_px(fixed_left, top, fixed_right, bottom, frame.width, frame.height),
            1.0f, false, false, Avoidance::NONE});
    }

    // 映射 SeaSaltV3Engine 输出为管线 Detection。
    if (sea_result.primary.found) {
        push_soy_detection(out, sea_result.primary, 10);
    }

    // 多点找色叠加（声明式模板，与现有 sea_salt 路径对齐）。
    SceneAlgorithmParamsNative sea_params = params;
    append_multicolor_detections(out, frame, enabled_kind_mask, sea_params, detail_out);

    // 有非玩家障碍时抬升场景置信度。
    int obstacle_n = 0;
    for (const auto& d : out.detections) {
        if (d.kind != Kind::PLAYER) ++obstacle_n;
    }
    if (obstacle_n > 0) {
        out.scene_confidence =
            std::max(out.scene_confidence, std::max(params.scene_confidence_floor, 0.85f));
    }

    return out;
}

}  // namespace hzzs
