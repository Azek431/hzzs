from __future__ import annotations

import argparse
import json
import time
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

from common import draw_detections
from sea_baseline import PATTERNS, find_pattern
from unified_detector import UnifiedObstacleDetector

EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


def images_under(path: Path):
    return sorted(p for p in path.rglob("*") if p.suffix.lower() in EXTENSIONS)


def benchmark_season(dataset_root: Path, season: str, output_root: Path, limit: int | None):
    paths = images_under(dataset_root / season)
    if limit:
        paths = paths[:limit]
    images = [cv2.imread(str(path)) for path in paths]
    images = [image for image in images if image is not None]
    detector = UnifiedObstacleDetector(season)

    for image in images[:3]:
        detector.detect(image)
    start = time.perf_counter()
    predictions = [detector.detect(image) for image in images]
    elapsed = time.perf_counter() - start

    counts = Counter(det.name for frame in predictions for det in frame)
    result = {
        "season": season,
        "frames": len(images),
        "milliseconds_per_frame": elapsed * 1000 / max(1, len(images)),
        "detections": dict(counts),
    }

    if images:
        indices = np.linspace(0, len(images) - 1, min(12, len(images))).round().astype(int)
        cells = []
        for index in indices:
            rendered = draw_detections(images[index], predictions[index])
            target_width = 240
            rendered = cv2.resize(rendered, (target_width, round(rendered.shape[0] * target_width / rendered.shape[1])))
            cells.append(rendered)
        columns = 4
        rows = (len(cells) + columns - 1) // columns
        cell_h = max(cell.shape[0] for cell in cells)
        sheet = np.full((rows * cell_h, columns * 240, 3), 255, np.uint8)
        for i, cell in enumerate(cells):
            y, x = (i // columns) * cell_h, (i % columns) * 240
            sheet[y:y + cell.shape[0], x:x + cell.shape[1]] = cell
        output_root.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(output_root / f"{season}_sample.jpg"), sheet)

    return result


def benchmark_autojs_sea(dataset_root: Path, limit: int | None):
    paths = images_under(dataset_root / "海盐客厅")
    # Use the same held-out subset used during development when it exists.
    held_out = [p for p in paths if "Screoshkts" in str(p)]
    if held_out:
        paths = held_out
    if limit:
        paths = paths[:limit]
    images = [cv2.imread(str(path)) for path in paths]
    images = [image for image in images if image is not None]

    relevant = {name: pattern for name, pattern in PATTERNS.items() if name != "复活"}
    start = time.perf_counter()
    for image in images:
        for first, points in relevant.values():
            find_pattern(image, first, points)
    elapsed = time.perf_counter() - start
    return {
        "frames": len(images),
        "milliseconds_per_frame": elapsed * 1000 / max(1, len(images)),
        "note": "NumPy emulation of one full-region findMultiColors scan per template; not Android wall-clock timing.",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset_root", type=Path, help="directory containing 甜品工厂/竹影书屋/海盐客厅")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--output", type=Path, default=Path("benchmark_outputs"))
    args = parser.parse_args()

    report = {
        "optimized": [benchmark_season(args.dataset_root, season, args.output, args.limit) for season in ("甜品工厂", "竹影书屋", "海盐客厅")],
        "autojs_sea_baseline": benchmark_autojs_sea(args.dataset_root, args.limit),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
