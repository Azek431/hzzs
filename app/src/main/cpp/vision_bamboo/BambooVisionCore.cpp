#include "BambooVisionCore.h"
#include "BambooVisionEngine.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace hzzs::vision_bamboo {
namespace {
constexpr int kGroundOffset = 12;
constexpr int kGapOffset = 28;
constexpr int kOverheadOffset = 44;
constexpr float kMinimumPlayerConfidence = 0.45f;
constexpr int kMaximumFrameDimension = 8192;

Detection convertObject(
    const HzzsObject& object,
    int targetKind,
    int playerLeft,
    int playerRight,
    int playerWidth) noexcept {
    Detection out{};
    const int objectRightExclusive = object.x + std::max(0, object.width);
    // 已完全位于角色身后的目标仅属于历史画面，不得进入动作协议。
    if (objectRightExclusive <= playerLeft) return out;

    out.found = 1;
    out.kind = targetKind;
    out.left = object.x;
    out.top = object.y;
    out.right = object.x + std::max(0, object.width - 1);
    out.bottom = object.y + std::max(0, object.height - 1);
    out.centerX = object.x + object.width / 2;
    out.centerY = object.y + object.height / 2;
    out.edgeGapPx = std::max(0, object.x - playerRight);
    out.widthPx = object.width;
    out.heightPx = object.height;
    out.widthMilliP = object.width * 1000 / std::max(1, playerWidth);
    out.heightMilliP = object.height * 1000 / std::max(1, playerWidth);
    out.scorePermille = std::clamp(
        static_cast<int>(object.confidence * 1000.0f + 0.5f),
        0,
        1000);
    out.samples = std::max(0, object.width) * std::max(0, object.height);
    return out;
}

bool isBetter(const Detection& current, const Detection& candidate) noexcept {
    if (!candidate.found) return false;
    if (!current.found) return true;
    if (candidate.edgeGapPx != current.edgeGapPx) return candidate.edgeGapPx < current.edgeGapPx;
    return candidate.scorePermille > current.scorePermille;
}

void writeDetection(const Detection& detection, std::int32_t* out, int offset) noexcept {
    out[offset] = detection.found;
    out[offset + 1] = detection.kind;
    out[offset + 2] = detection.left;
    out[offset + 3] = detection.top;
    out[offset + 4] = detection.right;
    out[offset + 5] = detection.bottom;
    out[offset + 6] = detection.centerX;
    out[offset + 7] = detection.centerY;
    out[offset + 8] = detection.edgeGapPx;
    out[offset + 9] = detection.widthPx;
    out[offset + 10] = detection.heightPx;
    out[offset + 11] = detection.widthMilliP;
    out[offset + 12] = detection.heightMilliP;
    out[offset + 13] = detection.sizeClass;
    out[offset + 14] = detection.scorePermille;
    out[offset + 15] = detection.samples;
}
} // namespace

AnalysisResult analyze(const FrameView& frame) noexcept {
    AnalysisResult out{};
    out.width = frame.width;
    out.height = frame.height;
    out.sceneState = HZZS_SCENE_UNSAFE;

    if (frame.pixels == nullptr || frame.width < 32 || frame.height < 64 || frame.stride < frame.width ||
        frame.width > kMaximumFrameDimension || frame.height > kMaximumFrameDimension) {
        return out;
    }

    const std::size_t pixelCount = static_cast<std::size_t>(frame.width) * static_cast<std::size_t>(frame.height);
    if (pixelCount > std::numeric_limits<std::size_t>::max() / 3U) return out;

    thread_local std::vector<std::uint8_t> rgb;
    const std::size_t needed = pixelCount * 3U;
    if (rgb.size() != needed) rgb.resize(needed);

    std::size_t dst = 0;
    for (int y = 0; y < frame.height; ++y) {
        const auto* row = frame.pixels + static_cast<std::size_t>(y) * frame.stride;
        for (int x = 0; x < frame.width; ++x) {
            const std::uint32_t argb = row[x];
            rgb[dst++] = static_cast<std::uint8_t>((argb >> 16U) & 0xFFU);
            rgb[dst++] = static_cast<std::uint8_t>((argb >> 8U) & 0xFFU);
            rgb[dst++] = static_cast<std::uint8_t>(argb & 0xFFU);
        }
    }

    HzzsFrameResult engine{};
    const int rc = hzzs_bamboo_analyze_rgb_internal(
        rgb.data(),
        frame.width,
        frame.height,
        frame.width * 3,
        &engine);
    out.sceneState = engine.scene_state;
    out.playerConfidencePermille = std::clamp(
        static_cast<int>(engine.player_confidence * 1000.0f + 0.5f),
        0,
        1000);

    if (rc != 0 || engine.scene_state != HZZS_SCENE_RUNNING) return out;
    if (engine.player_width <= 0 || engine.player_height <= 0 ||
        engine.player_confidence < kMinimumPlayerConfidence) {
        out.sceneState = HZZS_SCENE_UNSAFE;
        return out;
    }

    out.playerWidth = engine.player_width;
    out.playerLeft = engine.player_x;
    out.playerRight = out.playerLeft + out.playerWidth;
    out.playerCenterX = out.playerLeft + out.playerWidth / 2;
    out.playerCenterY = engine.player_y + engine.player_height / 2;

    for (int i = 0; i < engine.object_count; ++i) {
        const auto& object = engine.objects[i];
        Detection candidate{};
        if (object.kind == HZZS_OBJECT_GROUND && object.appearance == HZZS_APPEARANCE_PANDA_STATUE) {
            candidate = convertObject(
                object,
                kKindGround,
                out.playerLeft,
                out.playerRight,
                out.playerWidth);
            candidate.sizeClass =
                object.size_class == HZZS_SIZE_LARGE ? kSizeLargeOrWide : kSizeSmallOrNarrow;
            if (isBetter(out.ground, candidate)) out.ground = candidate;
        } else if (object.kind == HZZS_OBJECT_GAP && object.appearance == HZZS_APPEARANCE_BAMBOO_GAP) {
            candidate = convertObject(
                object,
                kKindGap,
                out.playerLeft,
                out.playerRight,
                out.playerWidth);
            candidate.sizeClass =
                object.size_class == HZZS_SIZE_WIDE ? kSizeLargeOrWide : kSizeSmallOrNarrow;
            if (isBetter(out.gap, candidate)) out.gap = candidate;
        } else if (object.kind == HZZS_OBJECT_OVERHEAD && object.appearance == HZZS_APPEARANCE_BRUSH) {
            candidate = convertObject(
                object,
                kKindOverhead,
                out.playerLeft,
                out.playerRight,
                out.playerWidth);
            candidate.sizeClass = kSizeHanging;
            if (isBetter(out.overhead, candidate)) out.overhead = candidate;
        }
    }

    int bestGap = std::numeric_limits<int>::max();
    for (const Detection* detection : {&out.ground, &out.gap, &out.overhead}) {
        out.totalSamples += detection->samples;
        if (detection->found && detection->edgeGapPx < bestGap) {
            bestGap = detection->edgeGapPx;
            out.primaryKind = detection->kind;
        }
    }
    return out;
}

int pack(const AnalysisResult& result, std::int32_t* out, int capacity) noexcept {
    if (out == nullptr || capacity < kResultInts) return 0;
    std::fill(out, out + kResultInts, 0);
    out[0] = kResultVersion;
    out[1] = result.width;
    out[2] = result.height;
    out[3] = result.playerLeft;
    out[4] = result.playerRight;
    out[5] = result.playerCenterX;
    out[6] = result.playerCenterY;
    out[7] = result.playerWidth;
    out[8] = result.primaryKind;
    out[9] = result.totalSamples;
    out[10] = result.sceneState;
    out[11] = result.playerConfidencePermille;
    writeDetection(result.ground, out, kGroundOffset);
    writeDetection(result.gap, out, kGapOffset);
    writeDetection(result.overhead, out, kOverheadOffset);
    return kResultInts;
}

} // namespace hzzs::vision_bamboo

extern "C" int hzzs_bamboo_analyze_packed(
    const std::uint32_t* pixels,
    int width,
    int height,
    int stride,
    std::int32_t* out,
    int capacity) noexcept {
    const hzzs::vision_bamboo::FrameView frame{pixels, width, height, stride};
    const auto result = hzzs::vision_bamboo::analyze(frame);
    return hzzs::vision_bamboo::pack(result, out, capacity);
}
