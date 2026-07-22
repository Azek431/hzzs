/**
 * 竹影主路径适配层：将 BambooVisionEngine 输出转为 Detection 协议，
 * 并过滤玩家身后障碍。
 */
#include "BambooVisionCore.h"
#include "BambooVisionEngine.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace hzzs::vision_bamboo {
namespace {
constexpr int kGroundOffset = 12;
constexpr int kGapOffset = 28;
constexpr int kOverheadOffset = 44;
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

AnalysisResult analyze(
    const FrameView& frame,
    float player_confidence_floor,
    int work_width) noexcept {
    AnalysisResult out{};
    out.width = frame.width;
    out.height = frame.height;
    out.sceneState = HZZS_SCENE_UNSAFE;

    if (frame.pixels == nullptr || frame.width < 32 || frame.height < 64 || frame.stride < frame.width ||
        frame.width > kMaximumFrameDimension || frame.height > kMaximumFrameDimension) {
        return out;
    }

    // 工作图宽度：与设置 VisionThresholds.workWidth 对齐。原图更宽时降采样，
    // 避免 legacy 全图 Mask/形态学/连通域在 1080p 上掉到百毫秒级。
    int target_w = work_width;
    if (target_w < 160) target_w = 160;
    if (target_w > 960) target_w = 960;

    const bool downscale = frame.width > target_w;
    const int work_w = downscale ? target_w : frame.width;
    const int work_h = downscale
        ? std::max(64, (frame.height * target_w + frame.width / 2) / frame.width)
        : frame.height;
    const float scale_x = static_cast<float>(frame.width) / static_cast<float>(std::max(1, work_w));
    const float scale_y = static_cast<float>(frame.height) / static_cast<float>(std::max(1, work_h));

    const std::size_t pixelCount =
        static_cast<std::size_t>(work_w) * static_cast<std::size_t>(work_h);
    if (pixelCount > std::numeric_limits<std::size_t>::max() / 3U) return out;

    thread_local std::vector<std::uint8_t> rgb;
    const std::size_t needed = pixelCount * 3U;
    if (rgb.size() != needed) rgb.resize(needed);

    // 最近邻缩放 + ARGB→RGB，一次完成，避免「全图拆通道再缩放」。
    for (int y = 0; y < work_h; ++y) {
        const int src_y = downscale
            ? std::min(frame.height - 1, static_cast<int>((y + 0.5f) * scale_y))
            : y;
        const auto* row = frame.pixels + static_cast<std::size_t>(src_y) * frame.stride;
        std::uint8_t* dst_row = rgb.data() + static_cast<std::size_t>(y) * static_cast<std::size_t>(work_w) * 3U;
        for (int x = 0; x < work_w; ++x) {
            const int src_x = downscale
                ? std::min(frame.width - 1, static_cast<int>((x + 0.5f) * scale_x))
                : x;
            const std::uint32_t argb = row[src_x];
            const std::size_t o = static_cast<std::size_t>(x) * 3U;
            dst_row[o + 0] = static_cast<std::uint8_t>((argb >> 16U) & 0xFFU);
            dst_row[o + 1] = static_cast<std::uint8_t>((argb >> 8U) & 0xFFU);
            dst_row[o + 2] = static_cast<std::uint8_t>(argb & 0xFFU);
        }
    }

    HzzsFrameResult engine{};
    const int rc = hzzs_bamboo_analyze_rgb_internal(
        rgb.data(),
        work_w,
        work_h,
        work_w * 3,
        &engine);
    out.sceneState = engine.scene_state;
    out.playerConfidencePermille = std::clamp(
        static_cast<int>(engine.player_confidence * 1000.0f + 0.5f),
        0,
        1000);

    if (rc != 0 || engine.scene_state != HZZS_SCENE_RUNNING) return out;
    const float floor =
        (std::isfinite(player_confidence_floor) && player_confidence_floor >= 0.0f &&
         player_confidence_floor <= 1.0f)
            ? player_confidence_floor
            : 0.45f;
    if (engine.player_width <= 0 || engine.player_height <= 0 ||
        engine.player_confidence < floor) {
        out.sceneState = HZZS_SCENE_UNSAFE;
        return out;
    }

    auto map_x = [&](int v) -> int {
        if (!downscale) return v;
        return std::clamp(static_cast<int>(std::lround(v * scale_x)), 0, frame.width);
    };
    auto map_y = [&](int v) -> int {
        if (!downscale) return v;
        return std::clamp(static_cast<int>(std::lround(v * scale_y)), 0, frame.height);
    };
    auto map_object = [&](HzzsObject object) -> HzzsObject {
        if (!downscale) return object;
        const int x0 = map_x(object.x);
        const int y0 = map_y(object.y);
        const int x1 = map_x(object.x + std::max(0, object.width));
        const int y1 = map_y(object.y + std::max(0, object.height));
        object.x = x0;
        object.y = y0;
        object.width = std::max(1, x1 - x0);
        object.height = std::max(1, y1 - y0);
        return object;
    };

    // 玩家框映射回原图。
    const int mapped_player_x = map_x(engine.player_x);
    const int mapped_player_y = map_y(engine.player_y);
    const int mapped_player_w = std::max(1, map_x(engine.player_x + engine.player_width) - mapped_player_x);
    const int mapped_player_h = std::max(1, map_y(engine.player_y + engine.player_height) - mapped_player_y);

    out.playerWidth = mapped_player_w;
    out.playerLeft = mapped_player_x;
    out.playerRight = out.playerLeft + out.playerWidth;
    out.playerCenterX = out.playerLeft + out.playerWidth / 2;
    out.playerCenterY = mapped_player_y + mapped_player_h / 2;

    for (int i = 0; i < engine.object_count; ++i) {
        const auto object = map_object(engine.objects[i]);
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
    // 打包入口无 workWidth 时用 384 默认，与产品默认阈值一致。
    const auto result = hzzs::vision_bamboo::analyze(frame, 0.45f, 384);
    return hzzs::vision_bamboo::pack(result, out, capacity);
}
