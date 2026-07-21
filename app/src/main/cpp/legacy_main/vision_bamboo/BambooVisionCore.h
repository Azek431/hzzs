#pragma once

/**
 * 竹影书屋主路径检测核心（从历史 main 移植）。
 *
 * 障碍语义：地面 / 缺口 / 头顶物；映射到统一 Kind 时在 vision_engine 完成。
 * 角色身后目标不得进入动作协议（实现侧过滤）。
 */

#include <cstdint>

namespace hzzs::vision_bamboo {

constexpr int kResultVersion = 4;
constexpr int kResultInts = 64;
constexpr int kKindNone = 0;
constexpr int kKindGround = 1;
constexpr int kKindGap = 2;
constexpr int kKindOverhead = 3;
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
    int sceneState = 0;
    int playerConfidencePermille = 0;
    Detection ground;
    Detection gap;
    Detection overhead;
};

AnalysisResult analyze(const FrameView& frame) noexcept;
int pack(const AnalysisResult& result, std::int32_t* out, int capacity) noexcept;

} // namespace hzzs::vision_bamboo

extern "C" int hzzs_bamboo_analyze_packed(
    const std::uint32_t* pixels,
    int width,
    int height,
    int stride,
    std::int32_t* out,
    int capacity) noexcept;
