#!/usr/bin/env python3
"""Verify an unsigned .hzzsalg package for integrity (manifest/rules/changelog + file digests). Ed25519 official signing not yet enabled in 0.1.0."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from common import (
    AlgorithmPackError,
    digests_for_mapping,
    read_zip_entries,
    sha256_file,
    validate_changelog,
    validate_manifest,
    validate_rules,
)


def verify_package(package_path: Path) -> dict:
    entries = read_zip_entries(package_path)
    manifest = validate_manifest(json.loads(entries["manifest.json"].decode("utf-8")))
    validate_rules(json.loads(entries["rules.json"].decode("utf-8")), manifest["supportedScenes"])
    validate_changelog(entries["CHANGELOG.txt"].decode("utf-8"))
    digests_for_mapping(entries)
    return {
        "id": manifest["id"],
        "version": manifest["version"],
        "sha256": sha256_file(package_path),
        "size": package_path.stat().st_size,
        "revoked": manifest["revoked"],
        "supportedScenes": list(manifest["supportedScenes"]),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        result = verify_package(arguments.package)
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        "OK "
        f"id={result['id']} version={result['version']} "
        f"sha256={result['sha256']} size={result['size']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
