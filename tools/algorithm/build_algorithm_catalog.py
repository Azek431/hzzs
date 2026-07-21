#!/usr/bin/env python3
"""Build and sign the official algorithm catalog (stable.json / beta.json)."""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from common import (
    CHANNELS,
    SAFE_KEY_ID,
    SCHEMA_VERSION,
    AlgorithmPackError,
    canonical_json,
    package_filename,
    release_tag,
    sha256_file,
    write_json,
)
from sign_algorithm_pack import load_private_key, public_key_b64
from verify_algorithm_pack import verify_package


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def algorithm_entry_from_package(
    package_path: Path,
    *,
    public_key: Path | None,
    public_key_b64_value: str | None,
    key_id: str,
    channel: str,
) -> dict[str, Any]:
    verified = verify_package(
        package_path,
        public_key_path=public_key,
        public_key_b64=public_key_b64_value,
        require_key_id=key_id,
        allow_embedded_key=public_key is None and not public_key_b64_value,
    )
    # Re-read manifest fields for catalog metadata.
    import zipfile

    with zipfile.ZipFile(package_path, "r") as archive:
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        changelog = archive.read("CHANGELOG.txt").decode("utf-8")
    filename = package_path.name
    expected_name = package_filename(manifest["id"], manifest["version"])
    if filename != expected_name:
        raise AlgorithmPackError(
            f"package filename must be {expected_name}, got {filename}"
        )
    if channel not in CHANNELS:
        raise AlgorithmPackError("invalid channel")
    return {
        "id": manifest["id"],
        "version": manifest["version"],
        "tag": release_tag(manifest["id"], manifest["version"]),
        "filename": filename,
        "size": verified["size"],
        "sha256": verified["sha256"],
        "engineId": manifest["engineId"],
        "engineApiVersion": manifest["engineApiVersion"],
        "minimumAppVersionCode": manifest["minimumAppVersionCode"],
        "supportedScenes": list(manifest["supportedScenes"]),
        "description": manifest["description"],
        "changelog": changelog.strip()[: 16 * 1024],
        "releaseDate": manifest["releaseDate"],
        "revoked": bool(manifest["revoked"]),
        "displayName": manifest["displayName"],
        "author": manifest["author"],
    }


def build_catalog_payload(
    *,
    channel: str,
    key_id: str,
    algorithms: list[dict[str, Any]],
    generated_at: str | None = None,
) -> dict[str, Any]:
    if channel not in CHANNELS:
        raise AlgorithmPackError("invalid channel")
    if not SAFE_KEY_ID.fullmatch(key_id):
        raise AlgorithmPackError("invalid key id")
    # Sort for deterministic catalogs.
    ordered = sorted(algorithms, key=lambda item: (item["id"], item["version"]))
    seen: set[tuple[str, str]] = set()
    for item in ordered:
        key = (item["id"], item["version"])
        if key in seen:
            raise AlgorithmPackError(f"duplicate algorithm entry: {key}")
        seen.add(key)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": generated_at or _utc_now_iso(),
        "channel": channel,
        "keyId": key_id,
        "algorithms": ordered,
    }


def sign_catalog(payload: dict[str, Any], private_key: Ed25519PrivateKey, key_id: str) -> dict[str, Any]:
    if payload.get("keyId") != key_id:
        raise AlgorithmPackError("catalog keyId mismatch")
    canonical = canonical_json(payload)
    signature = private_key.sign(canonical.encode("utf-8"))
    return {
        "schemaVersion": SCHEMA_VERSION,
        "keyId": key_id,
        "signatureAlgorithm": "Ed25519",
        "publicKeyDerB64": public_key_b64(private_key.public_key()),
        "signedPayload": canonical,
        "catalogSignature": base64.b64encode(signature).decode("ascii"),
        # Convenience mirror of payload fields for human readers / older clients.
        "channel": payload["channel"],
        "generatedAt": payload["generatedAt"],
        "algorithms": payload["algorithms"],
    }


def verify_catalog_document(
    document: dict[str, Any],
    *,
    public_key_path: Path | None = None,
    public_key_b64_value: str | None = None,
    require_key_id: str | None = None,
) -> dict[str, Any]:
    from verify_algorithm_pack import load_public_key
    from cryptography.exceptions import InvalidSignature

    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise AlgorithmPackError("unsupported catalog schemaVersion")
    key_id = document.get("keyId")
    if not isinstance(key_id, str) or not SAFE_KEY_ID.fullmatch(key_id):
        raise AlgorithmPackError("catalog keyId invalid")
    if require_key_id is not None and key_id != require_key_id:
        raise AlgorithmPackError("catalog keyId mismatch")
    if document.get("signatureAlgorithm") != "Ed25519":
        raise AlgorithmPackError("unsupported catalog signature algorithm")
    signed_payload = document.get("signedPayload")
    if not isinstance(signed_payload, str) or not signed_payload:
        raise AlgorithmPackError("catalog signedPayload missing")
    try:
        payload = json.loads(signed_payload)
    except json.JSONDecodeError as error:
        raise AlgorithmPackError("catalog signedPayload is not JSON") from error
    if payload.get("channel") not in CHANNELS:
        raise AlgorithmPackError("catalog channel invalid")
    if payload.get("keyId") != key_id:
        raise AlgorithmPackError("catalog payload keyId mismatch")
    if not isinstance(payload.get("algorithms"), list):
        raise AlgorithmPackError("catalog algorithms must be a list")

    public_key = load_public_key(
        public_key_path=public_key_path,
        public_key_b64=public_key_b64_value,
        embedded_b64=document.get("publicKeyDerB64")
        if public_key_path is None and not public_key_b64_value
        else None,
    )
    try:
        signature = base64.b64decode(document["catalogSignature"], validate=True)
    except Exception as error:  # noqa: BLE001
        raise AlgorithmPackError(f"invalid catalog signature encoding: {error}") from error
    try:
        public_key.verify(signature, signed_payload.encode("utf-8"))
    except InvalidSignature as error:
        raise AlgorithmPackError("catalog signature verification failed") from error
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", required=True, choices=sorted(CHANNELS))
    parser.add_argument(
        "--package",
        action="append",
        default=[],
        type=Path,
        help="Signed .hzzsalg package (repeatable)",
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--private-key", type=Path)
    parser.add_argument("--private-key-b64")
    parser.add_argument(
        "--key-id",
        default=os.environ.get("ALGORITHM_SIGNING_KEY_ID", "hzzs-algorithm-official-1"),
    )
    parser.add_argument("--public-key", type=Path, help="Trusted public key used while ingesting packages")
    parser.add_argument("--public-key-b64")
    parser.add_argument(
        "--generated-at",
        help="Override generatedAt (ISO-8601). Defaults to current UTC.",
    )
    parser.add_argument(
        "--verify-only",
        type=Path,
        help="Verify an existing catalog file and exit",
    )
    arguments = parser.parse_args(argv)
    try:
        if arguments.verify_only is not None:
            document = json.loads(arguments.verify_only.read_text(encoding="utf-8"))
            payload = verify_catalog_document(
                document,
                public_key_path=arguments.public_key,
                public_key_b64_value=arguments.public_key_b64,
                require_key_id=arguments.key_id,
            )
            print(
                f"OK catalog channel={payload['channel']} "
                f"algorithms={len(payload['algorithms'])} keyId={payload['keyId']}"
            )
            return 0
        if not arguments.package:
            raise AlgorithmPackError("at least one --package is required")
        algorithms = [
            algorithm_entry_from_package(
                path,
                public_key=arguments.public_key,
                public_key_b64_value=arguments.public_key_b64,
                key_id=arguments.key_id,
                channel=arguments.channel,
            )
            for path in arguments.package
        ]
        payload = build_catalog_payload(
            channel=arguments.channel,
            key_id=arguments.key_id,
            algorithms=algorithms,
            generated_at=arguments.generated_at,
        )
        private_key = load_private_key(
            private_key_path=arguments.private_key,
            private_key_b64=arguments.private_key_b64,
        )
        document = sign_catalog(payload, private_key, arguments.key_id)
        write_json(arguments.output, document)
        # Self-check
        verify_catalog_document(
            document,
            public_key_path=arguments.public_key,
            public_key_b64_value=arguments.public_key_b64,
            require_key_id=arguments.key_id,
        )
        print(
            f"OK catalog={arguments.output} channel={arguments.channel} "
            f"algorithms={len(algorithms)} keyId={arguments.key_id}"
        )
        return 0
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
