#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace hzzs::vision_v3 {

enum class SoyObstacleKind : std::uint8_t {
    LARGE_CLIFF = 0,
    SMALL_CLIFF = 1,
    LOW_SANDCASTLE = 2,
    HIGH_SANDCASTLE = 3,
    ANCHOR = 4,
    REVIVE = 5,
    COUNT = 6,
};

enum class SoyActionType : std::uint8_t {
    NONE = 0,
    JUMP = 1,
    DOUBLE_JUMP = 2,
    SLIDE = 3,
    REVIVE = 4,
};

enum class VerifyMetric : std::uint8_t {
    // OpenCV Core.inRange style: every RGB channel must be within threshold.
    BOX_PER_CHANNEL = 0,
    // AutoJs6 DifferenceDetector style: (|dr|+|dg|+|db|)/3 <= threshold.
    MEAN_L1 = 1,
};

struct ArgbFrameView {
    const std::uint32_t* pixels{};  // 0xAARRGGBB
    int width{};
    int height{};
    int row_stride_pixels{};

    [[nodiscard]] bool valid() const noexcept;
};

struct RatioRect {
    float left{};
    float top{};
    float right{};
    float bottom{};
};

struct SoyActionTiming {
    std::uint16_t press_ms{30};
    std::uint16_t second_press_ms{20};
    std::uint16_t double_gap_ms{60};
    std::uint16_t post_jump_wait_ms{300};
    std::uint16_t post_double_wait_ms{200};
    std::uint16_t swipe_ms{20};
    std::uint16_t post_slide_wait_ms{100};
    std::uint16_t revive_press_ms{20};
    std::uint16_t post_revive_wait_ms{100};
};

struct SoySauceConfig {
    std::uint8_t threshold{10};
    VerifyMetric verification_metric{VerifyMetric::BOX_PER_CHANNEL};
    // Outward rectangle padding in one-thousandths of viewport dimensions.
    // 10 = 1%. Integer ceil is used when converted to pixels.
    std::uint16_t visual_margin_permille{10};
    // Same design-space trigger values as the AutoJS script (base width 1272).
    std::array<std::uint16_t, 5> trigger_design_px{140, 140, 370, 470, 390};
    std::uint16_t fixed_player_offset_design_px{250};
    SoyActionTiming timing{};
};

struct SoyDetection {
    bool found{};
    bool action_triggered{};
    SoyObstacleKind kind{SoyObstacleKind::LARGE_CLIFF};
    SoyActionType action{SoyActionType::NONE};
    int anchor_x{};
    int anchor_y{};
    int swipe_end_y{};
    RatioRect visual_bounds{};
    RatioRect action_bounds{};
    float anchor_x_ratio{};
    float anchor_y_ratio{};
};

// Convert a validated obstacle anchor into normalized visual/action geometry and
// the source-compatible fixed-player action decision. Shared by Exact/Fast/Shadow.
[[nodiscard]] SoyDetection materialize_soy_detection(
    SoyObstacleKind kind,
    int anchor_x,
    int anchor_y,
    int frame_width,
    int frame_height,
    const SoySauceConfig& config) noexcept;

class SoySauceExactEngine final {
public:
    explicit SoySauceExactEngine(SoySauceConfig config = {}) noexcept;

    [[nodiscard]] const SoySauceConfig& config() const noexcept { return config_; }
    void set_config(SoySauceConfig config) noexcept;

    // Preserves the source script's observable semantics:
    // - pattern priority order is fixed;
    // - first valid point inside each pattern is row-major;
    // - the first pattern with any hit wins, even when its action threshold is not met.
    [[nodiscard]] SoyDetection detect(const ArgbFrameView& frame) const noexcept;

private:
    static constexpr std::size_t kLutSize = 1u << 15u;
    SoySauceConfig config_{};
    std::array<std::uint8_t, kLutSize> anchor_lut_{};

    void rebuild_lut() noexcept;
};

}  // namespace hzzs::vision_v3

extern "C" {

struct HzzsSoyConfigC {
    std::uint8_t threshold;
    std::uint8_t verification_metric;
    std::uint16_t visual_margin_permille;
};

struct HzzsSoyResultC {
    std::uint8_t found;
    std::uint8_t action_triggered;
    std::uint8_t kind;
    std::uint8_t action;
    int anchor_x;
    int anchor_y;
    int swipe_end_y;
    float visual_left;
    float visual_top;
    float visual_right;
    float visual_bottom;
    float action_left;
    float action_top;
    float action_right;
    float action_bottom;
};

void* hzzs_soy_create(const HzzsSoyConfigC* config) noexcept;
void hzzs_soy_destroy(void* handle) noexcept;
int hzzs_soy_detect(
    void* handle,
    const std::uint32_t* argb,
    int width,
    int height,
    int row_stride_pixels,
    HzzsSoyResultC* output) noexcept;

}
