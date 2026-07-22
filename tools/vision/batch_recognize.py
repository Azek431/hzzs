#!/usr/bin/env python3
"""Batch-run host native vision on the local image corpus and write overlays.

Does not claim accuracy. Reports timing and detection counts only.
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate_dataset import COLORS, KINDS, HostVision, scene_for  # noqa: E402

DEFAULT_INPUT = Path(r"D:\Code\AI\火崽崽\火崽崽奇妙屋\算法测试\测试图片")
DEFAULT_OUTPUT = Path(r"D:\Code\AI\火崽崽\火崽崽奇妙屋\算法测试\识别结果")


def collect_images(root: Path) -> list[Path]:
    files: list[Path] = []
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
            files.append(path)
    return files


def draw_result(bgr: np.ndarray, result: dict) -> np.ndarray:
    out = bgr.copy()
    h, w = out.shape[:2]
    for det in result.get("detections", []):
        kind_name = det.get("kind", "unknown")
        kind_index = KINDS.index(kind_name) if kind_name in KINDS else 0
        color = COLORS[kind_index % len(COLORS)]
        left = int(float(det["left"]) * w)
        top = int(float(det["top"]) * h)
        right = int(float(det["right"]) * w)
        bottom = int(float(det["bottom"]) * h)
        cv2.rectangle(out, (left, top), (right, bottom), color, 2)
        conf = float(det.get("confidence", 0.0))
        cv2.putText(
            out,
            f"{kind_name}:{conf:.2f}",
            (left, max(16, top - 6)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.45,
            color,
            1,
            cv2.LINE_AA,
        )
    ms = float(result.get("elapsedMs", result.get("cost_ms", 0.0)))
    scene = int(result.get("scene", -1))
    conf = float(result.get("sceneConfidence", 0.0))
    cv2.putText(
        out,
        f"scene={scene} conf={conf:.2f} {ms:.1f}ms",
        (12, 24),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        (20, 220, 20),
        2,
        cv2.LINE_AA,
    )
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--work-width", type=int, default=320)
    parser.add_argument("--max-images", type=int, default=0)
    parser.add_argument("--library", type=Path, default=None)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()

    if not args.input.is_dir():
        raise SystemExit(f"input missing: {args.input}")

    lib = args.library
    if lib is None:
        host_dir = ROOT / "build" / "host"
        host_dir.mkdir(parents=True, exist_ok=True)
        if not args.skip_build:
            # Prefer native Windows build script when bash/WSL unavailable.
            win_script = ROOT / "tools" / "vision" / "build_host.ps1"
            sh_script = ROOT / "tools" / "vision" / "build_host.sh"
            if win_script.exists():
                subprocess.check_call(
                    ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(win_script)],
                )
            else:
                subprocess.check_call(["bash", str(sh_script)])
        candidates = list(host_dir.glob("libhzzs_vision.*")) + list(host_dir.glob("hzzs_vision.*"))
        if not candidates:
            raise SystemExit("host library missing; build failed")
        lib = candidates[0]

    vision = HostVision(lib)
    images = collect_images(args.input)
    if args.max_images > 0:
        images = images[: args.max_images]
    if not images:
        raise SystemExit("no images found")

    args.output.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    by_scene: dict[int, list[float]] = defaultdict(list)
    total_dets = 0

    for index, path in enumerate(images, start=1):
        data = np.fromfile(str(path), dtype=np.uint8)
        bgr = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if bgr is None:
            continue
        scene = scene_for(path)
        wall_started = time.perf_counter()
        result = vision.analyze(bgr, scene=scene, work_width=args.work_width)
        wall_ms = (time.perf_counter() - wall_started) * 1000.0
        native_ms = float(result.get("elapsedMs", wall_ms))
        result["scene"] = scene
        result["cost_ms"] = native_ms
        dets = result.get("detections", [])
        total_dets += len(dets)
        by_scene[scene].append(native_ms)

        rel = path.relative_to(args.input)
        out_img = args.output / f"{str(rel).replace(chr(92), '__').replace('/', '__')}_vision.jpg"
        overlay = draw_result(bgr, result)
        ok, buf = cv2.imencode(".jpg", overlay)
        if ok:
            buf.tofile(str(out_img))

        rows.append(
            {
                "index": index,
                "file": str(rel),
                "scene": scene,
                "cost_ms": round(native_ms, 3),
                "wall_ms": round(wall_ms, 3),
                "scene_confidence": round(float(result.get("sceneConfidence", 0.0)), 4),
                "detections": len(dets),
                "kinds": ",".join(str(d.get("kind")) for d in dets),
                "error": result.get("error", ""),
            }
        )
        if index % 25 == 0 or index == len(images):
            print(
                f"[{index}/{len(images)}] {rel} scene={scene} dets={len(dets)} "
                f"native={native_ms:.1f}ms wall={wall_ms:.1f}ms"
            )

    costs = [r["cost_ms"] for r in rows]
    summary = {
        "images": len(rows),
        "detections": total_dets,
        "work_width": args.work_width,
        "library": str(lib),
        "cost_ms": {
            "mean": statistics.fmean(costs) if costs else 0.0,
            "p50": float(np.percentile(costs, 50)) if costs else 0.0,
            "p95": float(np.percentile(costs, 95)) if costs else 0.0,
            "max": max(costs) if costs else 0.0,
        },
        "by_scene": {
            str(scene): {
                "images": len(values),
                "mean_ms": statistics.fmean(values) if values else 0.0,
                "p95_ms": float(np.percentile(values, 95)) if values else 0.0,
                "max_ms": max(values) if values else 0.0,
            }
            for scene, values in sorted(by_scene.items())
        },
        "note": "No accuracy claim. native elapsedMs excludes Python decode/draw.",
    }

    csv_path = args.output / "detection_summary.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(
            fh,
            fieldnames=[
                "index",
                "file",
                "scene",
                "cost_ms",
                "wall_ms",
                "scene_confidence",
                "detections",
                "kinds",
                "error",
            ],
        )
        writer.writeheader()
        writer.writerows(rows)

    json_path = args.output / "detection_summary.json"
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"wrote {csv_path}")
    print(f"wrote {json_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
