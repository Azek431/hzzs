#!/usr/bin/env python3
"""CI helper: validate ALGORITHM_SIGNING_PRIVATE_KEY_B64 shape without printing secrets.

Exit 0 only when the env value is base64 of a PEM private key.
Prints only lengths / booleans for Actions logs.
"""

from __future__ import annotations

import base64
import os
import sys


def main() -> int:
    raw = os.environ.get("ALGORITHM_SIGNING_PRIVATE_KEY_B64", "")
    compact = "".join(raw.split())
    print(f"signing_secret_chars={len(raw)} compact_chars={len(compact)}")
    if not compact:
        print("::error::ALGORITHM_SIGNING_PRIVATE_KEY_B64 secret is required (empty)")
        return 1
    try:
        decoded = base64.b64decode(compact, validate=True)
    except Exception as error:  # noqa: BLE001 — surface type only
        print(
            f"::error::ALGORITHM_SIGNING_PRIVATE_KEY_B64 is not valid base64 "
            f"({type(error).__name__})"
        )
        print(
            "hint: Secret must be base64 of the whole PEM private key file, "
            "not raw PEM text and not the public der.b64"
        )
        return 1

    def _is_private_pem(blob: bytes) -> bool:
        text = blob.decode("utf-8", errors="replace")
        return (
            "BEGIN PRIVATE KEY" in text
            or "BEGIN ED25519 PRIVATE KEY" in text
            or "BEGIN OPENSSH PRIVATE KEY" in text
        )

    # 正确：Secret = base64(PEM 文件字节) → 解码即 PEM
    if _is_private_pem(decoded):
        print(f"decoded_pem_bytes={len(decoded)} has_private_pem_header=True")
        print("secret_shape_ok=true")
        return 0

    # 常见误配：对「已经是 base64 的 .txt」再 ToBase64String 一次（双重编码）
    # 第一次解码得到仍是 base64 文本（约 160 字符），没有 BEGIN PRIVATE KEY。
    inner_text = decoded.decode("utf-8", errors="replace")
    inner_compact = "".join(inner_text.split())
    double_encoded = False
    if inner_compact and all(
        ch.isalnum() or ch in "+/=" for ch in inner_compact
    ):
        try:
            pem2 = base64.b64decode(inner_compact, validate=True)
            if _is_private_pem(pem2):
                double_encoded = True
                print(
                    f"decoded_pem_bytes={len(decoded)} has_private_pem_header=False"
                )
                print(
                    "::error::ALGORITHM_SIGNING_PRIVATE_KEY_B64 looks double-base64-encoded"
                )
                print(
                    "hint: paste the contents of ALGORITHM_SIGNING_PRIVATE_KEY_B64.txt "
                    "as-is; do NOT base64 that file again. "
                    "Correct: base64(private.pem bytes) once only."
                )
                print(f"inner_b64_chars={len(inner_compact)} after_unwrap_pem_bytes={len(pem2)}")
        except Exception:  # noqa: BLE001
            pass

    if not double_encoded:
        print(f"decoded_pem_bytes={len(decoded)} has_private_pem_header=False")
        print(
            "::error::decoded secret is not a PEM private key (missing BEGIN PRIVATE KEY)"
        )
        print("hint: [Convert]::ToBase64String([IO.File]::ReadAllBytes('private.pem'))")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
