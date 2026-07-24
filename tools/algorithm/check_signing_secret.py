#!/usr/bin/env python3
"""CI helper: validate ALGORITHM_SIGNING_PRIVATE_KEY_B64 without printing secrets.

Self-heal diagnostics:
- Accepts correct base64(PEM)
- Detects and allows one-level double-base64 (common paste mistake), matching
  sign_algorithm_pack.decode_private_key_pem_from_secret — publish will load OK
- Accepts raw PEM text (also loadable)

Prints only lengths / booleans / form labels for Actions logs.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

# Allow `python tools/algorithm/check_signing_secret.py` from repo root.
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from common import AlgorithmPackError  # noqa: E402
from sign_algorithm_pack import (  # noqa: E402
    decode_private_key_pem_from_secret,
    load_private_key_from_pem,
)


def main() -> int:
    raw = os.environ.get("ALGORITHM_SIGNING_PRIVATE_KEY_B64", "")
    print(f"signing_secret_chars={len(raw)} compact_chars={len(''.join(raw.split()))}")
    if not "".join(raw.split()):
        print("::error::ALGORITHM_SIGNING_PRIVATE_KEY_B64 secret is required (empty)")
        return 1

    try:
        pem, diag = decode_private_key_pem_from_secret(raw)
        key = load_private_key_from_pem(pem)
    except AlgorithmPackError as error:
        print(f"::error::{error}")
        print(
            "hint: paste contents of build/release-secrets/ALGORITHM_SIGNING_PRIVATE_KEY_B64.txt "
            "as-is (≈160 chars). Do NOT ToBase64String that txt again. "
            "KEY_ID should be hzzs-algorithm-official-1."
        )
        return 1
    except Exception as error:  # noqa: BLE001
        print(f"::error::private key load failed: {type(error).__name__}")
        return 1

    form = diag.get("form", "unknown")
    print(
        f"decoded_pem_bytes={diag.get('decoded_pem_bytes')} "
        f"form={form} "
        f"has_private_pem_header=True "
        f"autoheal_double_base64={bool(diag.get('autoheal_double_base64'))}"
    )
    if diag.get("autoheal_double_base64"):
        print(
            "::warning::Secret is double-base64-encoded; tooling auto-heals once. "
            "Prefer pasting the single-encoded txt contents to avoid confusion."
        )
        if diag.get("inner_b64_chars") is not None:
            print(f"inner_b64_chars={diag.get('inner_b64_chars')}")
    # Confirm Ed25519 without printing key material
    print(f"ed25519_private=True key_type={type(key).__name__}")
    print("secret_shape_ok=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
