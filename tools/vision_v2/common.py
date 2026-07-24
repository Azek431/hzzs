from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable

import cv2
import numpy as np


@dataclass(slots=True)
class Detection:
    name: str
    score: float
    contour: np.ndarray
    box: tuple[int, int, int, int]
    metadata: dict


def resize_to_work_width(image: np.ndarray, work_width: int = 360) -> tuple[np.ndarray, float]:
    if image is None or image.ndim != 3:
        raise ValueError("image must be a BGR HxWx3 array")
    h, w = image.shape[:2]
    if w <= 0 or h <= 0:
        raise ValueError("image has invalid dimensions")
    if w == work_width:
        return image, 1.0
    scale = work_width / float(w)
    work_h = max(1, int(round(h * scale)))
    interpolation = cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR
    return cv2.resize(image, (work_width, work_h), interpolation=interpolation), scale


def densify_polygon(poly: np.ndarray, spacing: float = 6.0) -> np.ndarray:
    p = np.asarray(poly, np.float32)
    if len(p) < 2:
        return p.copy()
    out: list[np.ndarray] = []
    for i in range(len(p)):
        a = p[i]
        b = p[(i + 1) % len(p)]
        length = float(np.linalg.norm(b - a))
        count = max(1, int(math.ceil(length / max(spacing, 1.0))))
        for j in range(count):
            out.append(a + (b - a) * (j / count))
    return np.asarray(out, np.float32)


def _sample_bgr_nearest(image: np.ndarray, x: np.ndarray, y: np.ndarray) -> np.ndarray:
    h, w = image.shape[:2]
    xi = np.clip(np.rint(x).astype(np.int32), 0, w - 1)
    yi = np.clip(np.rint(y).astype(np.int32), 0, h - 1)
    return image[yi, xi].astype(np.int16)


def snap_contour_rgb(
    image: np.ndarray,
    polygon: np.ndarray,
    radius: int = 4,
    normal_gap: float = 2.0,
    distance_penalty: float = 3.0,
    spacing: float = 7.0,
) -> np.ndarray:
    """Snap a canonical polygon to local RGB discontinuities.

    This deliberately avoids constructing a full-frame Sobel/Canny map. It samples
    only O(P * radius) pixels around a sparse polygon, where P is normally below 100.
    """
    p = densify_polygon(np.asarray(polygon, np.float32), spacing=spacing)
    if len(p) < 3:
        return np.asarray(polygon, np.float32)

    previous = np.roll(p, 1, axis=0)
    following = np.roll(p, -1, axis=0)
    tangent = following - previous
    tangent /= np.linalg.norm(tangent, axis=1, keepdims=True) + 1e-6
    normal = np.stack([-tangent[:, 1], tangent[:, 0]], axis=1)

    best_offset = np.zeros(len(p), np.float32)
    best_score = np.full(len(p), -1e9, np.float32)
    for offset in np.arange(-radius, radius + 1, dtype=np.float32):
        center = p + normal * offset
        inner = center - normal * normal_gap
        outer = center + normal * normal_gap
        a = _sample_bgr_nearest(image, inner[:, 0], inner[:, 1])
        b = _sample_bgr_nearest(image, outer[:, 0], outer[:, 1])
        contrast = np.sum(np.abs(a - b), axis=1).astype(np.float32)
        score = contrast - distance_penalty * abs(float(offset))
        update = score > best_score
        best_score[update] = score[update]
        best_offset[update] = offset

    snapped = p + normal * best_offset[:, None]
    snapped = (np.roll(snapped, 1, axis=0) + 2 * snapped + np.roll(snapped, -1, axis=0)) / 4
    contour = np.rint(snapped).astype(np.int32).reshape(-1, 1, 2)
    epsilon = max(1.0, 0.0035 * cv2.arcLength(contour, True))
    return cv2.approxPolyDP(contour, epsilon, True).reshape(-1, 2).astype(np.float32)


def polygon_box(poly: np.ndarray) -> tuple[int, int, int, int]:
    p = np.asarray(poly, np.float32)
    x0, y0 = np.floor(p.min(axis=0)).astype(int)
    x1, y1 = np.ceil(p.max(axis=0)).astype(int)
    return int(x0), int(y0), int(max(1, x1 - x0)), int(max(1, y1 - y0))


def map_polygon_to_original(poly: np.ndarray, work_scale: float, shape: tuple[int, ...]) -> np.ndarray:
    p = np.asarray(poly, np.float32) / max(work_scale, 1e-9)
    h, w = shape[:2]
    p[:, 0] = np.clip(p[:, 0], 0, w - 1)
    p[:, 1] = np.clip(p[:, 1], 0, h - 1)
    return p


def draw_detections(image: np.ndarray, detections: Iterable[Detection]) -> np.ndarray:
    out = image.copy()
    for det in detections:
        contour = np.rint(det.contour).astype(np.int32).reshape(-1, 1, 2)
        cv2.polylines(out, [contour], True, (0, 0, 255), 2, cv2.LINE_AA)
        x, y, _, _ = det.box
        cv2.putText(
            out,
            f"{det.name} {det.score:.2f}",
            (x, max(18, y - 5)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.48,
            (0, 0, 255),
            1,
            cv2.LINE_AA,
        )
    return out
