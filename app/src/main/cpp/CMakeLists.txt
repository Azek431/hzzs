cmake_minimum_required(VERSION 3.22.1)
project(hzzs_vision LANGUAGES CXX)

# 统一入口 + 历史 main 线 vision2 / bamboo 检测核心（不含旧 JNI 类名桥）。
add_library(hzzs_vision SHARED
    jni_bridge.cpp
    algorithm_runtime.cpp
    vision_engine.cpp
    sweet_factory.cpp
    bamboo_bookstore.cpp
    sea_salt_living_room.cpp
    multicolor_detector.cpp
    legacy_main/vision2/HzzsVisionCore.cpp
    legacy_main/vision_bamboo/BambooVisionCore.cpp
    legacy_main/vision_bamboo/BambooVisionEngine.cpp
)

target_include_directories(hzzs_vision PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/legacy_main/vision2
    ${CMAKE_CURRENT_SOURCE_DIR}/legacy_main/vision_bamboo
)

target_compile_features(hzzs_vision PRIVATE cxx_std_17)

# 公共编译选项：隐藏符号、栈保护、无 RTTI（工程未使用 dynamic_cast/typeid）。
# 异常保留：jni_bridge 捕获 std::bad_alloc。
target_compile_options(hzzs_vision PRIVATE
    -fvisibility=hidden
    -fstack-protector-strong
    -fno-rtti
    -ffunction-sections
    -fdata-sections
    $<$<CONFIG:Release>:-O3 -DNDEBUG>
    $<$<CONFIG:RelWithDebInfo>:-O2 -DNDEBUG>
    $<$<CONFIG:Debug>:-O0 -g>
)

# 回收未引用段，减小 .so；与 -ffunction-sections/-fdata-sections 配对。
target_link_options(hzzs_vision PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

find_library(log-lib log)
target_link_libraries(hzzs_vision PRIVATE ${log-lib})
