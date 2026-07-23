#!/usr/bin/env python3
"""Host ABI, bounds and representative-frame smoke tests for the native engine."""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from host_build import ensure_host_library  # noqa: E402

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


def scene_for(path: Path) -> int:
    text = "/".join(part.lower() for part in path.parts)
    if "海盐客厅" in text or "sea" in text or "salt" in text:
        return 2
    if "竹影书屋" in text or "bamboo" in text:
        return 1
    if "甜品工厂" in text or "甜甜圈" in text or "sweet" in text:
        return 0
    return 0


def resolve_dataset(explicit: str | None) -> Path | None:
    candidates = [
        Path(explicit) if explicit else None,
        Path(os.environ["HZZS_TEST_DATASET"]) if os.environ.get("HZZS_TEST_DATASET") else None,
        ROOT / "test_images",
        ROOT / ".test-data",
        Path("/mnt/data/test_images"),
    ]
    for candidate in candidates:
        if candidate and candidate.is_dir():
            return candidate
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", help="optional screenshot dataset")
    parser.add_argument("--max-representative", type=int, default=24)
    args = parser.parse_args()

    # Do not exec build_host.sh directly: git often stores it as 100644 (no +x),
    # which fails on CI Linux with PermissionError. host_build always uses bash/ps1.
    library_path = ensure_host_library(ROOT)
    library = ctypes.CDLL(str(library_path))
    analyze = library.hzzs_analyze_host
    analyze.argtypes = [
        ctypes.c_int,
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int,
    ]
    analyze.restype = ctypes.c_int
    analyze_config = library.hzzs_analyze_host_config
    analyze_config.argtypes = [
        ctypes.c_int,
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_bool,
        ctypes.c_float,
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int,
    ]
    analyze_config.restype = ctypes.c_int

    def invoke(scene: int, argb: np.ndarray | None, width: int, height: int, work_width: int = 320):
        output = np.zeros(641, np.float32)
        pointer = None if argb is None else argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32))
        count = analyze(
            scene,
            pointer,
            width,
            height,
            work_width,
            output.ctypes.data_as(ctypes.POINTER(ctypes.c_float)),
            64,
        )
        return count, output

    def invoke_config(
        scene: int,
        argb: np.ndarray,
        width: int,
        height: int,
        enabled_kind_mask: int,
        detect_player: bool,
        fixed_player_x_ratio: float = 0.185,
    ):
        output = np.zeros(641, np.float32)
        count = analyze_config(
            scene,
            argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32)),
            width,
            height,
            320,
            enabled_kind_mask,
            detect_player,
            fixed_player_x_ratio,
            output.ctypes.data_as(ctypes.POINTER(ctypes.c_float)),
            64,
        )
        return count, output

    # Invalid inputs fail closed.
    count, _ = invoke(0, None, 0, 0)
    assert count == -2, count
    blank = np.full(320 * 640, 0xFFFFFFFF, np.uint32)
    count, _ = invoke(0, blank, 320, 640, 100)
    assert count == -2, count

    # 三赛季（甜品/竹影/海盐）在空白帧与合成帧上均须 fail-soft，不得越界。
    blank_detections: dict[str, int] = {}
    for scene in (0, 1, 2):
        count, output = invoke(scene, blank, 320, 640)
        assert 0 <= count <= 64
        blank_detections[str(scene)] = count
        validate_rows(output, count)

        synthetic = np.full((640, 320), 0xFFF4F1EA, np.uint32)
        synthetic[360:560, 40:95] = 0xFF251F1B
        if scene == 0:
            synthetic[410:545, 190:245] = 0xFF28A96B
        elif scene == 1:
            synthetic[410:545, 190:245] = 0xFF9A7A3B
        else:
            # 海盐：偏暖木地板 + 沙堡暖黄块，仅验证引擎不崩溃。
            synthetic[:, :] = 0xFFC8A070
            synthetic[410:545, 190:245] = 0xFFD2A050
        synthetic_argb = np.ascontiguousarray(synthetic.ravel())
        count, output = invoke(scene, synthetic_argb, 320, 640)
        assert 0 <= count <= 64
        validate_rows(output, count)

        # Disabling every obstacle must suppress obstacle output without making
        # the engine read outside the frame. Player detection is independently configurable.
        count, output = invoke_config(scene, synthetic_argb, 320, 640, 0, False)
        assert count == 0, (scene, count, output[: 1 + max(count, 0) * 10])
        count, output = invoke_config(scene, synthetic_argb, 320, 640, 0, True)
        assert 0 <= count <= 1
        for index in range(count):
            assert int(round(float(output[1 + index * 10 + 1]))) == 0

    dataset = resolve_dataset(args.dataset)
    checked = 0
    per_scene = {0: 0, 1: 0, 2: 0}
    if dataset is not None:
        images = sorted(path for path in dataset.rglob("*") if path.suffix.lower() in IMAGE_SUFFIXES)
        if images:
            step = max(1, len(images) // max(1, args.max_representative))
            selected = images[::step][: args.max_representative]
            for path in selected:
                image = cv2.imread(str(path), cv2.IMREAD_COLOR)
                assert image is not None, path
                height, width = image.shape[:2]
                rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB).astype(np.uint32)
                argb = np.ascontiguousarray(
                    (
                        0xFF000000
                        | (rgb[:, :, 0] << 16)
                        | (rgb[:, :, 1] << 8)
                        | rgb[:, :, 2]
                    ).ravel(),
                    dtype=np.uint32,
                )
                scene = scene_for(path.relative_to(dataset))
                count, output = invoke(scene, argb, width, height)
                assert 0 <= count <= 64, (path, count)
                validate_rows(output, count)
                checked += 1
                per_scene[scene] += 1

    print(
        json.dumps(
            {
                "status": "PASS",
                "representativeFrames": checked,
                "representativeByScene": per_scene,
                "dataset": str(dataset) if dataset else None,
                "blankFrameDetections": blank_detections,
            },
            ensure_ascii=False,
        )
    )


def validate_rows(output: np.ndarray, count: int) -> None:
    for index in range(count):
        row = output[1 + index * 10 : 1 + (index + 1) * 10]
        # Kind 0..10：PLAYER + 10 种障碍（含海盐 SAND_CASTLE/HANGING_ANCHOR/SEA_PIT）
        assert 0 <= int(round(float(row[1]))) <= 10
        assert 0.0 <= row[2] <= row[4] <= 1.0
        assert 0.0 <= row[3] <= row[5] <= 1.0
        assert 0.0 <= row[6] <= 1.0
        assert row[7] in (0.0, 1.0)
        assert row[8] in (0.0, 1.0)
        # Avoidance 0..5（含 PRESS / SWIPE_UP）
        assert 0 <= int(round(float(row[9]))) <= 5


if __name__ == "__main__":
    main()
