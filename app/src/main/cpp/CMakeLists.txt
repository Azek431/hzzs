cmake_minimum_required(VERSION 3.18.1)

# C++ 原生库项目名称
project(hzzs_native LANGUAGES CXX)

# 构建共享库 libhzzs_native.so
# 包含所有分析引擎源文件：
# - NativeAnalysisBridge.cpp：JNI 桥接层（Kotlin ↔ C++）
# - SceneStateMachine.cpp：场景识别状态机
# - RunnerStateMachine.cpp：角色姿态推断
# - JumpStageEstimator.cpp：跳跃阶段估计（含二段跳）
# - HazardEtaEstimator.cpp：危险物 ETA 计算
# - ActionPromptEngine.cpp：HUD 动作提示生成
# - NativeAnalysisEngine.cpp：分析引擎总入口，协调所有子模块
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

# 包含目录：指向 include/ 下的所有头文件
target_include_directories(
    hzzs_native
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

# 编译标准：C++17（使用 std::optional, std::filesystem 等特性）
target_compile_features(
    hzzs_native
    PRIVATE
    cxx_std_17
)

# 编译警告选项：开启严格警告，帮助发现潜在问题
target_compile_options(
    hzzs_native
    PRIVATE
    -Wall    # 开启所有常见警告
    -Wextra  # 开启额外警告
    -Wpedantic  # 严格遵循 C++ 标准
)

# 查找 Android 日志库（用于 __android_log_print）
find_library(
    log-lib
    log
)

# 链接日志库
target_link_libraries(
    hzzs_native
    PRIVATE
    ${log-lib}
)
