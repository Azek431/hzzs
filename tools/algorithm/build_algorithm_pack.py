#!/usr/bin/env python3
"""Build a deterministic unsigned .hzzsalg package from a source directory."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from common import (
    AlgorithmPackError,
    list_source_files,
    load_json,
    package_filename,
    sha256_file,
    validate_changelog,
    validate_manifest,
    validate_rules,
    validate_size,
    write_deterministic_zip,
)
from validate_algorithm_pack import validate_source


def build_package(source_dir: Path, output: Path | None = None) -> Path:
    validated = validate_source(source_dir)
    manifest = validated["manifest"]
    files = list_source_files(source_dir)
    payload: dict[str, bytes] = {}
    for path in files:
        data = path.read_bytes()
        validate_size(path.name, len(data))
        # Re-parse text files as UTF-8 and normalize newlines for reproducibility.
        if path.name.endswith((".json", ".txt")):
            text = data.decode("utf-8")
            if path.name == "manifest.json":
                validate_manifest(load_json(path))
                text = _canonical_text_json(path)
            elif path.name == "rules.json":
                validate_rules(load_json(path), manifest["supportedScenes"])
                text = _canonical_text_json(path)
            elif path.name == "CHANGELOG.txt":
                text = validate_changelog(text.replace("\r\n", "\n").replace("\r", "\n"))
                if not text.endswith("\n"):
                    text += "\n"
            payload[path.name] = text.encode("utf-8")
        else:
            payload[path.name] = data

    filename = package_filename(manifest["id"], manifest["version"])
    target = output if output is not None else Path.cwd() / filename
    if target.is_dir():
        target = target / filename
    write_deterministic_zip(target, payload)
    return target


def _canonical_text_json(path: Path) -> str:
    import json

    data = json.loads(path.read_text(encoding="utf-8"))
    text = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True)
    if not text.endswith("\n"):
        text += "\n"
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        help="Output .hzzsalg path or directory (default: ./<id>-v<version>.hzzsalg)",
    )
    arguments = parser.parse_args(argv)
    try:
        target = build_package(arguments.source, arguments.output)
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"OK package={target} sha256={sha256_file(target)} size={target.stat().st_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
