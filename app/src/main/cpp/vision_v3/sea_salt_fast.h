#pragma once

#include "soy_sauce_exact.h"

#include <array>
#include <cstddef>
#include <cstdint>

namespace hzzs::vision_v3 {

struct SeaFastConfig {
    std::uint8_t anchor_threshold{16};
    std::uint8_t verify_threshold{16};
    VerifyMetric verification_metric{VerifyMetric::BOX_PER_CHANNEL};
    // Relative verification samples may search a square neighborhood around the expected point.
    std::uint8_t neighborhood_radius{1};
    // Exact-compatibility mode rejects recovered source anchors outside the AutoJS search region.
    bool enforce_source_anchor_region{false};
    // Exact-compatibility gate: after scan-point verification, require a strict source
    // anchor+relative pattern before returning. Builtin tolerant mode leaves this false.
    bool require_exact_anchor_pattern{false};
    // Work-grid density expressed as target samples across viewport width.
    // step = ceil(width / horizontal_samples); default matches ~360-wide work image.
    std::uint16_t horizontal_samples{360};
    // Builtin mode may scan further left than the original AutoJS region.
    // 50 = 5% of viewport width; conversion uses integer ceil.
    std::uint16_t minimum_scan_x_permille{150};
};

struct SeaFastHit {
    bool found{};
    SoyObstacleKind kind{SoyObstacleKind::LARGE_CLIFF};
    int anchor_x{};
    int anchor_y{};
};

class SeaSaltFastEngine final {
public:
    explicit SeaSaltFastEngine(SeaFastConfig config = {}) noexcept;
    void set_config(SeaFastConfig config) noexcept;
    [[nodiscard]] const SeaFastConfig& config() const noexcept { return config_; }
    [[nodiscard]] SeaFastHit detect(const ArgbFrameView& frame) const noexcept;

private:
    static constexpr std::size_t kLutSize = 1u << 15u;
    SeaFastConfig config_{};
    std::array<std::uint8_t, kLutSize> scan_lut_{};
    void rebuild_lut() noexcept;
};

}  // namespace hzzs::vision_v3

extern "C" {

struct HzzsSeaFastConfigC {
    std::uint8_t anchor_threshold;
    std::uint8_t verify_threshold;
    std::uint8_t verification_metric;
    std::uint8_t neighborhood_radius;
    std::uint8_t enforce_source_anchor_region;
    std::uint8_t require_exact_anchor_pattern;
    std::uint16_t horizontal_samples;
    std::uint16_t minimum_scan_x_permille;
};

void* hzzs_sea_fast_create(const HzzsSeaFastConfigC* config) noexcept;
void hzzs_sea_fast_destroy(void* handle) noexcept;
int hzzs_sea_fast_detect(
    void* handle,
    const std::uint32_t* argb,
    int width,
    int height,
    int row_stride_pixels,
    HzzsSoyResultC* output) noexcept;

}
