// 火崽崽助手（HZZS）视觉识别 — 绘制模块实现。
//
// 移植自 Python 脚本的 render_debug_image()。
// 纯像素级绘制，不依赖任何图形库。

#include "DebugRenderer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdio>

namespace hzzs::vision {

// ============================================================
// 工具函数
// ============================================================

inline int clamp_int(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static void setPixel(uint8_t* rgba, int32_t w, int32_t h, int x, int y,
                     uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    if (x < 0 || x >= w || y < 0 || y >= h) return;
    int idx = (y * w + x) * 4;
    float fa = a / 255.0f;
    if (fa == 0) return;
    float fo = (rgba[idx + 3] / 255.0f) * (1.0f - fa);
    rgba[idx]     = static_cast<uint8_t>(r * fa + rgba[idx]     * fo + 0.5f);
    rgba[idx + 1] = static_cast<uint8_t>(g * fa + rgba[idx + 1] * fo + 0.5f);
    rgba[idx + 2] = static_cast<uint8_t>(b * fa + rgba[idx + 2] * fo + 0.5f);
    rgba[idx + 3] = static_cast<uint8_t>((fa + fo) * 255.0f + 0.5f);
}

static void drawHLine(uint8_t* rgba, int32_t w, int32_t h,
                      int y, int x1, int x2, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    for (int x = x1; x <= x2; ++x) setPixel(rgba, w, h, x, y, r, g, b, a);
}

static void drawVLine(uint8_t* rgba, int32_t w, int32_t h,
                      int x, int y1, int y2, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    for (int y = y1; y <= y2; ++y) setPixel(rgba, w, h, x, y, r, g, b, a);
}

static void drawRect(uint8_t* rgba, int32_t w, int32_t h,
                     int x1, int y1, int x2, int y2,
                     uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    drawHLine(rgba, w, h, y1, x1, x2, r, g, b, a);
    drawHLine(rgba, w, h, y2, x1, x2, r, g, b, a);
    drawVLine(rgba, w, h, x1, y1, y2, r, g, b, a);
    drawVLine(rgba, w, h, x2, y1, y2, r, g, b, a);
}

static void fillRect(uint8_t* rgba, int32_t w, int32_t h,
                     int x1, int y1, int x2, int y2,
                     uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    for (int y = y1; y <= y2; ++y) drawHLine(rgba, w, h, y, x1, x2, r, g, b, a);
}

static void drawEllipse(uint8_t* rgba, int32_t w, int32_t h,
                        int cx, int cy, int rx, int ry,
                        uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    for (int dy = -ry; dy <= ry; ++dy) {
        for (int dx = -rx; dx <= rx; ++dx) {
            if (dx * dx * ry * ry + dy * dy * rx * rx <= rx * rx * ry * ry + rx * ry) {
                setPixel(rgba, w, h, cx + dx, cy + dy, r, g, b, a);
            }
        }
    }
}

// ============================================================
// 绘制函数
// ============================================================

static void drawPlayerBox(uint8_t* rgba, int32_t w, int32_t h,
                          const VisionPlayerRef& player) {
    drawRect(rgba, w, h, player.left_x, player.top_y,
             player.right_x, player.bottom_y, 255, 80, 100, 200);
    drawEllipse(rgba, w, h, player.center_x, player.center_y, 3, 3, 255, 80, 100, 200);
}

static void drawGlowRect(uint8_t* rgba, int32_t w, int32_t h,
                         int x1, int y1, int x2, int y2,
                         uint8_t r, uint8_t g, uint8_t b, int base_width) {
    for (int i = 0; i < 3; ++i) {
        int offset = base_width + 5 - i * 2;
        uint8_t fade_r = static_cast<uint8_t>(std::min(255, r + 40));
        uint8_t fade_g = static_cast<uint8_t>(std::min(255, g + 40));
        uint8_t fade_b = static_cast<uint8_t>(std::min(255, b + 40));
        uint8_t fade_a = static_cast<uint8_t>(std::max(1, 200 - i * 60));
        drawRect(rgba, w, h, x1 - offset, y1 - offset, x2 + offset, y2 + offset,
                 fade_r, fade_g, fade_b, fade_a);
    }
    drawRect(rgba, w, h, x1, y1, x2, y2, r, g, b, 200);
}

static void drawLabel(uint8_t* rgba, int32_t w, int32_t h,
                      int x, int y, const char* text,
                      uint8_t r, uint8_t g, uint8_t b) {
    int text_len = 0;
    while (text[text_len] != '\0') text_len++;
    int tw = text_len * 6 + 16;
    int th = 16;
    x = clamp_int(x, 8, w - tw - 8);
    y = clamp_int(y, 8, h - th - 8);
    fillRect(rgba, w, h, x - 8, y - 4, x + tw - 8, y + th - 4, 0, 0, 0, 150);
    drawRect(rgba, w, h, x - 8, y - 4, x + tw - 8, y + th - 4, r, g, b, 200);
    for (int ci = 0; ci < text_len; ++ci) {
        int px = x + ci * 6;
        int py = y;
        uint8_t ch = static_cast<uint8_t>(text[ci]);
        if (ch >= 32 && ch < 127) {
            setPixel(rgba, w, h, px + 1, py, r, g, b, 220);
            setPixel(rgba, w, h, px + 2, py, r, g, b, 220);
            setPixel(rgba, w, h, px + 3, py, r, g, b, 220);
            setPixel(rgba, w, h, px + 1, py + 1, r, g, b, 220);
            setPixel(rgba, w, h, px + 3, py + 1, r, g, b, 220);
            setPixel(rgba, w, h, px + 1, py + 2, r, g, b, 220);
            setPixel(rgba, w, h, px + 2, py + 2, r, g, b, 220);
            setPixel(rgba, w, h, px + 3, py + 2, r, g, b, 220);
        }
    }
}

static void drawSummaryPanel(uint8_t* rgba, int32_t w, int32_t h,
                             const VisionFrameResult* result) {
    char line[256];
    int y = 46;

    std::snprintf(line, sizeof(line), "%s", result->file_name);
    drawLabel(rgba, w, h, 8, y, line, 0, 220, 255);
    y += 18;

    std::snprintf(line, sizeof(line), "%dx%d total=%.2fms",
               result->width, result->height, result->total_cost_ms);
    drawLabel(rgba, w, h, 8, y, line, 220, 255, 255);
    y += 18;

    std::snprintf(line, sizeof(line), "Bottle: %s",
               result->bottle.found ? "OK" : "not found");
    uint8_t col = result->bottle.found ? 0 : 255;
    drawLabel(rgba, w, h, 8, y, line, col, 255, col == 0 ? 105 : 80);
    y += 18;

    std::snprintf(line, sizeof(line), "Pit: %s",
               result->pit.found ? "OK" : "not found");
    col = result->pit.found ? 0 : 255;
    drawLabel(rgba, w, h, 8, y, line, col, 255, col == 0 ? 105 : 80);
}

// ============================================================
// 主绘制函数
// ============================================================

void renderDebugImage(
    const uint8_t* rgba_input,
    const uint8_t* rgb_input,
    int32_t width,
    int32_t height,
    const VisionFrameResult* result,
    uint8_t* rgba_output
) {
    (void)rgb_input;
    std::memcpy(rgba_output, rgba_input, width * height * 4);
    // 1. 玩家参考框
    drawPlayerBox(rgba_output, width, height, result->player);

    // 2. 绿瓶绘制
    if (result->bottle.found && result->bottle.left_x >= 0) {
        int top_y = static_cast<int>(height * 802.0f / 1536.0f);
        int bottom_y = static_cast<int>(height * 1006.0f / 1536.0f);

        drawHLine(rgba_output, width, height, result->bottle.scan_y,
                  result->player.right_x, width - 12, 0, 220, 255, 170);

        fillRect(rgba_output, width, height,
                 result->bottle.left_x, top_y, result->bottle.right_x, bottom_y,
                 0, 255, 90, 42);

        drawGlowRect(rgba_output, width, height,
                     result->bottle.left_x, top_y, result->bottle.right_x, bottom_y,
                     0, 255, 105, 3);

        drawVLine(rgba_output, width, height, result->bottle.left_x, top_y, bottom_y, 255, 230, 0, 200);
        drawVLine(rgba_output, width, height, result->bottle.right_x, top_y, bottom_y, 255, 230, 0, 200);

        int cx = result->bottle.center_x;
        int cy = (top_y + bottom_y) / 2;
        drawEllipse(rgba_output, width, height, cx, cy, 5, 5, 0, 255, 105, 200);
        drawEllipse(rgba_output, width, height, cx, cy, 5, 5, 255, 255, 255, 100);

        char label[128];
        std::snprintf(label, sizeof(label),
                      "BOT L=%d R=%d Cx=%d gap=%dpx %.2fms",
                      result->bottle.left_x, result->bottle.right_x,
                      result->bottle.center_x, result->bottle.edge_gap_px,
                      result->bottle.cost_ms);
        drawLabel(rgba_output, width, height,
                  result->bottle.left_x - 80, top_y - 34, label, 0, 255, 105);
    }

    // 3. 坑位绘制
    if (result->pit.found && result->pit.left_x >= 0) {
        drawHLine(rgba_output, width, height, result->pit.scan_y,
                  result->player.right_x, width - 8, 0, 190, 255, 170);

        int danger_top = clamp_int(result->pit.scan_y - static_cast<int>(height * 0.018f), 0, height - 1);
        int danger_bottom = clamp_int(result->pit.scan_y + static_cast<int>(height * 0.185f), 0, height - 1);

        fillRect(rgba_output, width, height,
                 result->pit.left_x, danger_top, result->pit.right_x, danger_bottom,
                 255, 70, 40, 58);

        drawGlowRect(rgba_output, width, height,
                     result->pit.left_x, danger_top, result->pit.right_x, danger_bottom,
                     255, 105, 35, 3);

        drawVLine(rgba_output, width, height, result->pit.left_x, danger_top, danger_bottom, 255, 40, 40, 200);
        drawVLine(rgba_output, width, height, result->pit.right_x, danger_top, danger_bottom, 255, 230, 0, 200);

        int cx = result->pit.center_x;
        int cy = result->pit.scan_y;
        drawEllipse(rgba_output, width, height, cx, cy, 5, 5, 255, 80, 40, 200);
        drawEllipse(rgba_output, width, height, cx, cy, 5, 5, 255, 255, 255, 100);

        char label[128];
        std::snprintf(label, sizeof(label),
                      "PIT L=%d R=%d W=%d gap=%dpx %.2fms",
                      result->pit.left_x, result->pit.right_x,
                      result->pit.width_px, result->pit.edge_gap_px,
                      result->pit.cost_ms);
        drawLabel(rgba_output, width, height,
                  result->pit.left_x - 55, danger_top - 34, label, 255, 170, 40);
    }

    // 4. 汇总面板
    drawSummaryPanel(rgba_output, width, height, result);

    // 5. 无检测提示
    if (!result->bottle.found && !result->pit.found) {
        drawLabel(rgba_output, width, height, 16, 46 + 72 + 12,
                  "No bottle / pit detected", 255, 220, 80);
    }
}

}  // namespace hzzs::vision
