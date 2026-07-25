#include "sea_salt_fast.h"

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
constexpr int kSearchWidth = 982;
constexpr int kSearchHeight = 1229;

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

struct FastSpec {
    SoyObstacleKind kind;
    std::uint32_t anchor_argb;
    const DesignPoint* points;
    std::uint8_t point_count;
    std::uint8_t scan_point_index;  // 0=anchor, otherwise 1+points index
    std::int16_t scan_dx;
    std::int16_t scan_dy;
    std::uint32_t scan_argb;
    float expected_scan_y_ratio;
    float scan_y_tolerance_ratio;
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


// Scan points are deliberately selected from the original anchor + relative points.
// They are rare and vertically stable on the current 114-frame sea-salt corpus.
constexpr std::array<FastSpec, 5> kSpecs{{
    {
        SoyObstacleKind::LARGE_CLIFF,
        static_cast<std::uint32_t>(-14134134),
        kLargeCliff,
        6,
        0,
        0,
        0,
        static_cast<std::uint32_t>(-14134134),
        0.6340F,
        0.012F,
    },
    {
        SoyObstacleKind::SMALL_CLIFF,
        static_cast<std::uint32_t>(-13605719),
        kSmallCliff,
        4,
        0,
        0,
        0,
        static_cast<std::uint32_t>(-13605719),
        0.6343F,
        0.012F,
    },
    {
        SoyObstacleKind::LOW_SANDCASTLE,
        static_cast<std::uint32_t>(-400963),
        kLowSandcastle,
        4,
        4,
        225,
        2,
        static_cast<std::uint32_t>(-4088705),
        0.62717F,
        0.012F,
    },
    {
        SoyObstacleKind::HIGH_SANDCASTLE,
        static_cast<std::uint32_t>(-465469),
        kHighSandcastle,
        4,
        2,
        141,
        -413,
        static_cast<std::uint32_t>(-4042089),
        0.4774F,
        0.014F,
    },
    {
        SoyObstacleKind::ANCHOR,
        static_cast<std::uint32_t>(-4603180),
        kAnchor,
        5,
        4,
        116,
        50,
        static_cast<std::uint32_t>(-11382703),
        0.50515F,
        0.015F,
    },
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

[[nodiscard]] bool channel_box_match(Rgb value, Rgb expected, int threshold) noexcept {
    return std::abs(static_cast<int>(value.r) - static_cast<int>(expected.r)) <= threshold &&
        std::abs(static_cast<int>(value.g) - static_cast<int>(expected.g)) <= threshold &&
        std::abs(static_cast<int>(value.b) - static_cast<int>(expected.b)) <= threshold;
}

[[nodiscard]] bool verify_match(
    Rgb value,
    Rgb expected,
    int threshold,
    VerifyMetric metric) noexcept {
    if (metric == VerifyMetric::BOX_PER_CHANNEL) {
        return channel_box_match(value, expected, threshold);
    }
    const int sum = std::abs(static_cast<int>(value.r) - static_cast<int>(expected.r)) +
        std::abs(static_cast<int>(value.g) - static_cast<int>(expected.g)) +
        std::abs(static_cast<int>(value.b) - static_cast<int>(expected.b));
    return sum <= threshold * 3;
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

[[nodiscard]] int max_channel_distance(Rgb value, Rgb expected) noexcept {
    return std::max({
        std::abs(static_cast<int>(value.r) - static_cast<int>(expected.r)),
        std::abs(static_cast<int>(value.g) - static_cast<int>(expected.g)),
        std::abs(static_cast<int>(value.b) - static_cast<int>(expected.b)),
    });
}



[[nodiscard]] bool verify_neighborhood(
    const ArgbFrameView& frame,
    int center_x,
    int center_y,
    Rgb expected,
    int threshold,
    VerifyMetric metric,
    int radius) noexcept {
    const int safe_radius = std::max(0, radius);
    for (int y = std::max(0, center_y - safe_radius);
         y <= std::min(frame.height - 1, center_y + safe_radius);
         ++y) {
        for (int x = std::max(0, center_x - safe_radius);
             x <= std::min(frame.width - 1, center_x + safe_radius);
             ++x) {
            const Rgb actual = rgb_from_argb(
                frame.pixels[static_cast<std::size_t>(y) * frame.row_stride_pixels + x]);
            if (verify_match(actual, expected, threshold, metric)) {
                return true;
            }
        }
    }
    return false;
}

[[nodiscard]] DesignPoint point_with_anchor(const FastSpec& spec, std::uint8_t index) noexcept {
    if (index == 0u) {
        return DesignPoint{0, 0, spec.anchor_argb};
    }
    return spec.points[index - 1u];
}

[[nodiscard]] bool verify_from_scan_point(
    const ArgbFrameView& frame,
    const FastSpec& spec,
    int scan_x,
    int scan_y,
    int threshold,
    VerifyMetric metric,
    int neighborhood_radius) noexcept {
    const std::uint8_t all_point_count = static_cast<std::uint8_t>(spec.point_count + 1u);
    for (std::uint8_t index = 0; index < all_point_count; ++index) {
        if (index == spec.scan_point_index) {
            continue;
        }
        const DesignPoint point = point_with_anchor(spec, index);
        const int x = scan_x +
            js_round_scaled(point.dx, frame.width, kBaseWidth) -
            js_round_scaled(spec.scan_dx, frame.width, kBaseWidth);
        const int y = scan_y +
            js_round_scaled(point.dy, frame.height, kBaseHeight) -
            js_round_scaled(spec.scan_dy, frame.height, kBaseHeight);
        if (x < 0 || x >= frame.width || y < 0 || y >= frame.height) {
            return false;
        }
        if (!verify_neighborhood(
                frame,
                x,
                y,
                rgb_from_argb(point.argb),
                threshold,
                metric,
                neighborhood_radius)) {
            return false;
        }
    }
    return true;
}


[[nodiscard]] bool verify_from_anchor(
    const ArgbFrameView& frame,
    const FastSpec& spec,
    int anchor_x,
    int anchor_y,
    int threshold,
    VerifyMetric metric,
    int neighborhood_radius) noexcept {
    const Rgb anchor_actual = rgb_from_argb(
        frame.pixels[static_cast<std::size_t>(anchor_y) * frame.row_stride_pixels + anchor_x]);
    if (!channel_box_match(anchor_actual, rgb_from_argb(spec.anchor_argb), threshold)) {
        return false;
    }
    for (std::uint8_t index = 0; index < spec.point_count; ++index) {
        const DesignPoint point = spec.points[index];
        const int x = anchor_x + js_round_scaled(point.dx, frame.width, kBaseWidth);
        const int y = anchor_y + js_round_scaled(point.dy, frame.height, kBaseHeight);
        if (x < 0 || x >= frame.width || y < 0 || y >= frame.height) {
            return false;
        }
        if (!verify_neighborhood(
                frame,
                x,
                y,
                rgb_from_argb(point.argb),
                threshold,
                metric,
                neighborhood_radius)) {
            return false;
        }
    }
    return true;
}

[[nodiscard]] bool recover_anchor(
    const ArgbFrameView& frame,
    const FastSpec& spec,
    int scan_x,
    int scan_y,
    int threshold,
    VerifyMetric metric,
    bool require_exact_pattern,
    int radius_x,
    int radius_y,
    int minimum_x,
    int maximum_x,
    int minimum_y,
    int maximum_y,
    int& output_x,
    int& output_y) noexcept {
    const int expected_x = scan_x - js_round_scaled(spec.scan_dx, frame.width, kBaseWidth);
    const int expected_y = scan_y - js_round_scaled(spec.scan_dy, frame.height, kBaseHeight);
    const Rgb expected_color = rgb_from_argb(spec.anchor_argb);

    // Compatibility-first cascade: when the original exact template is present, recover
    // the same row-major anchor before using tolerant geometry. This keeps action edges
    // stable while still allowing the fast detector to cover frames the source misses.
    const int strict_threshold = std::min(threshold, 10);
    for (int y = std::max(minimum_y, expected_y - radius_y);
         y <= std::min(maximum_y, expected_y + radius_y);
         ++y) {
        for (int x = std::max(minimum_x, expected_x - radius_x);
             x <= std::min(maximum_x, expected_x + radius_x);
             ++x) {
            if (verify_from_anchor(
                    frame, spec, x, y, strict_threshold, metric, 0)) {
                output_x = x;
                output_y = y;
                return true;
            }
        }
    }

    if (require_exact_pattern) {
        return false;
    }

    int best_color_distance = 256;
    int best_position_distance = std::numeric_limits<int>::max();
    bool found = false;

    for (int y = std::max(minimum_y, expected_y - radius_y);
         y <= std::min(maximum_y, expected_y + radius_y);
         ++y) {
        for (int x = std::max(minimum_x, expected_x - radius_x);
             x <= std::min(maximum_x, expected_x + radius_x);
             ++x) {
            const Rgb actual = rgb_from_argb(
                frame.pixels[static_cast<std::size_t>(y) * frame.row_stride_pixels + x]);
            const int color_distance = max_channel_distance(actual, expected_color);
            if (color_distance > threshold) {
                continue;
            }
            const int dx = x - expected_x;
            const int dy = y - expected_y;
            const int position_distance = dx * dx + dy * dy;
            if (position_distance < best_position_distance ||
                (position_distance == best_position_distance &&
                 color_distance < best_color_distance)) {
                best_position_distance = position_distance;
                best_color_distance = color_distance;
                output_x = x;
                output_y = y;
                found = true;
            }
        }
    }
    return found;
}

}  // namespace

SeaSaltFastEngine::SeaSaltFastEngine(SeaFastConfig config) noexcept {
    set_config(config);
}

void SeaSaltFastEngine::set_config(SeaFastConfig config) noexcept {
    config.horizontal_samples = std::clamp<std::uint16_t>(config.horizontal_samples, 64, 1024);
    config.neighborhood_radius = std::min<std::uint8_t>(config.neighborhood_radius, 3);
    config.minimum_scan_x_permille =
        std::min<std::uint16_t>(config.minimum_scan_x_permille, 1000);
    config_ = config;
    rebuild_lut();
}

void SeaSaltFastEngine::rebuild_lut() noexcept {
    scan_lut_.fill(0);
    for (int r = 0; r < 32; ++r) {
        for (int g = 0; g < 32; ++g) {
            for (int b = 0; b < 32; ++b) {
                std::uint8_t bits = 0;
                for (std::size_t i = 0; i < kSpecs.size(); ++i) {
                    const Rgb expected = rgb_from_argb(kSpecs[i].scan_argb);
                    if (bucket_intersects(r, expected.r, config_.anchor_threshold) &&
                        bucket_intersects(g, expected.g, config_.anchor_threshold) &&
                        bucket_intersects(b, expected.b, config_.anchor_threshold)) {
                        bits = static_cast<std::uint8_t>(bits | static_cast<std::uint8_t>(1u << i));
                    }
                }
                const std::size_t index = static_cast<std::size_t>((r << 10) | (g << 5) | b);
                scan_lut_[index] = bits;
            }
        }
    }
}

SeaFastHit SeaSaltFastEngine::detect(const ArgbFrameView& frame) const noexcept {
    SeaFastHit output{};
    if (!frame.valid()) {
        return output;
    }

    // Proportional integer grid: ceil(width / horizontal_samples).
    const int stride = std::max(
        1,
        (frame.width + static_cast<int>(config_.horizontal_samples) - 1) /
            static_cast<int>(config_.horizontal_samples));

    const int exact_x0 = std::clamp(
        js_round_scaled(kSearchX, frame.width, kBaseWidth), 0, frame.width);
    const int exact_y0 = std::clamp(
        js_round_scaled(kSearchY, frame.height, kBaseHeight), 0, frame.height);
    const int exact_x1 = std::clamp(
        exact_x0 + std::max(0, js_round_scaled(kSearchWidth, frame.width, kBaseWidth)),
        0,
        frame.width);
    const int exact_y1 = std::clamp(
        exact_y0 + std::max(0, js_round_scaled(kSearchHeight, frame.height, kBaseHeight)),
        0,
        frame.height);

    // Avoid UI-heavy left margin, but never narrow beyond the exact source search region.
    const int configured_scan_x0 = static_cast<int>(
        (static_cast<std::int64_t>(frame.width) * config_.minimum_scan_x_permille + 999) / 1000);
    const int scan_x0 = config_.enforce_source_anchor_region
        ? exact_x0
        : std::clamp(configured_scan_x0, 0, exact_x1);

    // Preserve source pattern priority exactly. Each class gets its own narrow scan band;
    // the first class with a valid row-major anchor wins and returns immediately.
    for (std::size_t spec_index = 0; spec_index < kSpecs.size(); ++spec_index) {
        const FastSpec& spec = kSpecs[spec_index];
        const std::uint8_t spec_bit = static_cast<std::uint8_t>(1u << spec_index);
        const int scan_y0 = std::clamp(
            static_cast<int>(std::floor(
                (spec.expected_scan_y_ratio - spec.scan_y_tolerance_ratio) *
                static_cast<float>(frame.height))),
            exact_y0,
            exact_y1);
        const int scan_y1 = std::clamp(
            static_cast<int>(std::ceil(
                (spec.expected_scan_y_ratio + spec.scan_y_tolerance_ratio) *
                static_cast<float>(frame.height))) +
                1,
            exact_y0,
            exact_y1);

        for (int y = scan_y0; y < scan_y1; y += stride) {
            const std::uint32_t* row =
                frame.pixels + static_cast<std::size_t>(y) * frame.row_stride_pixels;
            for (int x = scan_x0; x < exact_x1; x += stride) {
                if ((scan_lut_[rgb15_code(rgb_from_argb(row[x]))] & spec_bit) == 0u) {
                    continue;
                }

                // Recover and validate the rare scan pixel inside one proportional grid cell.
                // Color plateaus may contain many equal pixels; only a point whose full relative
                // geometry validates is accepted as the canonical scan point.
                int scan_pixel_x = -1;
                int scan_pixel_y = -1;
                const Rgb scan_expected = rgb_from_argb(spec.scan_argb);
                for (int yy = std::max(scan_y0, y - stride);
                     yy <= std::min(scan_y1 - 1, y + stride) && scan_pixel_x < 0;
                     ++yy) {
                    for (int xx = std::max(scan_x0, x - stride);
                         xx <= std::min(exact_x1 - 1, x + stride);
                         ++xx) {
                        const Rgb actual = rgb_from_argb(
                            frame.pixels[
                                static_cast<std::size_t>(yy) * frame.row_stride_pixels + xx]);
                        if (max_channel_distance(actual, scan_expected) > config_.anchor_threshold) {
                            continue;
                        }
                        if (verify_from_scan_point(
                                frame,
                                spec,
                                xx,
                                yy,
                                config_.verify_threshold,
                                config_.verification_metric,
                                config_.neighborhood_radius)) {
                            scan_pixel_x = xx;
                            scan_pixel_y = yy;
                            break;
                        }
                    }
                }
                if (scan_pixel_x < 0) {
                    continue;
                }

                const int anchor_radius_x = std::max(
                    4,
                    js_round_scaled(50, frame.width, kBaseWidth));
                const int anchor_radius_y = std::max(4, stride + 3);
                const int anchor_min_x = config_.enforce_source_anchor_region ? exact_x0 : 0;
                int anchor_x = 0;
                int anchor_y = 0;
                if (!recover_anchor(
                        frame,
                        spec,
                        scan_pixel_x,
                        scan_pixel_y,
                        config_.verify_threshold,
                        config_.verification_metric,
                        config_.require_exact_anchor_pattern,
                        anchor_radius_x,
                        anchor_radius_y,
                        anchor_min_x,
                        exact_x1 - 1,
                        exact_y0,
                        exact_y1 - 1,
                        anchor_x,
                        anchor_y)) {
                    continue;
                }
                return SeaFastHit{
                    true,
                    spec.kind,
                    anchor_x,
                    anchor_y,
                };
            }
        }
    }

    return output;
}

}  // namespace hzzs::vision_v3

extern "C" {

void* hzzs_sea_fast_create(const HzzsSeaFastConfigC* config) noexcept {
    hzzs::vision_v3::SeaFastConfig native{};
    if (config != nullptr) {
        native.anchor_threshold = config->anchor_threshold;
        native.verify_threshold = config->verify_threshold;
        native.verification_metric = config->verification_metric == 1u
            ? hzzs::vision_v3::VerifyMetric::MEAN_L1
            : hzzs::vision_v3::VerifyMetric::BOX_PER_CHANNEL;
        native.neighborhood_radius = config->neighborhood_radius;
        native.enforce_source_anchor_region = config->enforce_source_anchor_region != 0u;
        native.require_exact_anchor_pattern = config->require_exact_anchor_pattern != 0u;
        native.horizontal_samples = config->horizontal_samples;
        native.minimum_scan_x_permille = config->minimum_scan_x_permille;
    }
    return new (std::nothrow) hzzs::vision_v3::SeaSaltFastEngine(native);
}

void hzzs_sea_fast_destroy(void* handle) noexcept {
    delete static_cast<hzzs::vision_v3::SeaSaltFastEngine*>(handle);
}

int hzzs_sea_fast_detect(
    void* handle,
    const std::uint32_t* argb,
    int width,
    int height,
    int row_stride_pixels,
    HzzsSoyResultC* output) noexcept {
    if (handle == nullptr || output == nullptr) {
        return 0;
    }
    const hzzs::vision_v3::SeaFastHit result =
        static_cast<hzzs::vision_v3::SeaSaltFastEngine*>(handle)->detect(
            hzzs::vision_v3::ArgbFrameView{argb, width, height, row_stride_pixels});
    *output = {};
    output->found = result.found ? 1u : 0u;
    output->kind = static_cast<std::uint8_t>(result.kind);
    output->anchor_x = result.anchor_x;
    output->anchor_y = result.anchor_y;
    return 1;
}

}  // extern "C"
