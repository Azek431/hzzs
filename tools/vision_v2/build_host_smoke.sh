#!/usr/bin/env bash
# Build and run FastContourV2 host smokes (isolated from Android NDK / libhzzs_vision).
# Prefer: bash tools/vision_v2/build_host_smoke.sh
# Options:
#   --sanitize=address|undefined
#   --test=all|core|boundary|pipeline|profiles|sea_gap
#   --skip-run
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CPP_DIR="$ROOT/app/src/main/cpp/vision_v2"
OUT_DIR="$ROOT/build/vision-v2-host"
mkdir -p "$OUT_DIR"

SANITIZE=""
TEST="all"
SKIP_RUN=0
for arg in "$@"; do
  case "$arg" in
    --sanitize=address) SANITIZE="address" ;;
    --sanitize=undefined) SANITIZE="undefined" ;;
    --test=all|--test=core|--test=boundary|--test=pipeline|--test=profiles|--test=sea_gap) TEST="${arg#--test=}" ;;
    --skip-run) SKIP_RUN=1 ;;
    -h|--help)
      echo "Usage: bash tools/vision_v2/build_host_smoke.sh [--sanitize=address|undefined] [--test=all|core|boundary|pipeline|profiles|sea_gap] [--skip-run]"
      exit 0
      ;;
    *)
      echo "Unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

CXX=""
for cand in clang++ g++; do
  if command -v "$cand" >/dev/null 2>&1; then
    CXX=$cand
    break
  fi
done
if [[ -z "$CXX" ]]; then
  echo "No clang++/g++ in PATH" >&2
  exit 1
fi

COMMON=(-std=c++20 -Wall -Wextra -Werror -O1 -g -I"$CPP_DIR")
if [[ "$SANITIZE" == "address" ]]; then
  COMMON+=(-fsanitize=address)
elif [[ "$SANITIZE" == "undefined" ]]; then
  COMMON+=(-fsanitize=undefined)
fi

SUFFIX=""
if [[ -n "$SANITIZE" ]]; then
  SUFFIX="_${SANITIZE}"
fi

run_one() {
  local name=$1
  shift
  local exe="$OUT_DIR/fast_contour_${name}${SUFFIX}"
  echo "+ $CXX ${COMMON[*]} $* -o $exe"
  "$CXX" "${COMMON[@]}" "$@" -o "$exe"
  if [[ "$SKIP_RUN" -eq 0 ]]; then
    echo "+ $exe"
    "$exe"
  fi
  echo "PASS ${name}${SUFFIX} -> $exe"
}

ran=0
if [[ "$TEST" == "all" || "$TEST" == "core" ]]; then
  run_one core_test "$CPP_DIR/fast_contour_core.cpp" "$CPP_DIR/fast_contour_core_test.cpp"
  ran=1
fi
if [[ "$TEST" == "all" || "$TEST" == "boundary" ]]; then
  run_one boundary_test "$CPP_DIR/fast_contour_core.cpp" "$CPP_DIR/fast_contour_core_boundary_test.cpp"
  ran=1
fi
if [[ "$TEST" == "all" || "$TEST" == "pipeline" ]]; then
  run_one pipeline_test \
    "$CPP_DIR/fast_contour_core.cpp" \
    "$CPP_DIR/fast_contour_pipeline.cpp" \
    "$CPP_DIR/fast_contour_pipeline_test.cpp"
  ran=1
fi
if [[ "$TEST" == "all" || "$TEST" == "profiles" ]]; then
  run_one profiles_test \
    "$CPP_DIR/fast_contour_core.cpp" \
    "$CPP_DIR/fast_contour_pipeline.cpp" \
    "$CPP_DIR/fast_contour_profiles_test.cpp"
  ran=1
fi
if [[ "$TEST" == "all" || "$TEST" == "sea_gap" ]]; then
  run_one sea_gap_test \
    "$CPP_DIR/fast_contour_core.cpp" \
    "$CPP_DIR/fast_contour_pipeline.cpp" \
    "$CPP_DIR/fast_contour_sea_gap_test.cpp"
  ran=1
fi
if [[ "$ran" -eq 0 ]]; then
  echo "no tests selected" >&2
  exit 1
fi
echo "All selected FastContourV2 host smokes passed."
