"""
diagnose_misses.py — 分析 reference_detect 在 11 个漏检帧上的逐像素行为

核心问题：reference_detect 和 C++ exact/fast 引擎用同一份 PATTERNS 和同一坐标公式，
但 11 个漏检帧上的行为不同。本脚本逐步拆解每个漏检帧的检测过程。
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

BASE_W, BASE_H = 1272, 2772
SEARCH_PADDING_LEFT = 50  # covers ground-truth left-edge anchors (x≥50 px)
THRESHOLD = 10

PATTERNS = [
    ("大断崖", -14134134, [(57, 86, -659994), (206, 17, -66830), (294, 65, -198418), (633, 88, -67343), (739, 168, -1514535), (733, 29, -12676910)]),
    ("小断崖", -13605719, [(69, 29, -462617), (144, 63, -395286), (333, 63, -198418), (422, 87, -3369373)]),
    ("矮沙丘", -400963, [(70, -188, -399674), (121, -210, -268857), (198, -171, -1720697), (225, 2, -4088705)]),
    ("高沙丘", -465469, [(44, -216, -1854336), (141, -413, -4042089), (210, -180, -993359), (231, -24, -4355479)]),
    ("船锚", -4603180, [(68, 22, -9666401), (150, -18, -2235925), (50, 78, -8881018), (116, 50, -11382703), (11, -55, -5539265)]),
    ("复活", -51345, [(71, 42, -5918), (176, 121, -117170), (337, 68, -237986), (300, -46, -3595), (380, 75, -34736)]),
]


def js_round(value: float) -> int:
    return math.floor(value + 0.5)


def int_to_rgb(value: int) -> np.ndarray:
    u = value & 0xFFFFFFFF
    return np.array([(u >> 16) & 255, (u >> 8) & 255, u & 255], dtype=np.int16)


def verify_color(actual: np.ndarray, expected: np.ndarray, threshold: int) -> bool:
    delta = np.abs(actual.astype(np.int16) - expected)
    return int(delta.max()) <= threshold


def load_image(path: Path) -> np.ndarray:
    with Image.open(path) as im:
        im = im.convert("RGB")
        return np.array(im)[:, :, ::-1].copy()


def diagnose_frame(image_bgr: np.ndarray, gt: dict) -> dict:
    """逐步拆解 reference_detect 的行为，返回诊断信息。"""
    rgb = image_bgr[:, :, ::-1]
    h, w = rgb.shape[:2]
    sx, sy = w / BASE_W, h / BASE_H

    rx = max(0, js_round(290 * sx) - SEARCH_PADDING_LEFT)
    ry = max(0, js_round(1213 * sy))
    original_right = js_round(290 * sx) + max(0, js_round(982 * sx))
    x2 = max(rx, min(w, original_right + SEARCH_PADDING_LEFT))
    y2 = max(ry, min(h, ry + max(0, js_round(1229 * sy))))

    result = {
        "frame_size": f"{w}x{h}",
        "scale": f"sx={sx:.4f}, sy={sy:.4f}",
        "search_region": f"[{rx},{ry}]→[{x2},{y2}]",
        "gt_anchor": f"({gt['anchor_x']},{gt['anchor_y']}) kind={gt['kind']}",
        "steps": [],
    }

    for kind, first, points in PATTERNS:
        target = int_to_rgb(first)
        roi = rgb[ry:y2, rx:x2]
        mask = np.max(np.abs(roi.astype(np.int16) - target), axis=2) <= THRESHOLD
        ys, xs = np.nonzero(mask)
        candidates = list(zip(ys.tolist(), xs.tolist()))
        step_info = {
            "kind": kind,
            "target_rgb": target.tolist(),
            "anchor_matches": len(candidates),
            "verified": 0,
            "failures": [],
        }

        for yy, xx in candidates[:20]:  # 只检查前 20 个候选
            ax, ay = rx + xx, ry + yy
            ok = True
            failures = []
            for dx, dy, color in points:
                tx = ax + js_round(dx * sx)
                ty = ay + js_round(dy * sy)
                if tx < 0 or tx >= w or ty < 0 or ty >= h:
                    failures.append(f"OOB ({dx},{dy})→({tx},{ty})")
                    ok = False
                    break
                if not verify_color(rgb[ty, tx], int_to_rgb(color), THRESHOLD):
                    actual_rgb = rgb[ty, tx].tolist()
                    expected_rgb = int_to_rgb(color).tolist()
                    delta = [abs(a - e) for a, e in zip(actual_rgb, expected_rgb)]
                    failures.append(
                        f"({dx},{dy})→({tx},{ty}) "
                        f"actual={actual_rgb} expected={expected_rgb} delta={delta}"
                    )
                    ok = False
                    break
            if ok:
                step_info["verified"] += 1
                step_info["first_verified_anchor"] = (ax, ay)
                if kind == gt["kind"]:
                    step_info["match_found"] = True
                break
            else:
                step_info["failures"].append({
                    "anchor": (ax, ay),
                    "reasons": failures[:2],
                })

        result["steps"].append(step_info)
        if step_info.get("match_found"):
            break

    return result


def main():
    gt_path = Path("tools/vision_v3/ground_truth/sea_salt_114_v2.json")
    dataset_root = Path(r"D:\Code\AI\火崽崽\火崽崽奇妙屋\算法测试\测试图片\海盐客厅")
    gt = json.loads(gt_path.read_text(encoding="utf-8"))

    misses = [r for r in gt["records"] if r.get("primary") is not None]
    # Filter to just the false negatives from the benchmark
    fn_files = {
        "Screenshot_2026-07-21-10-15-02-56_73230ea9d8560975a5358fd7bd043f47.jpg",
        "Screenshot_2026-07-21-10-15-07-84_73230ea9d8560975a5358fd7bd043f47.jpg",
        "Screenshot_2026-07-21-10-15-17-22_73230ea9d8560975a5358fd7bd043f47.jpg",
        "Screenshot_20260721_102524424.jpg",
        "Screenshot_20260721_102721338.jpg",
        "Screenshot_20260721_102724981.jpg",
        "Screenshot_20260721_102728992.jpg",
        "Screenshot_2026-07-21-10-26-21-32_73230ea9d8560975a5358fd7bd043f47.jpg",
        "Screenshot_2026-07-21-10-26-53-18_73230ea9d8560975a5358fd7bd043f47.jpg",
        "Screenshot_2026-07-21-10-26-54-67_73230ea9d8560975a5358fd7bd043f47.jpg",
        "Screenshot_2026-07-21-10-27-09-38_73230ea9d8560975a5358fd7bd043f47.jpg",
    }

    for record in gt["records"]:
        if record["file"] not in fn_files:
            continue
        img_path = None
        rel = record.get("relative_path", "")
        name = record["file"]

        # Find image
        candidate = dataset_root / rel
        if candidate.is_file():
            img_path = candidate
        else:
            matches = list(dataset_root.rglob(name))
            if matches:
                img_path = matches[0]

        if img_path is None:
            print(f"MISSING: {name}")
            continue

        print(f"\n{'='*70}")
        print(f"文件: {name}")
        print(f"GT: kind={record['primary']['kind']}, "
              f"anchor=({record['primary']['anchor_x']},{record['primary']['anchor_y']})")
        print(f"geometry_gate={record['primary'].get('geometry_gate', True)}")

        image = load_image(img_path)
        diag = diagnose_frame(image, record["primary"])

        print(f"帧大小: {diag['frame_size']}, 缩放: {diag['scale']}")
        print(f"搜索区: {diag['search_region']}")

        for step in diag["steps"]:
            status = ""
            if step.get("match_found"):
                status = " ← 找到了！"
            print(f"\n  [{step['kind']}] "
                  f"锚候选数={step['anchor_matches']}, "
                  f"通过验证={step['verified']}{status}")
            if step["failures"] and not step.get("match_found"):
                for f in step["failures"][:3]:
                    print(f"    失败: anchor={f['anchor']}")
                    for r in f["reasons"][:2]:
                        print(f"      {r}")


if __name__ == "__main__":
    main()
