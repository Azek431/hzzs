#!/usr/bin/env python3
"""Create/update a Gitee release and upload the selected assets."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import requests

API = "https://gitee.com/api/v5"


def request(method: str, url: str, **kwargs):
    response = requests.request(method, url, timeout=kwargs.pop("timeout", 30), **kwargs)
    if response.status_code not in range(200, 300):
        raise SystemExit(f"Gitee API failed: {response.status_code} {response.text[:1000]}")
    return response


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--body-file", required=True)
    parser.add_argument("--assets", nargs="*", default=[])
    parser.add_argument("--prerelease", action="store_true")
    arguments = parser.parse_args()

    token = os.environ["GITEE_TOKEN"]
    base = f"{API}/repos/{arguments.owner}/{arguments.repo}"
    auth = {"access_token": token}
    releases_response = request("GET", base + "/releases", params={**auth, "per_page": 100})
    releases = releases_response.json()
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
        raise SystemExit(f"Gitee release response lacks id: {release}")

    existing = {item.get("name"): item for item in release.get("assets", [])}
    for path in map(Path, arguments.assets):
        if not path.is_file():
            raise SystemExit(f"release asset missing: {path}")
        previous = existing.get(path.name)
        if previous:
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
    print(release.get("html_url", ""))


if __name__ == "__main__":
    main()
