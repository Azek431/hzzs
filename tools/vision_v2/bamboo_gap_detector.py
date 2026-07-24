from __future__ import annotations

import cv2
import numpy as np


def _runs(mask: np.ndarray, minimum_width: int):
    out = []
    start = None
    for i, value in enumerate(mask):
        if value and start is None:
            start = i
        elif not value and start is not None:
            if i - start >= minimum_width:
                out.append((start, i))
            start = None
    if start is not None and len(mask) - start >= minimum_width:
        out.append((start, len(mask)))
    return out


def detect_bamboo_gaps(image_bgr: np.ndarray) -> list[dict]:
    """Detect dynamic floor openings using platform absence + dark/open evidence."""
    rgb = image_bgr[:, :, ::-1].astype(np.int16)
    h, w = rgb.shape[:2]
    r, g, b = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    ground = int(round(0.609 * h))
    y0, y1 = ground - 2, min(h, ground + 62)

    bamboo_green = (g > b + 25) & (g >= r - 18) & (g > 65) & (r < 205)
    evidence = np.sum(bamboo_green[y0:y1], axis=0).astype(np.float32)
    evidence = cv2.blur(evidence[None, :], (17, 1))[0]
    absent = evidence < 7.0

    below = slice(ground + 12, min(h, ground + 85))
    dark_open = np.mean((r[below] < 100) & (g[below] < 100), axis=0)
    absent &= dark_open > 0.12

    mask = cv2.morphologyEx(absent.astype(np.uint8)[None, :], cv2.MORPH_CLOSE, np.ones((1, 11), np.uint8))[0]
    mask = cv2.morphologyEx(mask[None, :], cv2.MORPH_OPEN, np.ones((1, 9), np.uint8))[0].astype(bool)
    mask[:int(0.20 * w)] = False

    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY).astype(np.int16)
    results = []
    for x0, x1 in _runs(mask, max(18, int(0.075 * w))):
        left, right = x0, x1
        while left > 0 and evidence[left] < 12:
            left -= 1
        while right < w - 1 and evidence[right] < 12:
            right += 1
        x0, x1 = max(0, left + 1), min(w, right)
        if x1 - x0 < 18:
            continue

        tops = []
        for x in range(x0, x1, 3):
            column = gray[ground - 12:ground + 18, x]
            tops.append(ground - 12 + int(np.argmax(np.abs(np.diff(column)))))
        top = int(np.median(tops)) if tops else ground
        bottom = min(h - 1, ground + int(0.34 * h))
        coarse = np.array([[x0, top], [x1, top], [x1, bottom], [x0, bottom]], np.float32)
        contour = _refine_gap_contour(image_bgr, x0, x1, top, bottom, coarse)
        score = float(np.clip(1.0 - np.mean(evidence[x0:x1]) / 12.0, 0.5, 1.0))
        results.append({"name": "BAMBOO_GAP", "polygon": contour, "score": score, "evidence": float(np.mean(evidence[x0:x1]))})
    return results


def _refine_gap_contour(image_bgr: np.ndarray, x0: int, x1: int, top: int, bottom: int, fallback: np.ndarray) -> np.ndarray:
    pad = 5
    xx0, xx1 = max(0, x0 - pad), min(image_bgr.shape[1], x1 + pad)
    yy0, yy1 = max(0, top), min(image_bgr.shape[0], bottom)
    crop = image_bgr[yy0:yy1, xx0:xx1]
    if crop.size == 0:
        return fallback

    gray = cv2.GaussianBlur(cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    otsu, _ = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    mask = (gray < min(float(otsu), 105.0)).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8), iterations=2)

    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    seed_labels = set()
    a = max(0, x0 - xx0 + 3)
    b = min(labels.shape[1], x1 - xx0 - 3)
    for label in np.unique(labels[:min(12, labels.shape[0]), a:b]):
        if label and label < count and stats[label, cv2.CC_STAT_AREA] > 50:
            seed_labels.add(int(label))
    if not seed_labels:
        return fallback

    component = np.isin(labels, list(seed_labels)).astype(np.uint8)
    contours, _ = cv2.findContours(component * 255, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return fallback
    contour = max(contours, key=cv2.contourArea)
    epsilon = max(1.0, 0.01 * cv2.arcLength(contour, True))
    polygon = cv2.approxPolyDP(contour, epsilon, True).reshape(-1, 2).astype(np.float32)
    polygon[:, 0] += xx0
    polygon[:, 1] += yy0
    return polygon
