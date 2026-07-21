#!/usr/bin/env python3
"""Create/update a Gitee release and upload selected assets.

Safety defaults for algorithm releases:
- --immutable refuses to replace an existing attachment whose content hash differs.
- Same-name assets with identical SHA-256 are skipped (idempotent).
- Never deletes-then-uploads by default when --immutable is set.
- Limited retries, pagination for release listing, and redacted error text.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import time
from pathlib import Path

import requests

API = "https://gitee.com/api/v5"
TOKEN_ENV = "GITEE_TOKEN"


def redact(text: str) -> str:
    token = os.environ.get(TOKEN_ENV)
    if token:
        return text.replace(token, "***")
    return text


def request(method: str, url: str, *, retries: int = 4, **kwargs):
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            response = requests.request(method, url, timeout=kwargs.pop("timeout", 30), **kwargs)
        except requests.RequestException as error:
            last_error = error
            time.sleep(1.5 * (attempt + 1))
            continue
        if response.status_code in range(200, 300):
            return response
        if response.status_code in {408, 429, 500, 502, 503, 504} and attempt + 1 < retries:
            time.sleep(1.5 * (attempt + 1))
            last_error = SystemExit(
                f"Gitee API failed: {response.status_code} {redact(response.text[:500])}"
            )
            continue
        raise SystemExit(
            f"Gitee API failed: {response.status_code} {redact(response.text[:1000])}"
        )
    raise SystemExit(f"Gitee API failed after retries: {redact(str(last_error))}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def list_releases(base: str, auth: dict) -> list[dict]:
    page = 1
    releases: list[dict] = []
    while True:
        response = request(
            "GET",
            base + "/releases",
            params={**auth, "per_page": 100, "page": page},
        )
        batch = response.json()
        if not isinstance(batch, list) or not batch:
            break
        releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1
        if page > 50:
            raise SystemExit("Gitee release pagination exceeded safety limit")
    return releases


def download_asset(url: str, auth: dict) -> bytes:
    # Gitee attach file download URLs are often public; still pass token if needed.
    response = request("GET", url, params=auth, timeout=120)
    return response.content


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--body-file", required=True)
    parser.add_argument("--assets", nargs="*", default=[])
    parser.add_argument("--prerelease", action="store_true")
    parser.add_argument(
        "--immutable",
        action="store_true",
        help="Never replace existing assets; skip when hash matches, fail when differs",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print actions without calling mutating Gitee APIs",
    )
    arguments = parser.parse_args()

    token = os.environ.get(TOKEN_ENV)
    if not token and not arguments.dry_run:
        raise SystemExit(f"{TOKEN_ENV} is required")
    auth = {"access_token": token or "dry-run"}
    base = f"{API}/repos/{arguments.owner}/{arguments.repo}"

    if arguments.dry_run:
        print(
            f"[dry-run] would ensure Gitee release tag={arguments.tag} "
            f"assets={','.join(Path(item).name for item in arguments.assets)}"
        )
        return

    releases = list_releases(base, auth)
    release = next((item for item in releases if item.get("tag_name") == arguments.tag), None)
    data = {
        **auth,
        "tag_name": arguments.tag,
        "name": arguments.name,
        "body": Path(arguments.body_file).read_text(encoding="utf-8")[: 128 * 1024],
        "prerelease": str(arguments.prerelease).lower(),
        "target_commitish": "main",
    }
    if release:
        release = request("PATCH", base + f"/releases/{release['id']}", data=data).json()
    else:
        release = request("POST", base + "/releases", data=data).json()
    if "id" not in release:
        raise SystemExit(f"Gitee release response lacks id: {redact(str(release))}")

    existing = {item.get("name"): item for item in release.get("assets", [])}
    for path in map(Path, arguments.assets):
        if not path.is_file():
            raise SystemExit(f"release asset missing: {path}")
        local_sha = sha256_file(path)
        previous = existing.get(path.name)
        if previous:
            download_url = (
                previous.get("browser_download_url")
                or previous.get("url")
                or previous.get("download_url")
            )
            if not download_url:
                if arguments.immutable:
                    raise SystemExit(
                        f"existing Gitee asset {path.name} has no download URL; "
                        "refusing immutable replace"
                    )
            else:
                remote = download_asset(download_url, auth)
                remote_sha = hashlib.sha256(remote).hexdigest()
                if remote_sha == local_sha:
                    print(f"skip identical Gitee asset: {path.name}")
                    continue
                if arguments.immutable:
                    raise SystemExit(
                        f"Gitee asset {path.name} hash mismatch "
                        f"(remote={remote_sha}, local={local_sha}); refusing clobber"
                    )
                # Legacy APK workflow may still replace after explicit non-immutable mode.
                request(
                    "DELETE",
                    base + f"/releases/{release['id']}/attach_files/{previous['id']}",
                    params=auth,
                )
        with path.open("rb") as stream:
            request(
                "POST",
                base + f"/releases/{release['id']}/attach_files",
                data=auth,
                files={"file": (path.name, stream)},
                timeout=300,
            )
            print(f"uploaded Gitee asset: {path.name}")
    print(release.get("html_url", ""))


if __name__ == "__main__":
    main()
