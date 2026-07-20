#pragma once
#include <cstdint>

extern "C" {

enum HzzsSeason : int32_t {
    HZZS_SEASON_UNKNOWN = 0,
    HZZS_SEASON_SWEET_FACTORY = 1,
    HZZS_SEASON_BAMBOO_STUDY = 2,
};

enum HzzsSceneState : int32_t {
    HZZS_SCENE_UNSAFE = 0,
    HZZS_SCENE_RUNNING = 1,
};

enum HzzsObjectKind : int32_t {
    HZZS_OBJECT_NONE = 0,
    HZZS_OBJECT_GROUND = 1,
    HZZS_OBJECT_GAP = 2,
    HZZS_OBJECT_OVERHEAD = 3,
    HZZS_OBJECT_COLLECTIBLE = 4,
    HZZS_OBJECT_POWERUP = 5,
};

enum HzzsAppearance : int32_t {
    HZZS_APPEARANCE_UNKNOWN = 0,
    HZZS_APPEARANCE_BOTTLE = 1,
    HZZS_APPEARANCE_PANDA_STATUE = 2,
    HZZS_APPEARANCE_CAKE_GAP = 3,
    HZZS_APPEARANCE_BAMBOO_GAP = 4,
    HZZS_APPEARANCE_PIPING_BAG = 5,
    HZZS_APPEARANCE_BRUSH = 6,
    HZZS_APPEARANCE_CANDY = 7,
    HZZS_APPEARANCE_PANDA_TOKEN = 8,
    HZZS_APPEARANCE_BONUS_ORB = 9,
};

enum HzzsSizeClass : int32_t {
    HZZS_SIZE_UNKNOWN = 0,
    HZZS_SIZE_SMALL = 1,
    HZZS_SIZE_LARGE = 2,
    HZZS_SIZE_NARROW = 3,
    HZZS_SIZE_WIDE = 4,
    HZZS_SIZE_HANGING = 5,
};

struct HzzsObject {
    int32_t kind;
    int32_t appearance;
    int32_t size_class;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    float confidence;
};

struct HzzsFrameResult {
    int32_t protocol_version;
    int32_t season;
    int32_t scene_state;
    int32_t floor_y;
    int32_t player_x;
    int32_t player_y;
    int32_t player_width;
    int32_t player_height;
    float season_confidence;
    float floor_confidence;
    float player_confidence;
    int32_t object_count;
    HzzsObject objects[48];
};

int32_t hzzs_bamboo_analyze_rgb_internal(
    const uint8_t* rgb,
    int32_t width,
    int32_t height,
    int32_t stride_bytes,
    HzzsFrameResult* out_result);

const char* hzzs_bamboo_engine_version();

}
