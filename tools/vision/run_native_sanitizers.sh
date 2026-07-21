#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/build/host/native_tests"
mkdir -p "$(dirname "$OUT")"
# 与 app/src/main/cpp/CMakeLists.txt 保持一致：统一入口 + legacy_main 主检测路径。
g++ -std=c++17 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$CPP" \
  -I"$CPP/legacy_main/vision2" \
  -I"$CPP/legacy_main/vision_bamboo" \
  "$ROOT/app/src/test/cpp/native_tests.cpp" \
  "$CPP/algorithm_runtime.cpp" \
  "$CPP/vision_engine.cpp" \
  "$CPP/sweet_factory.cpp" \
  "$CPP/bamboo_bookstore.cpp" \
  "$CPP/legacy_main/vision2/HzzsVisionCore.cpp" \
  "$CPP/legacy_main/vision_bamboo/BambooVisionCore.cpp" \
  "$CPP/legacy_main/vision_bamboo/BambooVisionEngine.cpp" \
  -o "$OUT"
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=print_stacktrace=1 "$OUT"
