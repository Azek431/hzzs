cmake_minimum_required(VERSION 3.22.1)
project(hzzs_vision LANGUAGES CXX)
add_library(hzzs_vision SHARED
    jni_bridge.cpp
    vision_engine.cpp
    sweet_factory.cpp
    bamboo_bookstore.cpp
)
target_include_directories(hzzs_vision PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(hzzs_vision PRIVATE cxx_std_17)
target_compile_options(hzzs_vision PRIVATE -fvisibility=hidden -fstack-protector-strong)
find_library(log-lib log)
target_link_libraries(hzzs_vision PRIVATE ${log-lib})
