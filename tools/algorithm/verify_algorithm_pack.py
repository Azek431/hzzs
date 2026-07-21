#!/usr/bin/env python3
"""Verify a signed .hzzsalg package against an embedded or provided public key."""

from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from common import (
    SIGNATURE_ALGORITHM,
    SIGNATURE_FILE,
    AlgorithmPackError,
    digests_for_mapping,
    read_zip_entries,
    sha256_file,
    unsigned_payload_from_entries,
    validate_changelog,
    validate_manifest,
    validate_rules,
)


def load_public_key(
    *,
    public_key_path: Path | None,
    public_key_b64: str | None,
    embedded_b64: str | None,
) -> Ed25519PublicKey:
    candidates: list[bytes] = []
    if public_key_path is not None:
        candidates.append(public_key_path.read_bytes())
    if public_key_b64:
        try:
            candidates.append(base64.b64decode(public_key_b64, validate=True))
        except Exception as error:  # noqa: BLE001
            raise AlgorithmPackError(f"invalid public key b64: {error}") from error
    if embedded_b64:
        try:
            candidates.append(base64.b64decode(embedded_b64, validate=True))
        except Exception as error:  # noqa: BLE001
            raise AlgorithmPackError(f"invalid embedded public key: {error}") from error
    if not candidates:
        raise AlgorithmPackError("no public key provided")

    last_error: Exception | None = None
    for raw in candidates:
        try:
            if b"BEGIN PUBLIC KEY" in raw:
                key = serialization.load_pem_public_key(raw)
            else:
                key = serialization.load_der_public_key(raw)
            if not isinstance(key, Ed25519PublicKey):
                raise AlgorithmPackError("public key must be Ed25519")
            return key
        except Exception as error:  # noqa: BLE001
            last_error = error
            continue
    raise AlgorithmPackError(f"unable to load public key: {last_error}")


def verify_package(
    package_path: Path,
    *,
    public_key_path: Path | None = None,
    public_key_b64: str | None = None,
    require_key_id: str | None = None,
    allow_embedded_key: bool = True,
) -> dict:
    entries = read_zip_entries(package_path, allow_signature=True)
    signature_doc = json.loads(entries[SIGNATURE_FILE].decode("utf-8"))
    if signature_doc.get("schemaVersion") != 1:
        raise AlgorithmPackError("unsupported signature schemaVersion")
    if signature_doc.get("signatureAlgorithm") != SIGNATURE_ALGORITHM:
        raise AlgorithmPackError("unsupported signature algorithm")
    key_id = signature_doc.get("keyId")
    if not isinstance(key_id, str) or not key_id:
        raise AlgorithmPackError("signature missing keyId")
    if require_key_id is not None and key_id != require_key_id:
        raise AlgorithmPackError(f"keyId mismatch: expected {require_key_id}, got {key_id}")

    unsigned = {name: data for name, data in entries.items() if name != SIGNATURE_FILE}
    expected_payload = unsigned_payload_from_entries(unsigned)
    try:
        signed_payload = json.loads(signature_doc["signedPayload"])
    except (KeyError, json.JSONDecodeError) as error:
        raise AlgorithmPackError("signature signedPayload invalid") from error

    for field in ("schemaVersion", "files"):
        if signed_payload.get(field) != expected_payload.get(field):
            raise AlgorithmPackError(f"signed payload field mismatch: {field}")

    manifest = validate_manifest(json.loads(unsigned["manifest.json"].decode("utf-8")))
    if signed_payload.get("algorithmId") != manifest["id"]:
        raise AlgorithmPackError("signed algorithmId does not match manifest")
    if signed_payload.get("version") != manifest["version"]:
        raise AlgorithmPackError("signed version does not match manifest")
    if signed_payload.get("keyId") != key_id:
        raise AlgorithmPackError("signed keyId does not match signature envelope")
    if signed_payload.get("signatureAlgorithm") != SIGNATURE_ALGORITHM:
        raise AlgorithmPackError("signed signatureAlgorithm mismatch")

    validate_rules(json.loads(unsigned["rules.json"].decode("utf-8")), manifest["supportedScenes"])
    validate_changelog(unsigned["CHANGELOG.txt"].decode("utf-8"))
    digests_for_mapping(unsigned)

    embedded = signature_doc.get("publicKeyDerB64") if allow_embedded_key else None
    if not isinstance(embedded, str):
        embedded = None
    public_key = load_public_key(
        public_key_path=public_key_path,
        public_key_b64=public_key_b64,
        embedded_b64=embedded if public_key_path is None and not public_key_b64 else None,
    )
    # When a trusted key is provided, also ensure it matches the embedded key if present.
    if (public_key_path is not None or public_key_b64) and embedded:
        trusted = load_public_key(
            public_key_path=public_key_path,
            public_key_b64=public_key_b64,
            embedded_b64=None,
        )
        embedded_key = load_public_key(
            public_key_path=None,
            public_key_b64=None,
            embedded_b64=embedded,
        )
        trusted_raw = trusted.public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )
        embedded_raw = embedded_key.public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )
        if trusted_raw != embedded_raw:
            raise AlgorithmPackError("embedded public key does not match trusted key")
        public_key = trusted

    try:
        signature = base64.b64decode(signature_doc["signature"], validate=True)
    except Exception as error:  # noqa: BLE001
        raise AlgorithmPackError(f"invalid signature encoding: {error}") from error
    try:
        public_key.verify(signature, signature_doc["signedPayload"].encode("utf-8"))
    except InvalidSignature as error:
        raise AlgorithmPackError("signature verification failed") from error

    return {
        "id": manifest["id"],
        "version": manifest["version"],
        "keyId": key_id,
        "sha256": sha256_file(package_path),
        "size": package_path.stat().st_size,
        "revoked": manifest["revoked"],
        "supportedScenes": list(manifest["supportedScenes"]),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--public-key", type=Path, help="Trusted Ed25519 public key PEM/DER")
    parser.add_argument("--public-key-b64", help="Trusted SubjectPublicKeyInfo DER (base64)")
    parser.add_argument("--key-id", help="Require this key id")
    parser.add_argument(
        "--no-embedded-key",
        action="store_true",
        help="Do not trust publicKeyDerB64 inside signature.json without --public-key",
    )
    arguments = parser.parse_args(argv)
    try:
        if arguments.no_embedded_key and arguments.public_key is None and not arguments.public_key_b64:
            raise AlgorithmPackError("trusted public key required when --no-embedded-key is set")
        result = verify_package(
            arguments.package,
            public_key_path=arguments.public_key,
            public_key_b64=arguments.public_key_b64,
            require_key_id=arguments.key_id,
            allow_embedded_key=not arguments.no_embedded_key,
        )
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        "OK "
        f"id={result['id']} version={result['version']} keyId={result['keyId']} "
        f"sha256={result['sha256']} size={result['size']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
