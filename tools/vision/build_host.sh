#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CPP="$ROOT/app/src/main/cpp"
mkdir -p "$ROOT/build/host"
g++ -std=c++17 -O3 -DNDEBUG -fPIC -shared \
  -I"$CPP" \
  "$CPP/vision_engine.cpp" "$CPP/sweet_factory.cpp" "$CPP/bamboo_bookstore.cpp" \
  "$ROOT/tools/vision/host_api.cpp" \
  -o "$ROOT/build/host/libhzzs_vision.so"
echo "$ROOT/build/host/libhzzs_vision.so"
