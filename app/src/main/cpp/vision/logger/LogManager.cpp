// 火崽崽助手（HZZS）视觉识别 — 日志模块实现。

#include "LogManager.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

static HzzsLogEntry g_log_entries[HZZS_LOG_MAX_ENTRIES];
static int g_log_count = 0;

void hzzs_log_init(void) {
    g_log_count = 0;
}

void hzzs_log_append(const HzzsLogEntry* entry) {
    if (g_log_count >= HZZS_LOG_MAX_ENTRIES) return;
    g_log_entries[g_log_count] = *entry;
    g_log_entries[g_log_count].index = g_log_count + 1;
    g_log_count++;
}

int hzzs_log_export_csv(char* buffer, int capacity) {
    if (capacity <= 0) return 0;

    // 表头
    int written = std::snprintf(buffer, capacity,
        "file,width,height,total_cost_ms,"
        "bottle_found,bottle_left,bottle_right,bottle_center_x,bottle_width,bottle_gap,bottle_confidence,bottle_cost_ms,"
        "pit_found,pit_left,pit_right,pit_center_x,pit_width,pit_gap,pit_confidence,pit_cost_ms\n");

    // 数据行
    for (int i = 0; i < g_log_count; i++) {
        const HzzsLogEntry* e = &g_log_entries[i];
        int remaining = capacity - written;
        if (remaining <= 0) break;

        int n = std::snprintf(buffer + written, remaining,
            "%s,%d,%d,%.4f,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%.4f,%.4f\n",
            e->file_name, e->width, e->height, e->total_cost_ms,
            e->bottle_found, e->bottle_left_x, e->bottle_right_x, e->bottle_center_x,
            e->bottle_width_px, e->bottle_edge_gap_px, e->bottle_confidence, e->bottle_cost_ms,
            e->pit_found, e->pit_left_x, e->pit_right_x, e->pit_center_x,
            e->pit_width_px, e->pit_edge_gap_px, e->pit_confidence, e->pit_cost_ms);
        written += n;
    }

    return written;
}

int hzzs_log_export_json(char* buffer, int capacity) {
    if (capacity <= 0) return 0;

    int written = std::snprintf(buffer, capacity, "[\n");

    for (int i = 0; i < g_log_count; i++) {
        const HzzsLogEntry* e = &g_log_entries[i];
        int remaining = capacity - written;
        if (remaining <= 0) break;

        int n = std::snprintf(buffer + written, remaining,
            "  {\"file\":\"%s\",\"width\":%d,\"height\":%d,\"total_cost_ms\":%.4f,"
            "\"bottle\":{\"found\":%d,\"left\":%d,\"right\":%d,\"center\":%d,\"width\":%d,\"gap\":%d,\"conf\":%.4f,\"cost_ms\":%.4f},"
            "\"pit\":{\"found\":%d,\"left\":%d,\"right\":%d,\"center\":%d,\"width\":%d,\"gap\":%d,\"conf\":%.4f,\"cost_ms\":%.4f}}%s\n",
            e->file_name, e->width, e->height, e->total_cost_ms,
            e->bottle_found, e->bottle_left_x, e->bottle_right_x, e->bottle_center_x,
            e->bottle_width_px, e->bottle_edge_gap_px, e->bottle_confidence, e->bottle_cost_ms,
            e->pit_found, e->pit_left_x, e->pit_right_x, e->pit_center_x,
            e->pit_width_px, e->pit_edge_gap_px, e->pit_confidence, e->pit_cost_ms,
            i < g_log_count - 1 ? "," : "");
        written += n;
    }

    int remaining = capacity - written;
    if (remaining > 0) {
        std::strncat(buffer, "]", remaining);
    }

    return written;
}

void hzzs_log_clear(void) {
    g_log_count = 0;
}

int hzzs_log_count(void) {
    return g_log_count;
}
