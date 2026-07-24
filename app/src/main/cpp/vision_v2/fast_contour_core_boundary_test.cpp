#include "fast_contour_core.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <span>

using namespace hzzs::vision_v2;

namespace {

void test_rgba_view_invalid() {
    RgbaView null_view{};
    assert(!null_view.valid());
    assert(null_view.rgb_at_clamped(0, 0).r == 0);

    std::array<std::uint8_t, 16> pixels{};
    RgbaView zero_size{pixels.data(), 0, 1, 4};
    assert(!zero_size.valid());

    RgbaView bad_stride{pixels.data(), 2, 2, 4};  // needs >= 8
    assert(!bad_stride.valid());
}

void test_lut_rejects_out_of_range_class() {
    Rgb15Lut lut;
    lut.clear();
    assert(!lut.add_class_color(Rgb15Lut::kMaxClasses, Rgb{1, 2, 3}, 1));
    assert(lut.add_class_color(0, Rgb{10, 20, 30}, 4));
}

void test_verify_sparse_rejects_bad_input() {
    std::array<std::uint8_t, 4 * 4 * 4> pixels{};
    const RgbaView image{pixels.data(), 4, 4, 16};
    const std::array points{PatternPoint{0, 0, Rgb{1, 2, 3}}};
    const SparsePattern pattern{0, 1, points};

    assert(!verify_sparse_pattern({}, 0, 0, pattern, 1, 0).matched);
    assert(!verify_sparse_pattern(image, 0, 0, SparsePattern{}, 1, 0).matched);

    const SparsePattern need_too_many{0, 2, points};
    assert(!verify_sparse_pattern(image, 0, 0, need_too_many, 1, 0).matched);

    assert(!verify_sparse_pattern(image, 0, 0, pattern, 1, -1).matched);
}

void test_contour_arena_capacity() {
    ContourArena<4> arena;
    const std::array three{
        PointF{0, 0}, PointF{1, 0}, PointF{1, 1},
    };
    std::uint16_t offset = 99;
    assert(arena.append(three, offset));
    assert(offset == 0);
    assert(arena.size() == 3);

    const std::array two{PointF{2, 2}, PointF{3, 3}};
    assert(!arena.append(two, offset));  // only 1 slot left
    assert(arena.size() == 3);

    assert(arena.append(std::array{PointF{9, 9}}, offset));
    assert(offset == 3);
    assert(arena.size() == 4);

    arena.clear();
    assert(arena.size() == 0);
}

void test_refine_contour_rejects_bad_input() {
    std::array<std::uint8_t, 8 * 8 * 4> pixels{};
    for (std::size_t i = 0; i < pixels.size(); i += 4) {
        pixels[i + 3] = 255;
    }
    const RgbaView image{pixels.data(), 8, 8, 32};
    const std::array contour{
        PointF{2, 2}, PointF{5, 2}, PointF{5, 5}, PointF{2, 5},
    };
    std::array<PointF, 4> out{};

    assert(refine_contour_rgb({}, contour, out, 1, 1.0F, 1.0F) == 0);
    assert(refine_contour_rgb(
        image, std::span<const PointF>(contour).first(2), out, 1, 1.0F, 1.0F) == 0);

    std::array<PointF, 2> tiny_out{};
    assert(refine_contour_rgb(image, contour, tiny_out, 1, 1.0F, 1.0F) == 0);

    assert(refine_contour_rgb(image, contour, out, -1, 1.0F, 1.0F) == 0);
    assert(refine_contour_rgb(image, contour, out, 1, -1.0F, 1.0F) == 0);
    assert(refine_contour_rgb(image, contour, out, 1, 1.0F, -1.0F) == 0);

    assert(refine_contour_rgb(image, contour, out, 1, 1.0F, 1.0F) == contour.size());
}

}  // namespace

int main() {
    test_rgba_view_invalid();
    test_lut_rejects_out_of_range_class();
    test_verify_sparse_rejects_bad_input();
    test_contour_arena_capacity();
    test_refine_contour_rejects_bad_input();
    return 0;
}
