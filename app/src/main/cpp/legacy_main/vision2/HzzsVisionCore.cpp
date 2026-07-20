#include "HzzsVisionCore.h"

#include <algorithm>
#include <array>
#include <cstring>

namespace hzzs::vision2 {
namespace {

constexpr int kMaxWorkWidth = 720;

inline int clampInt(int v, int lo, int hi) noexcept {
    return std::max(lo, std::min(v, hi));
}
inline int ratio(int value, int num, int den) noexcept {
    return (value * num + den / 2) / den;
}
inline int red(std::uint32_t c) noexcept { return static_cast<int>((c >> 16) & 0xffu); }
inline int green(std::uint32_t c) noexcept { return static_cast<int>((c >> 8) & 0xffu); }
inline int blue(std::uint32_t c) noexcept { return static_cast<int>(c & 0xffu); }
inline std::uint32_t pixel(const FrameView& f, int x, int y) noexcept {
    if (!f.pixels || x < 0 || y < 0 || x >= f.width || y >= f.height) return 0u;
    return f.pixels[y * f.stride + x];
}
inline bool isBottleGreen(std::uint32_t c) noexcept {
    const int r = red(c), g = green(c), b = blue(c);
    const int mx = std::max(r, std::max(g, b));
    const int mn = std::min(r, std::min(g, b));
    return g > 120 && g - r > 15 && g - b > 30 && mx - mn > 45;
}
inline bool isCakeOrange(std::uint32_t c) noexcept {
    const int r = red(c), g = green(c), b = blue(c);
    return r >= 215 && g >= 135 && g <= 235 && b <= 205 &&
           r - g >= 10 && r - g <= 125 && g - b >= 18;
}
inline bool isDarkMetal(std::uint32_t c) noexcept {
    const int r = red(c), g = green(c), b = blue(c);
    const int mx = std::max(r, std::max(g, b));
    const int mn = std::min(r, std::min(g, b));
    return mx >= 35 && mx <= 195 && mx - mn <= 65;
}

template <typename Predicate>
int rowRatioPermille(const FrameView& f, int left, int right, int y, int step,
                     Predicate predicate, int& samples) noexcept {
    int hit = 0, total = 0;
    step = clampInt(step, 1, 8);
    for (int x = left; x <= right; x += step) {
        ++total; ++samples;
        if (predicate(pixel(f, x, y))) ++hit;
    }
    return total > 0 ? hit * 1000 / total : 0;
}

Detection detectBottle(const FrameView& f, int playerRight, int playerWidth, int coarseStep) noexcept {
    Detection out{}; out.kind = kKindBottle;
    const int scanY = ratio(f.height, 894, 1536);
    const int scanStart = clampInt(playerRight + ratio(playerWidth, 15, 100), 0, f.width - 1);
    const int scanEnd = clampInt(f.width - 1 - std::max(8, ratio(f.width, 20, 576)), scanStart, f.width - 1);
    const int step = clampInt(coarseStep, 1, 4);
    const int mergeGap = std::max(1, ratio(playerWidth, 25, 100));
    const int minWidth = std::max(ratio(f.width, 20, 576), ratio(playerWidth, 25, 100));
    const int maxWidth = ratio(playerWidth, 150, 100);

    int segmentLeft = -1, segmentRight = -1, lastGreen = -100000;
    int bestLeft = -1, bestRight = -1;
    for (int x = scanStart; x <= scanEnd; x += step) {
        ++out.samples;
        if (!isBottleGreen(pixel(f, x, scanY))) continue;
        if (segmentLeft < 0 || x - lastGreen > mergeGap + step) {
            if (segmentLeft >= 0) {
                const int w = segmentRight - segmentLeft + step;
                if (w >= minWidth && w <= maxWidth && segmentLeft > playerRight) {
                    bestLeft = segmentLeft; bestRight = segmentRight + step - 1; break;
                }
            }
            segmentLeft = x;
        }
        segmentRight = x;
        lastGreen = x;
    }
    if (bestLeft < 0 && segmentLeft >= 0) {
        const int w = segmentRight - segmentLeft + step;
        int required = minWidth;
        if (segmentRight >= scanEnd - step * 2) required = std::max(8, ratio(playerWidth, 12, 100));
        if (w >= required && w <= maxWidth && segmentLeft > playerRight) {
            bestLeft = segmentLeft; bestRight = segmentRight + step - 1;
        }
    }
    if (bestLeft < 0) return out;

    int first = -1, last = -1;
    for (int x = std::max(scanStart, bestLeft - step * 2); x <= std::min(scanEnd, bestRight + step * 2); ++x) {
        ++out.samples;
        if (isBottleGreen(pixel(f, x, scanY))) { if (first < 0) first = x; last = x; }
    }
    if (first >= 0) { bestLeft = first; bestRight = last; }

    constexpr std::array<int, 5> offsets{-35, -18, 0, 18, 35};
    int supported = 0, ratioSum = 0;
    for (const int offset : offsets) {
        const int y = clampInt(scanY + ratio(f.height, offset, 1000), 0, f.height - 1);
        const int p = rowRatioPermille(f, bestLeft, bestRight, y, 2, isBottleGreen, out.samples);
        ratioSum += p;
        if (p >= 100) ++supported;
    }
    if (supported < 3) return out;

    int top = -1, bottom = -1;
    const int yStart = ratio(f.height, 44, 100), yEnd = ratio(f.height, 72, 100);
    int activeStart = -1, activeEnd = -1, bestDistance = 1000000;
    for (int y = yStart; y <= yEnd; y += 2) {
        const int p = rowRatioPermille(f, bestLeft, bestRight, y, 2, isBottleGreen, out.samples);
        if (p >= 80) {
            if (activeStart < 0) activeStart = y;
            activeEnd = y;
        } else if (activeStart >= 0) {
            const int distance = scanY < activeStart ? activeStart - scanY : (scanY > activeEnd ? scanY - activeEnd : 0);
            if (distance < bestDistance) { bestDistance = distance; top = activeStart; bottom = activeEnd + 1; }
            activeStart = activeEnd = -1;
        }
    }
    if (activeStart >= 0) {
        const int distance = scanY < activeStart ? activeStart - scanY : (scanY > activeEnd ? scanY - activeEnd : 0);
        if (distance < bestDistance) { top = activeStart; bottom = activeEnd + 1; }
    }
    if (top < 0) return out;

    const int padding = std::max(2, ratio(playerWidth, 65, 1000));
    out.found = 1;
    out.left = std::max(0, bestLeft - padding);
    out.right = std::min(f.width - 1, bestRight + padding);
    out.top = top; out.bottom = std::min(f.height - 1, bottom);
    out.centerX = (out.left + out.right) / 2; out.centerY = (out.top + out.bottom) / 2;
    out.edgeGapPx = std::max(0, out.left - playerRight);
    out.widthPx = out.right - out.left + 1; out.heightPx = out.bottom - out.top + 1;
    out.widthMilliP = out.widthPx * 1000 / std::max(1, playerWidth);
    out.heightMilliP = out.heightPx * 1000 / std::max(1, playerWidth);
    out.sizeClass = out.heightMilliP >= 1585 ? kSizeLargeOrWide : kSizeSmallOrNarrow;
    out.scorePermille = std::min(990, 550 + supported * 70 + std::min(250, (ratioSum / 5) / 4));
    return out;
}

Detection detectCake(const FrameView& f, int playerRight, int playerWidth, int coarseStep) noexcept {
    Detection out{}; out.kind = kKindCakeStructure;
    if (f.width > kMaxWorkWidth) return out;
    const int xStart = std::max(0, playerRight - ratio(f.width, 2, 100));
    const int xEnd = f.width - 1;
    const int yStart = ratio(f.height, 61, 100), yEnd = ratio(f.height, 96, 100);
    const int upperEnd = ratio(f.height, 72, 100);
    const int sx = clampInt(coarseStep, 1, 4), sy = clampInt(coarseStep, 1, 4);
    std::array<int, kMaxWorkWidth> xs{}, full{}, upper{};
    std::array<unsigned char, kMaxWorkWidth> active{};
    int cols = 0, fullRows = 0, upperRows = 0;
    for (int x = xStart; x <= xEnd && cols < kMaxWorkWidth; x += sx) xs[cols++] = x;
    for (int y = yStart; y < yEnd; y += sy) {
        ++fullRows; const bool isUpper = y < upperEnd; if (isUpper) ++upperRows;
        for (int i = 0; i < cols; ++i) {
            ++out.samples;
            if (isCakeOrange(pixel(f, xs[i], y))) { ++full[i]; if (isUpper) ++upper[i]; }
        }
    }
    for (int i = 0; i < cols; ++i) {
        const int fd = fullRows ? full[i] * 1000 / fullRows : 0;
        const int ud = upperRows ? upper[i] * 1000 / upperRows : 0;
        active[i] = static_cast<unsigned char>(fd >= 120 && ud >= 120);
    }
    const int mergeGap = std::max(sx, ratio(f.width, 12, 1000));
    const int minWidth = std::max(10, ratio(f.width, 5, 100));
    const int maxWidth = ratio(f.width, 90, 100);
    const int ahead = playerRight + ratio(f.width, 5, 100);
    int clusterStart = -1, clusterEnd = -1, previous = -100000;
    int bestLeft = -1, bestRight = -1;
    auto accept = [&](int a, int b) {
        if (a < 0 || b < a) return;
        const int left = xs[a], right = xs[b] + sx - 1, w = right - left + 1;
        if (w >= minWidth && w <= maxWidth && right > ahead &&
            (bestLeft < 0 || std::max(0, left - playerRight) < std::max(0, bestLeft - playerRight))) {
            bestLeft = left; bestRight = right;
        }
    };
    for (int i = 0; i < cols; ++i) {
        if (!active[i]) continue;
        if (clusterStart < 0 || xs[i] - previous > mergeGap + sx) {
            accept(clusterStart, clusterEnd); clusterStart = i;
        }
        clusterEnd = i; previous = xs[i];
    }
    accept(clusterStart, clusterEnd);
    if (bestLeft < 0) return out;

    int refinedLeft = bestLeft, refinedRight = bestRight; bool any = false;
    for (int x = std::max(xStart, bestLeft - sx * 2); x <= std::min(xEnd, bestRight + sx * 2); ++x) {
        int count = 0, rows = 0;
        for (int y = yStart; y < yEnd; y += sy) { ++rows; ++out.samples; if (isCakeOrange(pixel(f, x, y))) ++count; }
        if (rows && count * 1000 / rows >= 120) {
            if (!any) refinedLeft = x;
            refinedRight = x; any = true;
        }
    }
    if (any) { bestLeft = refinedLeft; bestRight = refinedRight; }

    int top = -1, bottom = -1;
    for (int y = yStart; y < yEnd; y += 2) {
        int hit = 0, total = 0;
        for (int x = bestLeft; x <= bestRight; x += sx) { ++total; ++out.samples; if (isCakeOrange(pixel(f, x, y))) ++hit; }
        if (total && hit * 1000 / total >= 50) { if (top < 0) top = y; bottom = y + 1; }
    }
    if (top < 0) { top = yStart; bottom = yEnd - 1; }
    out.found = 1; out.left = bestLeft; out.right = std::min(f.width - 1, bestRight);
    out.top = top; out.bottom = std::min(f.height - 1, bottom);
    out.centerX = (out.left + out.right) / 2; out.centerY = (out.top + out.bottom) / 2;
    out.edgeGapPx = std::max(0, out.left - playerRight);
    out.widthPx = out.right - out.left + 1; out.heightPx = out.bottom - out.top + 1;
    out.widthMilliP = out.widthPx * 1000 / std::max(1, playerWidth);
    out.heightMilliP = out.heightPx * 1000 / std::max(1, playerWidth);
    out.sizeClass = out.widthMilliP >= 3600 ? kSizeLargeOrWide : kSizeSmallOrNarrow;
    out.scorePermille = 940;
    return out;
}

Detection detectSpike(const FrameView& f, int playerRight, int playerWidth, int spikeStep) noexcept {
    Detection out{}; out.kind = kKindHangingSpike;
    if (f.width > kMaxWorkWidth) return out;
    const int leftScan = ratio(f.width, 28, 100), rightScan = ratio(f.width, 96, 100);
    const int topScan = ratio(f.height, 46, 100), bottomScan = ratio(f.height, 57, 100);
    const int sx = clampInt(spikeStep, 1, 3), sy = clampInt(spikeStep, 1, 3);
    std::array<int, kMaxWorkWidth> xs{}, count{}, density{};
    std::array<unsigned char, kMaxWorkWidth> active{};
    int cols = 0, rows = 0;
    for (int x = leftScan; x <= rightScan && cols < kMaxWorkWidth; x += sx) xs[cols++] = x;
    for (int y = topScan; y < bottomScan; y += sy) {
        ++rows;
        for (int i = 0; i < cols; ++i) { ++out.samples; if (isDarkMetal(pixel(f, xs[i], y))) ++count[i]; }
    }
    for (int i = 0; i < cols; ++i) {
        density[i] = rows ? count[i] * 1000 / rows : 0;
        active[i] = static_cast<unsigned char>(density[i] >= 150);
    }
    const int mergeGap = std::max(3, sx * 2), minWidth = std::max(3, ratio(f.width, 4, 576));
    const int maxWidth = ratio(f.width, 9, 100), behind = playerRight - ratio(f.width, 2, 100);
    int start = -1, end = -1, previous = -100000, bestLeft = -1, bestRight = -1, bestPeak = 0, bestMean = 0;
    auto accept = [&](int a, int b) {
        if (a < 0 || b < a || bestLeft >= 0) return;
        const int left = xs[a], right = xs[b] + sx - 1, w = right - left + 1;
        int peak = 0, sum = 0;
        for (int i = a; i <= b; ++i) { peak = std::max(peak, density[i]); sum += density[i]; }
        const int mean = sum / std::max(1, b - a + 1);
        if (w >= minWidth && w <= maxWidth && right > behind && peak >= 300) {
            bestLeft = left; bestRight = right; bestPeak = peak; bestMean = mean;
        }
    };
    for (int i = 0; i < cols; ++i) {
        if (!active[i]) continue;
        if (start < 0 || xs[i] - previous > mergeGap + sx) { accept(start, end); start = i; }
        end = i; previous = xs[i];
    }
    accept(start, end);
    if (bestLeft < 0) return out;

    const int paddingX = std::max(4, ratio(f.width, 12, 1000));
    int darkLeft = f.width, darkRight = -1, darkTop = f.height, darkBottom = -1;
    for (int y = topScan; y < bottomScan; ++y) {
        for (int x = std::max(leftScan, bestLeft - paddingX); x <= std::min(rightScan, bestRight + paddingX); ++x) {
            ++out.samples;
            if (isDarkMetal(pixel(f, x, y))) {
                darkLeft = std::min(darkLeft, x); darkRight = std::max(darkRight, x);
                darkTop = std::min(darkTop, y); darkBottom = std::max(darkBottom, y);
            }
        }
    }
    if (darkRight < 0) { darkLeft = bestLeft; darkRight = bestRight; darkTop = topScan; darkBottom = bottomScan - 1; }
    const int py = std::max(3, ratio(f.height, 8, 1000));
    out.found = 1; out.left = std::max(0, darkLeft - 2); out.right = std::min(f.width - 1, darkRight + 2);
    out.top = std::max(0, darkTop - py); out.bottom = std::min(f.height - 1, darkBottom + py);
    out.centerX = (out.left + out.right) / 2; out.centerY = (out.top + out.bottom) / 2;
    out.edgeGapPx = std::max(0, out.left - playerRight);
    out.widthPx = out.right - out.left + 1; out.heightPx = out.bottom - out.top + 1;
    out.widthMilliP = out.widthPx * 1000 / std::max(1, playerWidth);
    out.heightMilliP = out.heightPx * 1000 / std::max(1, playerWidth);
    out.sizeClass = kSizeHanging;
    out.scorePermille = std::min(990, 440 + std::min(340, bestPeak * 34 / 48) + std::min(210, bestMean * 21 / 28));
    return out;
}

void packDetection(const Detection& d, std::int32_t* out, int offset) noexcept {
    out[offset + 0] = d.found; out[offset + 1] = d.kind;
    out[offset + 2] = d.left; out[offset + 3] = d.top; out[offset + 4] = d.right; out[offset + 5] = d.bottom;
    out[offset + 6] = d.centerX; out[offset + 7] = d.centerY; out[offset + 8] = d.edgeGapPx;
    out[offset + 9] = d.widthPx; out[offset + 10] = d.heightPx;
    out[offset + 11] = d.widthMilliP; out[offset + 12] = d.heightMilliP;
    out[offset + 13] = d.sizeClass; out[offset + 14] = d.scorePermille; out[offset + 15] = d.samples;
}

} // namespace

AnalysisResult analyze(const FrameView& frame, int coarseStep, int spikeStep) noexcept {
    AnalysisResult result{};
    result.width = frame.width; result.height = frame.height;
    if (!frame.pixels || frame.width <= 0 || frame.height <= 0 || frame.stride < frame.width) return result;
    result.playerLeft = ratio(frame.width, 108, 691);
    result.playerRight = ratio(frame.width, 214, 691);
    result.playerCenterX = ratio(frame.width, 161, 691);
    result.playerCenterY = ratio(frame.height, 894, 1536);
    result.playerWidth = std::max(1, ratio(frame.width, 107, 691));
    result.bottle = detectBottle(frame, result.playerRight, result.playerWidth, coarseStep);
    result.cake = detectCake(frame, result.playerRight, result.playerWidth, coarseStep);
    result.spike = detectSpike(frame, result.playerRight, result.playerWidth, spikeStep);
    result.totalSamples = result.bottle.samples + result.cake.samples + result.spike.samples;
    int bestGap = 1000000;
    for (const Detection* d : {&result.bottle, &result.cake, &result.spike}) {
        if (d->found && d->edgeGapPx < bestGap) { bestGap = d->edgeGapPx; result.primaryKind = d->kind; }
    }
    return result;
}

int pack(const AnalysisResult& r, std::int32_t* out, int capacity) noexcept {
    if (!out || capacity < kResultInts) return 0;
    std::memset(out, 0, sizeof(std::int32_t) * kResultInts);
    out[0] = kResultVersion; out[1] = r.width; out[2] = r.height;
    out[3] = r.playerLeft; out[4] = r.playerRight; out[5] = r.playerCenterX;
    out[6] = r.playerCenterY; out[7] = r.playerWidth; out[8] = r.primaryKind; out[9] = r.totalSamples;
    packDetection(r.bottle, out, 12);
    packDetection(r.cake, out, 28);
    packDetection(r.spike, out, 44);
    return kResultInts;
}

} // namespace hzzs::vision2

extern "C" int hzzs_vision_analyze_packed(
    const std::uint32_t* pixels,
    int width,
    int height,
    int stride,
    int coarseStep,
    int spikeStep,
    std::int32_t* out,
    int capacity) noexcept {
    const hzzs::vision2::FrameView frame{pixels, width, height, stride};
    const auto result = hzzs::vision2::analyze(frame, coarseStep, spikeStep);
    return hzzs::vision2::pack(result, out, capacity);
}
