#!/usr/bin/env python3
"""Fast resource integrity checks that also work without OpenCV."""
from __future__ import annotations

import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ERRORS: list[str] = []
CHECKS: list[str] = []

for xml in ROOT.rglob("src/main/res/**/*.xml"):
    relative_parts = xml.relative_to(ROOT).parts
    if any(part in {".git", ".gradle", ".backups", "build"} for part in relative_parts):
        continue
    try:
        ET.parse(xml)
        CHECKS.append(f"xml:{xml.relative_to(ROOT)}")
    except Exception as exc:
        ERRORS.append(f"{xml}: {exc}")

for relative in (
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable/ic_launcher_monochrome.xml",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    "app/src/main/res/xml/accessibility_service_config.xml",
    "app/src/main/res/xml/file_paths.xml",
):
    path = ROOT / relative
    if path.exists():
        CHECKS.append(f"present:{relative}")
    else:
        ERRORS.append(f"missing {relative}")

for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
    path = ROOT / f"app/src/main/res/mipmap-{density}/ic_launcher.png"
    if not path.exists():
        ERRORS.append(f"missing {path}")
        continue
    image = Image.open(path).convert("RGBA")
    corners = (
        image.getpixel((0, 0)),
        image.getpixel((image.width - 1, 0)),
        image.getpixel((0, image.height - 1)),
        image.getpixel((image.width - 1, image.height - 1)),
    )
    if any(alpha < 250 for *_, alpha in corners):
        ERRORS.append(f"{path}: transparent corner in legacy icon")
    CHECKS.append(f"icon:{density}:{image.width}x{image.height}")

assets: dict[str, Path] = {
    "wechat": ROOT / "app/src/main/res/drawable/donation_wechat.png",
    "alipay": ROOT / "app/src/main/res/drawable/donation_alipay.jpg",
}
for name, path in assets.items():
    if not path.exists():
        ERRORS.append(f"missing {path}")
        continue
    image = Image.open(path)
    if min(image.size) < 600:
        ERRORS.append(f"{path}: image too small {image.size}")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    CHECKS.append(f"donation:{name}:{image.width}x{image.height}:{digest[:12]}")

# OpenCV is optional locally. CI installs it and therefore performs the stronger
# standard Alipay QR decode check.
try:
    import cv2  # type: ignore
except ImportError:
    CHECKS.append("alipay-qr:opencv-unavailable-hash-only")
else:
    alipay = cv2.imread(str(assets["alipay"]))
    if alipay is None:
        ERRORS.append("Alipay image cannot be decoded")
    else:
        data, _, _ = cv2.QRCodeDetector().detectAndDecode(alipay)
        if data.startswith("https://qr.alipay.com/"):
            CHECKS.append("alipay-qr:decoded")
        else:
            ERRORS.append("Alipay QR decode check failed")

result = {"status": "PASS" if not ERRORS else "FAIL", "checks": len(CHECKS), "errors": ERRORS}
print(json.dumps(result, ensure_ascii=False, indent=2))
if ERRORS:
    raise SystemExit(1)
