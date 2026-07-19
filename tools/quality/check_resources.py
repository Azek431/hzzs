#!/usr/bin/env python3
"""Fast resource integrity checks with no mandatory OpenCV dependency."""

from __future__ import annotations

import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    from PIL import Image
except ImportError as exc:  # pragma: no cover - environment setup failure
    raise SystemExit(
        "Pillow is required for resource checks. Install it with: "
        "python -m pip install Pillow"
    ) from exc

try:
    import cv2  # type: ignore
except ImportError:
    cv2 = None

ROOT = Path(__file__).resolve().parents[2]
ERRORS: list[str] = []
CHECKS: list[str] = []

EXPECTED_ASSET_SHA256 = {
    "donation_alipay.jpg": "5d280f70ac8b77c258346730882cc1cb3670788f228b11a2a1b7584f9daf2e9a",
    "donation_wechat.png": "97c42c97a56800bd58fd9ab54a86eb25efd1678a9700dc0d9965858b15c0d3cd",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


for xml in ROOT.rglob("src/main/res/**/*.xml"):
    try:
        ET.parse(xml)
        CHECKS.append(f"xml:{xml.relative_to(ROOT)}")
    except Exception as exc:  # noqa: BLE001 - report every malformed resource
        ERRORS.append(f"{xml}: {exc}")

# Adaptive icon layers must exist.
for path in [
    ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml",
    ROOT / "app/src/main/res/drawable/ic_launcher_monochrome.xml",
    ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
]:
    if not path.exists():
        ERRORS.append(f"missing {path}")

# Legacy icons must cover the canvas and must not regress to a white card.
for density in ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]:
    path = ROOT / f"app/src/main/res/mipmap-{density}/ic_launcher.png"
    if not path.exists():
        ERRORS.append(f"missing {path}")
        continue
    with Image.open(path) as source:
        image = source.convert("RGBA")
        corners = [
            image.getpixel((0, 0)),
            image.getpixel((image.width - 1, 0)),
            image.getpixel((0, image.height - 1)),
            image.getpixel((image.width - 1, image.height - 1)),
        ]
        if any(alpha < 250 for *_, alpha in corners):
            ERRORS.append(f"{path}: transparent corner in legacy icon")
        pixels = (
            image.get_flattened_data()
            if hasattr(image, "get_flattened_data")
            else image.getdata()
        )
        near_white = sum(
            1 for red, green, blue, alpha in pixels
            if alpha > 0 and red > 245 and green > 245 and blue > 245
        ) / max(1, image.width * image.height)
        if near_white > 0.55:
            ERRORS.append(f"{path}: probable white-card regression ({near_white:.1%})")
        CHECKS.append(f"icon:{density}:{image.width}x{image.height}")

# Donation assets must remain the exact user-supplied files and be large enough.
donation_dir = ROOT / "feature/about/src/main/res/drawable"
for name, expected_hash in EXPECTED_ASSET_SHA256.items():
    path = donation_dir / name
    if not path.exists():
        ERRORS.append(f"missing {path}")
        continue
    with Image.open(path) as image:
        if min(image.size) < 600:
            ERRORS.append(f"{path}: image too small {image.size}")
        CHECKS.append(f"donation:{name}:{image.size[0]}x{image.size[1]}")
    actual_hash = sha256(path)
    if actual_hash != expected_hash:
        ERRORS.append(f"{path}: SHA-256 mismatch")
    else:
        CHECKS.append(f"donation-sha256:{name}")

# OpenCV is optional. When present, additionally prove that the Alipay image
# decodes to an official Alipay QR URL. The SHA-256 check remains authoritative
# on machines where OpenCV is intentionally not installed.
alipay_path = donation_dir / "donation_alipay.jpg"
if cv2 is not None and alipay_path.exists():
    alipay = cv2.imread(str(alipay_path))
    if alipay is None:
        ERRORS.append("Alipay QR image could not be loaded by OpenCV")
    else:
        data, _, _ = cv2.QRCodeDetector().detectAndDecode(alipay)
        if not data.startswith("https://qr.alipay.com/"):
            ERRORS.append("Alipay QR decode check failed")
        else:
            CHECKS.append("alipay-qr:decoded")
else:
    CHECKS.append("alipay-qr:sha256-fallback")

result = {
    "status": "PASS" if not ERRORS else "FAIL",
    "checks": len(CHECKS),
    "errors": ERRORS,
}
print(json.dumps(result, ensure_ascii=False, indent=2))
if ERRORS:
    raise SystemExit(1)
