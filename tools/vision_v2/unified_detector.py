from __future__ import annotations

from typing import Literal

import numpy as np

from bamboo_gap_detector import detect_bamboo_gaps
from common import Detection, map_polygon_to_original, polygon_box, resize_to_work_width, snap_contour_rgb
from fixed_strip_detector import FixedStripDetector
from sea_fast_detector import SeaLUTDetector

Season = Literal["甜品工厂", "竹影书屋", "海盐客厅"]

SEASON_CLASSES = {
    "甜品工厂": ["SWEET_SPIKE", "POISON_BOTTLE", "CAKE"],
    "竹影书屋": ["HANGING_BRUSH", "PANDA_STATUE"],
}

SEA_NAMES = {
    "大断崖": "LARGE_WATER_PIT",
    "小断崖": "SMALL_WATER_PIT",
    "矮沙丘": "LOW_SANDCASTLE",
    "高沙丘": "HIGH_SANDCASTLE",
    "船锚": "ANCHOR",
}


class UnifiedObstacleDetector:
    """One runtime architecture with season-specific profiles/parameters."""

    def __init__(self, season: Season, work_width: int = 360, edge_snap: bool = True):
        if season not in {"甜品工厂", "竹影书屋", "海盐客厅"}:
            raise ValueError(f"unsupported season: {season}")
        self.season = season
        self.work_width = work_width
        self.edge_snap = edge_snap
        if season == "海盐客厅":
            self.detector = SeaLUTDetector(anchor_threshold=16, verify_threshold=16, stride=2)
        else:
            self.detector = FixedStripDetector(SEASON_CLASSES[season])

    def detect(self, image_bgr: np.ndarray) -> list[Detection]:
        work, scale = resize_to_work_width(image_bgr, self.work_width)
        work_results: list[tuple[str, float, np.ndarray, dict]] = []

        if self.season == "海盐客厅":
            for hit in self.detector.detect(work):
                polygon = self.detector.canonical_polygon(hit.name, hit.anchor, work.shape)
                if self.edge_snap:
                    polygon = snap_contour_rgb(work, polygon, radius=3, normal_gap=1.5, spacing=5.0)
                work_results.append((SEA_NAMES[hit.name], hit.score, polygon, {"source_name": hit.name, "anchor": hit.anchor}))
        else:
            for hit in self.detector.detect(work):
                polygon = hit["polygon"]
                if self.edge_snap:
                    polygon = snap_contour_rgb(work, polygon, radius=4, normal_gap=2.0, spacing=7.0)
                work_results.append((hit["name"], hit["score"], polygon, {"matched_points": hit["matched"], "color_cost": hit["cost"]}))
            if self.season == "竹影书屋":
                for hit in detect_bamboo_gaps(work):
                    work_results.append((hit["name"], hit["score"], hit["polygon"], {"platform_evidence": hit["evidence"]}))

        output = []
        for name, score, polygon, metadata in work_results:
            original_polygon = map_polygon_to_original(polygon, scale, image_bgr.shape)
            output.append(Detection(name, float(score), original_polygon, polygon_box(original_polygon), metadata))
        return output
