"""
benchmark_sea_v2.py — 海盐客厅 v2 ground truth 评估

使用 reference_and_benchmark.py 的 reference_detect() 作为算法代理
（与 C++ SoySauceExactEngine 语义一致，同一份 PATTERNS 定义）

用法:
    python tools/vision_v3/benchmark_sea_v2.py \
        --dataset "D:/Code/AI/火崽崽/火崽崽奇妙屋/算法测试/测试图片/海盐客厅" \
        --ground-truth tools/vision_v3/ground_truth/sea_salt_114_v2.json
"""
from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

def load_image_cv2_compatible(path: Path) -> np.ndarray | None:
    """用 PIL 加载中文路径图片，转为 BGR numpy 数组（与 cv2.imread 等价）。"""
    try:
        with Image.open(path) as im:
            im = im.convert("RGB")
            arr = np.array(im)
            return arr[:, :, ::-1].copy()  # RGB→BGR
    except Exception:
        return None

# ── 从 reference_and_benchmark 复用 ──────────────────────────────
BASE_W, BASE_H = 1272, 2772
SEARCH_PADDING_LEFT = 50  # covers ground-truth left-edge anchors (x≥50 px)

PATTERNS = [
    ("大断崖", -14134134, [(57, 86, -659994), (206, 17, -66830), (294, 65, -198418), (633, 88, -67343), (739, 168, -1514535), (733, 29, -12676910)]),
    ("小断崖", -13605719, [(69, 29, -462617), (144, 63, -395286), (333, 63, -198418), (422, 87, -3369373)]),
    ("矮沙丘", -400963, [(70, -188, -399674), (121, -210, -268857), (198, -171, -1720697), (225, 2, -4088705)]),
    ("高沙丘", -465469, [(44, -216, -1854336), (141, -413, -4042089), (210, -180, -993359), (231, -24, -4355479)]),
    ("船锚", -4603180, [(68, 22, -9666401), (150, -18, -2235925), (50, 78, -8881018), (116, 50, -11382703), (11, -55, -5539265)]),
    ("复活", -51345, [(71, 42, -5918), (176, 121, -117170), (337, 68, -237986), (300, -46, -3595), (380, 75, -34736)]),
]

NAMES = [p[0] for p in PATTERNS]


def js_round(value: float) -> int:
    return math.floor(value + 0.5)


def int_to_rgb(value: int) -> np.ndarray:
    u = value & 0xFFFFFFFF
    return np.array([(u >> 16) & 255, (u >> 8) & 255, u & 255], dtype=np.int16)


def verify_color(actual: np.ndarray, expected: np.ndarray, threshold: int, metric: int) -> bool:
    delta = np.abs(actual.astype(np.int16) - expected)
    if metric == 1:
        return int(delta.sum()) <= threshold * 3
    return int(delta.max()) <= threshold


def reference_detect(image_bgr: np.ndarray, threshold: int = 10, metric: int = 0):
    """与 reference_and_benchmark.py 相同的 reference_detect 实现。"""
    rgb = image_bgr[:, :, ::-1]
    h, w = rgb.shape[:2]
    sx, sy = w / BASE_W, h / BASE_H
    rx = max(0, js_round(290 * sx) - SEARCH_PADDING_LEFT)
    ry = max(0, js_round(1213 * sy))
    # Keep the same right boundary as original (extend width by padding, not shift).
    original_right = js_round(290 * sx) + max(0, js_round(982 * sx))
    x2 = max(rx, min(w, original_right + SEARCH_PADDING_LEFT))
    y2 = max(ry, min(h, ry + max(0, js_round(1229 * sy))))
    for kind, first, points in PATTERNS:
        target = int_to_rgb(first)
        roi = rgb[ry:y2, rx:x2]
        mask = np.max(np.abs(roi.astype(np.int16) - target), axis=2) <= threshold
        ys, xs = np.nonzero(mask)
        for yy, xx in zip(ys.tolist(), xs.tolist()):
            ax, ay = rx + xx, ry + yy
            ok = True
            for dx, dy, color in points:
                tx = ax + js_round(dx * sx)
                ty = ay + js_round(dy * sy)
                if tx < 0 or tx >= w or ty < 0 or ty >= h or not verify_color(rgb[ty, tx], int_to_rgb(color), threshold, metric):
                    ok = False
                    break
            if ok:
                return kind, ax, ay
    return None


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    arr = np.asarray(values, dtype=np.float64)
    return float(np.percentile(arr, p))


def find_image(dataset_root: Path, record: dict) -> Path | None:
    """尝试多种方式定位图片。"""
    rel = record.get("relative_path", "")
    name = record.get("file", "")

    # 1) 直接相对路径
    candidate = dataset_root / rel
    if candidate.is_file():
        return candidate

    # 2) 按文件名递归搜索
    matches = list(dataset_root.rglob(name))
    if len(matches) == 1:
        return matches[0]
    if len(matches) > 1:
        # 优先选相对路径中包含的子目录
        rel_dir = str(Path(rel).parent) if rel else ""
        for m in matches:
            if rel_dir and rel_dir in str(m):
                return m
        return matches[0]

    return None


def evaluate(record: dict, actual: tuple | None, img_w: int) -> str:
    """返回错误类型: false_positive / false_negative / wrong_class /
       action_edge_over_1pct / correct"""
    expected = record.get("primary")
    if expected is None:
        if actual is not None:
            return "false_positive"
        return "correct"

    if actual is None:
        return "false_negative"

    if actual[0] != expected["kind"]:
        return "wrong_class"

    if expected.get("geometry_gate", True):
        error_ratio = abs(actual[1] - expected["anchor_x"]) / img_w
        if error_ratio > 0.01:
            return "action_edge_over_1pct"

    return "correct"


def main() -> None:
    parser = argparse.ArgumentParser(description="海盐客厅 v2 ground truth 评估")
    parser.add_argument("--dataset", type=Path, required=True,
                        help="测试图片根目录")
    parser.add_argument("--ground-truth", type=Path, required=True,
                        help="sea_salt_114_v2.json 路径")
    parser.add_argument("--threshold", type=int, default=10,
                        help="颜色匹配阈值 (默认 10)")
    parser.add_argument("--iterations", type=int, default=8,
                        help="每帧计时迭代次数")
    parser.add_argument("--output", type=Path, default=None,
                        help="JSON 报告输出路径")
    args = parser.parse_args()

    truth = json.loads(args.ground_truth.read_text(encoding="utf-8"))
    records = truth["records"]
    metric = 0  # box metric

    # ── 逐帧评估 ────────────────────────────────────────────────
    errors: list[dict] = []
    edge_errors: list[float] = []
    detections: dict[str, int] = {name: 0 for name in NAMES}
    read_fail = 0
    timings_ms: list[float] = []
    frame_medians: list[float] = []
    per_kind: dict[str, dict[str, int]] = {
        name: {"correct": 0, "fn": 0, "wrong": 0, "edge_err": 0}
        for name in NAMES
    }

    total = len(records)
    t0 = time.perf_counter()

    for idx, record in enumerate(records):
        img_path = find_image(args.dataset, record)
        if img_path is None:
            read_fail += 1
            errors.append({"file": record["file"], "error": "read_failed"})
            continue

        image = load_image_cv2_compatible(img_path)
        if image is None:
            read_fail += 1
            errors.append({"file": record["file"], "error": "read_failed"})
            continue

        h, w = image.shape[:2]

        # 检测
        start = time.perf_counter_ns()
        actual = reference_detect(image, threshold=args.threshold, metric=metric)
        elapsed = (time.perf_counter_ns() - start) / 1_000_000.0
        timings_ms.append(elapsed)

        # 评估
        err_type = evaluate(record, actual, w)
        expected = record.get("primary")
        expected_kind = expected["kind"] if expected else None

        if err_type == "correct":
            if actual is not None:
                detections[actual[0]] += 1
                if expected_kind and expected_kind in per_kind:
                    per_kind[expected_kind]["correct"] += 1
        else:
            info: dict = {"file": record["file"], "error": err_type}
            if expected:
                info["expected"] = expected
            if actual:
                info["actual"] = {"kind": actual[0], "anchor_x": actual[1], "anchor_y": actual[2]}
            if err_type == "action_edge_over_1pct":
                ratio = abs(actual[1] - expected["anchor_x"]) / w  # type: ignore[index]
                info["error_ratio"] = round(ratio, 6)
                edge_errors.append(ratio)
                per_kind[expected_kind]["edge_err"] += 1  # type: ignore[index]
            elif err_type == "false_negative":
                if expected_kind and expected_kind in per_kind:
                    per_kind[expected_kind]["fn"] += 1
            elif err_type == "wrong_class":
                if expected_kind and expected_kind in per_kind:
                    per_kind[expected_kind]["wrong"] += 1
            errors.append(info)

        # 计时（预热后）
        for _ in range(3):
            reference_detect(image, threshold=args.threshold, metric=metric)
        local: list[float] = []
        for _ in range(max(1, args.iterations)):
            s = time.perf_counter_ns()
            reference_detect(image, threshold=args.threshold, metric=metric)
            local.append((time.perf_counter_ns() - s) / 1_000_000.0)
        frame_medians.append(statistics.median(local))

        if (idx + 1) % 20 == 0:
            print(f"  进度 {idx + 1}/{total}...")

    elapsed_total = time.perf_counter() - t0

    # ── 汇总统计 ────────────────────────────────────────────────
    positives = [r for r in records if r.get("primary") is not None]
    negatives = [r for r in records if r.get("primary") is None]
    gated = [r for r in positives if r["primary"].get("geometry_gate", True)]

    fn_list = [e for e in errors if e["error"] == "false_negative"]
    fp_list = [e for e in errors if e["error"] == "false_positive"]
    wc_list = [e for e in errors if e["error"] == "wrong_class"]
    edge_list = [e for e in errors if e["error"] == "action_edge_over_1pct"]

    recall = (len(positives) - len(fn_list)) / len(positives) if positives else 0
    precision = (len(positives) - len(fn_list) - len(wc_list)) / max(
        len(positives) - len(fn_list) + len(fp_list), 1
    )
    class_acc = (len(positives) - len(fn_list) - len(wc_list)) / len(positives) if positives else 0
    geometry_rate = (len(gated) - len(edge_list)) / len(gated) if gated else 0

    report = {
        "dataset": "海盐客厅 v2",
        "schema": truth.get("schema"),
        "total_frames": total,
        "positives": len(positives),
        "negatives": len(negatives),
        "geometry_gated_positives": len(gated),
        "read_failures": read_fail,
        "threshold": args.threshold,
        "metric": "box",
        "algorithm": "reference_detect (Python, same PATTERNS as SoySauceExactEngine)",
        "results": {
            "false_positives": len(fp_list),
            "false_negatives": len(fn_list),
            "wrong_class": len(wc_list),
            "action_edge_over_1pct": len(edge_list),
            "recall": round(recall, 4),
            "precision": round(precision, 4),
            "class_accuracy": round(class_acc, 4),
            "geometry_pass_rate": round(geometry_rate, 4),
        },
        "per_kind": {},
        "edge_errors": {
            "count": len(edge_errors),
            "mean_ratio": round(statistics.fmean(edge_errors), 6) if edge_errors else 0.0,
            "p95_ratio": round(percentile(edge_errors, 95), 6),
            "max_ratio": round(max(edge_errors, default=0.0), 6),
        },
        "detections": detections,
        "errors": errors,
        "timing": {
            "per_frame_median": {
                "p50_ms": round(percentile(frame_medians, 50), 2),
                "p95_ms": round(percentile(frame_medians, 95), 2),
                "p99_ms": round(percentile(frame_medians, 99), 2),
                "max_ms": round(max(frame_medians, default=0.0), 2),
            },
            "total_elapsed_s": round(elapsed_total, 2),
        },
        "limitations": truth.get("limitations", []),
    }

    # 每类统计
    for kind, stats in per_kind.items():
        total_for_kind = stats["correct"] + stats["fn"] + stats["wrong"] + stats["edge_err"]
        if total_for_kind > 0:
            report["per_kind"][kind] = {
                "ground_truth_count": total_for_kind,
                "correct": stats["correct"],
                "false_negative": stats["fn"],
                "wrong_class": stats["wrong"],
                "edge_over_1pct": stats["edge_err"],
                "recall": round(stats["correct"] / total_for_kind, 4),
            }

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
        print(f"\n报告已写入: {args.output}")


if __name__ == "__main__":
    main()
