from __future__ import annotations

import json
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
PROFILES = json.loads((HERE / "fixed_profiles_v2.json").read_text(encoding="utf-8"))


class FixedStripDetector:
    """One LUT scan over narrow expected rows, then sparse 4-of-7 verification."""

    def __init__(
        self,
        names: list[str],
        anchors_per_class: int = 2,
        anchor_threshold: int = 18,
        verify_threshold: int = 28,
        required_points: int = 4,
        row_tolerance: int = 7,
    ):
        self.names = names
        self.anchor_threshold = anchor_threshold
        self.verify_threshold = verify_threshold
        self.required_points = required_points
        self.row_tolerance = row_tolerance
        self.slots: list[tuple[str, int, float]] = []

        for name in names:
            profile = PROFILES[name]
            for index in range(min(anchors_per_class, len(profile["points"]))):
                point = profile["points"][index]
                expected_y = float(profile["expected_match_y"] - profile["match_offset"][1] + point["y"])
                self.slots.append((name, index, expected_y))

        values = np.arange(32, dtype=np.int16) * 8 + 4
        rr, gg, bb = np.meshgrid(values, values, values, indexing="ij")
        centers = np.stack([rr.ravel(), gg.ravel(), bb.ravel()], axis=1)
        self.lut = np.zeros(32768, dtype=np.uint32)
        for i, (name, point_index, _) in enumerate(self.slots):
            color = np.asarray(PROFILES[name]["points"][point_index]["rgb"], np.int16)
            valid = np.max(np.abs(centers - color), axis=1) <= anchor_threshold + 5
            self.lut[valid] |= np.uint32(1 << i)

        intervals = sorted((int(round(y)) - row_tolerance, int(round(y)) + row_tolerance + 1) for _, _, y in self.slots)
        self.strips: list[tuple[int, int]] = []
        for start, end in intervals:
            if self.strips and start <= self.strips[-1][1]:
                self.strips[-1] = self.strips[-1][0], max(end, self.strips[-1][1])
            else:
                self.strips.append((start, end))

    def detect(self, image_bgr: np.ndarray) -> list[dict]:
        rgb = image_bgr[:, :, ::-1]
        h, w = rgb.shape[:2]
        x_start = int(0.10 * w)
        candidates = []

        for strip_start, strip_end in self.strips:
            y0, y1 = max(0, strip_start), min(h, strip_end)
            if y1 <= y0:
                continue
            roi = rgb[y0:y1, x_start:]
            code = ((roi[:, :, 0].astype(np.uint16) >> 3) << 10) | ((roi[:, :, 1].astype(np.uint16) >> 3) << 5) | (roi[:, :, 2].astype(np.uint16) >> 3)
            bits = self.lut[code]
            ys, xs = np.nonzero(bits)

            for gy, gx in zip(ys, xs):
                py, px = y0 + int(gy), x_start + int(gx)
                class_bits = int(bits[gy, gx])
                while class_bits:
                    low = class_bits & -class_bits
                    slot_index = low.bit_length() - 1
                    class_bits -= low
                    name, point_index, expected_y = self.slots[slot_index]
                    if abs(py - expected_y) > self.row_tolerance:
                        continue

                    profile = PROFILES[name]
                    anchor_point = profile["points"][point_index]
                    target = np.asarray(anchor_point["rgb"], np.int16)
                    local = rgb[max(0, py - 1):min(h, py + 2), max(0, px - 1):min(w, px + 2)].astype(np.int16)
                    difference = np.max(np.abs(local - target), axis=2)
                    iy, ix = np.unravel_index(np.argmin(difference), difference.shape)
                    if int(difference[iy, ix]) > self.anchor_threshold:
                        continue

                    anchor_y = max(0, py - 1) + int(iy)
                    anchor_x = max(0, px - 1) + int(ix)
                    full_x = anchor_x - int(anchor_point["x"])
                    full_y = anchor_y - int(anchor_point["y"])
                    full_w, full_h = profile["full_size"]
                    if full_x < 0 or full_y < 0 or full_x + full_w > w or full_y + full_h > h:
                        continue

                    matched = 0
                    cost = 0
                    for point in profile["points"]:
                        tx = full_x + int(point["x"])
                        ty = full_y + int(point["y"])
                        point_target = np.asarray(point["rgb"], np.int16)
                        patch = rgb[max(0, ty - 1):min(h, ty + 2), max(0, tx - 1):min(w, tx + 2)].astype(np.int16)
                        if not patch.size:
                            continue
                        d = int(np.min(np.max(np.abs(patch - point_target), axis=2)))
                        if d <= self.verify_threshold:
                            matched += 1
                            cost += d
                        else:
                            cost += self.verify_threshold + 12

                    required = min(self.required_points, len(profile["points"]))
                    if matched >= required:
                        candidates.append((name, matched, cost, full_x, full_y))

        results = []
        for name in self.names:
            same_class = [candidate for candidate in candidates if candidate[0] == name]
            if not same_class:
                continue
            _, matched, cost, x, y = max(same_class, key=lambda item: (item[1], -item[2]))
            profile = PROFILES[name]
            polygon = np.asarray(profile["polygon"], np.float32) + np.array([x, y], np.float32)
            score = max(0.5, min(1.0, matched / len(profile["points"]) * (1.0 - cost / max(1.0, len(profile["points"]) * 120.0))))
            results.append({
                "name": name,
                "x": int(x),
                "y": int(y),
                "w": int(profile["full_size"][0]),
                "h": int(profile["full_size"][1]),
                "polygon": polygon,
                "matched": int(matched),
                "cost": int(cost),
                "score": float(score),
            })
        return results
