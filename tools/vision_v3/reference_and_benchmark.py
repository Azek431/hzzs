from __future__ import annotations

import argparse
import ctypes
import json
import math
import os
import statistics
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

BASE_W, BASE_H = 1272, 2772
PATTERNS = [
    ("大断崖", -14134134, [(57, 86, -659994), (206, 17, -66830), (294, 65, -198418), (633, 88, -67343), (739, 168, -1514535), (733, 29, -12676910)]),
    ("小断崖", -13605719, [(69, 29, -462617), (144, 63, -395286), (333, 63, -198418), (422, 87, -3369373)]),
    ("矮沙丘", -400963, [(70, -188, -399674), (121, -210, -268857), (198, -171, -1720697), (225, 2, -4088705)]),
    ("高沙丘", -465469, [(44, -216, -1854336), (141, -413, -4042089), (210, -180, -993359), (231, -24, -4355479)]),
    ("船锚", -4603180, [(68, 22, -9666401), (150, -18, -2235925), (50, 78, -8881018), (116, 50, -11382703), (11, -55, -5539265)]),
    ("复活", -51345, [(71, 42, -5918), (176, 121, -117170), (337, 68, -237986), (300, -46, -3595), (380, 75, -34736)]),
]


def js_round(value: float) -> int:
    return math.floor(value + 0.5)


def int_to_rgb(value: int) -> np.ndarray:
    u = value & 0xFFFFFFFF
    return np.array([(u >> 16) & 255, (u >> 8) & 255, u & 255], dtype=np.int16)


def verify_color(actual: np.ndarray, expected: np.ndarray, threshold: int, metric: int) -> bool:
    delta = np.abs(actual.astype(np.int16) - expected)
    if metric == 1:
        return int(delta.sum()) <= threshold * 3
    return int(delta.max()) <= threshold


def reference_detect(image_bgr: np.ndarray, threshold: int, metric: int):
    rgb = image_bgr[:, :, ::-1]
    h, w = rgb.shape[:2]
    sx, sy = w / BASE_W, h / BASE_H
    rx = max(0, min(w, js_round(290 * sx)))
    ry = max(0, min(h, js_round(1213 * sy)))
    x2 = max(0, min(w, rx + max(0, js_round(982 * sx))))
    y2 = max(0, min(h, ry + max(0, js_round(1229 * sy))))
    for kind, first, points in PATTERNS:
        target = int_to_rgb(first)
        roi = rgb[ry:y2, rx:x2]
        # Anchor is always OpenCV inRange semantics.
        mask = np.max(np.abs(roi.astype(np.int16) - target), axis=2) <= threshold
        ys, xs = np.nonzero(mask)
        for yy, xx in zip(ys.tolist(), xs.tolist()):
            ax, ay = rx + xx, ry + yy
            ok = True
            for dx, dy, color in points:
                tx = ax + js_round(dx * sx)
                ty = ay + js_round(dy * sy)
                if tx < 0 or tx >= w or ty < 0 or ty >= h or not verify_color(rgb[ty, tx], int_to_rgb(color), threshold, metric):
                    ok = False
                    break
            if ok:
                return kind, ax, ay
    return None


class Config(ctypes.Structure):
    _fields_ = [("threshold", ctypes.c_uint8), ("verification_metric", ctypes.c_uint8), ("visual_margin_permille", ctypes.c_uint16)]


class Result(ctypes.Structure):
    _fields_ = [
        ("found", ctypes.c_uint8), ("action_triggered", ctypes.c_uint8),
        ("kind", ctypes.c_uint8), ("action", ctypes.c_uint8),
        ("anchor_x", ctypes.c_int), ("anchor_y", ctypes.c_int), ("swipe_end_y", ctypes.c_int),
        ("visual_left", ctypes.c_float), ("visual_top", ctypes.c_float),
        ("visual_right", ctypes.c_float), ("visual_bottom", ctypes.c_float),
        ("action_left", ctypes.c_float), ("action_top", ctypes.c_float),
        ("action_right", ctypes.c_float), ("action_bottom", ctypes.c_float),
    ]


NAMES = [p[0] for p in PATTERNS]


def to_argb(image_bgr: np.ndarray) -> np.ndarray:
    b = image_bgr[:, :, 0].astype(np.uint32)
    g = image_bgr[:, :, 1].astype(np.uint32)
    r = image_bgr[:, :, 2].astype(np.uint32)
    return np.ascontiguousarray(0xFF000000 | (r << 16) | (g << 8) | b, dtype=np.uint32)


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    arr = np.asarray(values, dtype=np.float64)
    return float(np.percentile(arr, p))


def image_paths(root: Path) -> list[Path]:
    suffixes = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
    return sorted(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in suffixes)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--metric", choices=["box", "mean_l1"], default="box")
    parser.add_argument("--iterations", type=int, default=8)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    metric = 1 if args.metric == "mean_l1" else 0
    lib = ctypes.CDLL(str(args.library))
    lib.hzzs_soy_create.argtypes = [ctypes.POINTER(Config)]
    lib.hzzs_soy_create.restype = ctypes.c_void_p
    lib.hzzs_soy_destroy.argtypes = [ctypes.c_void_p]
    lib.hzzs_soy_detect.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.POINTER(Result)]
    lib.hzzs_soy_detect.restype = ctypes.c_int

    config = Config(10, metric, 10)
    handle = lib.hzzs_soy_create(ctypes.byref(config))
    if not handle:
        raise RuntimeError("failed to create native engine")

    records = []
    timings_ms: list[float] = []
    frame_medians_ms: list[float] = []
    mismatches = []
    try:
        for path in image_paths(args.dataset):
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if image is None:
                records.append({"file": str(path), "read": False})
                continue
            argb = to_argb(image)
            h, w = argb.shape
            ptr = argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32))
            native = Result()
            lib.hzzs_soy_detect(handle, ptr, w, h, w, ctypes.byref(native))
            expected = reference_detect(image, 10, metric)
            actual = None if not native.found else (NAMES[native.kind], native.anchor_x, native.anchor_y)
            equal = actual == expected
            if not equal:
                mismatches.append({"file": str(path), "expected": expected, "actual": actual})

            # Warm cache, then measure only native algorithm execution.
            for _ in range(2):
                lib.hzzs_soy_detect(handle, ptr, w, h, w, ctypes.byref(native))
            local = []
            for _ in range(max(1, args.iterations)):
                start = time.perf_counter_ns()
                lib.hzzs_soy_detect(handle, ptr, w, h, w, ctypes.byref(native))
                local.append((time.perf_counter_ns() - start) / 1_000_000.0)
            timings_ms.extend(local)
            frame_medians_ms.append(statistics.median(local))
            records.append({
                "file": str(path), "read": True, "width": w, "height": h,
                "expected": expected, "actual": actual, "equal": equal,
                "median_ms": statistics.median(local),
            })
    finally:
        lib.hzzs_soy_destroy(handle)

    report = {
        "metric": args.metric,
        "images": len(records),
        "read_ok": sum(bool(r.get("read")) for r in records),
        "mismatch_count": len(mismatches),
        "mismatches": mismatches,
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
        "detections": {name: sum(1 for r in records if r.get("actual") and r["actual"][0] == name) for name in NAMES},
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
