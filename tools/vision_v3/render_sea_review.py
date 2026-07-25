from __future__ import annotations

import argparse
import ctypes
import math
from pathlib import Path

import cv2
import numpy as np

from reference_and_benchmark import Config, NAMES, Result, image_paths, to_argb
from benchmark_fast import FastConfig


def load_library(path: Path):
    lib = ctypes.CDLL(str(path))
    lib.hzzs_soy_create.argtypes = [ctypes.POINTER(Config)]
    lib.hzzs_soy_create.restype = ctypes.c_void_p
    lib.hzzs_soy_destroy.argtypes = [ctypes.c_void_p]
    lib.hzzs_soy_detect.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.POINTER(Result),
    ]
    lib.hzzs_soy_detect.restype = ctypes.c_int
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
    return lib


def annotate(image: np.ndarray, exact: Result, fast: Result, filename: str) -> np.ndarray:
    canvas = image.copy()
    h, w = canvas.shape[:2]
    if exact.found:
        left = max(0, min(w - 1, round(exact.visual_left * w)))
        top = max(0, min(h - 1, round(exact.visual_top * h)))
        right = max(left + 1, min(w, round(exact.visual_right * w)))
        bottom = max(top + 1, min(h, round(exact.visual_bottom * h)))
        cv2.rectangle(canvas, (left, top), (right - 1, bottom - 1), (0, 255, 0), 4)
        cv2.drawMarker(
            canvas,
            (exact.anchor_x, exact.anchor_y),
            (0, 255, 255),
            cv2.MARKER_CROSS,
            28,
            4,
        )
    if fast.found:
        cv2.drawMarker(
            canvas,
            (fast.anchor_x, fast.anchor_y),
            (0, 0, 255),
            cv2.MARKER_TILTED_CROSS,
            24,
            3,
        )

    exact_text = "NONE" if not exact.found else f"{NAMES[exact.kind]}@{exact.anchor_x},{exact.anchor_y}"
    fast_text = "NONE" if not fast.found else f"{NAMES[fast.kind]}@{fast.anchor_x},{fast.anchor_y}"
    status = "MATCH" if exact_text == fast_text else "DIFF"
    label = f"{status} exact={exact_text} fast={fast_text}"
    cv2.rectangle(canvas, (0, 0), (w, 80), (0, 0, 0), -1)
    cv2.putText(canvas, label, (12, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (255, 255, 255), 2)
    cv2.putText(canvas, filename[:70], (12, 66), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (220, 220, 220), 1)
    return canvas


def fit_tile(image: np.ndarray, tile_w: int, tile_h: int) -> np.ndarray:
    h, w = image.shape[:2]
    scale = min(tile_w / w, tile_h / h)
    resized = cv2.resize(image, (max(1, round(w * scale)), max(1, round(h * scale))))
    tile = np.zeros((tile_h, tile_w, 3), dtype=np.uint8)
    y = (tile_h - resized.shape[0]) // 2
    x = (tile_w - resized.shape[1]) // 2
    tile[y : y + resized.shape[0], x : x + resized.shape[1]] = resized
    return tile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--columns", type=int, default=4)
    parser.add_argument("--rows", type=int, default=3)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    lib = load_library(args.library)
    exact_handle = lib.hzzs_soy_create(ctypes.byref(Config(10, 0, 10)))
    fast_handle = lib.hzzs_sea_fast_create(ctypes.byref(FastConfig(10, 10, 0, 0, 1, 0, 360, 228)))
    if not exact_handle or not fast_handle:
        raise RuntimeError("failed to create engines")

    tiles: list[np.ndarray] = []
    page = 0
    reviewed = 0
    try:
        for path in image_paths(args.dataset):
            image = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if image is None:
                continue
            argb = to_argb(image)
            h, w = argb.shape
            pointer = argb.ctypes.data_as(ctypes.POINTER(ctypes.c_uint32))
            exact = Result()
            fast = Result()
            lib.hzzs_soy_detect(exact_handle, pointer, w, h, w, ctypes.byref(exact))
            lib.hzzs_sea_fast_detect(fast_handle, pointer, w, h, w, ctypes.byref(fast))
            marked = annotate(image, exact, fast, path.name)
            tiles.append(fit_tile(marked, 360, 720))
            reviewed += 1

            if len(tiles) == args.columns * args.rows:
                sheet = np.vstack([
                    np.hstack(tiles[row * args.columns : (row + 1) * args.columns])
                    for row in range(args.rows)
                ])
                cv2.imwrite(str(args.output / f"sea_review_{page:02d}.jpg"), sheet, [cv2.IMWRITE_JPEG_QUALITY, 92])
                page += 1
                tiles.clear()

        if tiles:
            blank = np.zeros_like(tiles[0])
            tiles.extend([blank] * (args.columns * args.rows - len(tiles)))
            sheet = np.vstack([
                np.hstack(tiles[row * args.columns : (row + 1) * args.columns])
                for row in range(args.rows)
            ])
            cv2.imwrite(str(args.output / f"sea_review_{page:02d}.jpg"), sheet, [cv2.IMWRITE_JPEG_QUALITY, 92])
            page += 1
    finally:
        lib.hzzs_soy_destroy(exact_handle)
        lib.hzzs_sea_fast_destroy(fast_handle)

    print(f"reviewed={reviewed} pages={page}")


if __name__ == "__main__":
    main()
