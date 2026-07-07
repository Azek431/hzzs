cmake_minimum_required(VERSION 3.18.1)

project(hzzs_native LANGUAGES CXX)

add_library(
    hzzs_native
    SHARED
    src/jni/NativeAnalysisBridge.cpp
    src/analysis/SceneStateMachine.cpp
    src/analysis/RunnerStateMachine.cpp
    src/analysis/JumpStageEstimator.cpp
    src/analysis/HazardEtaEstimator.cpp
    src/analysis/ActionPromptEngine.cpp
    src/analysis/NativeAnalysisEngine.cpp
)

target_include_directories(
    hzzs_native
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_compile_features(
    hzzs_native
    PRIVATE
    cxx_std_17
)

target_compile_options(
    hzzs_native
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

find_library(
    log-lib
    log
)

target_link_libraries(
    hzzs_native
    PRIVATE
    ${log-lib}
)
