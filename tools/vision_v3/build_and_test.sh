#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CPP="$ROOT/app/src/main/cpp/vision_v3"
OUT="$ROOT/build/vision_v3"
mkdir -p "$OUT"

CXX="${CXX:-g++}"
COMMON=(
  -std=c++17
  -Wall
  -Wextra
  -Werror
  -fno-rtti
  -I"$CPP"
  -I"$ROOT/app/src/main/cpp/hud_v3"
)

"$CXX" "${COMMON[@]}" -O2 \
  "$CPP/soy_sauce_exact.cpp" \
  "$CPP/soy_sauce_exact_test.cpp" \
  -o "$OUT/soy_sauce_exact_test"
"$OUT/soy_sauce_exact_test"

"$CXX" "${COMMON[@]}" -O2 \
  "$CPP/soy_sauce_exact.cpp" \
  "$CPP/sea_salt_fast.cpp" \
  "$CPP/sea_salt_fast_test.cpp" \
  -o "$OUT/sea_salt_fast_test"
"$OUT/sea_salt_fast_test"

"$CXX" "${COMMON[@]}" -O2 \
  "$CPP/soy_sauce_exact.cpp" \
  "$CPP/sea_salt_fast.cpp" \
  "$CPP/sea_salt_v3.cpp" \
  "$CPP/sea_salt_v3_test.cpp" \
  -o "$OUT/sea_salt_v3_test"

"$CXX" "${COMMON[@]}" -O2 -pthread \
  "$ROOT/app/src/main/cpp/hud_v3/hud_snapshot.cpp" \
  "$ROOT/app/src/main/cpp/hud_v3/hud_snapshot_test.cpp" \
  -o "$OUT/hud_snapshot_test"
"$OUT/hud_snapshot_test"
"$OUT/sea_salt_v3_test"

if [[ "${SANITIZE:-1}" == "1" ]]; then
  SAN_FLAGS=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined)
  "$CXX" "${COMMON[@]}" "${SAN_FLAGS[@]}" \
    "$CPP/soy_sauce_exact.cpp" \
    "$CPP/soy_sauce_exact_test.cpp" \
    -o "$OUT/soy_sauce_exact_sanitized_test"
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$OUT/soy_sauce_exact_sanitized_test"

  "$CXX" "${COMMON[@]}" "${SAN_FLAGS[@]}" \
    "$CPP/soy_sauce_exact.cpp" \
    "$CPP/sea_salt_fast.cpp" \
    "$CPP/sea_salt_fast_test.cpp" \
    -o "$OUT/sea_salt_fast_sanitized_test"
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$OUT/sea_salt_fast_sanitized_test"

  "$CXX" "${COMMON[@]}" "${SAN_FLAGS[@]}" \
    "$CPP/soy_sauce_exact.cpp" \
    "$CPP/sea_salt_fast.cpp" \
    "$CPP/sea_salt_v3.cpp" \
    "$CPP/sea_salt_v3_test.cpp" \
    -o "$OUT/sea_salt_v3_sanitized_test"
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$OUT/sea_salt_v3_sanitized_test"

  "$CXX" "${COMMON[@]}" "${SAN_FLAGS[@]}" -pthread \
    "$ROOT/app/src/main/cpp/hud_v3/hud_snapshot.cpp" \
    "$ROOT/app/src/main/cpp/hud_v3/hud_snapshot_test.cpp" \
    -o "$OUT/hud_snapshot_sanitized_test"
  ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$OUT/hud_snapshot_sanitized_test"
fi

HOST_OPT=(-O3 -DNDEBUG -fPIC -flto)
if [[ "${NATIVE_ARCH:-1}" == "1" ]]; then
  HOST_OPT+=(-march=native)
fi
"$CXX" "${COMMON[@]}" "${HOST_OPT[@]}" -shared \
  "$CPP/soy_sauce_exact.cpp" \
  "$CPP/sea_salt_fast.cpp" \
  "$CPP/sea_salt_v3.cpp" \
  -o "$OUT/libhzzs_vision_v3.so"

echo "PASS: C++17 tests + sanitizer"
echo "$OUT/libhzzs_vision_v3.so"
