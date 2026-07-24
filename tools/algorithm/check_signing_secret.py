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
        pem = base64.b64decode(compact, validate=True)
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
    text = pem.decode("utf-8", errors="replace")
    has_begin = (
        "BEGIN PRIVATE KEY" in text
        or "BEGIN ED25519 PRIVATE KEY" in text
        or "BEGIN OPENSSH PRIVATE KEY" in text
    )
    print(f"decoded_pem_bytes={len(pem)} has_private_pem_header={has_begin}")
    if not has_begin:
        print("::error::decoded secret is not a PEM private key (missing BEGIN PRIVATE KEY)")
        print("hint: [Convert]::ToBase64String([IO.File]::ReadAllBytes('private.pem'))")
        return 1
    # Compact form for same-step consumers via stdout marker (caller may re-export).
    # Do not print the value — only confirm shape.
    print("secret_shape_ok=true")
    # Write compact form to GITHUB_OUTPUT if present (value still masked by Actions if secret).
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            handle.write(f"compact_b64={compact}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
