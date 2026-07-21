cmake_minimum_required(VERSION 3.22.1)
project(hzzs_vision LANGUAGES CXX)

# 统一入口 + 历史 main 线 vision2 / bamboo 检测核心（不含旧 JNI 类名桥）。
add_library(hzzs_vision SHARED
    jni_bridge.cpp
    algorithm_runtime.cpp
    vision_engine.cpp
    sweet_factory.cpp
    bamboo_bookstore.cpp
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
target_compile_options(hzzs_vision PRIVATE -fvisibility=hidden -fstack-protector-strong)
find_library(log-lib log)
target_link_libraries(hzzs_vision PRIVATE ${log-lib})
