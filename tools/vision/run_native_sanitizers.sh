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
  -I"$CPP/vision_v3" \
  "$ROOT/app/src/test/cpp/native_tests.cpp" \
  "$CPP/algorithm_runtime.cpp" \
  "$CPP/vision_engine.cpp" \
  "$CPP/sweet_factory.cpp" \
  "$CPP/bamboo_bookstore.cpp" \
  "$CPP/sea_salt_living_room.cpp" \
  "$CPP/multicolor_detector.cpp" \
  "$CPP/vision_v3/sea_salt_v3.cpp" \
  "$CPP/vision_v3/sea_salt_fast.cpp" \
  "$CPP/vision_v3/soy_sauce_exact.cpp" \
  "$CPP/legacy_main/vision2/HzzsVisionCore.cpp" \
  "$CPP/legacy_main/vision_bamboo/BambooVisionCore.cpp" \
  "$CPP/legacy_main/vision_bamboo/BambooVisionEngine.cpp" \
  -o "$OUT"
asan_options="detect_leaks=1"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) asan_options="detect_leaks=0" ;;
esac
ASAN_OPTIONS="$asan_options" UBSAN_OPTIONS=print_stacktrace=1 "$OUT"
