#!/usr/bin/env python3
"""Unit tests for official algorithm pack tooling."""

from __future__ import annotations

import base64
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
    sign_catalog,
    verify_catalog_document,
)
from build_algorithm_pack import build_package  # noqa: E402
from common import (  # noqa: E402
    AlgorithmPackError,
    MAX_FILE_BYTES,
    MAX_TOTAL_UNCOMPRESSED_BYTES,
    package_filename,
    read_zip_entries,
    sha256_file,
    write_deterministic_zip,
)
from sign_algorithm_pack import load_private_key, sign_package  # noqa: E402
from validate_algorithm_pack import validate_source  # noqa: E402
from verify_algorithm_pack import verify_package  # noqa: E402

OFFICIAL = ROOT / "algorithm-packs" / "official-bamboo-baseline"


class AlgorithmPackToolingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="hzzs-alg-"))
        self.addCleanup(lambda: shutil.rmtree(self.tmpdir, ignore_errors=True))
        self.private = self.tmpdir / "private.pem"
        self.public = self.tmpdir / "public.pem"
        # generate_keypair prints; capture via direct call
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives import serialization

        key = Ed25519PrivateKey.generate()
        self.private.write_bytes(
            key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )
        self.public.write_bytes(
            key.public_key().public_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )
        self.key_id = "hzzs-algorithm-official-1"

    def _signed_official(self) -> Path:
        unsigned = build_package(OFFICIAL, self.tmpdir / "out")
        signed = self.tmpdir / package_filename("official-bamboo-baseline", "1.0.0")
        # build_package may already write expected name into out dir
        if unsigned.name != signed.name:
            signed = self.tmpdir / "signed.hzzsalg"
        sign_package(
            unsigned,
            signed,
            private_key=load_private_key(private_key_path=self.private, private_key_b64=None),
            key_id=self.key_id,
        )
        return signed

    def test_official_source_validates(self) -> None:
        result = validate_source(OFFICIAL)
        self.assertEqual(result["manifest"]["id"], "official-bamboo-baseline")
        self.assertIn("BAMBOO_BOOKSTORE", result["manifest"]["supportedScenes"])

    def test_reproducible_build(self) -> None:
        a = build_package(OFFICIAL, self.tmpdir / "a.hzzsalg")
        b = build_package(OFFICIAL, self.tmpdir / "b.hzzsalg")
        self.assertEqual(a.read_bytes(), b.read_bytes())
        self.assertEqual(sha256_file(a), sha256_file(b))

    def test_sign_and_verify(self) -> None:
        signed = self._signed_official()
        result = verify_package(
            signed,
            public_key_path=self.public,
            require_key_id=self.key_id,
        )
        self.assertEqual(result["id"], "official-bamboo-baseline")
        self.assertEqual(result["version"], "1.0.0")

    def test_tampered_file_fails(self) -> None:
        signed = self._signed_official()
        entries = read_zip_entries(signed, allow_signature=True)
        entries["CHANGELOG.txt"] = b"tampered\n"
        bad = self.tmpdir / "tampered-file.hzzsalg"
        write_deterministic_zip(bad, entries)
        with self.assertRaises(AlgorithmPackError):
            verify_package(bad, public_key_path=self.public, require_key_id=self.key_id)

    def test_tampered_manifest_fails(self) -> None:
        signed = self._signed_official()
        entries = read_zip_entries(signed, allow_signature=True)
        manifest = json.loads(entries["manifest.json"].decode("utf-8"))
        manifest["description"] = "mutated description"
        entries["manifest.json"] = (
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        ).encode("utf-8")
        bad = self.tmpdir / "tampered-manifest.hzzsalg"
        write_deterministic_zip(bad, entries)
        with self.assertRaises(AlgorithmPackError):
            verify_package(bad, public_key_path=self.public, require_key_id=self.key_id)

    def test_duplicate_path_rejected(self) -> None:
        path = self.tmpdir / "dup.hzzsalg"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("manifest.json", b"{}")
            # ZipFile allows writing same name twice in some modes; force dual entries.
            archive.writestr("manifest.json", b"{}")
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path, allow_signature=False)

    def test_path_traversal_rejected(self) -> None:
        path = self.tmpdir / "trav.hzzsalg"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("../evil.json", b"{}")
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path, allow_signature=False)

    def test_absolute_path_rejected(self) -> None:
        path = self.tmpdir / "abs.hzzsalg"
        with zipfile.ZipFile(path, "w") as archive:
            # zipfile may normalize; write via ZipInfo
            info = zipfile.ZipInfo("/tmp/evil.json")
            archive.writestr(info, b"{}")
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path, allow_signature=False)

    def test_zip_bomb_total_size_rejected(self) -> None:
        path = self.tmpdir / "bomb.hzzsalg"
        # Highly compressible payload exceeding uncompressed limit.
        payload = b"A" * (MAX_TOTAL_UNCOMPRESSED_BYTES + 1024)
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", payload)
        with self.assertRaises(AlgorithmPackError):
            read_zip_entries(path, allow_signature=False)

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
        signed = self._signed_official()
        # Ensure filename matches expected package name for catalog entry
        expected = self.tmpdir / package_filename("official-bamboo-baseline", "1.0.0")
        if signed != expected:
            expected.write_bytes(signed.read_bytes())
            signed = expected
        private = load_private_key(private_key_path=self.private, private_key_b64=None)
        from build_algorithm_catalog import algorithm_entry_from_package

        entry = algorithm_entry_from_package(
            signed,
            public_key=self.public,
            public_key_b64_value=None,
            key_id=self.key_id,
            channel="stable",
        )
        stable_payload = build_catalog_payload(
            channel="stable",
            key_id=self.key_id,
            algorithms=[entry],
            generated_at="2026-07-21T00:00:00Z",
        )
        beta_payload = build_catalog_payload(
            channel="beta",
            key_id=self.key_id,
            algorithms=[entry],
            generated_at="2026-07-21T00:00:00Z",
        )
        self.assertEqual(stable_payload["channel"], "stable")
        self.assertEqual(beta_payload["channel"], "beta")
        self.assertNotEqual(
            json.dumps(stable_payload, sort_keys=True),
            json.dumps(beta_payload, sort_keys=True),
        )
        stable_doc = sign_catalog(stable_payload, private, self.key_id)
        beta_doc = sign_catalog(beta_payload, private, self.key_id)
        verify_catalog_document(stable_doc, public_key_path=self.public, require_key_id=self.key_id)
        verify_catalog_document(beta_doc, public_key_path=self.public, require_key_id=self.key_id)

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
        signed = self.tmpdir / package_filename("official-bamboo-baseline", "1.0.1")
        sign_package(
            unsigned,
            signed,
            private_key=load_private_key(private_key_path=self.private, private_key_b64=None),
            key_id=self.key_id,
        )
        result = verify_package(signed, public_key_path=self.public, require_key_id=self.key_id)
        self.assertTrue(result["revoked"])

    def test_publish_dry_run(self) -> None:
        from publish_algorithm_release import main as publish_main

        code = publish_main(
            [
                "--source",
                str(OFFICIAL),
                "--work-dir",
                str(self.tmpdir / "publish"),
                "--private-key",
                str(self.private),
                "--key-id",
                self.key_id,
                "--channel",
                "stable",
                "--generated-at",
                "2026-07-21T00:00:00Z",
            ]
        )
        self.assertEqual(code, 0)
        work = self.tmpdir / "publish"
        signed = work / package_filename("official-bamboo-baseline", "1.0.0")
        self.assertTrue(signed.is_file())
        self.assertTrue((work / "stable.json").is_file())
        self.assertTrue((work / "SHA256SUMS").is_file())
        catalog = json.loads((work / "stable.json").read_text(encoding="utf-8"))
        self.assertIn("catalogSignature", catalog)
        self.assertEqual(catalog["channel"], "stable")
        # dry-run must not require network tokens
        self.assertNotIn("execute", sys.argv)

    def test_private_key_b64_env_style(self) -> None:
        pem = self.private.read_bytes()
        b64 = base64.b64encode(pem).decode("ascii")
        key = load_private_key(private_key_path=None, private_key_b64=b64)
        self.assertIsNotNone(key)


def archive_infolist_reopen(path: Path):
    with zipfile.ZipFile(path, "r") as archive:
        return list(archive.infolist())


if __name__ == "__main__":
    unittest.main()
