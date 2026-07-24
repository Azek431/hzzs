#include "fast_contour_core.h"

#include <array>
#include <cassert>
#include <cstdint>

using namespace hzzs::vision_v2;

int main() {
    Rgb15Lut lut;
    lut.clear();
    assert(lut.add_class_color(2, Rgb{100, 150, 200}, 8));
    assert((lut.lookup(Rgb{102, 151, 198}) & (1u << 2u)) != 0);
    assert((lut.lookup(Rgb{220, 10, 10}) & (1u << 2u)) == 0);
    assert(!lut.add_class_color(Rgb15Lut::kMaxClasses, Rgb{}, 1));

    constexpr int width = 16;
    constexpr int height = 16;
    std::array<std::uint8_t, width * height * 4> pixels{};
    for (int i = 0; i < width * height; ++i) {
        pixels[i * 4 + 3] = 255;
    }
    auto set_rgb = [&](int x, int y, Rgb value) {
        const int offset = (y * width + x) * 4;
        pixels[offset] = value.r;
        pixels[offset + 1] = value.g;
        pixels[offset + 2] = value.b;
    };
    set_rgb(5, 5, Rgb{10, 20, 30});
    set_rgb(8, 5, Rgb{40, 50, 60});
    set_rgb(5, 9, Rgb{70, 80, 90});

    const RgbaView image{pixels.data(), width, height, width * 4};
    const std::array points{
        PatternPoint{0, 0, Rgb{10, 20, 30}},
        PatternPoint{3, 0, Rgb{40, 50, 60}},
        PatternPoint{0, 4, Rgb{70, 80, 90}},
    };
    const SparsePattern pattern{1, 3, points};
    const MatchResult matched = verify_sparse_pattern(image, 5, 5, pattern, 3, 0);
    assert(matched.matched);
    assert(matched.matched_points == 3);
    assert(matched.score > 0.9F);

    ContourArena<8> arena;
    const std::array contour{
        PointF{3.0F, 3.0F}, PointF{12.0F, 3.0F},
        PointF{12.0F, 12.0F}, PointF{3.0F, 12.0F},
    };
    std::uint16_t offset = 99;
    assert(arena.append(contour, offset));
    assert(offset == 0);
    assert(arena.size() == contour.size());

    std::array<PointF, 4> refined{};
    assert(refine_contour_rgb(image, contour, refined, 2, 1.0F, 2.0F) == contour.size());
    return 0;
}
