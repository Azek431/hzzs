#!/usr/bin/env python3
"""Sign an algorithm pack with an independent Ed25519 key (not the APK keystore)."""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

from common import (
    SIGNATURE_ALGORITHM,
    SIGNATURE_FILE,
    AlgorithmPackError,
    canonical_json,
    digests_for_mapping,
    read_zip_entries,
    sha256_file,
    unsigned_payload_from_entries,
    write_deterministic_zip,
    write_json,
)


def load_private_key_from_pem(pem: bytes) -> Ed25519PrivateKey:
    key = serialization.load_pem_private_key(pem, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise AlgorithmPackError("algorithm signing key must be Ed25519 private key PEM")
    return key


def _looks_like_private_pem(blob: bytes) -> bool:
    text = blob.decode("utf-8", errors="replace")
    return (
        "BEGIN PRIVATE KEY" in text
        or "BEGIN ED25519 PRIVATE KEY" in text
        or "BEGIN OPENSSH PRIVATE KEY" in text
    )


def decode_private_key_pem_from_secret(raw: str) -> tuple[bytes, dict[str, object]]:
    """Decode ALGORITHM_SIGNING_PRIVATE_KEY_B64 (or equivalent) to PEM bytes.

    Accepts, in order:
    1. Raw PEM text (paste error)
    2. base64(PEM bytes) — correct GitHub Secret form
    3. base64(base64(PEM)) — common double-encode when ToBase64String is applied
       to an already-base64 txt file; **auto-healed** once

    Returns (pem_bytes, diagnostics). Diagnostics never include key material.
    """
    compact = "".join((raw or "").split())
    diag: dict[str, object] = {
        "input_chars": len(raw or ""),
        "compact_chars": len(compact),
        "form": "unknown",
        "autoheal_double_base64": False,
    }
    if not compact:
        raise AlgorithmPackError(
            "missing private key; set ALGORITHM_SIGNING_PRIVATE_KEY_B64 or pass --private-key"
        )

    # 1) Raw PEM pasted into the secret
    if compact.startswith("-----BEGIN") or "BEGIN PRIVATE KEY" in compact:
        pem = compact.encode("utf-8")
        if not _looks_like_private_pem(pem):
            raise AlgorithmPackError("secret looks like PEM but is not a private key")
        diag["form"] = "raw_pem"
        diag["decoded_pem_bytes"] = len(pem)
        return pem, diag

    # 2) Standard: base64(PEM)
    try:
        decoded = base64.b64decode(compact, validate=True)
    except Exception as error:  # noqa: BLE001
        raise AlgorithmPackError(f"invalid ALGORITHM_SIGNING_PRIVATE_KEY_B64: {error}") from error

    if _looks_like_private_pem(decoded):
        diag["form"] = "base64_pem"
        diag["decoded_pem_bytes"] = len(decoded)
        return decoded, diag

    # 3) Double base64: first decode yields ASCII base64 of the PEM
    inner_text = decoded.decode("utf-8", errors="replace")
    inner_compact = "".join(inner_text.split())
    if inner_compact and all(ch.isalnum() or ch in "+/=" for ch in inner_compact):
        try:
            pem2 = base64.b64decode(inner_compact, validate=True)
        except Exception:
            pem2 = b""
        if _looks_like_private_pem(pem2):
            diag["form"] = "double_base64_pem"
            diag["autoheal_double_base64"] = True
            diag["decoded_pem_bytes"] = len(pem2)
            diag["inner_b64_chars"] = len(inner_compact)
            return pem2, diag

    raise AlgorithmPackError(
        "decoded secret is not a PEM private key (missing BEGIN PRIVATE KEY); "
        "if you base64'd ALGORITHM_SIGNING_PRIVATE_KEY_B64.txt again, paste that "
        "file's text as-is instead"
    )


def load_private_key(
    *,
    private_key_path: Path | None,
    private_key_b64: str | None,
) -> Ed25519PrivateKey:
    if private_key_path is not None and private_key_b64:
        raise AlgorithmPackError("provide only one of --private-key / --private-key-b64 / env")
    if private_key_path is not None:
        return load_private_key_from_pem(private_key_path.read_bytes())
    raw_b64 = private_key_b64 or os.environ.get("ALGORITHM_SIGNING_PRIVATE_KEY_B64")
    if not raw_b64:
        raise AlgorithmPackError(
            "missing private key; set ALGORITHM_SIGNING_PRIVATE_KEY_B64 or pass --private-key"
        )
    pem, _diag = decode_private_key_pem_from_secret(raw_b64)
    return load_private_key_from_pem(pem)


def public_key_b64(public_key: Ed25519PublicKey) -> str:
    der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode("ascii")


def public_key_pem(public_key: Ed25519PublicKey) -> str:
    return public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")


def build_signature_document(
    *,
    private_key: Ed25519PrivateKey,
    key_id: str,
    unsigned_entries: dict[str, bytes],
) -> dict:
    from common import SAFE_KEY_ID

    if not SAFE_KEY_ID.fullmatch(key_id):
        raise AlgorithmPackError("invalid key id")
    payload = unsigned_payload_from_entries(unsigned_entries)
    # Include file digests and manifest identity in the signed payload.
    manifest = json.loads(unsigned_entries["manifest.json"].decode("utf-8"))
    signed_body = {
        "schemaVersion": payload["schemaVersion"],
        "algorithmId": manifest["id"],
        "version": manifest["version"],
        "files": payload["files"],
        "keyId": key_id,
        "signatureAlgorithm": SIGNATURE_ALGORITHM,
    }
    canonical = canonical_json(signed_body)
    signature = private_key.sign(canonical.encode("utf-8"))
    return {
        "schemaVersion": 1,
        "keyId": key_id,
        "signatureAlgorithm": SIGNATURE_ALGORITHM,
        "publicKeyDerB64": public_key_b64(private_key.public_key()),
        "signedPayload": canonical,
        "signature": base64.b64encode(signature).decode("ascii"),
    }


def sign_package(
    input_path: Path,
    output_path: Path,
    *,
    private_key: Ed25519PrivateKey,
    key_id: str,
) -> Path:
    entries = read_zip_entries(input_path, allow_signature=False)
    signature_doc = build_signature_document(
        private_key=private_key,
        key_id=key_id,
        unsigned_entries=entries,
    )
    signed_entries = dict(entries)
    signed_entries[SIGNATURE_FILE] = (
        json.dumps(signature_doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    # Ensure digests still within limits after adding signature.json
    digests_for_mapping(signed_entries)
    write_deterministic_zip(output_path, signed_entries)
    return output_path


def generate_keypair(private_out: Path, public_out: Path) -> None:
    private_key = Ed25519PrivateKey.generate()
    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    private_out.parent.mkdir(parents=True, exist_ok=True)
    private_out.write_bytes(private_pem)
    public_out.parent.mkdir(parents=True, exist_ok=True)
    public_out.write_text(public_key_pem(private_key.public_key()), encoding="utf-8")
    der_b64 = public_key_b64(private_key.public_key())
    companion = public_out.with_suffix(public_out.suffix + ".der.b64")
    if public_out.suffix == ".pem":
        companion = public_out.with_name(public_out.stem + ".der.b64")
    companion.write_text(der_b64 + "\n", encoding="utf-8")
    print(f"OK private={private_out} public_pem={public_out} public_der_b64={companion}")
    print("Store private key only in CI secrets as ALGORITHM_SIGNING_PRIVATE_KEY_B64.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    sign = sub.add_parser("sign", help="Sign an unsigned .hzzsalg package")
    sign.add_argument("--input", required=True, type=Path)
    sign.add_argument("--output", required=True, type=Path)
    sign.add_argument("--private-key", type=Path)
    sign.add_argument("--private-key-b64")
    sign.add_argument(
        "--key-id",
        default=os.environ.get("ALGORITHM_SIGNING_KEY_ID", "hzzs-algorithm-official-1"),
    )
    sign.add_argument(
        "--public-key-output",
        type=Path,
        help="Optional path to write public key PEM for app embedding",
    )

    gen = sub.add_parser("generate-key", help="Generate a new Ed25519 keypair for algorithm packs")
    gen.add_argument("--private-out", required=True, type=Path)
    gen.add_argument("--public-out", required=True, type=Path)

    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "generate-key":
            generate_keypair(arguments.private_out, arguments.public_out)
            return 0
        private_key = load_private_key(
            private_key_path=arguments.private_key,
            private_key_b64=arguments.private_key_b64,
        )
        target = sign_package(
            arguments.input,
            arguments.output,
            private_key=private_key,
            key_id=arguments.key_id,
        )
        if arguments.public_key_output is not None:
            arguments.public_key_output.parent.mkdir(parents=True, exist_ok=True)
            arguments.public_key_output.write_text(
                public_key_pem(private_key.public_key()),
                encoding="utf-8",
            )
            der_path = arguments.public_key_output.with_name(
                arguments.public_key_output.stem + ".der.b64"
            )
            der_path.write_text(public_key_b64(private_key.public_key()) + "\n", encoding="utf-8")
        print(
            f"OK signed={target} sha256={sha256_file(target)} size={target.stat().st_size} "
            f"keyId={arguments.key_id}"
        )
        return 0
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
