#!/usr/bin/env python3
"""Cross-platform host vision library build helper.

Shell scripts in this repo may be checked out without the executable bit
(common on Windows and when git filemode is 100644). Always invoke them via
an interpreter rather than as direct executables.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

HOST_LIB_GLOBS = ("libhzzs_vision.*", "hzzs_vision.*")


def build_host_library(project_root: Path) -> Path:
    """Build the host shared library and return its path."""
    project_root = project_root.resolve()
    host_dir = project_root / "build" / "host"
    host_dir.mkdir(parents=True, exist_ok=True)

    win_script = project_root / "tools" / "vision" / "build_host.ps1"
    sh_script = project_root / "tools" / "vision" / "build_host.sh"

    if os.name == "nt" and win_script.is_file():
        subprocess.check_call(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(win_script),
            ]
        )
    else:
        # Prefer bash over execve(script): avoids PermissionError when +x is missing.
        subprocess.check_call(["bash", str(sh_script)])

    candidates = []
    for pattern in HOST_LIB_GLOBS:
        candidates.extend(host_dir.glob(pattern))
    if not candidates:
        raise FileNotFoundError(f"host library missing under {host_dir}")
    # Prefer .so on POSIX / .dll on Windows when multiple exist.
    preferred_suffix = ".dll" if os.name == "nt" else ".so"
    preferred = [path for path in candidates if path.suffix.lower() == preferred_suffix]
    return sorted(preferred or candidates)[0]


def ensure_host_library(project_root: Path, library: Path | None = None) -> Path:
    """Return an existing library path, building one when needed."""
    if library is not None:
        library = library.resolve()
        if not library.is_file():
            raise FileNotFoundError(f"host library missing: {library}")
        return library
    return build_host_library(project_root)
