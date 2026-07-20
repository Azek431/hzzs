#!/usr/bin/env python3
"""Build the canonical unsigned HZZS update payload."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9._+-]{1,160}$")
SAFE_TAG = re.compile(r"^[A-Za-z0-9._+-]{1,96}$")
MAX_ARTIFACT = 1024 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact(path_value: str) -> dict:
    path = Path(path_value)
    if not path.is_file():
        raise ValueError(f"artifact missing: {path}")
    if not SAFE_NAME.fullmatch(path.name):
        raise ValueError(f"unsafe artifact name: {path.name}")
    size = path.stat().st_size
    if not 0 < size <= MAX_ARTIFACT:
        raise ValueError(f"artifact size invalid: {path}")
    return {"name": path.name, "sha256": sha256(path), "size": size}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--channel", choices=["stable", "beta"], required=True)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--patch-list")
    parser.add_argument("--output", required=True)
    arguments = parser.parse_args()

    if not SAFE_TAG.fullmatch(arguments.tag):
        raise ValueError("tag contains unsafe characters")
    if arguments.version_code <= 0:
        raise ValueError("version code must be positive")
    if not arguments.version_name.strip() or len(arguments.version_name) > 64:
        raise ValueError("version name is invalid")
    certificate = arguments.certificate_sha256.replace(":", "").lower()
    if not SHA256.fullmatch(certificate):
        raise ValueError("certificate SHA-256 is invalid")

    patches = []
    if arguments.patch_list and Path(arguments.patch_list).exists():
        for row in Path(arguments.patch_list).read_text(encoding="utf-8").splitlines():
            if not row.strip():
                continue
            version, old_sha, path = row.split("\t", 2)
            if int(version) <= 0 or not SHA256.fullmatch(old_sha):
                raise ValueError(f"invalid patch source row: {row}")
            patches.append(
                {
                    "fromVersionCode": int(version),
                    "fromApkSha256": old_sha.lower(),
                    "patch": artifact(path),
                }
            )
    if len(patches) > 32:
        raise ValueError("too many patch artifacts")

    payload = {
        "schemaVersion": 1,
        "tag": arguments.tag,
        "versionName": arguments.version_name,
        "versionCode": arguments.version_code,
        "channel": arguments.channel,
        "packageName": arguments.package_name,
        "certificateSha256": certificate,
        "fullApk": artifact(arguments.apk),
        "patches": patches,
        "releaseNotes": arguments.notes[: 64 * 1024],
    }
    Path(arguments.output).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
