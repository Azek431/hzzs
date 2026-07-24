#!/usr/bin/env python3
"""Bump algorithm pack semver in source tree (+ optional assets mirror).

Default: PATCH +1 (x.y.z → x.y.(z+1)). Pre-release suffix is stripped on bump.
Does not publish; call publish_algorithm_release.py after validation.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import AlgorithmPackError, write_json  # noqa: E402

# 0.1.0 or 0.2.0-beta.1
_SEMVER = re.compile(
    r"^(?P<major>0|[1-9]\d*)\.(?P<minor>0|[1-9]\d*)\.(?P<patch>0|[1-9]\d*)"
    r"(?:-(?P<pre>[0-9A-Za-z.-]+))?$"
)


def parse_semver(version: str) -> tuple[int, int, int, str | None]:
    match = _SEMVER.fullmatch(version.strip())
    if not match:
        raise AlgorithmPackError(f"invalid semver: {version!r}")
    return (
        int(match.group("major")),
        int(match.group("minor")),
        int(match.group("patch")),
        match.group("pre"),
    )


def format_semver(major: int, minor: int, patch: int, pre: str | None = None) -> str:
    base = f"{major}.{minor}.{patch}"
    return f"{base}-{pre}" if pre else base


def bump_semver(version: str, level: str = "patch") -> str:
    major, minor, patch, _pre = parse_semver(version)
    # 升正式号时丢掉预发布后缀
    if level == "major":
        return format_semver(major + 1, 0, 0)
    if level == "minor":
        return format_semver(major, minor + 1, 0)
    if level == "patch":
        return format_semver(major, minor, patch + 1)
    raise AlgorithmPackError(f"unknown bump level: {level}")


def version_code_from_semver(version: str) -> int:
    """Match client bundled installer convention: major*1e6 + minor*1e3 + patch."""
    major, minor, patch, _ = parse_semver(version)
    return major * 1_000_000 + minor * 1_000 + patch


def load_manifest(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or "version" not in data or "id" not in data:
        raise AlgorithmPackError(f"invalid manifest: {path}")
    return data


def prepend_changelog(path: Path, version: str, note: str) -> None:
    header = f"## {version} ({date.today().isoformat()})\n"
    body = note.strip() or "- 参数/规则调整（自动 PATCH）。"
    if not body.startswith("-"):
        body = f"- {body}"
    block = f"{header}{body}\n\n"
    previous = path.read_text(encoding="utf-8") if path.is_file() else ""
    path.write_text(block + previous, encoding="utf-8", newline="\n")


def sync_assets_mirror(source: Path, pack_id: str) -> Path | None:
    assets = ROOT / "app" / "src" / "main" / "assets" / "algorithms" / pack_id
    if not assets.is_dir():
        return None
    for name in ("manifest.json", "rules.json", "CHANGELOG.txt"):
        src = source / name
        if src.is_file():
            (assets / name).write_bytes(src.read_bytes())
    return assets


def bump_pack(
    source: Path,
    *,
    level: str = "patch",
    note: str = "",
    sync_assets: bool = True,
) -> dict:
    source = source.resolve()
    manifest_path = source / "manifest.json"
    if not manifest_path.is_file():
        raise AlgorithmPackError(f"missing manifest: {manifest_path}")
    manifest = load_manifest(manifest_path)
    old = str(manifest["version"])
    new = bump_semver(old, level)
    manifest["version"] = new
    # 可选 versionCode 字段（部分清单可能有）
    if "versionCode" in manifest:
        manifest["versionCode"] = version_code_from_semver(new)
    write_json(manifest_path, manifest)
    changelog = source / "CHANGELOG.txt"
    prepend_changelog(
        changelog,
        new,
        note or f"自动 {level.upper()}：相对 {old} 的内容更新。",
    )
    assets = None
    if sync_assets:
        assets = sync_assets_mirror(source, str(manifest["id"]))
    return {
        "id": manifest["id"],
        "old_version": old,
        "new_version": new,
        "source": str(source),
        "assets": str(assets) if assets else None,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument(
        "--level",
        choices=("patch", "minor", "major"),
        default="patch",
    )
    parser.add_argument("--note", default="")
    parser.add_argument(
        "--no-assets",
        action="store_true",
        help="Do not mirror into app/src/main/assets/algorithms/<id>/",
    )
    args = parser.parse_args(argv)
    try:
        result = bump_pack(
            args.source,
            level=args.level,
            note=args.note,
            sync_assets=not args.no_assets,
        )
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        f"OK bumped {result['id']} {result['old_version']} → {result['new_version']}"
        + (f" assets={result['assets']}" if result["assets"] else "")
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
