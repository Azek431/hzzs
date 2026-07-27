#!/usr/bin/env python3
"""Build the official algorithm catalog (stable.json / beta.json). Ed25519 official signing not yet enabled in 0.1.0."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import zipfile

from common import (
    CHANNELS,
    SCHEMA_VERSION,
    AlgorithmPackError,
    package_filename,
    write_json,
)
from verify_algorithm_pack import verify_package


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def algorithm_entry_from_package(
    package_path: Path,
    *,
    channel: str,
) -> dict[str, Any]:
    verified = verify_package(package_path)
    # Re-read manifest fields for catalog metadata.
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
        # 资产与目录同在 release-index；不再依赖 GitHub/Gitee Release tag。
        "assetPath": f"algorithms/packages/{filename}",
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
    algorithms: list[dict[str, Any]],
    generated_at: str | None = None,
) -> dict[str, Any]:
    if channel not in CHANNELS:
        raise AlgorithmPackError("invalid channel")
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
        "algorithms": ordered,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", required=True, choices=sorted(CHANNELS))
    parser.add_argument(
        "--package",
        action="append",
        default=[],
        type=Path,
        help="Unsigned .hzzsalg package (repeatable)",
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--generated-at",
        help="Override generatedAt (ISO-8601). Defaults to current UTC.",
    )
    arguments = parser.parse_args(argv)
    try:
        if not arguments.package:
            raise AlgorithmPackError("at least one --package is required")
        algorithms = [
            algorithm_entry_from_package(path, channel=arguments.channel)
            for path in arguments.package
        ]
        payload = build_catalog_payload(
            channel=arguments.channel,
            algorithms=algorithms,
            generated_at=arguments.generated_at,
        )
        write_json(arguments.output, payload)
        print(
            f"OK catalog={arguments.output} channel={arguments.channel} "
            f"algorithms={len(algorithms)}"
        )
        return 0
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
