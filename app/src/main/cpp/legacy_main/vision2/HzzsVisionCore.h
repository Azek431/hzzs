#pragma once

/**
 * 甜甜圈主路径检测核心（从历史 main 移植）。
 *
 * 输出像素坐标系 Detection / AnalysisResult；由 vision_engine.cpp 映射为
 * 统一 hzzs::Detection 归一化协议。跨帧状态不在此维护。
 *
 * 修改阈值时优先用相对帧宽高或玩家宽度的比例，避免固定屏幕像素。
 */

#include <cstdint>

namespace hzzs::vision2 {

constexpr int kResultVersion = 3;
constexpr int kResultInts = 64;
constexpr int kKindNone = 0;
constexpr int kKindBottle = 1;
constexpr int kKindCakeStructure = 2;
constexpr int kKindHangingSpike = 3;
constexpr int kSizeUnknown = 0;
constexpr int kSizeSmallOrNarrow = 1;
constexpr int kSizeLargeOrWide = 2;
constexpr int kSizeHanging = 3;

struct FrameView {
    const std::uint32_t* pixels = nullptr;
    int width = 0;
    int height = 0;
    int stride = 0;
};

struct Detection {
    int found = 0;
    int kind = 0;
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    int centerX = 0;
    int centerY = 0;
    int edgeGapPx = 0;
    int widthPx = 0;
    int heightPx = 0;
    int widthMilliP = 0;
    int heightMilliP = 0;
    int sizeClass = 0;
    int scorePermille = 0;
    int samples = 0;
};

struct AnalysisResult {
    int width = 0;
    int height = 0;
    int playerLeft = 0;
    int playerRight = 0;
    int playerCenterX = 0;
    int playerCenterY = 0;
    int playerWidth = 0;
    int primaryKind = 0;
    int totalSamples = 0;
    Detection bottle;
    Detection cake;
    Detection spike;
};

AnalysisResult analyze(const FrameView& frame, int coarseStep = 3, int spikeStep = 2) noexcept;
int pack(const AnalysisResult& result, std::int32_t* out, int capacity) noexcept;

} // namespace hzzs::vision2

extern "C" int hzzs_vision_analyze_packed(
    const std::uint32_t* pixels,
    int width,
    int height,
    int stride,
    int coarseStep,
    int spikeStep,
    std::int32_t* out,
    int capacity) noexcept;
