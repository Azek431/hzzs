#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace hzzs {
enum class Kind : int32_t {
    PLAYER = 0, POISON_BOTTLE = 1, CAKE_STRUCTURE = 2, HANGING_SPIKE = 3,
    PIT = 4, PANDA_STATUE = 5, BAMBOO_GAP = 6, HANGING_BRUSH = 7,
};
enum class Avoidance : int32_t { NONE = 0, JUMP = 1, DOUBLE_JUMP = 2, SLIDE = 3 };
struct Rect { float left{}, top{}, right{}, bottom{}; };
struct Detection {
    int32_t track_hint{};
    Kind kind{Kind::PLAYER};
    Rect bounds{};
    float confidence{};
    bool actionable{};
    bool diagnostic_only{};
    Avoidance avoidance{Avoidance::NONE};
};
struct Result {
    float scene_confidence{};
    std::vector<Detection> detections;
    std::string error;
};
struct FrameView {
    const uint32_t* pixels{};
    int width{};
    int height{};
};
}
