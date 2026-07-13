#!/usr/bin/env python3
"""使用合成像素帧验证正式 HZZS 原生视觉核心。"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


RESULT_VERSION = 3
RESULT_INTS = 64
KIND_NONE = 0
KIND_BOTTLE = 1
KIND_CAKE = 2
KIND_SPIKE = 3
DETECTION_OFFSETS = {
    KIND_BOTTLE: 12,
    KIND_CAKE: 28,
    KIND_SPIKE: 44,
}

BOTTLE_GREEN = 0xFF28C85A
CAKE_ORANGE = 0xFFF0A050
DARK_METAL = 0xFF606060
NEAR_THRESHOLD = 0xFFC87850


def ratio(value: int, numerator: int, denominator: int) -> int:
    return (value * numerator + denominator // 2) // denominator


@dataclass
class SyntheticFrame:
    width: int
    height: int
    stride: int
    pixels: object

    @classmethod
    def create(cls, width: int, height: int, stride: int | None = None) -> "SyntheticFrame":
        actual_stride = stride or width
        if actual_stride < width:
            raise ValueError("stride 不能小于 width")
        pixels = (ctypes.c_uint32 * (actual_stride * height))()
        ctypes.memset(ctypes.addressof(pixels), 0xFF, ctypes.sizeof(pixels))
        return cls(width=width, height=height, stride=actual_stride, pixels=pixels)

    def fill_rect(
        self,
        left: int,
        top: int,
        right: int,
        bottom: int,
        color: int,
    ) -> None:
        clipped_left = max(0, min(left, self.width - 1))
        clipped_right = max(0, min(right, self.width - 1))
        clipped_top = max(0, min(top, self.height - 1))
        clipped_bottom = max(0, min(bottom, self.height - 1))
        if clipped_left > clipped_right or clipped_top > clipped_bottom:
            return
        for y in range(clipped_top, clipped_bottom + 1):
            row = y * self.stride
            for x in range(clipped_left, clipped_right + 1):
                self.pixels[row + x] = color


@dataclass(frozen=True)
class RegressionCase:
    name: str
    frame: SyntheticFrame
    expected_kinds: frozenset[int]
    expected_primary: int = KIND_NONE


def player_geometry(frame: SyntheticFrame) -> tuple[int, int]:
    return (
        ratio(frame.width, 214, 691),
        max(1, ratio(frame.width, 107, 691)),
    )


def paint_bottle(frame: SyntheticFrame, distance_percent: int = 10) -> None:
    player_right, player_width = player_geometry(frame)
    left = player_right + ratio(frame.width, distance_percent, 100)
    right = left + max(24, ratio(player_width, 55, 100))
    frame.fill_rect(
        left,
        ratio(frame.height, 48, 100),
        right,
        ratio(frame.height, 70, 100),
        BOTTLE_GREEN,
    )


def paint_cake(frame: SyntheticFrame, distance_percent: int = 15) -> None:
    player_right, _ = player_geometry(frame)
    left = player_right + ratio(frame.width, distance_percent, 100)
    right = left + ratio(frame.width, 20, 100)
    frame.fill_rect(
        left,
        ratio(frame.height, 62, 100),
        right,
        ratio(frame.height, 92, 100),
        CAKE_ORANGE,
    )


def paint_spike(frame: SyntheticFrame, distance_percent: int = 12) -> None:
    player_right, _ = player_geometry(frame)
    left = player_right + ratio(frame.width, distance_percent, 100)
    right = left + max(8, ratio(frame.width, 5, 100))
    frame.fill_rect(
        left,
        ratio(frame.height, 47, 100),
        right,
        ratio(frame.height, 56, 100),
        DARK_METAL,
    )


def make_cases() -> list[RegressionCase]:
    empty = SyntheticFrame.create(480, 1067)

    bottle = SyntheticFrame.create(480, 1067)
    paint_bottle(bottle)

    cake_with_padding = SyntheticFrame.create(480, 1067, stride=496)
    paint_cake(cake_with_padding)

    spike_large_frame = SyntheticFrame.create(576, 1280)
    paint_spike(spike_large_frame)

    combined = SyntheticFrame.create(480, 1067)
    paint_bottle(combined, distance_percent=8)
    paint_cake(combined, distance_percent=25)
    paint_spike(combined, distance_percent=50)

    near_threshold = SyntheticFrame.create(360, 800)
    player_right, player_width = player_geometry(near_threshold)
    near_threshold.fill_rect(
        player_right + ratio(near_threshold.width, 10, 100),
        ratio(near_threshold.height, 58, 100),
        player_right + ratio(near_threshold.width, 10, 100) + max(20, player_width // 2),
        ratio(near_threshold.height, 70, 100),
        NEAR_THRESHOLD,
    )

    return [
        RegressionCase("全负样本", empty, frozenset()),
        RegressionCase("绿瓶", bottle, frozenset({KIND_BOTTLE}), KIND_BOTTLE),
        RegressionCase("带行填充的蛋糕", cake_with_padding, frozenset({KIND_CAKE}), KIND_CAKE),
        RegressionCase("不同分辨率的悬挂尖刺", spike_large_frame, frozenset({KIND_SPIKE}), KIND_SPIKE),
        RegressionCase(
            "多类障碍同时出现",
            combined,
            frozenset({KIND_BOTTLE, KIND_CAKE, KIND_SPIKE}),
            KIND_BOTTLE,
        ),
        RegressionCase("颜色阈值负样本", near_threshold, frozenset()),
    ]


def compile_native(source_root: Path, output: Path, compiler: str) -> list[str]:
    source = source_root / "HzzsVisionCore.cpp"
    header = source_root / "HzzsVisionCore.h"
    if not source.is_file() or not header.is_file():
        raise FileNotFoundError(f"找不到正式视觉核心：{source_root}")
    if sys.platform == "darwin":
        shared_flag = "-dynamiclib"
    elif sys.platform.startswith("linux"):
        shared_flag = "-shared"
    else:
        raise RuntimeError("合成回归当前仅支持 Linux 和 macOS 宿主机")

    command = [
        compiler,
        "-std=c++17",
        "-O2",
        "-fPIC",
        shared_flag,
        "-fno-exceptions",
        "-fno-rtti",
        "-Wall",
        "-Wextra",
        "-Wpedantic",
        "-Werror",
        str(source),
        "-I",
        str(source_root),
        "-o",
        str(output),
    ]
    subprocess.run(command, check=True)
    return command


def load_native(path: Path):
    library = ctypes.CDLL(str(path))
    analyze = library.hzzs_vision_analyze_packed
    analyze.argtypes = [
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.POINTER(ctypes.c_int32),
        ctypes.c_int,
    ]
    analyze.restype = ctypes.c_int
    return analyze


def analyze_frame(analyze, frame: SyntheticFrame) -> list[int]:
    packed = (ctypes.c_int32 * RESULT_INTS)()
    count = analyze(
        frame.pixels,
        frame.width,
        frame.height,
        frame.stride,
        3,
        2,
        packed,
        RESULT_INTS,
    )
    if count != RESULT_INTS:
        raise AssertionError(f"结果长度应为 {RESULT_INTS}，实际为 {count}")
    return list(packed)


def found_kinds(packed: list[int]) -> frozenset[int]:
    return frozenset(
        kind
        for kind, offset in DETECTION_OFFSETS.items()
        if packed[offset] == 1
    )


def validate_case(analyze, case: RegressionCase) -> dict[str, object]:
    packed = analyze_frame(analyze, case.frame)
    failures: list[str] = []
    actual_kinds = found_kinds(packed)

    if packed[0] != RESULT_VERSION:
        failures.append(f"结果版本应为 {RESULT_VERSION}，实际为 {packed[0]}")
    if packed[1] != case.frame.width or packed[2] != case.frame.height:
        failures.append("结果尺寸与输入尺寸不一致")
    if actual_kinds != case.expected_kinds:
        failures.append(
            f"检测类型应为 {sorted(case.expected_kinds)}，实际为 {sorted(actual_kinds)}"
        )
    if packed[8] != case.expected_primary:
        failures.append(f"主目标应为 {case.expected_primary}，实际为 {packed[8]}")

    for kind in actual_kinds:
        offset = DETECTION_OFFSETS[kind]
        left, top, right, bottom = packed[offset + 2 : offset + 6]
        score = packed[offset + 14]
        if not (0 <= left <= right < case.frame.width):
            failures.append(f"类型 {kind} 的水平边界无效")
        if not (0 <= top <= bottom < case.frame.height):
            failures.append(f"类型 {kind} 的垂直边界无效")
        if score <= 0:
            failures.append(f"类型 {kind} 的置信度无效")

    return {
        "name": case.name,
        "passed": not failures,
        "expectedKinds": sorted(case.expected_kinds),
        "actualKinds": sorted(actual_kinds),
        "expectedPrimary": case.expected_primary,
        "actualPrimary": packed[8],
        "failures": failures,
    }


def validate_guards(analyze) -> list[str]:
    failures: list[str] = []
    short_output = (ctypes.c_int32 * (RESULT_INTS - 1))()
    short_count = analyze(None, 480, 1067, 480, 3, 2, short_output, RESULT_INTS - 1)
    if short_count != 0:
        failures.append(f"输出容量不足时应返回 0，实际为 {short_count}")

    invalid_stride = SyntheticFrame.create(480, 1067)
    packed = (ctypes.c_int32 * RESULT_INTS)()
    count = analyze(
        invalid_stride.pixels,
        invalid_stride.width,
        invalid_stride.height,
        invalid_stride.width - 1,
        3,
        2,
        packed,
        RESULT_INTS,
    )
    if count != RESULT_INTS:
        failures.append("无效 stride 仍应返回完整的空结果包")
    if found_kinds(list(packed)):
        failures.append("无效 stride 不应产生检测结果")
    return failures


def parse_args() -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root",
        type=Path,
        default=repo_root / "app/src/main/cpp/vision2",
        help="HzzsVisionCore.cpp 所在目录",
    )
    parser.add_argument(
        "--compiler",
        default=os.environ.get("CXX", "g++"),
        help="宿主机 C++ 编译器，默认读取 CXX 或使用 g++",
    )
    parser.add_argument("--report", type=Path, help="可选 JSON 报告路径")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    suffix = ".dylib" if sys.platform == "darwin" else ".so"
    with tempfile.TemporaryDirectory(prefix="hzzs-synthetic-vision-") as temp_dir:
        library_path = Path(temp_dir) / f"libhzzsvision_synthetic{suffix}"
        command = compile_native(args.source_root.resolve(), library_path, args.compiler)
        analyze = load_native(library_path)
        rows = [validate_case(analyze, case) for case in make_cases()]
        guard_failures = validate_guards(analyze)

    report = {
        "resultVersion": RESULT_VERSION,
        "compiler": args.compiler,
        "compileCommand": command,
        "total": len(rows) + 1,
        "passed": sum(bool(row["passed"]) for row in rows) + (0 if guard_failures else 1),
        "failed": sum(not bool(row["passed"]) for row in rows) + (1 if guard_failures else 0),
        "cases": rows,
        "guardFailures": guard_failures,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    return 1 if report["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
