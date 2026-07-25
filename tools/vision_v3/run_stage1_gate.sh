#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /absolute/path/to/海盐客厅" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DATASET="$1"
OUT="$ROOT/build/vision_v3/gate"
mkdir -p "$OUT"

bash "$ROOT/tools/vision_v3/build_and_test.sh"
python -m compileall -q "$ROOT/tools/vision_v3"

python "$ROOT/tools/vision_v3/reference_and_benchmark.py" \
  --dataset "$DATASET" \
  --library "$ROOT/build/vision_v3/libhzzs_vision_v3.so" \
  --metric box --iterations 5 \
  --output "$OUT/exact.json"

python "$ROOT/tools/vision_v3/benchmark_fast.py" \
  --dataset "$DATASET" \
  --library "$ROOT/build/vision_v3/libhzzs_vision_v3.so" \
  --metric box --iterations 5 --samples 360 \
  --output "$OUT/fast_exact.json"

python "$ROOT/tools/vision_v3/benchmark_sea_ground_truth.py" \
  --dataset "$DATASET" \
  --ground-truth "$ROOT/tools/vision_v3/ground_truth/sea_salt_114_v2.json" \
  --library "$ROOT/build/vision_v3/libhzzs_vision_v3.so" \
  --metric box --iterations 10 --samples 360 \
  --output "$OUT/builtin.json"

python "$ROOT/tools/vision_v3/validate_stage1.py" \
  --exact "$OUT/exact.json" \
  --fast-exact "$OUT/fast_exact.json" \
  --builtin "$OUT/builtin.json"
