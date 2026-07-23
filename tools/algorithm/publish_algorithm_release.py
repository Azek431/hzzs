#!/usr/bin/env python3
"""Publish official algorithm pack assets and catalog with dry-run support.

Publishing order (atomic catalog last) — **no GitHub/Gitee Release tags**:
1. Validate source
2. Build + sign package
3. Local verify
4. Upload package (+ checksums + public key) to release-index branch under
   algorithms/packages/
5. Anonymous download both Gitee/GitHub raw mirrors, compare SHA-256 + verify signatures
6. Build and sign catalog (stable.json / beta.json)
7. Publish catalog to release-index only after all prior steps succeed

Network operations require explicit --execute. Default is dry-run.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parents[2]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    AlgorithmPackError,
    package_filename,
    sha256_file,
    write_json,
)
from build_algorithm_catalog import (  # noqa: E402
    algorithm_entry_from_package,
    build_catalog_payload,
    sign_catalog,
    verify_catalog_document,
)
from build_algorithm_pack import build_package  # noqa: E402
from sign_algorithm_pack import load_private_key, public_key_b64, sign_package  # noqa: E402
from validate_algorithm_pack import validate_source  # noqa: E402
from verify_algorithm_pack import verify_package  # noqa: E402


def _redact(text: str) -> str:
    for key in (
        "GH_TOKEN",
        "GITHUB_TOKEN",
        "GITEE_TOKEN",
        "ALGORITHM_SIGNING_PRIVATE_KEY_B64",
    ):
        value = os.environ.get(key)
        if value:
            text = text.replace(value, "***")
    return text


def _log(message: str) -> None:
    print(_redact(message), flush=True)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _public_pem(private_key) -> str:
    return private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")


def anonymous_download(url: str, retries: int = 4) -> bytes:
    last_error: Exception | None = None
    for attempt in range(retries):
        request = Request(url, headers={"User-Agent": "HZZS-Algorithm-Publisher-Anon"})
        try:
            with urlopen(request, timeout=120) as response:
                data = response.read()
                if not data:
                    raise AlgorithmPackError(f"empty download: {url}")
                return data
        except (HTTPError, URLError, AlgorithmPackError) as error:
            last_error = error
            time.sleep(1.5 * (attempt + 1))
    raise AlgorithmPackError(f"anonymous download failed: {url}: {last_error}")


def merge_catalog_algorithms(
    existing: list[dict[str, Any]] | None,
    new_entry: dict[str, Any],
) -> list[dict[str, Any]]:
    """Keep other algorithms / versions; replace only the same (id, version).

    Same version is treated as immutable: if sha256 differs, raise.
    """
    merged: list[dict[str, Any]] = []
    replaced = False
    new_key = (new_entry["id"], new_entry["version"])
    for item in existing or []:
        if not isinstance(item, dict):
            continue
        key = (item.get("id"), item.get("version"))
        if key != new_key:
            merged.append(item)
            continue
        if item.get("sha256") and item.get("sha256") != new_entry.get("sha256"):
            raise AlgorithmPackError(
                f"refusing to mutate immutable catalog entry {new_key}: "
                f"sha256 {item.get('sha256')} -> {new_entry.get('sha256')}"
            )
        merged.append(new_entry)
        replaced = True
    if not replaced:
        merged.append(new_entry)
    return merged


def load_remote_catalog_algorithms(
    *,
    owner: str,
    repo: str,
    channel: str,
    prefer_github: bool = True,
) -> list[dict[str, Any]]:
    """Best-effort anonymous fetch of existing channel catalog algorithms."""
    bases = [
        f"https://raw.githubusercontent.com/{owner}/{repo}/release-index",
        f"https://gitee.com/{owner}/{repo}/raw/release-index",
    ]
    if not prefer_github:
        bases.reverse()
    last_error: Exception | None = None
    for base in bases:
        url = f"{base}/algorithms/{channel}.json"
        try:
            data = anonymous_download(url, retries=2)
            doc = json.loads(data.decode("utf-8"))
            algorithms = doc.get("algorithms")
            if isinstance(algorithms, list):
                return [item for item in algorithms if isinstance(item, dict)]
            return []
        except Exception as error:  # noqa: BLE001 — best-effort merge
            last_error = error
            continue
    _log(f"no existing catalog for merge ({channel}): {last_error}")
    return []


def write_sha256sums(paths: list[Path], output: Path) -> None:
    lines = []
    for path in sorted(paths, key=lambda item: item.name):
        lines.append(f"{sha256_file(path)}  {path.name}")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def _publish_release_index_file(
    *,
    owner: str,
    repo: str,
    path: str,
    content: bytes,
    gh_token: str,
    gitee_token: str,
    message: str | None = None,
) -> None:
    """Create/update a single file on release-index for GitHub and Gitee."""
    if not content or len(content) > 1024 * 1024:
        raise AlgorithmPackError(f"content size invalid for {path}")
    if not path.startswith("algorithms/"):
        raise AlgorithmPackError(f"path must be under algorithms/: {path}")
    branch = "release-index"
    commit_msg = message or f"更新 {path}"

    api = f"https://api.github.com/repos/{owner}/{repo}"
    headers = {
        "Authorization": f"Bearer {gh_token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "HZZS-Algorithm-Publisher",
    }
    try:
        with urlopen(Request(api + f"/git/ref/heads/{branch}", headers=headers), timeout=30) as response:
            response.read()
    except HTTPError as error:
        if error.code != 404:
            raise AlgorithmPackError(f"GitHub ref lookup failed: {error.code}") from error
        with urlopen(Request(api + "/git/ref/heads/main", headers=headers), timeout=30) as response:
            main = json.loads(response.read().decode("utf-8"))
        create = Request(
            api + "/git/refs",
            data=json.dumps(
                {"ref": f"refs/heads/{branch}", "sha": main["object"]["sha"]}
            ).encode(),
            headers={**headers, "Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(create, timeout=30) as response:
            response.read()

    sha = None
    try:
        with urlopen(
            Request(api + f"/contents/{path}?ref={branch}", headers=headers),
            timeout=30,
        ) as response:
            current = json.loads(response.read().decode("utf-8"))
            sha = current.get("sha")
            # immutable: same content → skip
            if current.get("content"):
                existing = base64.b64decode("".join(current["content"].split()))
                if existing == content:
                    _log(f"GitHub {path} unchanged, skip")
                    sha = "skip"
    except HTTPError as error:
        if error.code != 404:
            raise AlgorithmPackError(f"GitHub content lookup failed: {error.code}") from error

    if sha != "skip":
        body: dict[str, Any] = {
            "message": commit_msg,
            "content": base64.b64encode(content).decode("ascii"),
            "branch": branch,
        }
        if sha:
            body["sha"] = sha
        put = Request(
            api + f"/contents/{path}",
            data=json.dumps(body).encode("utf-8"),
            headers={**headers, "Content-Type": "application/json"},
            method="PUT",
        )
        with urlopen(put, timeout=60) as response:
            response.read()

    gitee_api = f"https://gitee.com/api/v5/repos/{owner}/{repo}/contents/{path}"
    gitee_sha = None
    try:
        with urlopen(
            Request(f"{gitee_api}?access_token={gitee_token}&ref={branch}", method="GET"),
            timeout=30,
        ) as response:
            current = json.loads(response.read().decode("utf-8"))
            gitee_sha = current.get("sha")
            if current.get("content"):
                existing = base64.b64decode("".join(str(current["content"]).split()))
                if existing == content:
                    _log(f"Gitee {path} unchanged, skip")
                    return
    except HTTPError as error:
        if error.code != 404:
            raise AlgorithmPackError(f"Gitee content lookup failed: {error.code}") from error

    form = {
        "access_token": gitee_token,
        "message": commit_msg,
        "content": base64.b64encode(content).decode("ascii"),
        "branch": branch,
    }
    if gitee_sha:
        form["sha"] = gitee_sha
    put_gitee = Request(
        gitee_api,
        data=urlencode(form).encode("utf-8"),
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": "HZZS-Algorithm-Publisher",
        },
        method="PUT",
    )
    with urlopen(put_gitee, timeout=60) as response:
        response.read()


def _publish_algorithm_index(
    *,
    owner: str,
    repo: str,
    channel: str,
    content: bytes,
    gh_token: str,
    gitee_token: str,
) -> None:
    _publish_release_index_file(
        owner=owner,
        repo=repo,
        path=f"algorithms/{channel}.json",
        content=content,
        gh_token=gh_token,
        gitee_token=gitee_token,
        message=f"更新 algorithms/{channel}.json",
    )


def publish(arguments: argparse.Namespace) -> int:
    source = arguments.source.resolve()
    out_dir = arguments.work_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    dry_run = not arguments.execute
    _log(f"mode={'dry-run' if dry_run else 'execute'} source={source}")

    unsigned_dir = out_dir / "unsigned"
    unsigned_dir.mkdir(parents=True, exist_ok=True)
    unsigned = build_package(source, unsigned_dir)

    key_id = arguments.key_id or os.environ.get(
        "ALGORITHM_SIGNING_KEY_ID", "hzzs-algorithm-official-1"
    )
    private_key = load_private_key(
        private_key_path=arguments.private_key,
        private_key_b64=arguments.private_key_b64,
    )
    manifest = validate_source(source)["manifest"]
    filename = package_filename(manifest["id"], manifest["version"])
    asset_path = f"algorithms/packages/{filename}"
    signed_path = out_dir / filename
    sign_package(unsigned, signed_path, private_key=private_key, key_id=key_id)

    public_pem_path = out_dir / "algorithm-public-key.pem"
    public_pem_path.write_text(_public_pem(private_key), encoding="utf-8")
    public_b64_path = out_dir / "algorithm-public-key.der.b64"
    public_b64_path.write_text(public_key_b64(private_key.public_key()) + "\n", encoding="utf-8")

    verified = verify_package(
        signed_path,
        public_key_path=public_pem_path,
        require_key_id=key_id,
        allow_embedded_key=True,
    )
    _log(
        f"local verify OK id={verified['id']} version={verified['version']} "
        f"sha256={verified['sha256']}"
    )

    checksums = out_dir / "SHA256SUMS"
    write_sha256sums([signed_path, public_b64_path], checksums)

    channel = arguments.channel
    owner = arguments.owner
    repo = arguments.repo
    gh_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    gitee_token = os.environ.get("GITEE_TOKEN")

    if not dry_run and not gh_token:
        raise AlgorithmPackError("GH_TOKEN/GITHUB_TOKEN required for --execute")
    if not dry_run and not gitee_token:
        raise AlgorithmPackError("GITEE_TOKEN required for --execute")

    # 资产先上 release-index，再写目录（不创建 Release tag）
    package_bytes = signed_path.read_bytes()
    if dry_run:
        _log(
            f"[dry-run] would upload {asset_path} (+ SHA256SUMS, public key) "
            "to release-index on GitHub and Gitee"
        )
    else:
        _publish_release_index_file(
            owner=owner,
            repo=repo,
            path=asset_path,
            content=package_bytes,
            gh_token=gh_token or "",
            gitee_token=gitee_token or "",
            message=f"发布算法包 {manifest['id']} {manifest['version']}",
        )
        _publish_release_index_file(
            owner=owner,
            repo=repo,
            path=f"algorithms/packages/{checksums.name}",
            content=checksums.read_bytes(),
            gh_token=gh_token or "",
            gitee_token=gitee_token or "",
            message=f"更新算法包校验和 {manifest['id']} {manifest['version']}",
        )
        _publish_release_index_file(
            owner=owner,
            repo=repo,
            path="algorithms/packages/algorithm-public-key.der.b64",
            content=public_b64_path.read_bytes(),
            gh_token=gh_token or "",
            gitee_token=gitee_token or "",
            message="更新算法官方公钥",
        )
        _log(f"uploaded package to release-index {asset_path}")

    local_sha = verified["sha256"]
    if dry_run:
        _log("[dry-run] skip anonymous dual-source download verification")
    else:
        for base in (
            f"https://raw.githubusercontent.com/{owner}/{repo}/release-index",
            f"https://gitee.com/{owner}/{repo}/raw/release-index",
        ):
            url = f"{base}/{asset_path}"
            data = anonymous_download(url)
            remote_sha = sha256_bytes(data)
            if remote_sha != local_sha:
                raise AlgorithmPackError(
                    f"anonymous download hash mismatch for {url}: "
                    f"{remote_sha} != {local_sha}"
                )
            host = base.split("//", 1)[1].split("/", 1)[0]
            temp = out_dir / f"verify-{host}.hzzsalg"
            temp.write_bytes(data)
            verify_package(
                temp,
                public_key_path=public_pem_path,
                require_key_id=key_id,
                allow_embedded_key=True,
            )
            _log(f"anonymous verify OK source={base} sha256={remote_sha}")

    entry = algorithm_entry_from_package(
        signed_path,
        public_key=public_pem_path,
        public_key_b64_value=None,
        key_id=key_id,
        channel=channel,
    )
    if dry_run:
        existing_algorithms = load_remote_catalog_algorithms(
            owner=owner, repo=repo, channel=channel
        )
        _log(
            f"[dry-run] would merge with {len(existing_algorithms)} existing catalog "
            f"entries on algorithms/{channel}.json"
        )
    else:
        existing_algorithms = load_remote_catalog_algorithms(
            owner=owner, repo=repo, channel=channel
        )
    merged_algorithms = merge_catalog_algorithms(existing_algorithms, entry)
    _log(
        f"catalog merge: existing={len(existing_algorithms)} "
        f"merged={len(merged_algorithms)} entry={entry['id']}@{entry['version']}"
    )
    catalog_payload = build_catalog_payload(
        channel=channel,
        key_id=key_id,
        algorithms=merged_algorithms,
        generated_at=arguments.generated_at,
    )
    catalog_doc = sign_catalog(catalog_payload, private_key, key_id)
    catalog_path = out_dir / f"{channel}.json"
    write_json(catalog_path, catalog_doc)
    verify_catalog_document(
        catalog_doc,
        public_key_path=public_pem_path,
        require_key_id=key_id,
    )
    _log(f"catalog built {catalog_path}")

    if dry_run:
        _log(
            f"[dry-run] would publish catalog to release-index algorithms/{channel}.json "
            "on GitHub and Gitee AFTER assets verified"
        )
    else:
        _publish_algorithm_index(
            owner=owner,
            repo=repo,
            channel=channel,
            content=catalog_path.read_bytes(),
            gh_token=gh_token or "",
            gitee_token=gitee_token or "",
        )
        _log(f"published algorithms/{channel}.json on GitHub and Gitee")

    _log("DONE")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--work-dir", type=Path, default=Path("build/algorithm-release"))
    parser.add_argument("--channel", choices=["stable", "beta"], default="stable")
    parser.add_argument("--owner", default="Azek431")
    parser.add_argument("--repo", default="hzzs")
    parser.add_argument("--private-key", type=Path)
    parser.add_argument("--private-key-b64")
    parser.add_argument("--key-id", default=os.environ.get("ALGORITHM_SIGNING_KEY_ID"))
    parser.add_argument("--generated-at")
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Perform network publish. Default is dry-run only.",
    )
    arguments = parser.parse_args(argv)
    try:
        return publish(arguments)
    except AlgorithmPackError as error:
        print(f"ERROR: {_redact(str(error))}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
