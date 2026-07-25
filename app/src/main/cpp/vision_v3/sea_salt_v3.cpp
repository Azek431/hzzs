#include "sea_salt_v3.h"

#include <algorithm>
#include <cmath>

namespace hzzs::vision_v3 {
namespace {

[[nodiscard]] int ceil_permille(int dimension, int permille) noexcept {
    if (dimension <= 0 || permille <= 0) return 0;
    return (dimension * permille + 999) / 1000;
}

[[nodiscard]] SoyDetection materialize_fast(
    const SeaFastHit& hit,
    const ArgbFrameView& frame,
    const SoySauceConfig& action_config) noexcept {
    if (!hit.found) return {};
    return materialize_soy_detection(
        hit.kind,
        hit.anchor_x,
        hit.anchor_y,
        frame.width,
        frame.height,
        action_config);
}

[[nodiscard]] bool detections_agree(
    const SoyDetection& exact,
    const SoyDetection& fast,
    int tolerance_px,
    int& edge_delta_px) noexcept {
    edge_delta_px = 0;
    if (exact.found != fast.found) return false;
    if (!exact.found) return true;
    if (exact.kind != fast.kind) return false;
    edge_delta_px = std::abs(exact.anchor_x - fast.anchor_x);
    return edge_delta_px <= tolerance_px;
}

void suppress_action(SoyDetection& detection) noexcept {
    detection.action_triggered = false;
    detection.action = SoyActionType::NONE;
}

}  // namespace

SeaSaltV3Engine::SeaSaltV3Engine(SeaV3Config config) noexcept {
    set_config(config);
}

void SeaSaltV3Engine::set_config(SeaV3Config config) noexcept {
    config.shadow_edge_tolerance_permille =
        std::min<std::uint16_t>(config.shadow_edge_tolerance_permille, 100);
    config_ = config;
    exact_.set_config(config_.exact);
    fast_.set_config(config_.fast);
}

SeaV3Result SeaSaltV3Engine::detect(const ArgbFrameView& frame) const noexcept {
    SeaV3Result output{};
    if (!frame.valid()) return output;

    if (config_.mode == SeaAlgorithmMode::SOY_SAUCE_EXACT) {
        output.exact = exact_.detect(frame);
        output.primary = output.exact;
        output.agreement = true;
        output.action_allowed = output.primary.action_triggered;
        return output;
    }

    const SeaFastHit fast_hit = fast_.detect(frame);
    output.fast = materialize_fast(fast_hit, frame, config_.exact);
    if (config_.mode == SeaAlgorithmMode::BUILTIN_FAST) {
        output.primary = output.fast;
        output.agreement = true;
        output.action_allowed = output.primary.action_triggered;
        return output;
    }

    output.exact = exact_.detect(frame);
    const int tolerance_px = ceil_permille(
        frame.width,
        static_cast<int>(config_.shadow_edge_tolerance_permille));
    output.agreement = detections_agree(
        output.exact,
        output.fast,
        tolerance_px,
        output.edge_delta_px);

    // Shadow mode preserves source behavior as the visible/action truth, but actions are
    // fail-closed until the independent fast path agrees on presence, class, and edge.
    output.primary = output.exact;
    output.action_allowed = output.agreement && output.exact.action_triggered;
    if (!output.action_allowed) suppress_action(output.primary);
    return output;
}

}  // namespace hzzs::vision_v3
