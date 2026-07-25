#pragma once

#include "sea_salt_fast.h"
#include "soy_sauce_exact.h"

#include <cstdint>

namespace hzzs::vision_v3 {

enum class SeaAlgorithmMode : std::uint8_t {
    SOY_SAUCE_EXACT = 0,
    BUILTIN_FAST = 1,
    SHADOW_COMPARE = 2,
};

struct SeaV3Config {
    SeaAlgorithmMode mode{SeaAlgorithmMode::SHADOW_COMPARE};
    SoySauceConfig exact{};
    SeaFastConfig fast{};
    // Maximum anchor-edge disagreement allowed by ShadowCompare.
    // 10 permille = 1% viewport width; pixel conversion uses integer ceil.
    std::uint16_t shadow_edge_tolerance_permille{10};
};

struct SeaV3Result {
    SoyDetection primary{};
    SoyDetection exact{};
    SoyDetection fast{};
    bool agreement{};
    bool action_allowed{};
    int edge_delta_px{};
};

class SeaSaltV3Engine final {
public:
    explicit SeaSaltV3Engine(SeaV3Config config = {}) noexcept;

    void set_config(SeaV3Config config) noexcept;
    [[nodiscard]] const SeaV3Config& config() const noexcept { return config_; }
    [[nodiscard]] SeaV3Result detect(const ArgbFrameView& frame) const noexcept;

private:
    SeaV3Config config_{};
    SoySauceExactEngine exact_{};
    SeaSaltFastEngine fast_{};
};

}  // namespace hzzs::vision_v3
