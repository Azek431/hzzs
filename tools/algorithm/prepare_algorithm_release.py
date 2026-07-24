#!/usr/bin/env python3
"""Prepare packs for publish: auto PATCH bump when remote (id,version) content differs.

Flow per source pack:
1. validate + build/sign locally
2. load remote channel catalog (best-effort)
3. if remote has same (id, version) with same sha256 → skip (already published)
4. if remote has same (id, version) with different sha256 → bump PATCH, rebuild
5. if --execute: publish; if bumped, leave dirty tree for CI to commit

Does not create GitHub Release tags.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from bump_algorithm_version import bump_pack  # noqa: E402
from common import AlgorithmPackError  # noqa: E402
from publish_algorithm_release import (  # noqa: E402
    load_remote_catalog_algorithms,
    publish,
)
from validate_algorithm_pack import validate_source  # noqa: E402

# silence unused import if tempfile needed later
_ = tempfile


def _log(message: str) -> None:
    print(message, flush=True)


def _channel_of(source: Path, force: str | None) -> str:
    if force and force != "auto":
        return "beta" if force == "beta" else "stable"
    manifest = validate_source(source)["manifest"]
    ch = str(manifest.get("channel") or "stable").strip().lower()
    return "beta" if ch == "beta" else "stable"


def _find_entry(
    algorithms: list[dict[str, Any]],
    pack_id: str,
    version: str,
) -> dict[str, Any] | None:
    for item in algorithms:
        if item.get("id") == pack_id and item.get("version") == version:
            return item
    return None


def _local_signed_sha(
    source: Path,
    work_dir: Path,
    *,
    channel: str,
    key_id: str,
    private_key: Path | None,
    private_key_b64: str | None,
) -> tuple[str, str, str]:
    """Return (id, version, sha256) after dry-run build/sign into work_dir."""
    ns = argparse.Namespace(
        source=source,
        work_dir=work_dir,
        channel=channel,
        owner="Azek431",
        repo="hzzs",
        private_key=private_key,
        private_key_b64=private_key_b64,
        key_id=key_id,
        generated_at=None,
        mirrors="github",
        execute=False,
    )
    code = publish(ns)
    if code != 0:
        raise AlgorithmPackError(f"local sign failed for {source}")
    manifest = validate_source(source)["manifest"]
    pack_id = str(manifest["id"])
    version = str(manifest["version"])
    # publish writes signed file as package_filename
    from common import package_filename

    signed = work_dir / package_filename(pack_id, version)
    if not signed.is_file():
        # fallback: any hzzsalg in work_dir
        candidates = list(work_dir.glob("*.hzzsalg"))
        if not candidates:
            raise AlgorithmPackError(f"no signed package in {work_dir}")
        signed = candidates[0]
    from common import sha256_file

    return pack_id, version, sha256_file(signed)


def prepare_one(
    source: Path,
    *,
    channel_force: str,
    key_id: str,
    private_key: Path | None,
    private_key_b64: str | None,
    execute: bool,
    mirrors: str,
    auto_bump: bool,
    work_root: Path,
) -> dict[str, Any]:
    source = source.resolve()
    channel = _channel_of(source, channel_force)
    validate_source(source)
    work = work_root / source.name
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)

    pack_id, version, sha = _local_signed_sha(
        source,
        work / "probe",
        channel=channel,
        key_id=key_id,
        private_key=private_key,
        private_key_b64=private_key_b64,
    )
    remote = load_remote_catalog_algorithms(
        owner="Azek431",
        repo="hzzs",
        channel=channel,
        prefer_github=True,
    )
    existing = _find_entry(remote, pack_id, version)
    bumped = False
    skipped = False

    if existing and existing.get("sha256") == sha:
        _log(f"skip {pack_id}@{version}: already on remote with same sha256")
        skipped = True
        return {
            "id": pack_id,
            "version": version,
            "channel": channel,
            "skipped": True,
            "bumped": False,
            "published": False,
        }

    if existing and existing.get("sha256") != sha:
        if not auto_bump:
            raise AlgorithmPackError(
                f"{pack_id}@{version} exists remotely with different sha256; "
                f"bump version or pass --auto-bump"
            )
        _log(
            f"conflict {pack_id}@{version}: remote sha differs → auto PATCH bump"
        )
        info = bump_pack(
            source,
            level="patch",
            note="内容变更自动 PATCH（远端同 version 哈希不同）。",
            sync_assets=True,
        )
        bumped = True
        version = info["new_version"]
        # rebuild after bump
        shutil.rmtree(work / "probe", ignore_errors=True)
        pack_id, version, sha = _local_signed_sha(
            source,
            work / "probe2",
            channel=channel,
            key_id=key_id,
            private_key=private_key,
            private_key_b64=private_key_b64,
        )
        # if still colliding (unlikely), bump again once
        remote2 = load_remote_catalog_algorithms(
            owner="Azek431", repo="hzzs", channel=channel
        )
        if _find_entry(remote2, pack_id, version):
            info = bump_pack(
                source,
                level="patch",
                note="二次 PATCH：避免与远端 version 冲突。",
                sync_assets=True,
            )
            bumped = True
            version = info["new_version"]

    pub_work = work / "publish"
    ns = argparse.Namespace(
        source=source,
        work_dir=pub_work,
        channel=channel,
        owner="Azek431",
        repo="hzzs",
        private_key=private_key,
        private_key_b64=private_key_b64,
        key_id=key_id,
        generated_at=None,
        mirrors=mirrors,
        execute=execute,
    )
    code = publish(ns)
    if code != 0:
        raise AlgorithmPackError(f"publish failed for {source} code={code}")
    _log(
        f"{'published' if execute else 'prepared'} {pack_id}@{version} "
        f"channel={channel} bumped={bumped} sha256={sha[:12]}…"
    )
    return {
        "id": pack_id,
        "version": version,
        "channel": channel,
        "skipped": skipped,
        "bumped": bumped,
        "published": execute,
        "sha256": sha,
    }


def iter_default_sources() -> list[Path]:
    root = ROOT / "algorithm-packs"
    out: list[Path] = []
    for path in sorted(root.iterdir()):
        if not path.is_dir():
            continue
        if path.name == "official-public-keys":
            continue
        if (path / "manifest.json").is_file() and (path / "rules.json").is_file():
            out.append(path)
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        action="append",
        type=Path,
        help="Pack source (repeatable). Default: all algorithm-packs/* with manifest+rules",
    )
    parser.add_argument("--channel", default="auto", choices=("auto", "stable", "beta"))
    parser.add_argument("--key-id", default=os.environ.get("ALGORITHM_SIGNING_KEY_ID"))
    parser.add_argument("--private-key", type=Path)
    parser.add_argument("--private-key-b64")
    parser.add_argument("--mirrors", default=os.environ.get("ALGORITHM_PUBLISH_MIRRORS", "github"))
    parser.add_argument("--execute", action="store_true")
    parser.add_argument(
        "--auto-bump",
        action="store_true",
        default=True,
        help="PATCH bump when remote same version has different sha (default on)",
    )
    parser.add_argument("--no-auto-bump", action="store_true")
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=ROOT / "build" / "algorithm-prepare",
    )
    parser.add_argument(
        "--results-json",
        type=Path,
        help="Write machine-readable results for CI",
    )
    args = parser.parse_args(argv)
    auto_bump = args.auto_bump and not args.no_auto_bump
    sources = args.source or iter_default_sources()
    if not sources:
        print("ERROR: no packs found", file=sys.stderr)
        return 1

    key_id = args.key_id or "hzzs-algorithm-official-1"
    results: list[dict[str, Any]] = []
    args.work_dir.mkdir(parents=True, exist_ok=True)

    try:
        for source in sources:
            _log(f"==== prepare {source} ====")
            results.append(
                prepare_one(
                    source,
                    channel_force=args.channel,
                    key_id=key_id,
                    private_key=args.private_key,
                    private_key_b64=args.private_key_b64,
                    execute=args.execute,
                    mirrors=args.mirrors,
                    auto_bump=auto_bump,
                    work_root=args.work_dir,
                )
            )
    except AlgorithmPackError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if args.results_json:
        args.results_json.parent.mkdir(parents=True, exist_ok=True)
        args.results_json.write_text(
            json.dumps(results, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    bumped = [r for r in results if r.get("bumped")]
    published = [r for r in results if r.get("published")]
    skipped = [r for r in results if r.get("skipped")]
    _log(
        f"DONE packs={len(results)} published={len(published)} "
        f"bumped={len(bumped)} skipped={len(skipped)}"
    )
    if bumped:
        _log("BUMPED=1")
        for item in bumped:
            _log(f"  bumped {item['id']} → {item['version']}")
    else:
        _log("BUMPED=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
