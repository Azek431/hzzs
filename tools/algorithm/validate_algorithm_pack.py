#!/usr/bin/env python3
"""Validate an algorithm pack source directory before packaging."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from common import (
    AlgorithmPackError,
    load_json,
    list_source_files,
    validate_changelog,
    validate_manifest,
    validate_rules,
    validate_size,
)


def validate_source(source_dir: Path) -> dict:
    files = list_source_files(source_dir)
    by_name = {path.name: path for path in files}
    for path in files:
        validate_size(path.name, path.stat().st_size)
    manifest = validate_manifest(load_json(by_name["manifest.json"]))
    rules = validate_rules(load_json(by_name["rules.json"]), manifest["supportedScenes"])
    changelog = validate_changelog(by_name["CHANGELOG.txt"].read_text(encoding="utf-8"))
    return {
        "manifest": manifest,
        "rules": rules,
        "changelog_chars": len(changelog),
        "files": sorted(by_name),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        required=True,
        type=Path,
        help="Directory containing manifest.json, rules.json and CHANGELOG.txt",
    )
    parser.add_argument("--quiet", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        result = validate_source(arguments.source)
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if not arguments.quiet:
        manifest = result["manifest"]
        print(
            "OK "
            f"id={manifest['id']} version={manifest['version']} "
            f"scenes={','.join(manifest['supportedScenes'])} "
            f"files={','.join(result['files'])}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
