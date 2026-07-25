from __future__ import annotations

import argparse
import ctypes
import json
import statistics
import time
from pathlib import Path

import cv2

from reference_and_benchmark import (
    NAMES,
    Result,
    image_paths,
    percentile,
    reference_detect,
    to_argb,
)


class FastConfig(ctypes.Structure):
    _fields_ = [
        ("anchor_threshold", ctypes.c_uint8),
        ("verify_threshold", ctypes.c_uint8),
        ("verification_metric", ctypes.c_uint8),
        ("neighborhood_radius", ctypes.c_uint8),
        ("enforce_source_anchor_region", ctypes.c_uint8),
        ("require_exact_anchor_pattern", ctypes.c_uint8),
        ("horizontal_samples", ctypes.c_uint16),
        ("minimum_scan_x_permille", ctypes.c_uint16),
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--metric", choices=["box", "mean_l1"], default="box")
    parser.add_argument("--iterations", type=int, default=20)
    parser.add_argument("--samples", type=int, default=360)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    metric = 1 if args.metric == "mean_l1" else 0
    lib = ctypes.CDLL(str(args.library))
    lib.hzzs_sea_fast_create.argtypes = [ctypes.POINTER(FastConfig)]
    lib.hzzs_sea_fast_create.restype = ctypes.c_void_p
    lib.hzzs_sea_fast_destroy.argtypes = [ctypes.c_void_p]
    lib.hzzs_sea_fast_detect.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.POINTER(Result),
    ]
    lib.hzzs_sea_fast_detect.restype = ctypes.c_int

    config = FastConfig(10, 10, metric, 0, 1, 1, args.samples, 228)
    handle = lib.hzzs_sea_fast_create(ctypes.byref(config))
    if not handle:
        raise RuntimeError("failed to create native fast engine")

    timings_ms: list[float] = []
    frame_medians_ms: list[float] = []
    mismatches: list[dict[str, object]] = []
    detections = {name: 0 for name in NAMES}
    read_ok = 0

    try:
        for path in image_paths(args.dataset):
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if image is None:
                continue
            read_ok += 1
            argb = to_argb(image)
            height, width = argb.shape
            pointer = argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32))
            result = Result()

            lib.hzzs_sea_fast_detect(
                handle,
                pointer,
                width,
                height,
                width,
                ctypes.byref(result),
            )
            expected = reference_detect(image, 10, metric)
            actual = None if not result.found else (
                NAMES[result.kind],
                result.anchor_x,
                result.anchor_y,
            )

            # Builtin fast handles obstacle classes only; revive remains a separate UI action path.
            if expected and expected[0] == "复活":
                expected = None
            if actual != expected:
                mismatches.append({
                    "file": str(path),
                    "expected": expected,
                    "actual": actual,
                })
            if actual:
                detections[actual[0]] += 1

            for _ in range(3):
                lib.hzzs_sea_fast_detect(
                    handle,
                    pointer,
                    width,
                    height,
                    width,
                    ctypes.byref(result),
                )
            local: list[float] = []
            for _ in range(max(1, args.iterations)):
                start = time.perf_counter_ns()
                lib.hzzs_sea_fast_detect(
                    handle,
                    pointer,
                    width,
                    height,
                    width,
                    ctypes.byref(result),
                )
                local.append((time.perf_counter_ns() - start) / 1_000_000.0)
            timings_ms.extend(local)
            frame_medians_ms.append(statistics.median(local))
    finally:
        lib.hzzs_sea_fast_destroy(handle)

    report = {
        "metric": args.metric,
        "horizontal_samples": args.samples,
        "read_ok": read_ok,
        "mismatch_count": len(mismatches),
        "mismatches": mismatches,
        "detections": detections,
        "timing": {
            "samples": len(timings_ms),
            "mean_ms": statistics.fmean(timings_ms) if timings_ms else 0.0,
            "p50_ms": percentile(timings_ms, 50),
            "p95_ms": percentile(timings_ms, 95),
            "p99_ms": percentile(timings_ms, 99),
            "max_ms": max(timings_ms, default=0.0),
            "frame_median_p50_ms": percentile(frame_medians_ms, 50),
            "frame_median_p95_ms": percentile(frame_medians_ms, 95),
            "frame_median_p99_ms": percentile(frame_medians_ms, 99),
        },
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
