#!/usr/bin/env python3
"""Publish a signed channel index to release-index on GitHub and Gitee."""

from __future__ import annotations

import argparse
import base64
import os
from pathlib import Path

import requests


def checked(response: requests.Response, label: str) -> requests.Response:
    if response.status_code not in range(200, 300):
        raise SystemExit(f"{label} failed: {response.status_code} {response.text[:1000]}")
    return response


def github(owner: str, repo: str, path: str, branch: str, content: bytes, token: str) -> None:
    api = f"https://api.github.com/repos/{owner}/{repo}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    ref = requests.get(api + f"/git/ref/heads/{branch}", headers=headers, timeout=30)
    if ref.status_code == 404:
        main = checked(
            requests.get(api + "/git/ref/heads/main", headers=headers, timeout=30),
            "GitHub main ref lookup",
        ).json()
        checked(
            requests.post(
                api + "/git/refs",
                headers=headers,
                json={"ref": f"refs/heads/{branch}", "sha": main["object"]["sha"]},
                timeout=30,
            ),
            "GitHub release-index creation",
        )
    elif ref.status_code not in range(200, 300):
        checked(ref, "GitHub release-index lookup")

    current = requests.get(
        api + f"/contents/{path}",
        headers=headers,
        params={"ref": branch},
        timeout=30,
    )
    if current.status_code not in (200, 404):
        checked(current, "GitHub index lookup")
    body = {
        "message": f"更新 {path}",
        "content": base64.b64encode(content).decode(),
        "branch": branch,
    }
    if current.status_code == 200:
        body["sha"] = current.json()["sha"]
    checked(
        requests.put(api + f"/contents/{path}", headers=headers, json=body, timeout=30),
        "GitHub index update",
    )


def gitee(owner: str, repo: str, path: str, branch: str, content: bytes, token: str) -> None:
    api = f"https://gitee.com/api/v5/repos/{owner}/{repo}"
    auth = {"access_token": token}
    current = requests.get(
        api + f"/contents/{path}",
        params={**auth, "ref": branch},
        timeout=30,
    )
    if current.status_code not in (200, 404):
        checked(current, "Gitee index lookup")
    data = {
        **auth,
        "message": f"更新 {path}",
        "content": base64.b64encode(content).decode(),
        "branch": branch,
    }
    if current.status_code == 200:
        data["sha"] = current.json()["sha"]
    checked(
        requests.put(api + f"/contents/{path}", data=data, timeout=30),
        "Gitee index update",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--channel", choices=["stable", "beta"], required=True)
    parser.add_argument("--file", required=True)
    parser.add_argument(
        "--path-prefix",
        default="updates",
        help="Directory under release-index (updates or algorithms)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate inputs and print target paths without network writes",
    )
    arguments = parser.parse_args()
    content = Path(arguments.file).read_bytes()
    if not content or len(content) > 1024 * 1024:
        raise SystemExit("signed index size is invalid")
    if arguments.path_prefix not in {"updates", "algorithms"}:
        raise SystemExit("path-prefix must be updates or algorithms")
    path = f"{arguments.path_prefix}/{arguments.channel}.json"
    branch = "release-index"
    if arguments.dry_run:
        print(
            f"[dry-run] would publish {path} on GitHub/Gitee branch={branch} "
            f"bytes={len(content)}"
        )
        return
    github(arguments.owner, arguments.repo, path, branch, content, os.environ["GH_TOKEN"])
    gitee(arguments.owner, arguments.repo, path, branch, content, os.environ["GITEE_TOKEN"])


if __name__ == "__main__":
    main()
