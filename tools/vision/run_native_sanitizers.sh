#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/host/native_tests"
mkdir -p "$(dirname "$OUT")"
g++ -std=c++17 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$ROOT/app/src/main/cpp" \
  "$ROOT/app/src/test/cpp/native_tests.cpp" \
  "$ROOT/app/src/main/cpp/vision_engine.cpp" \
  "$ROOT/app/src/main/cpp/sweet_factory.cpp" \
  "$ROOT/app/src/main/cpp/bamboo_bookstore.cpp" \
  -o "$OUT"
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=print_stacktrace=1 "$OUT"
