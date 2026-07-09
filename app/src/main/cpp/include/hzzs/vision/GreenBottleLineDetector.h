// 火崽崽助手（HZZS）绿瓶单行扫描检测器 — 头文件。
//
// 职责：
// - 在玩家角色中心 Y 水平线上，从玩家右侧开始向右扫描一条像素行
// - 使用 RGB 色差算法检测绿色瓶子（毒瓶）
// - 合并相邻同色片段、计算边界和置信度
//
// 算法概要：
// 1. 确定扫描线 Y = playerCenterY
// 2. 确定扫描起点 X = playerRight + playerWidth * 0.15
// 3. 确定扫描终点 X = screenWidth - 20
// 4. 逐像素采样，判断是否满足绿色条件
// 5. 合并相邻绿色片段，过滤过窄区域
// 6. 输出归一化坐标、置信度、成本等完整结果
//
// 注意：
// - 此模块与主 NativeAnalysisEngine 完全独立，不污染原有逻辑
// - 输入为原始 RGB 像素数组（int[]，每元素 0xAARRGGBB）+ 屏幕分辨率
// - 所有输出坐标均为归一化坐标（0.0 ~ 1.0）

#pragma once

#include <cstdint>
#include <vector>
#include <string>

namespace hzzs::vision {

/**
 * 绿瓶检测结果。
 *
 * 包含一次扫描的全部输出信息：
 * - found：是否检测到绿瓶
 * - 边界坐标（像素 + 归一化）
 * - 置信度、成本、原始/合并片段数
 */
struct GreenBottleResult {
    /** 是否检测到绿瓶 */
    bool found{false};

    /** 扫描线 Y 坐标（像素） */
    std::int32_t scan_y{0};

    /** 左边界 X（像素） */
    std::int32_t left_x{0};

    /** 右边界 X（像素） */
    std::int32_t right_x{0};

    /** 中心 X（像素） */
    std::int32_t center_x{0};

    /** 左边缘到玩家右侧的距离（像素） */
    std::int32_t edge_gap_px{0};

    /** 中心到玩家右侧的距离（像素） */
    std::int32_t center_distance_px{0};

    /** 左边界归一化坐标 */
    float left_ratio{0.0F};

    /** 右边界归一化坐标 */
    float right_ratio{0.0F};

    /** 中心归一化坐标 */
    float center_x_ratio{0.0F};

    /** 边缘距离归一化 */
    float edge_gap_ratio{0.0F};

    /** 扫描耗时（毫秒） */
    float cost_ms{0.0F};

    /** 置信度（0.0 ~ 1.0） */
    float confidence{0.0F};

    /** 原始绿色片段数量（合并前） */
    int raw_segment_count{0};

    /** 合并后绿色片段数量 */
    int merged_segment_count{0};
};

/**
 * 绿瓶单行扫描检测器。
 *
 * 在固定扫描线上使用 RGB 色差法检测绿色瓶子。
 * 与主分析引擎完全解耦，独立调用、独立生命周期。
 *
 * 线程安全：此类的 Instance 方法是无状态的，可在多线程中安全调用。
 * 但 Scan 方法会分配临时缓冲区，不建议在高频帧循环中反复构造。
 * 推荐复用同一个实例。
 */
class GreenBottleLineDetector {
public:
    /**
     * 默认构造函数。
     *
     * 使用内置默认参数初始化检测器。
     * 所有参数均基于参考分辨率 691x1536 标定。
     */
    GreenBottleLineDetector();

    /**
     * 在原始 RGB 像素数组上执行绿瓶单行扫描。
     *
     * 算法步骤：
     * 1. 根据玩家矩形计算扫描线 Y 和扫描范围 [startX, endX]
     * 2. 沿扫描线逐像素采样 RGB 值
     * 3. 使用色差条件判断是否为绿色像素
     * 4. 将连续绿色像素聚合成片段（segment）
     * 5. 合并空间上相邻的片段
     * 6. 过滤宽度不足的片段
     * 7. 输出最佳结果的归一化坐标和置信度
     *
     * @param pixels ARGB 像素数组（每元素 0xAARRGGBB），长度为 width * height
     * @param width 屏幕宽度（像素）
     * @param height 屏幕高度（像素）
     * @param player_left 玩家矩形左边界归一化坐标
     * @param player_top 玩家矩形上边界归一化坐标
     * @param player_right 玩家矩形右边界归一化坐标
     * @param player_bottom 玩家矩形下边界归一化坐标
     * @return GreenBottleResult 检测结果
     */
    GreenBottleResult Scan(
        const std::int32_t* pixels,
        std::int32_t width,
        std::int32_t height,
        float player_left,
        float player_top,
        float player_right,
        float player_bottom
    );

private:
    /**
     * 判断单个像素是否为绿色。
     *
     * 色差条件：
     * - G > 120：绿色通道足够亮
     * - G - R > 15：绿色明显强于红色
     * - G - B > 30：绿色明显强于蓝色
     * - max(R,G,B) - min(R,G,B) > 45：色彩饱和度高（非灰绿）
     *
     * @param pixel ARGB 格式像素值
     * @return true 如果像素是绿色
     */
    [[nodiscard]] bool IsGreenPixel(std::int32_t pixel) const;

    /**
     * 合并空间上相邻的片段。
     *
     * 如果两个绿色片段的间距不超过 max_gap 距离，则合并为一个片段。
     * 合并后的边界取最外侧范围。
     *
     * @param segments 待合并的片段列表（已按 startX 排序）
     * @param max_gap 合并间隙阈值（像素），超过则视为独立片段
     * @return 合并后的片段列表
     */
    [[nodiscard]] std::vector<std::pair<std::int32_t, std::int32_t>> MergeSegments(
        std::vector<std::pair<std::int32_t, std::int32_t>> segments,
        std::int32_t max_gap
    ) const;

    // ==================== 标定参数（基于 691x1536 参考分辨率） ====================

    /** 玩家左边界归一化坐标 */
    static constexpr float kPlayerLeftRatio = 108.0F / 691.0F;

    /** 玩家右边界归一化坐标 */
    static constexpr float kPlayerRightRatio = 214.0F / 691.0F;

    /** 玩家中心 X 归一化坐标 */
    static constexpr float kPlayerCenterXRatio = 161.0F / 691.0F;

    /** 玩家中心 Y 归一化坐标（即扫描线 Y） */
    static constexpr float kPlayerCenterYRatio = 894.0F / 1536.0F;

    /** 玩家宽度归一化坐标 */
    static constexpr float kPlayerWidthRatio = 107.0F / 691.0F;

    /** 绿瓶顶部归一化坐标（仅作参考，当前算法不直接使用） */
    static constexpr float kBottleTopRatio = 802.0F / 1536.0F;

    /** 绿瓶底部归一化坐标（仅作参考，当前算法不直接使用） */
    static constexpr float kBottleBottomRatio = 1006.0F / 1536.0F;

    /** 扫描起点偏移：playerRight + playerWidth * 0.15 */
    static constexpr float kScanStartOffset = 0.15F;

    /** 扫描终点右侧留白（像素） */
    static constexpr std::int32_t kScanEndPaddingPx = 20;

    /** 片段合并间距阈值：playerWidth * 0.25 */
    static constexpr float kMaxGapRatio = 0.25F;

    /** 最小片段宽度：max(20, playerWidth * 0.25) */
    static constexpr std::int32_t kMinWidthAbsolute = 20;

    /** 最大片段宽度：playerWidth * 1.5 */
    static constexpr float kMaxWidthRatio = 1.5F;

    /** 圆角 padding：playerWidth * 0.065，限制在 4~10 像素之间 */
    static constexpr float kPaddingRatio = 0.065F;
    static constexpr std::int32_t kPaddingMinPx = 4;
    static constexpr std::int32_t kPaddingMaxPx = 10;
};

}  // namespace hzzs::vision
