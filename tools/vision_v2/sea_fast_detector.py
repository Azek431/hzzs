from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from common import snap_contour_rgb
from sea_baseline import BASE_H, BASE_W, PATTERNS, int_to_rgb

HERE = Path(__file__).resolve().parent

# The original anchor is not necessarily the best scan anchor. These indices select
# a rare/stable color from [original anchor] + relative verification points.
SCAN_POINT_INDEX = {"大断崖": 0, "小断崖": 0, "矮沙丘": 4, "高沙丘": 2, "船锚": 4}
SCAN_Y_RATIO = {"大断崖": 0.6340, "小断崖": 0.6343, "矮沙丘": 0.62717, "高沙丘": 0.4774, "船锚": 0.50515}
SCAN_Y_TOL = {"大断崖": 0.012, "小断崖": 0.012, "矮沙丘": 0.012, "高沙丘": 0.014, "船锚": 0.015}


@dataclass(slots=True)
class SeaHit:
    name: str
    anchor: tuple[int, int]
    scan_anchor: tuple[int, int]
    score: float


class SeaLUTDetector:
    def __init__(self, anchor_threshold: int = 16, verify_threshold: int = 16, stride: int = 2):
        self.names = list(SCAN_POINT_INDEX)
        self.anchor_threshold = anchor_threshold
        self.verify_threshold = verify_threshold
        self.stride = stride
        self.scan_specs = {}
        anchor_colors = []

        for name in self.names:
            first, points = PATTERNS[name]
            all_points = [(0, 0, first), *points]
            index = SCAN_POINT_INDEX[name]
            sdx, sdy, color = all_points[index]
            verification = [(dx - sdx, dy - sdy, c) for i, (dx, dy, c) in enumerate(all_points) if i != index]
            self.scan_specs[name] = (sdx, sdy, color, verification)
            anchor_colors.append(int_to_rgb(color))

        # 5 bits/channel => 32768 entries. Each entry is a class bitmask.
        values = np.arange(32, dtype=np.int16) * 8 + 4
        rr, gg, bb = np.meshgrid(values, values, values, indexing="ij")
        centers = np.stack([rr.ravel(), gg.ravel(), bb.ravel()], axis=1)
        self.lut = np.zeros(32768, dtype=np.uint16)
        for i, color in enumerate(np.stack(anchor_colors).astype(np.int16)):
            valid = np.max(np.abs(centers - color), axis=1) <= anchor_threshold + 5
            self.lut[valid] |= np.uint16(1 << i)

        self.profile = json.loads((HERE / "sea_polygons.json").read_text(encoding="utf-8"))

    def _verify(self, rgb: np.ndarray, name: str, cx: int, cy: int, sx: float, sy: float):
        h, w = rgb.shape[:2]
        sdx, sdy, scan_color, verification = self.scan_specs[name]
        target_scan = int_to_rgb(scan_color)

        best = None
        best_difference = 999
        radius = max(1, self.stride)
        for yy in range(max(0, cy - radius), min(h, cy + radius + 1)):
            for xx in range(max(0, cx - radius), min(w, cx + radius + 1)):
                difference = int(np.max(np.abs(rgb[yy, xx].astype(np.int16) - target_scan)))
                if difference < best_difference:
                    best_difference = difference
                    best = (xx, yy)
        if best is None or best_difference > self.anchor_threshold:
            return None

        ax, ay = best
        # Check chromatic/dark points first, so false candidates are rejected early.
        checks = []
        for dx, dy, color in verification:
            target = int_to_rgb(color)
            rarity_proxy = float(np.linalg.norm(target - np.array([235, 235, 235], np.int16)))
            checks.append((-rarity_proxy, dx, dy, target))
        checks.sort(key=lambda item: item[0])

        for _, dx, dy, target in checks:
            tx = ax + int(round(dx * sx))
            ty = ay + int(round(dy * sy))
            if tx < 0 or tx >= w or ty < 0 or ty >= h:
                return None
            local = rgb[max(0, ty - 1):min(h, ty + 2), max(0, tx - 1):min(w, tx + 2)].astype(np.int16)
            if int(np.min(np.max(np.abs(local - target), axis=2))) > self.verify_threshold:
                return None

        # Recover the original AutoJs anchor from the rare scan point. A small local
        # refinement is enough; no full-region rescan is needed.
        expected_x = ax - int(round(sdx * sx))
        expected_y = ay - int(round(sdy * sy))
        first, original_points = PATTERNS[name]
        first_color = int_to_rgb(first)
        refine_radius = max(3, int(round(18 * sx)))
        best_anchor = None
        best_cost = 1e9
        matched = 0

        for oy in range(max(0, expected_y - refine_radius), min(h, expected_y + refine_radius + 1)):
            for ox in range(max(0, expected_x - refine_radius), min(w, expected_x + refine_radius + 1)):
                anchor_difference = int(np.max(np.abs(rgb[oy, ox].astype(np.int16) - first_color)))
                if anchor_difference > self.verify_threshold:
                    continue
                cost = float(anchor_difference)
                good = 1
                for dx, dy, color in original_points:
                    tx = ox + int(round(dx * sx))
                    ty = oy + int(round(dy * sy))
                    if tx < 0 or tx >= w or ty < 0 or ty >= h:
                        good = -999
                        break
                    target = int_to_rgb(color)
                    local = rgb[max(0, ty - 1):min(h, ty + 2), max(0, tx - 1):min(w, tx + 2)].astype(np.int16)
                    difference = int(np.min(np.max(np.abs(local - target), axis=2)))
                    if difference > self.verify_threshold:
                        good = -999
                        break
                    good += 1
                    cost += difference
                if good > 0 and cost < best_cost:
                    best_cost = cost
                    best_anchor = (ox, oy)
                    matched = good

        if best_anchor is None:
            return None
        total = 1 + len(original_points)
        score = max(0.55, min(1.0, matched / total * (1.0 - best_cost / max(1.0, total * 80.0))))
        return best_anchor, (ax, ay), score

    def detect(self, image_bgr: np.ndarray) -> list[SeaHit]:
        rgb = image_bgr[:, :, ::-1]
        h, w = rgb.shape[:2]
        sx, sy = w / BASE_W, h / BASE_H
        x0 = max(int(round(290 * sx)), int(round(w * 0.20)))
        stride = self.stride

        y0 = max(0, int(min(SCAN_Y_RATIO[n] - SCAN_Y_TOL[n] for n in self.names) * h))
        y1 = min(h, int(max(SCAN_Y_RATIO[n] + SCAN_Y_TOL[n] for n in self.names) * h) + 1)
        roi = rgb[y0:y1:stride, x0:w:stride]
        code = ((roi[:, :, 0].astype(np.uint16) >> 3) << 10) | ((roi[:, :, 1].astype(np.uint16) >> 3) << 5) | (roi[:, :, 2].astype(np.uint16) >> 3)
        bits = self.lut[code]
        ys, xs = np.nonzero(bits)
        if len(xs):
            order = np.argsort(xs, kind="stable")
            xs, ys = xs[order], ys[order]

        found = {}
        for gy, gx in zip(ys, xs):
            py = y0 + int(gy) * stride
            px = x0 + int(gx) * stride
            class_bits = int(bits[gy, gx])
            while class_bits:
                low = class_bits & -class_bits
                index = low.bit_length() - 1
                class_bits -= low
                name = self.names[index]
                if name in found or abs(py / h - SCAN_Y_RATIO[name]) > SCAN_Y_TOL[name]:
                    continue
                hit = self._verify(rgb, name, px, py, sx, sy)
                if hit is not None:
                    found[name] = hit

        return [SeaHit(name, anchor, scan_anchor, score) for name, (anchor, scan_anchor, score) in found.items()]

    def canonical_polygon(self, name: str, anchor: tuple[int, int], shape: tuple[int, ...]) -> np.ndarray:
        h, w = shape[:2]
        sx, sy = w / BASE_W, h / BASE_H
        x0, y0, _, _ = self.profile["ext"][name]
        polygon = np.asarray(self.profile["polygons"][name], np.float32)
        return np.column_stack([
            (polygon[:, 0] + x0) * sx + anchor[0],
            (polygon[:, 1] + y0) * sy + anchor[1],
        ]).astype(np.float32)

    def contour(self, image_bgr: np.ndarray, hit: SeaHit) -> np.ndarray:
        raw = self.canonical_polygon(hit.name, hit.anchor, image_bgr.shape)
        return snap_contour_rgb(image_bgr, raw, radius=3, normal_gap=1.5, spacing=5.0)
