#!/usr/bin/env python3
"""Cross-platform entry for FastContourV2 host smokes.

Shell scripts may be checked out without +x; always invoke via interpreter
(same pattern as tools/vision/host_build.py). Does not build libhzzs_vision
or touch Android NDK.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description="Build/run FastContourV2 host smokes")
    parser.add_argument(
        "--sanitize",
        choices=("", "address", "undefined"),
        default="",
        help="optional sanitizer (clang/g++ must support it)",
    )
    parser.add_argument(
        "--test",
        choices=("all", "core", "boundary", "pipeline"),
        default="all",
        help="which smoke binary set to build",
    )
    parser.add_argument(
        "--skip-run",
        action="store_true",
        help="compile only",
    )
    args = parser.parse_args()

    ps1 = ROOT / "tools" / "vision_v2" / "build_host_smoke.ps1"
    sh = ROOT / "tools" / "vision_v2" / "build_host_smoke.sh"

    if os.name == "nt" and ps1.is_file():
        cmd = [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(ps1),
            "-Test",
            args.test,
        ]
        if args.sanitize:
            cmd.extend(["-Sanitize", args.sanitize])
        if args.skip_run:
            cmd.append("-SkipRun")
    else:
        cmd = ["bash", str(sh), f"--test={args.test}"]
        if args.sanitize:
            cmd.append(f"--sanitize={args.sanitize}")
        if args.skip_run:
            cmd.append("--skip-run")

    print("+", " ".join(cmd), flush=True)
    completed = subprocess.run(cmd, cwd=str(ROOT), check=False)
    return int(completed.returncode)


if __name__ == "__main__":
    sys.exit(main())
