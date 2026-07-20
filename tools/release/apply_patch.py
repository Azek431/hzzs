#!/usr/bin/env python3
"""Apply and verify the bounded HZZS block-delta format."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
import zipfile
from pathlib import Path

MAX_ARTIFACT = 1024 * 1024 * 1024
MAX_MANIFEST = 4 * 1024 * 1024
MAX_OPERATIONS = 100_000


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_exact(source, output, length: int) -> None:
    remaining = length
    while remaining:
        chunk = source.read(min(128 * 1024, remaining))
        if not chunk:
            raise ValueError("patch source ended early")
        output.write(chunk)
        remaining -= len(chunk)


def apply(old_path: str, patch_path: str, output_path: str) -> None:
    old = Path(old_path)
    patch = Path(patch_path)
    output = Path(output_path)
    if not old.is_file() or not patch.is_file():
        raise ValueError("old APK or patch does not exist")
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="hzzs-patch-", dir=output.parent) as temporary:
        temporary_dir = Path(temporary)
        data_path = temporary_dir / "data.bin"
        result_path = temporary_dir / "result.apk"
        with zipfile.ZipFile(patch) as archive:
            names = set(archive.namelist())
            if not {"patch.json", "data.bin"}.issubset(names):
                raise ValueError("patch entries are incomplete")
            manifest_info = archive.getinfo("patch.json")
            data_info = archive.getinfo("data.bin")
            if not 0 < manifest_info.file_size <= MAX_MANIFEST:
                raise ValueError("patch manifest size is invalid")
            if not 0 <= data_info.file_size <= MAX_ARTIFACT:
                raise ValueError("patch data size is invalid")
            manifest = json.loads(archive.read("patch.json"))
            with archive.open("data.bin") as source, data_path.open("wb") as target:
                shutil.copyfileobj(source, target, length=128 * 1024)

        if manifest.get("formatVersion") != 1:
            raise ValueError("unsupported patch format")
        if sha256(old).lower() != str(manifest.get("oldSha256", "")).lower():
            raise ValueError("old APK hash mismatch")
        expected_size = int(manifest.get("newSize", 0))
        if not 0 < expected_size <= MAX_ARTIFACT:
            raise ValueError("new APK size is invalid")
        operations = manifest.get("operations")
        if not isinstance(operations, list) or not 0 < len(operations) <= MAX_OPERATIONS:
            raise ValueError("patch operation count is invalid")

        written = 0
        with old.open("rb") as old_stream, data_path.open("rb") as data_stream, result_path.open("wb") as target:
            for operation in operations:
                kind = operation.get("type")
                offset = int(operation.get("offset", -1))
                length = int(operation.get("length", -1))
                if offset < 0 or length <= 0 or written + length > expected_size:
                    raise ValueError("patch operation range is invalid")
                if kind == "copy":
                    if offset + length > old.stat().st_size:
                        raise ValueError("copy operation exceeds old APK")
                    old_stream.seek(offset)
                    copy_exact(old_stream, target, length)
                elif kind == "data":
                    if offset + length > data_path.stat().st_size:
                        raise ValueError("data operation exceeds data.bin")
                    data_stream.seek(offset)
                    copy_exact(data_stream, target, length)
                else:
                    raise ValueError(f"unknown patch operation: {kind}")
                written += length

        if written != expected_size or result_path.stat().st_size != expected_size:
            raise ValueError("reconstructed APK size mismatch")
        if sha256(result_path).lower() != str(manifest.get("newSha256", "")).lower():
            raise ValueError("reconstructed APK hash mismatch")
        result_path.replace(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("old")
    parser.add_argument("patch")
    parser.add_argument("output")
    arguments = parser.parse_args()
    apply(arguments.old, arguments.patch, arguments.output)
