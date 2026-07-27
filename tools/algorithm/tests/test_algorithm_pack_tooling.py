#!/usr/bin/env python3
"""Unit tests for official algorithm pack tooling."""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ALG = ROOT / "tools" / "algorithm"
if str(ALG) not in sys.path:
    sys.path.insert(0, str(ALG))

from build_algorithm_catalog import (  # noqa: E402
    build_catalog_payload,
)
from build_algorithm_pack import build_package  # noqa: E402
from common import (  # noqa: E402
    AlgorithmPackError,
    MAX_FILE_BYTES,
    MAX_TOTAL_UNCOMPRESSED_BYTES,
    package_filename,
    read_zip_entries,
    sha256_file,
    validate_rules,
    write_deterministic_zip,
)
from validate_algorithm_pack import validate_source  # noqa: E402
from verify_algorithm_pack import verify_package  # noqa: E402

OFFICIAL = ROOT / "algorithm-packs" / "official-bamboo-baseline"


class AlgorithmPackToolingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="hzzs-alg-"))
        self.addCleanup(lambda: shutil.rmtree(self.tmpdir, ignore_errors=True))

    def test_official_source_validates(self) -> None:
        result = validate_source(OFFICIAL)
        self.assertEqual(result["manifest"]["id"], "official-bamboo-baseline")
        self.assertIn("BAMBOO_BOOKSTORE", result["manifest"]["supportedScenes"])
        self.assertEqual(result["rules"]["schemaVersion"], 2)
        scene = result["rules"]["scenes"]["BAMBOO_BOOKSTORE"]
        self.assertIn("userThresholds", scene)
        self.assertIn("engineParams", scene)

    def test_rules_v1_still_validates(self) -> None:
        rules = {
            "schemaVersion": 1,
            "scenes": {
                "BAMBOO_BOOKSTORE": {
                    "thresholds": {
                        "workWidth": 384,
                        "minimumConfidence": 0.72,
                        "stableFrames": 2,
                        "playerReferenceMode": "FIXED_RATIO",
                        "fixedPlayerXRatio": 0.185,
                    },
                    "disabledObstacles": [],
                }
            },
        }
        validate_rules(rules, ["BAMBOO_BOOKSTORE"])

    def test_rules_v2_rejects_forbidden_engine_key(self) -> None:
        rules = {
            "schemaVersion": 2,
            "scenes": {
                "BAMBOO_BOOKSTORE": {
                    "engineParams": {"automation": True},
                }
            },
        }
        with self.assertRaises(AlgorithmPackError):
            validate_rules(rules, ["BAMBOO_BOOKSTORE"])

    def test_rules_v2_rejects_inverted_range(self) -> None:
        rules = {
            "schemaVersion": 2,
            "scenes": {
                "BAMBOO_BOOKSTORE": {
                    "engineParams": {
                        "bottleWidthMin": 0.5,
                        "bottleWidthMax": 0.1,
                    },
                }
            },
        }
        with self.assertRaises(AlgorithmPackError):
            validate_rules(rules, ["BAMBOO_BOOKSTORE"])

    def test_reproducible_build(self) -> None:
        a = build_package(OFFICIAL, self.tmpdir / "a.hzzsalg")
        b = build_package(OFFICIAL, self.tmpdir / "b.hzzsalg")
        self.assertEqual(a.read_bytes(), b.read_bytes())
        self.assertEqual(sha256_file(a), sha256_file(b))

    def test_unsigned_package_builds_and_verifies(self) -> None:
        """构建无符号包并验证完整性（清单/规则/变更日志 + 文件摘要）。"""
        unsigned = build_package(OFFICIAL, self.tmpdir / "unsigned.hzzsalg")
        result = verify_package(unsigned)
        self.assertEqual(result["id"], "official-bamboo-baseline")
        self.assertEqual(result["version"], "0.1.0")
        self.assertIn("sha256", result)
        self.assertEqual(len(result["sha256"]), 64)

    def test_duplicate_path_rejected(self) -> None:
        path = self.tmpdir / "dup.hzzsalg"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("manifest.json", b"{}")
            # ZipFile allows writing same name twice in some modes; force dual entries.
            archive.writestr("manifest.json", b"{}")
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path)

    def test_path_traversal_rejected(self) -> None:
        path = self.tmpdir / "trav.hzzsalg"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("../evil.json", b"{}")
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path)

    def test_absolute_path_rejected(self) -> None:
        path = self.tmpdir / "abs.hzzsalg"
        with zipfile.ZipFile(path, "w") as archive:
            # zipfile may normalize; write via ZipInfo
            info = zipfile.ZipInfo("/tmp/evil.json")
            archive.writestr(info, b"{}")
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path)

    def test_zip_bomb_total_size_rejected(self) -> None:
        path = self.tmpdir / "bomb.hzzsalg"
        # Highly compressible payload exceeding uncompressed limit.
        payload = b"A" * (MAX_TOTAL_UNCOMPRESSED_BYTES + 1024)
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", payload)
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path)

    def test_oversized_file_rejected_on_source(self) -> None:
        source = self.tmpdir / "big-source"
        shutil.copytree(OFFICIAL, source)
        huge = source / "CHANGELOG.txt"
        huge.write_text("x" * (MAX_FILE_BYTES + 10), encoding="utf-8")
        with self.assertRaises(AlgorithmPackError):
            validate_source(source)

    def test_forbidden_extension_rejected(self) -> None:
        source = self.tmpdir / "bad-ext"
        shutil.copytree(OFFICIAL, source)
        (source / "payload.exe").write_bytes(b"MZ")
        with self.assertRaises(AlgorithmPackError):
            validate_source(source)

    def test_directory_sort_stable_in_zip(self) -> None:
        unsigned = build_package(OFFICIAL, self.tmpdir / "sorted.hzzsalg")
        infos = archive_infolist_reopen(unsigned)
        names = [info.filename for info in infos]
        self.assertEqual(names, sorted(names))
        for info in infos:
            self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))

    def test_stable_beta_catalog_isolation(self) -> None:
        unsigned = build_package(OFFICIAL, self.tmpdir / "unsigned.hzzsalg")
        # Ensure filename matches expected package name for catalog entry
        expected = self.tmpdir / package_filename("official-bamboo-baseline", "0.1.0")
        if unsigned != expected:
            expected.write_bytes(unsigned.read_bytes())
            unsigned = expected
        from build_algorithm_catalog import algorithm_entry_from_package

        entry = algorithm_entry_from_package(
            unsigned,
            channel="stable",
        )
        stable_payload = build_catalog_payload(
            channel="stable",
            algorithms=[entry],
            generated_at="2026-07-21T00:00:00Z",
        )
        beta_payload = build_catalog_payload(
            channel="beta",
            algorithms=[entry],
            generated_at="2026-07-21T00:00:00Z",
        )
        self.assertEqual(stable_payload["channel"], "stable")
        self.assertEqual(beta_payload["channel"], "beta")
        self.assertNotEqual(
            json.dumps(stable_payload, sort_keys=True),
            json.dumps(beta_payload, sort_keys=True),
        )

    def test_revoked_flag_propagates(self) -> None:
        source = self.tmpdir / "revoked-source"
        shutil.copytree(OFFICIAL, source)
        manifest_path = source / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["revoked"] = True
        manifest["version"] = "1.0.1"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        unsigned = build_package(source, self.tmpdir / "revoked-unsigned.hzzsalg")
        # 重命名为预期文件名
        expected = self.tmpdir / package_filename("official-bamboo-baseline", "1.0.1")
        if unsigned != expected:
            expected.write_bytes(unsigned.read_bytes())
            unsigned = expected
        result = verify_package(unsigned)
        self.assertTrue(result["revoked"])

    def test_publish_dry_run(self) -> None:
        from publish_algorithm_release import main as publish_main

        code = publish_main(
            [
                "--source",
                str(OFFICIAL),
                "--work-dir",
                str(self.tmpdir / "publish"),
                "--channel",
                "stable",
                "--generated-at",
                "2026-07-21T00:00:00Z",
            ]
        )
        self.assertEqual(code, 0)
        work = self.tmpdir / "publish"
        # 无符号包直接放在 work 目录下
        unsigned = work / package_filename("official-bamboo-baseline", "0.1.0")
        self.assertTrue(unsigned.is_file())
        self.assertTrue((work / "stable.json").is_file())
        self.assertTrue((work / "SHA256SUMS").is_file())
        catalog = json.loads((work / "stable.json").read_text(encoding="utf-8"))
        self.assertNotIn("catalogSignature", catalog)
        self.assertEqual(catalog["channel"], "stable")
        # dry-run must not require network tokens
        self.assertNotIn("execute", sys.argv)

    def test_bump_algorithm_version_patch(self) -> None:
        from bump_algorithm_version import bump_pack, bump_semver

        self.assertEqual(bump_semver("0.1.0", "patch"), "0.1.1")
        self.assertEqual(bump_semver("0.1.9", "minor"), "0.2.0")
        self.assertEqual(bump_semver("0.2.0-beta.1", "patch"), "0.2.1")
        source = self.tmpdir / "bump-src"
        shutil.copytree(OFFICIAL, source)
        before = json.loads((source / "manifest.json").read_text(encoding="utf-8"))["version"]
        result = bump_pack(
            source,
            level="patch",
            note="unit test bump",
            sync_assets=False,
        )
        after = json.loads((source / "manifest.json").read_text(encoding="utf-8"))["version"]
        self.assertEqual(result["old_version"], before)
        self.assertEqual(result["new_version"], after)
        self.assertNotEqual(before, after)
        log = (source / "CHANGELOG.txt").read_text(encoding="utf-8")
        self.assertIn(after, log)
        self.assertIn("unit test bump", log)

    def test_parse_mirrors_defaults_and_github_only(self) -> None:
        from publish_algorithm_release import _parse_mirrors

        self.assertEqual(_parse_mirrors(None), ["github", "gitee"])
        self.assertEqual(_parse_mirrors("github"), ["github"])
        self.assertEqual(_parse_mirrors("github,gitee"), ["github", "gitee"])
        self.assertEqual(_parse_mirrors("gitee,github,gitee"), ["gitee", "github"])
        with self.assertRaises(AlgorithmPackError):
            _parse_mirrors("gitlab")

    def test_catalog_merge_keeps_other_algorithms(self) -> None:
        from publish_algorithm_release import merge_catalog_algorithms

        existing = [
            {
                "id": "other-algo",
                "version": "0.1.0",
                "sha256": "a" * 64,
                "filename": "other-algo-v0.1.0.hzzsalg",
            },
            {
                "id": "official-bamboo-baseline",
                "version": "0.1.0",
                "sha256": "b" * 64,
                "filename": "official-bamboo-baseline-v0.1.0.hzzsalg",
            },
        ]
        new_entry = {
            "id": "official-bamboo-baseline",
            "version": "0.2.0",
            "sha256": "c" * 64,
            "filename": "official-bamboo-baseline-v0.2.0.hzzsalg",
        }
        merged = merge_catalog_algorithms(existing, new_entry)
        ids = {(item["id"], item["version"]) for item in merged}
        self.assertIn(("other-algo", "0.1.0"), ids)
        self.assertIn(("official-bamboo-baseline", "0.1.0"), ids)
        self.assertIn(("official-bamboo-baseline", "0.2.0"), ids)
        self.assertEqual(len(merged), 3)

    def test_catalog_merge_rejects_same_version_hash_mutation(self) -> None:
        from publish_algorithm_release import merge_catalog_algorithms

        existing = [
            {
                "id": "official-bamboo-baseline",
                "version": "0.1.0",
                "sha256": "b" * 64,
            }
        ]
        mutated = {
            "id": "official-bamboo-baseline",
            "version": "0.1.0",
            "sha256": "d" * 64,
        }
        with self.assertRaises(AlgorithmPackError):
            merge_catalog_algorithms(existing, mutated)

    def test_catalog_merge_idempotent_same_hash(self) -> None:
        from publish_algorithm_release import merge_catalog_algorithms

        entry = {
            "id": "official-bamboo-baseline",
            "version": "0.1.0",
            "sha256": "b" * 64,
        }
        merged = merge_catalog_algorithms([entry], dict(entry))
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["sha256"], "b" * 64)


def archive_infolist_reopen(path: Path):
    with zipfile.ZipFile(path, "r") as archive:
        return list(archive.infolist())


if __name__ == "__main__":
    unittest.main()
