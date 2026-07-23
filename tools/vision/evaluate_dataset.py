#!/usr/bin/env python3
"""Evaluate HZZS native vision against a screenshot corpus without claiming ground truth."""

from __future__ import annotations

import argparse
import csv
import ctypes
import json
import math
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from host_build import ensure_host_library  # noqa: E402

KINDS = [
    "player",
    "green-bottle",
    "cake-structure",
    "hanging-spike",
    "pit",
    "panda-statue",
    "bamboo-gap",
    "hanging-brush",
    "sand-castle",
    "hanging-anchor",
    "sea-pit",
]
COLORS = [
    (80, 220, 80),
    (40, 170, 30),
    (50, 180, 255),
    (180, 50, 240),
    (30, 80, 255),
    (190, 190, 190),
    (20, 120, 230),
    (230, 100, 20),
    (40, 200, 220),
    (90, 90, 90),
    (255, 120, 40),
]
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}
SCENES = {
    0: "sweet-factory",
    1: "bamboo-bookstore",
    2: "sea-salt-living-room",
}


def scene_for(path: Path) -> int:
    text = "/".join(part.lower() for part in path.parts)
    if "海盐客厅" in text or "sea" in text or "salt" in text:
        return 2
    if "竹影书屋" in text or "bamboo" in text:
        return 1
    if "甜品工厂" in text or "甜甜圈" in text or "sweet" in text:
        return 0
    return 0


class HostVision:
    def __init__(self, library: Path):
        self.library = ctypes.CDLL(str(library))
        self.analyze_host = self.library.hzzs_analyze_host
        self.analyze_host.argtypes = [
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_uint32),
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_float),
            ctypes.c_int,
        ]
        self.analyze_host.restype = ctypes.c_int

    def analyze(self, bgr: np.ndarray, scene: int, work_width: int = 320) -> dict:
        height, width = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.uint32)
        argb = np.ascontiguousarray(
            (
                0xFF000000
                | (rgb[:, :, 0] << 16)
                | (rgb[:, :, 1] << 8)
                | rgb[:, :, 2]
            ).ravel(),
            dtype=np.uint32,
        )
        output = np.zeros(1 + 64 * 10, dtype=np.float32)
        started = time.perf_counter_ns()
        count = self.analyze_host(
            scene,
            argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32)),
            width,
            height,
            work_width,
            output.ctypes.data_as(ctypes.POINTER(ctypes.c_float)),
            64,
        )
        elapsed_ms = (time.perf_counter_ns() - started) / 1e6
        if count < 0:
            return {
                "error": f"native error {count}",
                "sceneConfidence": 0.0,
                "detections": [],
                "elapsedMs": elapsed_ms,
            }

        detections = []
        for index in range(count):
            row = output[1 + index * 10 : 1 + (index + 1) * 10]
            kind = int(round(float(row[1])))
            detections.append(
                {
                    "trackHint": int(round(float(row[0]))),
                    "kind": KINDS[kind] if 0 <= kind < len(KINDS) else f"unknown-{kind}",
                    "left": float(row[2]),
                    "top": float(row[3]),
                    "right": float(row[4]),
                    "bottom": float(row[5]),
                    "confidence": float(row[6]),
                    "actionable": bool(row[7] > 0.5),
                    "diagnosticOnly": bool(row[8] > 0.5),
                    "avoidance": int(round(float(row[9]))),
                }
            )
        return {
            "sceneConfidence": float(output[0]),
            "detections": detections,
            "elapsedMs": elapsed_ms,
        }


def annotate(image: np.ndarray, result: dict) -> np.ndarray:
    output = image.copy()
    height, width = output.shape[:2]
    for detection in result["detections"]:
        kind_index = KINDS.index(detection["kind"]) if detection["kind"] in KINDS else 0
        color = COLORS[kind_index]
        x1 = max(0, min(width - 1, round(detection["left"] * width)))
        y1 = max(0, min(height - 1, round(detection["top"] * height)))
        x2 = max(0, min(width - 1, round(detection["right"] * width)))
        y2 = max(0, min(height - 1, round(detection["bottom"] * height)))
        thickness = max(2, width // 320)
        cv2.rectangle(output, (x1, y1), (x2, y2), color, thickness)
        label = f"{detection['kind']} {detection['confidence']:.2f}"
        if detection["actionable"]:
            label += " A"
        scale = max(0.45, width / 1400)
        (text_width, text_height), _ = cv2.getTextSize(
            label,
            cv2.FONT_HERSHEY_SIMPLEX,
            scale,
            thickness,
        )
        cv2.rectangle(
            output,
            (x1, max(0, y1 - text_height - 8)),
            (min(width, x1 + text_width + 6), y1),
            color,
            -1,
        )
        cv2.putText(
            output,
            label,
            (x1 + 3, max(text_height + 1, y1 - 4)),
            cv2.FONT_HERSHEY_SIMPLEX,
            scale,
            (0, 0, 0),
            max(1, thickness // 2),
            cv2.LINE_AA,
        )
    return output


def make_overview(items: list, output_path: Path, title: str, columns: int = 5) -> None:
    if not items:
        return
    thumb_width, thumb_height, header = 180, 400, 54
    rows = math.ceil(len(items) / columns)
    canvas = np.full((header + rows * thumb_height, columns * thumb_width, 3), 248, np.uint8)
    cv2.putText(canvas, title, (16, 36), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (30, 30, 30), 2, cv2.LINE_AA)
    for index, (path, image, result) in enumerate(items):
        thumb = cv2.resize(image, (thumb_width, thumb_height), interpolation=cv2.INTER_AREA)
        label = f"{path.name[:18]} {result['elapsedMs']:.1f}ms"
        cv2.rectangle(thumb, (0, thumb_height - 22), (thumb_width, thumb_height), (255, 255, 255), -1)
        cv2.putText(thumb, label, (3, thumb_height - 7), cv2.FONT_HERSHEY_SIMPLEX, 0.32, (0, 0, 0), 1, cv2.LINE_AA)
        y = header + (index // columns) * thumb_height
        x = (index % columns) * thumb_width
        canvas[y : y + thumb_height, x : x + thumb_width] = thumb
    cv2.imwrite(str(output_path), canvas, [cv2.IMWRITE_JPEG_QUALITY, 88])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--project-root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--limit", type=int)
    parser.add_argument("--overview-sample", type=int, default=50)
    args = parser.parse_args()

    project = Path(args.project_root)
    library = ensure_host_library(project)
    engine = HostVision(library)
    dataset = Path(args.dataset)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    all_images = sorted(path for path in dataset.rglob("*") if path.suffix.lower() in IMAGE_SUFFIXES)
    grouped: dict[int, list[Path]] = defaultdict(list)
    for path in all_images:
        grouped[scene_for(path.relative_to(dataset))].append(path)

    records: list[dict] = []
    all_times: list[float] = []
    for scene, slug in SCENES.items():
        files = grouped.get(scene, [])
        if args.limit:
            files = files[: args.limit]
        scene_dir = output / slug
        predicted = scene_dir / "predicted"
        overview = scene_dir / "overview"
        metrics = scene_dir / "metrics"
        failures = scene_dir / "failures"
        for directory in (predicted, overview, metrics, failures):
            directory.mkdir(parents=True, exist_ok=True)

        scene_items = []
        native_times: list[float] = []
        decode_times: list[float] = []
        counts = {kind: 0 for kind in KINDS}
        no_player = []

        if files:
            warm = cv2.imread(str(files[0]), cv2.IMREAD_COLOR)
            if warm is not None:
                for _ in range(3):
                    engine.analyze(warm, scene)

        for path in files:
            decode_started = time.perf_counter_ns()
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            decode_ms = (time.perf_counter_ns() - decode_started) / 1e6
            if image is None:
                continue
            result = engine.analyze(image, scene)
            result["decodeMs"] = decode_ms
            result["endToEndMs"] = decode_ms + result["elapsedMs"]
            decode_times.append(decode_ms)
            native_times.append(result["elapsedMs"])
            all_times.append(result["elapsedMs"])
            for detection in result["detections"]:
                counts[detection["kind"]] = counts.get(detection["kind"], 0) + 1
            if not any(item["kind"] == "player" for item in result["detections"]):
                no_player.append(str(path.relative_to(dataset)))

            drawn = annotate(image, result)
            relative = path.relative_to(dataset)
            target = (predicted / relative).with_suffix(".jpg")
            target.parent.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(target), drawn, [cv2.IMWRITE_JPEG_QUALITY, 90])
            record = {
                "scene": slug,
                "source": str(relative),
                "output": str(target.relative_to(output)),
                "width": image.shape[1],
                "height": image.shape[0],
                **result,
            }
            records.append(record)
            if len(scene_items) < args.overview_sample:
                scene_items.append((path, drawn, result))

        make_overview(scene_items, overview / "overview.jpg", f"{slug} · first {len(scene_items)} frames")
        end_to_end = [native + decode for native, decode in zip(native_times, decode_times)]
        summary = {
            "scene": slug,
            "images": len(files),
            "timingMs": {
                "nativeMean": statistics.fmean(native_times) if native_times else 0,
                "nativeP50": float(np.percentile(native_times, 50)) if native_times else 0,
                "nativeP95": float(np.percentile(native_times, 95)) if native_times else 0,
                "nativeMax": max(native_times) if native_times else 0,
                "decodeMean": statistics.fmean(decode_times) if decode_times else 0,
                "endToEndMean": statistics.fmean(end_to_end) if end_to_end else 0,
            },
            "detectionCounts": counts,
            "framesWithoutPlayer": len(no_player),
            "framesWithoutPlayerFiles": no_player,
            "accuracyStatus": "NOT_MEASURED_NO_INDEPENDENT_GROUND_TRUTH",
            "boundaryToleranceDefinition": (
                "abs(predicted edge - ground truth edge) <= player width * 0.05"
            ),
        }
        (metrics / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    (output / "results.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    draft = {
        "schemaVersion": 1,
        "machineGenerated": True,
        "reviewed": False,
        "warning": (
            "These boxes are algorithm output drafts, not independent ground truth. "
            "Human review is required before accuracy certification."
        ),
        "images": [
            {
                "source": record["source"],
                "scene": record["scene"],
                "width": record["width"],
                "height": record["height"],
                "objects": record["detections"],
            }
            for record in records
        ],
    }
    (output / "draft_annotations.json").write_text(
        json.dumps(draft, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    with (output / "timings.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(
            [
                "scene",
                "source",
                "width",
                "height",
                "decode_ms",
                "native_ms",
                "end_to_end_ms",
                "scene_confidence",
                "detection_count",
            ]
        )
        for record in records:
            writer.writerow(
                [
                    record["scene"],
                    record["source"],
                    record["width"],
                    record["height"],
                    f"{record.get('decodeMs', 0):.4f}",
                    f"{record['elapsedMs']:.4f}",
                    f"{record.get('endToEndMs', record['elapsedMs']):.4f}",
                    f"{record['sceneConfidence']:.4f}",
                    len(record["detections"]),
                ]
            )

    global_summary = {
        "images": len(records),
        "sceneImages": {SCENES[scene]: len(grouped.get(scene, [])) for scene in SCENES},
        "timingMs": {
            "mean": statistics.fmean(all_times) if all_times else 0,
            "p50": float(np.percentile(all_times, 50)) if all_times else 0,
            "p95": float(np.percentile(all_times, 95)) if all_times else 0,
            "max": max(all_times) if all_times else 0,
        },
        "importantNote": (
            "No independent human ground-truth boxes were present in the uploaded archive. "
            "Generated images are review artifacts, not proof of the 5% boundary requirement."
        ),
    }
    (output / "summary.json").write_text(
        json.dumps(global_summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(global_summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
