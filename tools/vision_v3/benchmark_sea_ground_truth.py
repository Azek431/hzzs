from __future__ import annotations

import argparse
import ctypes
import json
import statistics
import time
from pathlib import Path

import cv2

from benchmark_fast import FastConfig
from reference_and_benchmark import NAMES, Result, percentile, to_argb


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--ground-truth", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--metric", choices=["box", "mean_l1"], default="box")
    parser.add_argument("--iterations", type=int, default=30)
    parser.add_argument("--samples", type=int, default=360)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    truth = json.loads(args.ground_truth.read_text(encoding="utf-8"))
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

    # Builtin tolerant mode: wider left scan, 3x3 verification, no source-anchor restriction.
    config = FastConfig(16, 16, metric, 1, 0, 0, args.samples, 150)
    handle = lib.hzzs_sea_fast_create(ctypes.byref(config))
    if not handle:
        raise RuntimeError("failed to create native builtin engine")

    errors: list[dict[str, object]] = []
    edge_errors: list[float] = []
    timings_ms: list[float] = []
    frame_medians_ms: list[float] = []
    detections = {name: 0 for name in NAMES}
    try:
        for record in truth["records"]:
            path = args.dataset / record["relative_path"]
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if image is None:
                errors.append({"file": record["file"], "error": "read_failed"})
                continue
            argb = to_argb(image)
            height, width = argb.shape
            pointer = argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32))
            result = Result()
            lib.hzzs_sea_fast_detect(
                handle, pointer, width, height, width, ctypes.byref(result)
            )
            actual = None if not result.found else {
                "kind": NAMES[result.kind],
                "anchor_x": result.anchor_x,
                "anchor_y": result.anchor_y,
            }
            expected = record["primary"]
            if expected is None:
                if actual is not None:
                    errors.append({
                        "file": record["file"],
                        "error": "false_positive",
                        "actual": actual,
                    })
            elif actual is None:
                errors.append({
                    "file": record["file"],
                    "error": "false_negative",
                    "expected": expected,
                })
            elif actual["kind"] != expected["kind"]:
                errors.append({
                    "file": record["file"],
                    "error": "wrong_class",
                    "expected": expected,
                    "actual": actual,
                })
            else:
                if expected.get("geometry_gate", True):
                    error_ratio = abs(actual["anchor_x"] - expected["anchor_x"]) / width
                    edge_errors.append(error_ratio)
                    if error_ratio > 0.01:
                        errors.append({
                            "file": record["file"],
                            "error": "action_edge_over_1_percent",
                            "expected": expected,
                            "actual": actual,
                            "error_ratio": error_ratio,
                        })
                detections[actual["kind"]] += 1

            for _ in range(3):
                lib.hzzs_sea_fast_detect(
                    handle, pointer, width, height, width, ctypes.byref(result)
                )
            local: list[float] = []
            for _ in range(max(1, args.iterations)):
                start = time.perf_counter_ns()
                lib.hzzs_sea_fast_detect(
                    handle, pointer, width, height, width, ctypes.byref(result)
                )
                local.append((time.perf_counter_ns() - start) / 1_000_000.0)
            timings_ms.extend(local)
            frame_medians_ms.append(statistics.median(local))
    finally:
        lib.hzzs_sea_fast_destroy(handle)

    report = {
        "metric": args.metric,
        "mode": "builtin_tolerant",
        "frames": truth["frame_count"],
        "ground_truth_positives": truth["positive_count"],
        "geometry_gated_positives": sum(
            1 for record in truth["records"]
            if record.get("primary") is not None and record["primary"].get("geometry_gate", True)
        ),
        "error_count": len(errors),
        "errors": errors,
        "detections": detections,
        "action_edge_error": {
            "samples": len(edge_errors),
            "mean_ratio": statistics.fmean(edge_errors) if edge_errors else 0.0,
            "p95_ratio": percentile(edge_errors, 95),
            "max_ratio": max(edge_errors, default=0.0),
        },
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
        "limitations": truth["limitations"],
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
