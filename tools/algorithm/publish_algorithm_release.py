#!/usr/bin/env python3
"""Publish official algorithm pack assets and catalog with dry-run support.

Publishing order (atomic catalog last):
1. Validate source
2. Build + sign package
3. Local verify
4. Create/update GitHub draft release and upload assets (immutable on hash mismatch)
5. Sync identical assets to Gitee (no delete-then-upload clobber)
6. Anonymous download both sides and compare SHA-256 + verify signatures
7. Build and sign catalog (stable.json / beta.json)
8. Publish catalog to release-index only after all prior steps succeed

Network operations require explicit --execute. Default is dry-run.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import subprocess
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
    release_tag,
    sha256_file,
)
from build_algorithm_catalog import (  # noqa: E402
    algorithm_entry_from_package,
    build_catalog_payload,
    sign_catalog,
    verify_catalog_document,
)
from common import write_json  # noqa: E402
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


def http_json(
    method: str,
    url: str,
    *,
    token: str | None = None,
    host: str,
    body: dict[str, Any] | None = None,
    retries: int = 4,
) -> Any:
    headers = {"Accept": "application/json", "User-Agent": "HZZS-Algorithm-Publisher"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if host == "github" and token:
        headers["Authorization"] = f"Bearer {token}"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    last_error: Exception | None = None
    for attempt in range(retries):
        request = Request(url, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=60) as response:
                payload = response.read()
                if not payload:
                    return None
                return json.loads(payload.decode("utf-8"))
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            last_error = AlgorithmPackError(
                f"{host} HTTP {error.code} {method} {url.split('?')[0]}: {detail}"
            )
            if error.code in {408, 429, 500, 502, 503, 504} and attempt + 1 < retries:
                time.sleep(1.5 * (attempt + 1))
                continue
            raise last_error from error
        except URLError as error:
            last_error = AlgorithmPackError(f"{host} network error: {error}")
            if attempt + 1 < retries:
                time.sleep(1.5 * (attempt + 1))
                continue
            raise last_error from error
    raise last_error or AlgorithmPackError("HTTP request failed")


def github_paginate(url: str, token: str) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    page = 1
    while True:
        sep = "&" if "?" in url else "?"
        batch = http_json(
            "GET",
            f"{url}{sep}per_page=100&page={page}",
            token=token,
            host="github",
        )
        if not isinstance(batch, list) or not batch:
            break
        results.extend(batch)
        if len(batch) < 100:
            break
        page += 1
        if page > 50:
            raise AlgorithmPackError("GitHub pagination exceeded safety limit")
    return results


def ensure_github_release(
    *,
    owner: str,
    repo: str,
    tag: str,
    name: str,
    body: str,
    token: str,
    draft: bool,
    prerelease: bool,
    dry_run: bool,
) -> dict[str, Any]:
    api = f"https://api.github.com/repos/{owner}/{repo}"
    if dry_run:
        _log(f"[dry-run] would create/update GitHub release {tag} draft={draft}")
        return {"id": 0, "upload_url": "", "assets": [], "html_url": f"dry-run://{tag}"}
    releases = github_paginate(f"{api}/releases", token)
    existing = next((item for item in releases if item.get("tag_name") == tag), None)
    payload = {
        "tag_name": tag,
        "name": name,
        "body": body[: 128 * 1024],
        "draft": draft,
        "prerelease": prerelease,
        "target_commitish": "main",
    }
    if existing:
        return http_json(
            "PATCH",
            f"{api}/releases/{existing['id']}",
            token=token,
            host="github",
            body=payload,
        )
    return http_json("POST", f"{api}/releases", token=token, host="github", body=payload)


def github_asset_map(release: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {item["name"]: item for item in release.get("assets", []) if item.get("name")}


def upload_github_asset(
    *,
    upload_url_template: str,
    path: Path,
    token: str,
    existing: dict[str, Any] | None,
    dry_run: bool,
) -> None:
    digest = sha256_file(path)
    if existing is not None:
        remote_size = int(existing.get("size") or 0)
        local_size = path.stat().st_size
        if remote_size == local_size:
            _log(f"GitHub asset exists with same size, skip upload: {path.name}")
            return
        raise AlgorithmPackError(
            f"GitHub asset {path.name} exists with different size "
            f"(remote={remote_size}, local={local_size}); refusing to clobber"
        )
    if dry_run:
        _log(f"[dry-run] would upload GitHub asset {path.name} sha256={digest}")
        return
    base = upload_url_template.split("{", 1)[0]
    url = f"{base}?name={path.name}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/octet-stream",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "HZZS-Algorithm-Publisher",
    }
    request = Request(url, data=path.read_bytes(), headers=headers, method="POST")
    try:
        with urlopen(request, timeout=300) as response:
            response.read()
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise AlgorithmPackError(
            f"GitHub upload failed for {path.name}: {error.code} {detail}"
        ) from error


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


def write_sha256sums(paths: list[Path], output: Path) -> None:
    lines = []
    for path in sorted(paths, key=lambda item: item.name):
        lines.append(f"{sha256_file(path)}  {path.name}")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def _publish_algorithm_index(
    *,
    owner: str,
    repo: str,
    channel: str,
    content: bytes,
    gh_token: str,
    gitee_token: str,
) -> None:
    if not content or len(content) > 1024 * 1024:
        raise AlgorithmPackError("catalog size invalid")
    path = f"algorithms/{channel}.json"
    branch = "release-index"

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
    except HTTPError as error:
        if error.code != 404:
            raise AlgorithmPackError(f"GitHub content lookup failed: {error.code}") from error

    body: dict[str, Any] = {
        "message": f"更新 {path}",
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
    with urlopen(put, timeout=30) as response:
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
    except HTTPError as error:
        if error.code != 404:
            raise AlgorithmPackError(f"Gitee content lookup failed: {error.code}") from error

    form = {
        "access_token": gitee_token,
        "message": f"更新 {path}",
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
    with urlopen(put_gitee, timeout=30) as response:
        response.read()


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
    tag = release_tag(manifest["id"], manifest["version"])
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
    assets = [signed_path, checksums, public_b64_path]

    channel = arguments.channel
    owner = arguments.owner
    repo = arguments.repo
    gh_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    gitee_token = os.environ.get("GITEE_TOKEN")

    body = (
        f"Official HZZS algorithm pack `{manifest['id']}` version `{manifest['version']}`.\n\n"
        f"- scenes: {', '.join(manifest['supportedScenes'])}\n"
        f"- engine: {manifest['engineId']} api={manifest['engineApiVersion']}\n"
        f"- minimumAppVersionCode: {manifest['minimumAppVersionCode']}\n"
        f"- channel target: {channel}\n\n"
        "Package format: `.hzzsalg` (manifest.json, rules.json, CHANGELOG.txt, signature.json).\n"
        "Do not trust assets without signature verification against the official algorithm public key.\n"
    )
    notes_path = out_dir / "release-notes.md"
    notes_path.write_text(body, encoding="utf-8", newline="\n")

    if not dry_run and not gh_token:
        raise AlgorithmPackError("GH_TOKEN/GITHUB_TOKEN required for --execute")
    if not dry_run and not gitee_token:
        raise AlgorithmPackError("GITEE_TOKEN required for --execute")

    release = ensure_github_release(
        owner=owner,
        repo=repo,
        tag=tag,
        name=tag,
        body=body,
        token=gh_token or "",
        draft=arguments.draft,
        prerelease=channel == "beta",
        dry_run=dry_run,
    )
    existing_assets = github_asset_map(release)
    for asset in assets:
        upload_github_asset(
            upload_url_template=release.get("upload_url", ""),
            path=asset,
            token=gh_token or "",
            existing=existing_assets.get(asset.name),
            dry_run=dry_run,
        )

    sync_script = ROOT / "tools" / "release" / "sync_gitee_release.py"
    if dry_run:
        _log(
            "[dry-run] would sync Gitee release "
            f"{tag} assets=" + ",".join(path.name for path in assets)
        )
    else:
        command = [
            sys.executable,
            str(sync_script),
            "--owner",
            owner,
            "--repo",
            repo,
            "--tag",
            tag,
            "--name",
            tag,
            "--body-file",
            str(notes_path),
            "--assets",
            *[str(path) for path in assets],
            "--immutable",
        ]
        if channel == "beta":
            command.append("--prerelease")
        env = os.environ.copy()
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )
        if completed.returncode != 0:
            raise AlgorithmPackError(
                f"Gitee sync failed: {_redact(completed.stderr or completed.stdout)}"
            )
        _log(_redact(completed.stdout.strip() or "Gitee sync OK"))

    local_sha = verified["sha256"]
    if dry_run:
        _log("[dry-run] skip anonymous dual-source download verification")
    else:
        for base in (
            f"https://github.com/{owner}/{repo}/releases/download/{tag}",
            f"https://gitee.com/{owner}/{repo}/releases/download/{tag}",
        ):
            url = f"{base}/{filename}"
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
    catalog_payload = build_catalog_payload(
        channel=channel,
        key_id=key_id,
        algorithms=[entry],
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
        "--draft",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Create GitHub release as draft (default: true)",
    )
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
