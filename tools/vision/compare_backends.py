#!/usr/bin/env python3
"""Compare DEFAULT vs NATIVE_VISION backend on sea-salt test images."""

from __future__ import annotations

import ctypes
import json
import statistics
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from host_build import ensure_host_library

KINDS = [
    "player", "green-bottle", "cake-structure", "hanging-spike", "pit",
    "panda-statue", "bamboo-gap", "hanging-brush", "sand-castle",
    "hanging-anchor", "sea-pit",
]


def cstr(s: str) -> ctypes.c_char_p:
    return ctypes.c_char_p(s.encode("utf-8"))


class DualHostVision:
    def __init__(self, library: Path):
        self.lib = ctypes.CDLL(str(library))

        # DEFAULT backend: hzzs_analyze_host
        self._fn_default = self.lib.hzzs_analyze_host
        self._fn_default.argtypes = [
            ctypes.c_int, ctypes.POINTER(ctypes.c_uint32),
            ctypes.c_int, ctypes.c_int, ctypes.c_int,
            ctypes.POINTER(ctypes.c_float), ctypes.c_int,
        ]
        self._fn_default.restype = ctypes.c_int

        # NATIVE_VISION backend: hzzs_analyze_host_backend
        self._fn_native = self.lib.hzzs_analyze_host_backend
        self._fn_native.argtypes = [
            ctypes.c_int, ctypes.POINTER(ctypes.c_uint32),
            ctypes.c_int, ctypes.c_int, ctypes.c_int,
            ctypes.c_char_p,
            ctypes.POINTER(ctypes.c_float), ctypes.c_int,
        ]
        self._fn_native.restype = ctypes.c_int

    def _call(self, fn, bgr: np.ndarray, scene: int, work_width: int = 320,
              backend_id: str | None = None) -> dict:
        height, width = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.uint32)
        argb = np.ascontiguousarray(
            (0xFF000000 | (rgb[:, :, 0] << 16) | (rgb[:, :, 1] << 8) | rgb[:, :, 2]).ravel(),
            dtype=np.uint32,
        )
        output = np.zeros(1 + 64 * 10, dtype=np.float32)
        started = time.perf_counter_ns()
        if backend_id is not None:
            count = fn(scene, argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32)),
                       width, height, work_width, cstr(backend_id),
                       output.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 64)
        else:
            count = fn(scene, argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32)),
                       width, height, work_width,
                       output.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 64)
        elapsed_ms = (time.perf_counter_ns() - started) / 1e6

        if count < 0:
            return {"error": f"native error {count}", "sceneConfidence": 0.0,
                    "detections": [], "elapsedMs": elapsed_ms}

        detections = []
        for i in range(count):
            row = output[1 + i * 10: 1 + (i + 1) * 10]
            kind_idx = int(round(float(row[1])))
            detections.append({
                "trackHint": int(round(float(row[0]))),
                "kind": KINDS[kind_idx] if 0 <= kind_idx < len(KINDS) else f"unknown-{kind_idx}",
                "left": float(row[2]), "top": float(row[3]),
                "right": float(row[4]), "bottom": float(row[5]),
                "confidence": float(row[6]),
                "actionable": bool(row[7] > 0.5),
                "diagnosticOnly": bool(row[8] > 0.5),
                "avoidance": int(round(float(row[9]))),
            })
        return {
            "sceneConfidence": float(output[0]),
            "detections": detections,
            "elapsedMs": elapsed_ms,
        }

    def analyze_default(self, bgr: np.ndarray, scene: int) -> dict:
        return self._call(self._fn_default, bgr, scene)

    def analyze_native_vision(self, bgr: np.ndarray, scene: int) -> dict:
        return self._call(self._fn_native, bgr, scene, backend_id="native_vision")


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--output", default=str(Path(__file__).resolve().parent / "eval_output"))
    parser.add_argument("--project-root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()

    project = Path(args.project_root)
    library = ensure_host_library(project)
    engine = DualHostVision(library)
    dataset = Path(args.dataset)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    # Only sea-salt images
    sea_salt_dir = None
    for d in dataset.iterdir():
        if "海盐" in d.name or "sea" in d.name.lower() or "salt" in d.name.lower():
            sea_salt_dir = d
            break
    if sea_salt_dir is None:
        print("No sea-salt directory found")
        sys.exit(1)

    suffixes = {".jpg", ".jpeg", ".png", ".webp"}
    images = sorted(p for p in sea_salt_dir.rglob("*") if p.suffix.lower() in suffixes)
    if args.limit:
        images = images[: args.limit]

    print(f"Sea-salt images: {len(images)}")
    print(f"Library: {library}")
    print()

    # Warm up
    try:
        warm_data = images[0].read_bytes()
        warm = cv2.imdecode(np.frombuffer(warm_data, np.uint8), cv2.IMREAD_COLOR)
    except OSError:
        warm = None
    if warm is not None:
        for _ in range(3):
            engine.analyze_default(warm, 2)
            engine.analyze_native_vision(warm, 2)

    records = []
    default_times: list[float] = []
    native_times: list[float] = []

    for path in images:
        try:
            data = path.read_bytes()
        except OSError:
            continue
        image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            print(f"  WARN: cannot decode {path.name}")
            continue

        r_default = engine.analyze_default(image, 2)
        r_native = engine.analyze_native_vision(image, 2)

        default_times.append(r_default["elapsedMs"])
        native_times.append(r_native["elapsedMs"])

        records.append({
            "file": path.name,
            "width": image.shape[1],
            "height": image.shape[0],
            "default": {
                "sceneConfidence": r_default["sceneConfidence"],
                "detections": len(r_default["detections"]),
                "actionable": sum(1 for d in r_default["detections"] if d["actionable"]),
                "kinds": [d["kind"] for d in r_default["detections"]],
                "elapsedMs": r_default["elapsedMs"],
            },
            "native_vision": {
                "sceneConfidence": r_native["sceneConfidence"],
                "detections": len(r_native["detections"]),
                "actionable": sum(1 for d in r_native["detections"] if d["actionable"]),
                "kinds": [d["kind"] for d in r_native["detections"]],
                "elapsedMs": r_native["elapsedMs"],
            },
        })

    # Summary
    print(f"{'='*60}")
    print(f"DEFAULT backend (sea_salt_living_room):")
    if default_times:
        default_times.sort()
        n = len(default_times)
        print(f"  P50: {default_times[n//2]:.2f}ms  P95: {default_times[int(n*0.95)]:.2f}ms  "
              f"P99: {default_times[int(n*0.99)]:.2f}ms  max: {default_times[-1]:.2f}ms")
        print(f"  mean: {statistics.mean(default_times):.2f}ms")

    print(f"\nNATIVE_VISION backend (SeaSaltV3Engine):")
    if native_times:
        native_times.sort()
        n = len(native_times)
        print(f"  P50: {native_times[n//2]:.2f}ms  P95: {native_times[int(n*0.95)]:.2f}ms  "
              f"P99: {native_times[int(n*0.99)]:.2f}ms  max: {native_times[-1]:.2f}ms")
        print(f"  mean: {statistics.mean(native_times):.2f}ms")

    # Detection comparison
    print(f"\n{'='*60}")
    print(f"{'File':<30} {'Def conf':>9} {'NV conf':>9} {'Def #':>6} {'NV #':>6} {'Def ms':>8} {'NV ms':>8}")
    print(f"{'-'*30} {'-'*9} {'-'*9} {'-'*6} {'-'*6} {'-'*8} {'-'*8}")
    for r in records:
        print(f"{r['file']:<30} {r['default']['sceneConfidence']:>9.3f} "
              f"{r['native_vision']['sceneConfidence']:>9.3f} "
              f"{r['default']['detections']:>6} {r['native_vision']['detections']:>6} "
              f"{r['default']['elapsedMs']:>8.1f} {r['native_vision']['elapsedMs']:>8.1f}")

    # Save JSON
    result_path = output / "comparison.json"
    with open(result_path, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=2)
    print(f"\nSaved: {result_path}")


if __name__ == "__main__":
    main()
