#!/usr/bin/env python3
"""Shared constants and helpers for HZZS algorithm packs (.hzzsalg)."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

# ---------------------------------------------------------------------------
# Limits and schema
# ---------------------------------------------------------------------------

# Manifest / pack container schema (unchanged for v1 packages).
SCHEMA_VERSION = 1
# rules.json may be 1 (user thresholds only) or 2 (userThresholds + engineParams).
RULES_SCHEMA_VERSIONS = frozenset({1, 2})
ENGINE_API_VERSION = 1
DEFAULT_ENGINE_ID = "native-vision"

PACKAGE_EXTENSION = ".hzzsalg"
ALLOWED_FILENAMES = frozenset(
    {
        "manifest.json",
        "rules.json",
        "CHANGELOG.txt",
        "signature.json",
    }
)
REQUIRED_UNSIGNED = frozenset({"manifest.json", "rules.json", "CHANGELOG.txt"})

MAX_FILES = 16
MAX_FILE_BYTES = 256 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 1024 * 1024
MAX_COMPRESSED_BYTES = 1024 * 1024
MAX_DESCRIPTION_CHARS = 2_000
MAX_CHANGELOG_CHARS = 64 * 1024
MAX_JSON_DEPTH = 10
MAX_JSON_KEYS = 384

FORBIDDEN_EXTENSIONS = frozenset(
    {
        ".exe",
        ".dll",
        ".so",
        ".dylib",
        ".bat",
        ".cmd",
        ".com",
        ".msi",
        ".ps1",
        ".sh",
        ".bash",
        ".zsh",
        ".js",
        ".mjs",
        ".cjs",
        ".py",
        ".pyc",
        ".class",
        ".jar",
        ".apk",
        ".aab",
        ".dex",
        ".wasm",
        ".bin",
        ".elf",
        ".php",
        ".rb",
        ".pl",
        ".vbs",
        ".wsf",
        ".scr",
        ".hta",
        ".lnk",
    }
)

SAFE_ID = re.compile(r"^[a-z][a-z0-9-]{1,62}[a-z0-9]$")
SAFE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]{1,32})?$")
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9._+-]{1,160}$")
SAFE_TAG = re.compile(r"^alg-[a-z][a-z0-9-]{1,62}-v[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]{1,32})?$")
SAFE_KEY_ID = re.compile(r"^[A-Za-z0-9._-]{2,64}$")
SAFE_SCENE = re.compile(r"^[A-Z][A-Z0-9_]{1,47}$")
SAFE_ENGINE_ID = re.compile(r"^[a-z][a-z0-9-]{1,46}[a-z0-9]$")
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")

# ZIP timestamps are fixed for reproducible builds (UTC 1980-01-01 00:00:00).
ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)
ZIP_COMPRESSION = zipfile.ZIP_DEFLATED
ZIP_COMPRESSLEVEL = 9

SUPPORTED_SCENES = frozenset({"SWEET_FACTORY", "BAMBOO_BOOKSTORE", "SEA_SALT_LIVING_ROOM"})
CHANNELS = frozenset({"stable", "beta"})

SIGNATURE_ALGORITHM = "Ed25519"
SIGNATURE_FILE = "signature.json"


class AlgorithmPackError(ValueError):
    """Raised for validation, packaging or signature failures."""


@dataclass(frozen=True)
class FileDigest:
    name: str
    size: int
    sha256: str


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    """Stable JSON used for signing and digests."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def write_json(path: Path, value: Any, *, indent: int | None = 2) -> None:
    text = json.dumps(value, ensure_ascii=False, indent=indent, sort_keys=True)
    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise AlgorithmPackError(f"invalid JSON: {path}: {error}") from error


def ensure_relative_safe_name(name: str) -> str:
    if not name or name in {".", ".."}:
        raise AlgorithmPackError(f"invalid entry name: {name!r}")
    if name.startswith("/") or name.startswith("\\"):
        raise AlgorithmPackError(f"absolute path rejected: {name}")
    if "\\" in name:
        raise AlgorithmPackError(f"backslash path rejected: {name}")
    if ".." in name.split("/"):
        raise AlgorithmPackError(f"path traversal rejected: {name}")
    if name.startswith("./") or "/./" in name:
        raise AlgorithmPackError(f"dot-segment path rejected: {name}")
    if not SAFE_FILENAME.fullmatch(Path(name).name):
        raise AlgorithmPackError(f"unsafe filename: {name}")
    if "/" in name:
        # First version only allows root-level files from the whitelist.
        raise AlgorithmPackError(f"nested paths are not allowed: {name}")
    lower = name.lower()
    suffix = Path(lower).suffix
    if suffix in FORBIDDEN_EXTENSIONS:
        raise AlgorithmPackError(f"forbidden extension: {name}")
    if name not in ALLOWED_FILENAMES:
        raise AlgorithmPackError(f"file not in whitelist: {name}")
    return name


def reject_symlink(path: Path) -> None:
    if path.is_symlink():
        raise AlgorithmPackError(f"symbolic links are forbidden: {path}")
    try:
        mode = path.lstat().st_mode
    except OSError as error:
        raise AlgorithmPackError(f"cannot stat path: {path}: {error}") from error
    if stat.S_ISLNK(mode):
        raise AlgorithmPackError(f"symbolic links are forbidden: {path}")


def list_source_files(source_dir: Path) -> list[Path]:
    if not source_dir.is_dir():
        raise AlgorithmPackError(f"source directory missing: {source_dir}")
    reject_symlink(source_dir)
    files: list[Path] = []
    for child in sorted(source_dir.iterdir(), key=lambda item: item.name):
        reject_symlink(child)
        if child.is_dir():
            raise AlgorithmPackError(f"directories are not allowed in pack source: {child.name}")
        if not child.is_file():
            raise AlgorithmPackError(f"unsupported source entry: {child.name}")
        ensure_relative_safe_name(child.name)
        if child.name == SIGNATURE_FILE:
            # Signature is produced by the signer, never taken from source tree.
            raise AlgorithmPackError("source tree must not contain signature.json")
        files.append(child)
    if len(files) > MAX_FILES:
        raise AlgorithmPackError(f"too many files: {len(files)} > {MAX_FILES}")
    names = [item.name for item in files]
    if len(names) != len(set(names)):
        raise AlgorithmPackError("duplicate filenames in source tree")
    missing = REQUIRED_UNSIGNED - set(names)
    if missing:
        raise AlgorithmPackError(f"missing required files: {sorted(missing)}")
    return files


def validate_size(name: str, size: int, *, compressed: bool = False) -> None:
    limit = MAX_COMPRESSED_BYTES if compressed else MAX_FILE_BYTES
    if size < 0 or size > limit:
        raise AlgorithmPackError(f"file size out of range for {name}: {size}")


def _walk_json(value: Any, *, depth: int = 0, key_count: list[int] | None = None) -> None:
    if key_count is None:
        key_count = [0]
    if depth > MAX_JSON_DEPTH:
        raise AlgorithmPackError("JSON nesting too deep")
    if isinstance(value, dict):
        key_count[0] += len(value)
        if key_count[0] > MAX_JSON_KEYS:
            raise AlgorithmPackError("too many JSON keys")
        for key, child in value.items():
            if not isinstance(key, str) or not key or len(key) > 64:
                raise AlgorithmPackError("invalid JSON object key")
            _walk_json(child, depth=depth + 1, key_count=key_count)
    elif isinstance(value, list):
        if len(value) > 256:
            raise AlgorithmPackError("JSON array too large")
        for child in value:
            _walk_json(child, depth=depth + 1, key_count=key_count)
    elif isinstance(value, str):
        if len(value) > MAX_CHANGELOG_CHARS:
            raise AlgorithmPackError("JSON string too large")
    elif isinstance(value, bool) or value is None:
        return
    elif isinstance(value, (int, float)):
        if isinstance(value, float) and (value != value or value in (float("inf"), float("-inf"))):
            raise AlgorithmPackError("non-finite JSON number")
        return
    else:
        raise AlgorithmPackError(f"unsupported JSON type: {type(value)!r}")


def validate_manifest(data: Mapping[str, Any]) -> dict[str, Any]:
    _walk_json(data)
    required = {
        "schemaVersion",
        "id",
        "version",
        "displayName",
        "description",
        "engineId",
        "engineApiVersion",
        "minimumAppVersionCode",
        "supportedScenes",
        "releaseDate",
        "author",
        "revoked",
    }
    missing = required - set(data)
    if missing:
        raise AlgorithmPackError(f"manifest missing fields: {sorted(missing)}")
    extra = set(data) - required - {"channel", "notes"}
    if extra:
        raise AlgorithmPackError(f"manifest has unknown fields: {sorted(extra)}")

    if data["schemaVersion"] != SCHEMA_VERSION:
        raise AlgorithmPackError("unsupported manifest schemaVersion")
    algorithm_id = data["id"]
    if not isinstance(algorithm_id, str) or not SAFE_ID.fullmatch(algorithm_id):
        raise AlgorithmPackError("invalid algorithm id")
    version = data["version"]
    if not isinstance(version, str) or not SAFE_VERSION.fullmatch(version):
        raise AlgorithmPackError("invalid algorithm version")
    for field, limit in (
        ("displayName", 80),
        ("description", MAX_DESCRIPTION_CHARS),
        ("author", 80),
        ("releaseDate", 32),
    ):
        value = data[field]
        if not isinstance(value, str) or not value.strip() or len(value) > limit:
            raise AlgorithmPackError(f"invalid manifest field: {field}")
    engine_id = data["engineId"]
    if not isinstance(engine_id, str) or not SAFE_ENGINE_ID.fullmatch(engine_id):
        raise AlgorithmPackError("invalid engineId")
    if data["engineApiVersion"] != ENGINE_API_VERSION:
        raise AlgorithmPackError("unsupported engineApiVersion")
    code = data["minimumAppVersionCode"]
    if not isinstance(code, int) or isinstance(code, bool) or not 1 <= code <= 2_100_000_000:
        raise AlgorithmPackError("invalid minimumAppVersionCode")
    scenes = data["supportedScenes"]
    if not isinstance(scenes, list) or not scenes or len(scenes) > 8:
        raise AlgorithmPackError("supportedScenes must be a non-empty list")
    normalized_scenes: list[str] = []
    for scene in scenes:
        if not isinstance(scene, str) or not SAFE_SCENE.fullmatch(scene):
            raise AlgorithmPackError(f"invalid scene id: {scene!r}")
        if scene not in SUPPORTED_SCENES:
            raise AlgorithmPackError(f"unsupported scene: {scene}")
        if scene in normalized_scenes:
            raise AlgorithmPackError(f"duplicate scene: {scene}")
        normalized_scenes.append(scene)
    if not isinstance(data["revoked"], bool):
        raise AlgorithmPackError("revoked must be boolean")
    if "channel" in data and data["channel"] not in CHANNELS:
        raise AlgorithmPackError("invalid channel")
    release_date = data["releaseDate"]
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", release_date):
        raise AlgorithmPackError("releaseDate must be YYYY-MM-DD")
    return dict(data)


_FORBIDDEN_ENGINE_KEYS = frozenset(
    {
        "gesture",
        "gestures",
        "root",
        "shizuku",
        "allowedPackages",
        "automation",
        "mcp",
        "captureBackend",
        "script",
        "scripts",
        "url",
        "urls",
        "model",
        "weights",
        "so",
        "dex",
        "jar",
    }
)


def validate_rules(data: Mapping[str, Any], supported_scenes: Iterable[str]) -> dict[str, Any]:
    _walk_json(data)
    if set(data) - {"schemaVersion", "scenes", "notes"}:
        raise AlgorithmPackError("rules.json has unknown top-level fields")
    schema = data.get("schemaVersion")
    if schema not in RULES_SCHEMA_VERSIONS:
        raise AlgorithmPackError(f"unsupported rules schemaVersion: {schema!r}")
    scenes = data.get("scenes")
    if not isinstance(scenes, dict) or not scenes:
        raise AlgorithmPackError("rules.scenes must be a non-empty object")
    allowed = set(supported_scenes)
    for scene, payload in scenes.items():
        if scene not in allowed:
            raise AlgorithmPackError(f"rules contain scene not listed in manifest: {scene}")
        if not isinstance(payload, dict):
            raise AlgorithmPackError(f"rules for {scene} must be an object")
        if schema == 1:
            _validate_rules_scene_v1(scene, payload)
        else:
            _validate_rules_scene_v2(scene, payload)
    missing = allowed - set(scenes)
    if missing:
        raise AlgorithmPackError(f"rules missing scenes from manifest: {sorted(missing)}")
    return dict(data)


def _validate_disabled_obstacles(disabled: Any) -> None:
    if not isinstance(disabled, list) or len(disabled) > 16:
        raise AlgorithmPackError("disabledObstacles invalid")
    seen_obstacles: set[str] = set()
    for item in disabled:
        if not isinstance(item, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{1,47}", item):
            raise AlgorithmPackError(f"invalid obstacle kind: {item!r}")
        if item in seen_obstacles:
            raise AlgorithmPackError(f"duplicate obstacle kind: {item}")
        seen_obstacles.add(item)


def _validate_rules_scene_v1(scene: str, payload: Mapping[str, Any]) -> None:
    thresholds = payload.get("thresholds")
    if not isinstance(thresholds, dict):
        raise AlgorithmPackError(f"rules for {scene} require thresholds object")
    _validate_thresholds(thresholds)
    if "disabledObstacles" in payload:
        _validate_disabled_obstacles(payload["disabledObstacles"])
    extras = set(payload) - {"thresholds", "disabledObstacles", "notes"}
    if extras:
        raise AlgorithmPackError(f"unknown rules fields for {scene}: {sorted(extras)}")


def _validate_rules_scene_v2(scene: str, payload: Mapping[str, Any]) -> None:
    extras = set(payload) - {"userThresholds", "engineParams", "disabledObstacles", "notes", "thresholds"}
    if extras:
        raise AlgorithmPackError(f"unknown rules fields for {scene}: {sorted(extras)}")
    # Compat: allow legacy "thresholds" key as alias of userThresholds.
    user = payload.get("userThresholds")
    if user is None and "thresholds" in payload:
        user = payload.get("thresholds")
    engine = payload.get("engineParams")
    if user is None and engine is None:
        raise AlgorithmPackError(
            f"rules for {scene} require userThresholds and/or engineParams",
        )
    if user is not None:
        if not isinstance(user, dict):
            raise AlgorithmPackError(f"userThresholds for {scene} must be an object")
        _validate_thresholds(user)
    if engine is not None:
        if not isinstance(engine, dict):
            raise AlgorithmPackError(f"engineParams for {scene} must be an object")
        _validate_engine_params(engine)
    if "disabledObstacles" in payload:
        _validate_disabled_obstacles(payload["disabledObstacles"])


def _validate_thresholds(thresholds: Mapping[str, Any]) -> None:
    allowed = {
        "workWidth",
        "minimumConfidence",
        "stableFrames",
        "playerReferenceMode",
        "fixedPlayerXRatio",
        "behindPlayerMarginRatio",
        "boundaryTolerancePlayerWidthRatio",
    }
    unknown = set(thresholds) - allowed
    if unknown:
        raise AlgorithmPackError(f"unknown threshold fields: {sorted(unknown)}")
    if "workWidth" in thresholds:
        value = thresholds["workWidth"]
        if not isinstance(value, int) or isinstance(value, bool) or not 64 <= value <= 1024:
            raise AlgorithmPackError("workWidth out of range")
    if "minimumConfidence" in thresholds:
        value = thresholds["minimumConfidence"]
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0.0 <= float(value) <= 1.0:
            raise AlgorithmPackError("minimumConfidence out of range")
    if "stableFrames" in thresholds:
        value = thresholds["stableFrames"]
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 30:
            raise AlgorithmPackError("stableFrames out of range")
    if "playerReferenceMode" in thresholds:
        mode = thresholds["playerReferenceMode"]
        if mode not in {"FIXED_RATIO", "DETECT_ONCE", "CONTINUOUS"}:
            raise AlgorithmPackError("invalid playerReferenceMode")
    for ratio_key in (
        "fixedPlayerXRatio",
        "behindPlayerMarginRatio",
        "boundaryTolerancePlayerWidthRatio",
    ):
        if ratio_key not in thresholds:
            continue
        value = thresholds[ratio_key]
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0.0 <= float(value) <= 1.0:
            raise AlgorithmPackError(f"{ratio_key} out of range")


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _require_unit(name: str, value: Any) -> None:
    if not _is_number(value) or not 0.0 <= float(value) <= 1.0:
        raise AlgorithmPackError(f"{name} out of range")


def _require_range(name: str, value: Any, lo: float, hi: float) -> None:
    if not _is_number(value) or not lo <= float(value) <= hi:
        raise AlgorithmPackError(f"{name} out of range")


def _require_channel(name: str, value: Any) -> None:
    if not isinstance(value, int) or isinstance(value, bool) or not 0 <= value <= 255:
        raise AlgorithmPackError(f"{name} out of range")


def _validate_engine_params(params: Mapping[str, Any]) -> None:
    forbidden = set(params) & _FORBIDDEN_ENGINE_KEYS
    if forbidden:
        raise AlgorithmPackError(f"engineParams contains forbidden keys: {sorted(forbidden)}")
    float_unit = {
        "sceneConfidenceFloor",
        "playerConfidenceFloor",
        "fixedPlayerTop",
        "fixedPlayerBottom",
        "fallbackSceneConfidenceMax",
        "groundSearchTop",
        "groundSearchBottom",
        "groundConfidenceMin",
    }
    float_ranges = {
        "bottleWidthMin": (0.001, 0.5),
        "bottleWidthMax": (0.01, 0.8),
        "bottleHeightMin": (0.001, 0.6),
        "bottleHeightMax": (0.01, 0.8),
        "cakeWidthMin": (0.01, 0.7),
        "cakeWidthMax": (0.05, 0.95),
        "cakeHeightMin": (0.01, 0.8),
        "cakeWideWidthRatio": (0.05, 0.8),
        "statueWidthMin": (0.01, 0.5),
        "statueWidthMax": (0.05, 0.8),
        "statueHeightMin": (0.01, 0.6),
        "statueHeightMax": (0.05, 0.8),
        "gapWidthMin": (0.01, 0.8),
        "gapWidthMax": (0.05, 0.95),
        "gapHeightMin": (0.01, 0.8),
        "gapWideWidthRatio": (0.05, 0.8),
        "brushWidthMin": (0.005, 0.5),
        "brushWidthMax": (0.02, 0.8),
        "brushHeightMin": (0.01, 0.7),
        "brushHeightMax": (0.05, 0.9),
        "spikeWidthMin": (0.01, 0.6),
        "spikeWidthMax": (0.05, 0.9),
        "spikeHeightMin": (0.01, 0.7),
        "spikeHeightMax": (0.05, 0.9),
    }
    int_ranges = {
        "fixedPlayerWidthDivisor": (8, 64),
        "fallbackMaxDetections": (0, 8),
    }
    allowed = set(float_unit) | set(float_ranges) | set(int_ranges) | {"colors"}
    unknown = set(params) - allowed
    if unknown:
        raise AlgorithmPackError(f"unknown engineParams fields: {sorted(unknown)}")
    for key in float_unit:
        if key in params:
            _require_unit(key, params[key])
    for key, (lo, hi) in float_ranges.items():
        if key in params:
            _require_range(key, params[key], lo, hi)
    for key, (lo, hi) in int_ranges.items():
        if key not in params:
            continue
        value = params[key]
        if not isinstance(value, int) or isinstance(value, bool) or not lo <= value <= hi:
            raise AlgorithmPackError(f"{key} out of range")
    ordered_pairs = (
        ("fixedPlayerTop", "fixedPlayerBottom"),
        ("groundSearchTop", "groundSearchBottom"),
        ("bottleWidthMin", "bottleWidthMax"),
        ("bottleHeightMin", "bottleHeightMax"),
        ("cakeWidthMin", "cakeWidthMax"),
        ("statueWidthMin", "statueWidthMax"),
        ("statueHeightMin", "statueHeightMax"),
        ("gapWidthMin", "gapWidthMax"),
        ("brushWidthMin", "brushWidthMax"),
        ("brushHeightMin", "brushHeightMax"),
        ("spikeWidthMin", "spikeWidthMax"),
        ("spikeHeightMin", "spikeHeightMax"),
    )
    for a, b in ordered_pairs:
        if a in params and b in params and float(params[a]) > float(params[b]):
            raise AlgorithmPackError(f"{a} must be <= {b}")
    if "colors" in params:
        colors = params["colors"]
        if not isinstance(colors, dict):
            raise AlgorithmPackError("colors must be an object")
        _validate_engine_colors(colors)


def _validate_engine_colors(colors: Mapping[str, Any]) -> None:
    channel_keys = {
        "bottleGreenMin",
        "bottleRedMax",
        "cakeRedMin",
        "cakeGreenMin",
        "cakeBlueMax",
        "spikeRedMin",
        "spikeBlueMin",
        "bambooGreenMin",
        "bambooBlueMax",
        "brushDarkMax",
        "statueChromaMax",
    }
    ratio_keys = {
        "bottleGreenOverRed": (0.5, 3.0),
        "bottleGreenOverBlue": (0.5, 3.0),
        "spikeRedOverGreen": (0.5, 3.0),
        "bambooGreenOverRed": (0.2, 3.0),
        "bambooGreenOverBlue": (0.5, 4.0),
    }
    allowed = channel_keys | set(ratio_keys)
    unknown = set(colors) - allowed
    if unknown:
        raise AlgorithmPackError(f"unknown color fields: {sorted(unknown)}")
    for key in channel_keys:
        if key in colors:
            _require_channel(key, colors[key])
    for key, (lo, hi) in ratio_keys.items():
        if key in colors:
            _require_range(key, colors[key], lo, hi)


def validate_changelog(text: str) -> str:
    if not text or not text.strip():
        raise AlgorithmPackError("CHANGELOG.txt is empty")
    if len(text) > MAX_CHANGELOG_CHARS:
        raise AlgorithmPackError("CHANGELOG.txt too large")
    if "\x00" in text:
        raise AlgorithmPackError("CHANGELOG.txt contains NUL")
    return text


def package_filename(algorithm_id: str, version: str) -> str:
    return f"{algorithm_id}-v{version}{PACKAGE_EXTENSION}"


def release_tag(algorithm_id: str, version: str) -> str:
    tag = f"alg-{algorithm_id}-v{version}"
    if not SAFE_TAG.fullmatch(tag):
        raise AlgorithmPackError(f"derived tag is unsafe: {tag}")
    return tag


def digests_for_mapping(files: Mapping[str, bytes]) -> list[FileDigest]:
    digests: list[FileDigest] = []
    total = 0
    for name in sorted(files):
        ensure_relative_safe_name(name)
        payload = files[name]
        validate_size(name, len(payload))
        total += len(payload)
        if total > MAX_TOTAL_UNCOMPRESSED_BYTES:
            raise AlgorithmPackError("total uncompressed size exceeds limit")
        digests.append(FileDigest(name=name, size=len(payload), sha256=sha256_bytes(payload)))
    if len(digests) > MAX_FILES:
        raise AlgorithmPackError("too many files in package")
    return digests


def read_zip_entries(path: Path, *, allow_signature: bool) -> dict[str, bytes]:
    if not path.is_file():
        raise AlgorithmPackError(f"package missing: {path}")
    reject_symlink(path)
    size = path.stat().st_size
    if size <= 0 or size > MAX_COMPRESSED_BYTES:
        raise AlgorithmPackError(f"package compressed size invalid: {size}")
    entries: dict[str, bytes] = {}
    total_uncompressed = 0
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        if not infos:
            raise AlgorithmPackError("package is empty")
        if len(infos) > MAX_FILES:
            raise AlgorithmPackError("package has too many entries")
        names_seen: set[str] = set()
        for info in infos:
            if info.is_dir():
                raise AlgorithmPackError(f"directory entries are forbidden: {info.filename}")
            # ZipInfo.external_attr high bits may mark symlink on Unix.
            if (info.external_attr >> 16) & stat.S_IFLNK:
                raise AlgorithmPackError(f"symlink zip entry forbidden: {info.filename}")
            name = ensure_relative_safe_name(info.filename)
            if name in names_seen:
                raise AlgorithmPackError(f"duplicate zip entry: {name}")
            names_seen.add(name)
            if info.file_size > MAX_FILE_BYTES:
                raise AlgorithmPackError(f"zip entry too large: {name}")
            if info.compress_size > MAX_COMPRESSED_BYTES:
                raise AlgorithmPackError(f"zip entry compressed size too large: {name}")
            total_uncompressed += info.file_size
            if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                raise AlgorithmPackError("zip bomb / total uncompressed size exceeded")
            payload = archive.read(info)
            if len(payload) != info.file_size:
                raise AlgorithmPackError(f"zip entry size mismatch: {name}")
            entries[name] = payload
    required = set(REQUIRED_UNSIGNED)
    if allow_signature:
        required.add(SIGNATURE_FILE)
    missing = required - set(entries)
    if missing:
        raise AlgorithmPackError(f"package missing required entries: {sorted(missing)}")
    if not allow_signature and SIGNATURE_FILE in entries:
        raise AlgorithmPackError("unsigned package must not contain signature.json")
    if allow_signature and SIGNATURE_FILE not in entries:
        raise AlgorithmPackError("signed package requires signature.json")
    return entries


def write_deterministic_zip(path: Path, files: Mapping[str, bytes]) -> None:
    digests_for_mapping(files)  # size / name checks
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".partial")
    if temporary.exists():
        temporary.unlink()
    try:
        with zipfile.ZipFile(
            temporary,
            mode="w",
            compression=ZIP_COMPRESSION,
            compresslevel=ZIP_COMPRESSLEVEL,
        ) as archive:
            for name in sorted(files):
                payload = files[name]
                info = zipfile.ZipInfo(filename=name, date_time=ZIP_DATE_TIME)
                info.compress_type = ZIP_COMPRESSION
                info.external_attr = 0o644 << 16
                info.create_system = 3  # UNIX
                archive.writestr(info, payload, compress_type=ZIP_COMPRESSION, compresslevel=ZIP_COMPRESSLEVEL)
        if path.exists():
            path.unlink()
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink(missing_ok=True)
    final_size = path.stat().st_size
    if final_size <= 0 or final_size > MAX_COMPRESSED_BYTES:
        path.unlink(missing_ok=True)
        raise AlgorithmPackError(f"produced package size invalid: {final_size}")


def unsigned_payload_from_entries(entries: Mapping[str, bytes]) -> dict[str, Any]:
    files = {name: data for name, data in entries.items() if name != SIGNATURE_FILE}
    digests = digests_for_mapping(files)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "files": [
            {"name": item.name, "sha256": item.sha256, "size": item.size}
            for item in digests
        ],
    }


def env_flag(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}
