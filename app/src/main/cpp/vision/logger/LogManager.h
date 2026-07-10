// 火崽崽助手（HZZS）视觉识别 — 日志模块。
//
// 记录每次检测的结果、耗时、详细信息。
// 支持：
// - 追加日志条目
// - 导出为 CSV
// - 导出为 JSON
// - 清空日志

#pragma once

#include <cstdint>
#include <cstring>
#include <cstdio>

#ifdef __cplusplus
extern "C" {
#endif

/** 最大日志条目数 */
#define HZZS_LOG_MAX_ENTRIES 1000

/** 日志条目类型 */
typedef enum {
    HZZS_LOG_ENTRY_DETECTION = 0,  // 检测条目
    HZZS_LOG_ENTRY_SUMMARY,        // 汇总条目
} HzzsLogEntryType;

/** 日志条目 */
typedef struct {
    int32_t index;                  // 序号
    HzzsLogEntryType type;          // 类型
    char file_name[256];            // 文件名
    int32_t width;                  // 图像宽度
    int32_t height;                 // 图像高度
    double total_cost_ms;           // 总耗时（毫秒）

    // 绿瓶结果
    int bottle_found;               // 是否检测到
    int bottle_left_x;
    int bottle_right_x;
    int bottle_center_x;
    int bottle_width_px;
    int bottle_edge_gap_px;
    float bottle_confidence;
    double bottle_cost_ms;

    // 坑位结果
    int pit_found;
    int pit_left_x;
    int pit_right_x;
    int pit_center_x;
    int pit_width_px;
    int pit_edge_gap_px;
    float pit_confidence;
    double pit_cost_ms;
} HzzsLogEntry;

/**
 * 初始化日志管理器。
 */
void hzzs_log_init(void);

/**
 * 追加检测日志条目。
 *
 * @param entry 日志条目
 */
void hzzs_log_append(const HzzsLogEntry* entry);

/**
 * 导出为 CSV 字符串（调用方负责释放）。
 *
 * @param buffer 输出缓冲区
 * @param capacity 缓冲区大小
 * @return 实际写入的字节数
 */
int hzzs_log_export_csv(char* buffer, int capacity);

/**
 * 导出为 JSON 字符串（调用方负责释放）。
 *
 * @param buffer 输出缓冲区
 * @param capacity 缓冲区大小
 * @return 实际写入的字节数
 */
int hzzs_log_export_json(char* buffer, int capacity);

/**
 * 清空所有日志条目。
 */
void hzzs_log_clear(void);

/**
 * 获取当前日志条目数量。
 */
int hzzs_log_count(void);

#ifdef __cplusplus
}
#endif
