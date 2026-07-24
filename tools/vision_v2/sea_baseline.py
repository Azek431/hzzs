from __future__ import annotations

import cv2
import numpy as np

BASE_W, BASE_H = 1272, 2772
DEFAULT_THRESHOLD = 10


def int_to_rgb(value: int) -> np.ndarray:
    u = value & 0xFFFFFFFF
    return np.array([(u >> 16) & 255, (u >> 8) & 255, u & 255], dtype=np.int16)


PATTERNS = {
    "大断崖": (-14134134, [(57, 86, -659994), (206, 17, -66830), (294, 65, -198418), (633, 88, -67343), (739, 168, -1514535), (733, 29, -12676910)]),
    "小断崖": (-13605719, [(69, 29, -462617), (144, 63, -395286), (333, 63, -198418), (422, 87, -3369373)]),
    "矮沙丘": (-400963, [(70, -188, -399674), (121, -210, -268857), (198, -171, -1720697), (225, 2, -4088705)]),
    "高沙丘": (-465469, [(44, -216, -1854336), (141, -413, -4042089), (210, -180, -993359), (231, -24, -4355479)]),
    "船锚": (-4603180, [(68, 22, -9666401), (150, -18, -2235925), (50, 78, -8881018), (116, 50, -11382703), (11, -55, -5539265)]),
    "复活": (-51345, [(71, 42, -5918), (176, 121, -117170), (337, 68, -237986), (300, -46, -3595), (380, 75, -34736)]),
}


def find_pattern(image_bgr: np.ndarray, first: int, points: list[tuple[int, int, int]], threshold: int = DEFAULT_THRESHOLD):
    """Vectorized emulation of one AutoJs findMultiColors call.

    It is intentionally a baseline, not the proposed production algorithm.
    """
    rgb = image_bgr[:, :, ::-1].astype(np.int16)
    h, w = rgb.shape[:2]
    sx, sy = w / BASE_W, h / BASE_H
    rx = int(round(290 * sx))
    ry = int(round(1213 * sy))
    rw = int(round(982 * sx))
    rh = int(round(1229 * sy))
    x2 = min(w, rx + rw)
    y2 = min(h, ry + rh)

    target = int_to_rgb(first)
    roi = rgb[ry:y2, rx:x2]
    mask = np.max(np.abs(roi - target), axis=2) <= threshold
    ys, xs = np.nonzero(mask)
    scaled = [(int(round(dx * sx)), int(round(dy * sy)), int_to_rgb(color)) for dx, dy, color in points]
    for yy, xx in zip(ys, xs):
        ax, ay = rx + int(xx), ry + int(yy)
        valid = True
        for dx, dy, color in scaled:
            tx, ty = ax + dx, ay + dy
            if tx < 0 or tx >= w or ty < 0 or ty >= h or np.max(np.abs(rgb[ty, tx] - color)) > threshold:
                valid = False
                break
        if valid:
            return ax, ay
    return None
