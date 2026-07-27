#include "soy_sauce_exact.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <new>

namespace hzzs::vision_v3 {
namespace {

constexpr int kBaseWidth = 1272;
constexpr int kBaseHeight = 2772;
constexpr int kSearchX = 290;
constexpr int kSearchY = 1213;
constexpr int kSearchW = 982;
constexpr int kSearchH = 1229;
constexpr int kSlideDesignDy = 100;
constexpr int kSlideBottomInsetDesign = 20;
// Left-pad the scaled search region so near-edge anchors (e.g. x≈113 px at 720w)
// fall inside the scan window. 50 px covers all ground-truth left-edge obstacles.
constexpr int kSearchPaddingLeft = 50;

struct Rgb {
    std::uint8_t r;
    std::uint8_t g;
    std::uint8_t b;
};

struct DesignPoint {
    std::int16_t dx;
    std::int16_t dy;
    std::uint32_t argb;
};

struct PatternSpec {
    SoyObstacleKind kind;
    std::uint32_t anchor_argb;
    const DesignPoint* points;
    std::uint8_t point_count;
    SoyActionType action;
};

constexpr DesignPoint kLargeCliff[] = {
    {57, 86, static_cast<std::uint32_t>(-659994)},
    {206, 17, static_cast<std::uint32_t>(-66830)},
    {294, 65, static_cast<std::uint32_t>(-198418)},
    {633, 88, static_cast<std::uint32_t>(-67343)},
    {739, 168, static_cast<std::uint32_t>(-1514535)},
    {733, 29, static_cast<std::uint32_t>(-12676910)},
};
constexpr DesignPoint kSmallCliff[] = {
    {69, 29, static_cast<std::uint32_t>(-462617)},
    {144, 63, static_cast<std::uint32_t>(-395286)},
    {333, 63, static_cast<std::uint32_t>(-198418)},
    {422, 87, static_cast<std::uint32_t>(-3369373)},
};
constexpr DesignPoint kLowSandcastle[] = {
    {70, -188, static_cast<std::uint32_t>(-399674)},
    {121, -210, static_cast<std::uint32_t>(-268857)},
    {198, -171, static_cast<std::uint32_t>(-1720697)},
    {225, 2, static_cast<std::uint32_t>(-4088705)},
};
constexpr DesignPoint kHighSandcastle[] = {
    {44, -216, static_cast<std::uint32_t>(-1854336)},
    {141, -413, static_cast<std::uint32_t>(-4042089)},
    {210, -180, static_cast<std::uint32_t>(-993359)},
    {231, -24, static_cast<std::uint32_t>(-4355479)},
};
constexpr DesignPoint kAnchor[] = {
    {68, 22, static_cast<std::uint32_t>(-9666401)},
    {150, -18, static_cast<std::uint32_t>(-2235925)},
    {50, 78, static_cast<std::uint32_t>(-8881018)},
    {116, 50, static_cast<std::uint32_t>(-11382703)},
    {11, -55, static_cast<std::uint32_t>(-5539265)},
};
constexpr DesignPoint kRevive[] = {
    {71, 42, static_cast<std::uint32_t>(-5918)},
    {176, 121, static_cast<std::uint32_t>(-117170)},
    {337, 68, static_cast<std::uint32_t>(-237986)},
    {300, -46, static_cast<std::uint32_t>(-3595)},
    {380, 75, static_cast<std::uint32_t>(-34736)},
};

constexpr std::array<PatternSpec, 6> kPatterns{{
    {SoyObstacleKind::LARGE_CLIFF, static_cast<std::uint32_t>(-14134134), kLargeCliff, 6, SoyActionType::DOUBLE_JUMP},
    {SoyObstacleKind::SMALL_CLIFF, static_cast<std::uint32_t>(-13605719), kSmallCliff, 4, SoyActionType::JUMP},
    {SoyObstacleKind::LOW_SANDCASTLE, static_cast<std::uint32_t>(-400963), kLowSandcastle, 4, SoyActionType::JUMP},
    {SoyObstacleKind::HIGH_SANDCASTLE, static_cast<std::uint32_t>(-465469), kHighSandcastle, 4, SoyActionType::DOUBLE_JUMP},
    {SoyObstacleKind::ANCHOR, static_cast<std::uint32_t>(-4603180), kAnchor, 5, SoyActionType::SLIDE},
    {SoyObstacleKind::REVIVE, static_cast<std::uint32_t>(-51345), kRevive, 5, SoyActionType::REVIVE},
}};

[[nodiscard]] constexpr Rgb rgb_from_argb(std::uint32_t argb) noexcept {
    return Rgb{
        static_cast<std::uint8_t>((argb >> 16u) & 0xffu),
        static_cast<std::uint8_t>((argb >> 8u) & 0xffu),
        static_cast<std::uint8_t>(argb & 0xffu),
    };
}

// JavaScript Math.round(x): floor(x + 0.5), including negative values.
[[nodiscard]] int js_round_scaled(int value, int dimension, int design_dimension) noexcept {
    const double scaled = static_cast<double>(value) * static_cast<double>(dimension) /
        static_cast<double>(design_dimension);
    return static_cast<int>(std::floor(scaled + 0.5));
}

[[nodiscard]] int ceil_permille(int dimension, int permille) noexcept {
    if (dimension <= 0 || permille <= 0) return 0;
    return (dimension * permille + 999) / 1000;
}

[[nodiscard]] bool channel_box_match(Rgb value, Rgb expected, int threshold) noexcept {
    return std::abs(static_cast<int>(value.r) - static_cast<int>(expected.r)) <= threshold &&
           std::abs(static_cast<int>(value.g) - static_cast<int>(expected.g)) <= threshold &&
           std::abs(static_cast<int>(value.b) - static_cast<int>(expected.b)) <= threshold;
}

[[nodiscard]] bool mean_l1_match(Rgb value, Rgb expected, int threshold) noexcept {
    const int sum = std::abs(static_cast<int>(value.r) - static_cast<int>(expected.r)) +
        std::abs(static_cast<int>(value.g) - static_cast<int>(expected.g)) +
        std::abs(static_cast<int>(value.b) - static_cast<int>(expected.b));
    return sum <= threshold * 3;
}

[[nodiscard]] bool verify_match(Rgb value, Rgb expected, int threshold, VerifyMetric metric) noexcept {
    return metric == VerifyMetric::MEAN_L1
        ? mean_l1_match(value, expected, threshold)
        : channel_box_match(value, expected, threshold);
}

[[nodiscard]] std::uint16_t rgb15_code(Rgb value) noexcept {
    return static_cast<std::uint16_t>(
        ((static_cast<std::uint16_t>(value.r) >> 3u) << 10u) |
        ((static_cast<std::uint16_t>(value.g) >> 3u) << 5u) |
        (static_cast<std::uint16_t>(value.b) >> 3u));
}

[[nodiscard]] bool bucket_intersects(int bucket, int target, int threshold) noexcept {
    const int low = bucket * 8;
    const int high = std::min(255, low + 7);
    const int target_low = std::max(0, target - threshold);
    const int target_high = std::min(255, target + threshold);
    return high >= target_low && low <= target_high;
}

struct PixelBounds {
    int left;
    int top;
    int right;   // exclusive
    int bottom;  // exclusive
};

[[nodiscard]] PixelBounds pattern_bounds(
    const PatternSpec& pattern,
    int anchor_x,
    int anchor_y,
    int width,
    int height,
    int margin_permille) noexcept {
    int min_x = anchor_x;
    int max_x = anchor_x;
    int min_y = anchor_y;
    int max_y = anchor_y;
    for (std::uint8_t i = 0; i < pattern.point_count; ++i) {
        const int x = anchor_x + js_round_scaled(pattern.points[i].dx, width, kBaseWidth);
        const int y = anchor_y + js_round_scaled(pattern.points[i].dy, height, kBaseHeight);
        min_x = std::min(min_x, x);
        max_x = std::max(max_x, x);
        min_y = std::min(min_y, y);
        max_y = std::max(max_y, y);
    }
    const int mx = ceil_permille(width, margin_permille);
    const int my = ceil_permille(height, margin_permille);
    return PixelBounds{
        std::clamp(min_x - mx, 0, width),
        std::clamp(min_y - my, 0, height),
        std::clamp(max_x + mx + 1, 0, width),
        std::clamp(max_y + my + 1, 0, height),
    };
}

[[nodiscard]] RatioRect normalized_bounds(PixelBounds box, int width, int height) noexcept {
    const float fw = static_cast<float>(std::max(1, width));
    const float fh = static_cast<float>(std::max(1, height));
    return RatioRect{
        box.left / fw,
        box.top / fh,
        box.right / fw,
        box.bottom / fh,
    };
}

[[nodiscard]] bool verify_pattern(
    const ArgbFrameView& frame,
    const PatternSpec& pattern,
    int anchor_x,
    int anchor_y,
    int threshold,
    VerifyMetric metric) noexcept {
    for (std::uint8_t i = 0; i < pattern.point_count; ++i) {
        const DesignPoint& point = pattern.points[i];
        const int x = anchor_x + js_round_scaled(point.dx, frame.width, kBaseWidth);
        const int y = anchor_y + js_round_scaled(point.dy, frame.height, kBaseHeight);
        if (x < 0 || x >= frame.width || y < 0 || y >= frame.height) return false;
        const Rgb actual = rgb_from_argb(
            frame.pixels[static_cast<std::size_t>(y) * frame.row_stride_pixels + x]);
        if (!verify_match(actual, rgb_from_argb(point.argb), threshold, metric)) return false;
    }
    return true;
}

[[nodiscard]] SoyActionType action_for(const PatternSpec& pattern) noexcept {
    return pattern.action;
}

[[nodiscard]] std::size_t pattern_index_for_kind(SoyObstacleKind kind) noexcept {
    const std::size_t index = static_cast<std::size_t>(kind);
    return index < kPatterns.size() ? index : kPatterns.size();
}

}  // namespace

bool ArgbFrameView::valid() const noexcept {
    return pixels != nullptr && width > 0 && height > 0 && row_stride_pixels >= width;
}

SoyDetection materialize_soy_detection(
    SoyObstacleKind kind,
    int anchor_x,
    int anchor_y,
    int frame_width,
    int frame_height,
    const SoySauceConfig& config) noexcept {
    SoyDetection output{};
    if (frame_width <= 0 || frame_height <= 0) return output;
    const std::size_t selected = pattern_index_for_kind(kind);
    if (selected >= kPatterns.size()) return output;
    const PatternSpec& pattern = kPatterns[selected];
    const PixelBounds visual_px = pattern_bounds(
        pattern, anchor_x, anchor_y, frame_width, frame_height, config.visual_margin_permille);

    output.found = true;
    output.kind = pattern.kind;
    output.action = action_for(pattern);
    output.anchor_x = anchor_x;
    output.anchor_y = anchor_y;
    output.anchor_x_ratio = static_cast<float>(anchor_x) / static_cast<float>(frame_width);
    output.anchor_y_ratio = static_cast<float>(anchor_y) / static_cast<float>(frame_height);
    output.visual_bounds = normalized_bounds(visual_px, frame_width, frame_height);

    bool triggered = pattern.kind == SoyObstacleKind::REVIVE;
    if (selected < config.trigger_design_px.size()) {
        const int player_offset = js_round_scaled(
            config.fixed_player_offset_design_px, frame_width, kBaseWidth);
        const int trigger = js_round_scaled(
            config.trigger_design_px[selected], frame_width, kBaseWidth);
        triggered = anchor_x - player_offset < trigger;
    }
    output.action_triggered = triggered;
    if (!triggered) output.action = SoyActionType::NONE;

    const int edge_margin = ceil_permille(frame_width, 5);
    const PixelBounds action_px{
        std::clamp(anchor_x - edge_margin, 0, frame_width),
        visual_px.top,
        std::clamp(anchor_x + edge_margin + 1, 0, frame_width),
        visual_px.bottom,
    };
    output.action_bounds = normalized_bounds(action_px, frame_width, frame_height);

    if (pattern.kind == SoyObstacleKind::ANCHOR) {
        const int dy = js_round_scaled(kSlideDesignDy, frame_height, kBaseHeight);
        const int inset = js_round_scaled(kSlideBottomInsetDesign, frame_height, kBaseHeight);
        output.swipe_end_y = std::min(anchor_y + dy, std::max(0, frame_height - inset));
    } else {
        output.swipe_end_y = anchor_y;
    }
    return output;
}

SoySauceExactEngine::SoySauceExactEngine(SoySauceConfig config) noexcept : config_(config) {
    set_config(config);
}

void SoySauceExactEngine::set_config(SoySauceConfig config) noexcept {
    config.threshold = std::min<std::uint8_t>(config.threshold, 255);
    config.visual_margin_permille = std::min<std::uint16_t>(config.visual_margin_permille, 100);
    config_ = config;
    rebuild_lut();
}

void SoySauceExactEngine::rebuild_lut() noexcept {
    anchor_lut_.fill(0);
    const int threshold = config_.threshold;
    for (int r5 = 0; r5 < 32; ++r5) {
        for (int g5 = 0; g5 < 32; ++g5) {
            for (int b5 = 0; b5 < 32; ++b5) {
                std::uint8_t bits = 0;
                for (std::size_t i = 0; i < kPatterns.size(); ++i) {
                    const Rgb c = rgb_from_argb(kPatterns[i].anchor_argb);
                    if (bucket_intersects(r5, c.r, threshold) &&
                        bucket_intersects(g5, c.g, threshold) &&
                        bucket_intersects(b5, c.b, threshold)) {
                        bits |= static_cast<std::uint8_t>(1u << i);
                    }
                }
                anchor_lut_[static_cast<std::size_t>((r5 << 10) | (g5 << 5) | b5)] = bits;
            }
        }
    }
}

SoyDetection SoySauceExactEngine::detect(const ArgbFrameView& frame) const noexcept {
    SoyDetection output{};
    if (!frame.valid()) return output;

    const int unscaled_x = js_round_scaled(kSearchX, frame.width, kBaseWidth);
    const int region_x = std::clamp(unscaled_x - kSearchPaddingLeft, 0, frame.width);
    const int region_y = std::clamp(js_round_scaled(kSearchY, frame.height, kBaseHeight), 0, frame.height);
    const int region_w = std::max(0, js_round_scaled(kSearchW, frame.width, kBaseWidth));
    const int region_h = std::max(0, js_round_scaled(kSearchH, frame.height, kBaseHeight));
    const int region_right = std::clamp(unscaled_x + region_w, 0, frame.width);
    const int region_bottom = std::clamp(region_y + region_h, 0, frame.height);
    if (region_x >= region_right || region_y >= region_bottom) return output;

    std::array<int, 6> hit_x{};
    std::array<int, 6> hit_y{};
    std::uint8_t found_bits = 0;
    constexpr std::uint8_t kAllBits = (1u << 6u) - 1u;

    for (int y = region_y; y < region_bottom; ++y) {
        const std::uint32_t* row = frame.pixels + static_cast<std::size_t>(y) * frame.row_stride_pixels;
        for (int x = region_x; x < region_right; ++x) {
            const Rgb actual = rgb_from_argb(row[x]);
            std::uint8_t candidates = static_cast<std::uint8_t>(anchor_lut_[rgb15_code(actual)] &
                static_cast<std::uint8_t>(kAllBits & ~found_bits));
            while (candidates != 0) {
                const unsigned index = static_cast<unsigned>(__builtin_ctz(static_cast<unsigned>(candidates)));
                const std::uint8_t bit = static_cast<std::uint8_t>(1u << index);
                candidates = static_cast<std::uint8_t>(candidates & ~bit);
                const PatternSpec& pattern = kPatterns[index];
                if (!channel_box_match(actual, rgb_from_argb(pattern.anchor_argb), config_.threshold)) continue;
                if (!verify_pattern(frame, pattern, x, y, config_.threshold, config_.verification_metric)) continue;
                found_bits = static_cast<std::uint8_t>(found_bits | bit);
                hit_x[index] = x;
                hit_y[index] = y;
                // LARGE_CLIFF is first in the source object's insertion order. Its first
                // valid row-major hit is the final result, so no later pixels can matter.
                if (index == 0u) goto scan_complete;
            }
        }
    }

scan_complete:
    if (found_bits == 0) return output;

    std::size_t selected = 0;
    while ((found_bits & static_cast<std::uint8_t>(1u << selected)) == 0) ++selected;
    return materialize_soy_detection(
        kPatterns[selected].kind,
        hit_x[selected],
        hit_y[selected],
        frame.width,
        frame.height,
        config_);
}

}  // namespace hzzs::vision_v3

extern "C" {

void* hzzs_soy_create(const HzzsSoyConfigC* config) noexcept {
    hzzs::vision_v3::SoySauceConfig native{};
    if (config != nullptr) {
        native.threshold = config->threshold;
        native.verification_metric = config->verification_metric == 1
            ? hzzs::vision_v3::VerifyMetric::MEAN_L1
            : hzzs::vision_v3::VerifyMetric::BOX_PER_CHANNEL;
        native.visual_margin_permille = config->visual_margin_permille;
    }
    return new (std::nothrow) hzzs::vision_v3::SoySauceExactEngine(native);
}

void hzzs_soy_destroy(void* handle) noexcept {
    delete static_cast<hzzs::vision_v3::SoySauceExactEngine*>(handle);
}

int hzzs_soy_detect(
    void* handle,
    const std::uint32_t* argb,
    int width,
    int height,
    int row_stride_pixels,
    HzzsSoyResultC* output) noexcept {
    if (handle == nullptr || output == nullptr) return 0;
    const auto result = static_cast<hzzs::vision_v3::SoySauceExactEngine*>(handle)->detect(
        hzzs::vision_v3::ArgbFrameView{argb, width, height, row_stride_pixels});
    output->found = result.found ? 1 : 0;
    output->action_triggered = result.action_triggered ? 1 : 0;
    output->kind = static_cast<std::uint8_t>(result.kind);
    output->action = static_cast<std::uint8_t>(result.action);
    output->anchor_x = result.anchor_x;
    output->anchor_y = result.anchor_y;
    output->swipe_end_y = result.swipe_end_y;
    output->visual_left = result.visual_bounds.left;
    output->visual_top = result.visual_bounds.top;
    output->visual_right = result.visual_bounds.right;
    output->visual_bottom = result.visual_bounds.bottom;
    output->action_left = result.action_bounds.left;
    output->action_top = result.action_bounds.top;
    output->action_right = result.action_bounds.right;
    output->action_bottom = result.action_bounds.bottom;
    return 1;
}

}
