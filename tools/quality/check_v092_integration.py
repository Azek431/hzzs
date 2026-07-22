#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
checks = {
    "完成驱动门禁": (
        root / "app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt",
        "HZZS_V092_COMPLETION_DRIVEN_CAPTURE",
    ),
    "固定帧率门限已移除": (
        root / "app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt",
        "minimumFrameIntervalNanos",
    ),
    "领域轮廓类型": (
        root / "app/src/main/java/top/azek431/hzzs/domain/vision/VisionModels.kt",
        "displayContour: List<NormalizedPoint>",
    ),
    "近似轮廓生成器": (
        root / "app/src/main/java/top/azek431/hzzs/domain/vision/ApproximateContours.kt",
        "fun Detection.withApproximateDisplayContour()",
    ),
    "追踪后轮廓桥接": (
        root / "app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt",
        "it.detection.withApproximateDisplayContour()",
    ),
    "HUD Path 绘制": (
        root / "app/src/main/java/top/azek431/hzzs/service/overlay/OverlayController.kt",
        "drawDisplayContour",
    ),
    "HUD 临时隐身": (
        root / "app/src/main/java/top/azek431/hzzs/service/overlay/OverlayController.kt",
        "suspendForCapture",
    ),
}
errors = []
for name, (path, marker) in checks.items():
    if not path.exists():
        errors.append(f"{name}: 文件不存在 {path.relative_to(root)}")
        continue
    text = path.read_text(encoding="utf-8")
    if name == "固定帧率门限已移除":
        if marker in text:
            errors.append(f"{name}: 仍发现 {marker}")
    elif marker not in text:
        errors.append(f"{name}: 缺少标记 {marker}")

if errors:
    for error in errors:
        print(f"[FAIL] {error}")
    sys.exit(1)
print("[OK] v0.9.2 当前源码精确适配检查通过")
