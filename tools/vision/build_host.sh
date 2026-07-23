#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CPP="$ROOT/app/src/main/cpp"
mkdir -p "$ROOT/build/host"
# 与 app/src/main/cpp/CMakeLists.txt 保持一致：统一入口 + legacy_main 主检测路径。
g++ -std=c++17 -O3 -DNDEBUG -fPIC -shared \
  -I"$CPP" \
  -I"$CPP/legacy_main/vision2" \
  -I"$CPP/legacy_main/vision_bamboo" \
  "$CPP/algorithm_runtime.cpp" \
  "$CPP/vision_engine.cpp" \
  "$CPP/sweet_factory.cpp" \
  "$CPP/bamboo_bookstore.cpp" \
  "$CPP/sea_salt_living_room.cpp" \
  "$CPP/multicolor_detector.cpp" \
  "$CPP/legacy_main/vision2/HzzsVisionCore.cpp" \
  "$CPP/legacy_main/vision_bamboo/BambooVisionCore.cpp" \
  "$CPP/legacy_main/vision_bamboo/BambooVisionEngine.cpp" \
  "$ROOT/tools/vision/host_api.cpp" \
  -o "$ROOT/build/host/libhzzs_vision.so"
echo "$ROOT/build/host/libhzzs_vision.so"
