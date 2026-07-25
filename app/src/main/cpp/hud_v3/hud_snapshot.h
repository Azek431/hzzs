#pragma once

#include "../vision_v3/soy_sauce_exact.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace hzzs::hud_v3 {

constexpr std::size_t kMaxHudDetections = 32;

struct HudDetectionSnapshot {
    std::uint8_t kind{};
    std::uint8_t action{};
    std::uint8_t actionable{};
    std::uint8_t diagnostic_only{};
    float confidence{};
    hzzs::vision_v3::RatioRect visual_bounds{};
    hzzs::vision_v3::RatioRect action_bounds{};
};

struct HudFrameSnapshot {
    std::uint64_t generation{};
    std::uint64_t captured_elapsed_nanos{};
    std::uint32_t algorithm_micros{};
    std::uint32_t capture_micros{};
    std::uint32_t hud_cpu_micros{};
    std::uint8_t scene{};
    std::uint8_t algorithm_mode{};
    std::uint8_t shadow_agreement{};
    std::uint8_t detection_count{};
    std::array<HudDetectionSnapshot, kMaxHudDetections> detections{};
};

// Fixed-capacity two-slot seqlock exchange. The algorithm thread publishes whole
// immutable snapshots; the ImGui thread copies one stable snapshot without heap
// allocation or holding a lock across rendering.
class HudSnapshotExchange final {
public:
    void publish(const HudFrameSnapshot& snapshot) noexcept;
    [[nodiscard]] bool try_read(HudFrameSnapshot& output) const noexcept;

private:
    struct alignas(64) Slot {
        std::atomic<std::uint64_t> sequence{0};
        HudFrameSnapshot snapshot{};
    };

    std::array<Slot, 2> slots_{};
    std::atomic<std::uint8_t> published_index_{0};
    std::atomic<bool> has_value_{false};
};

}  // namespace hzzs::hud_v3
