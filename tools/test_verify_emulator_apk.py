#!/usr/bin/env python3
"""Contract tests for the opt-in ARM64 emulator APK."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_emulator_apk import PayloadError, verify_emulator_apk


EMULATOR_BADGING = """\
package: name='com.cattailsw.nanidroid' versionCode='6' versionName='open_0.1'
sdkVersion:'9'
targetSdkVersion:'13'
native-code: 'arm64-v8a' 'armeabi'
"""


class VerifyEmulatorApkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.legacy_root = self.root / "legacy"
        self.arm64_root = self.root / "emulator"
        for root, abi, suffix in (
            (self.legacy_root, "armeabi", b"32"),
            (self.arm64_root, "arm64-v8a", b"64"),
        ):
            directory = root / abi
            directory.mkdir(parents=True)
            (directory / "libkawari8.so").write_bytes(b"\x7fELF-kawari-" + suffix)
            (directory / "libnarfs.so").write_bytes(b"\x7fELF-narfs-" + suffix)
            (directory / "libsatoriya.so").write_bytes(b"\x7fELF-satori-" + suffix)
        self.arm64_contract = self.root / "native-contract.json"
        self._write_arm64_contract()

    def _write_arm64_contract(self) -> None:
        hashes = {
            f"arm64-v8a/{name}": hashlib.sha256(
                (self.arm64_root / "arm64-v8a" / name).read_bytes()
            ).hexdigest()
            for name in ("libkawari8.so", "libnarfs.so", "libsatoriya.so")
        }
        self.arm64_contract.write_text(
            json.dumps({"sha256": hashes}), encoding="utf-8"
        )

    def _apk(self, entries: dict[str, bytes]) -> Path:
        apk = self.root / "Nanidroid-emulator.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex")
            archive.writestr("resources.arsc", b"resources")
            for name, value in entries.items():
                archive.writestr(name, value)
        return apk

    def _exact_entries(self) -> dict[str, bytes]:
        return {
            f"lib/{abi}/{name}": (root / abi / name).read_bytes()
            for root, abi in (
                (self.legacy_root, "armeabi"),
                (self.arm64_root, "arm64-v8a"),
            )
            for name in ("libkawari8.so", "libnarfs.so", "libsatoriya.so")
        }

    def test_accepts_exact_additive_abi_profile_and_bytes(self) -> None:
        report = verify_emulator_apk(
            self._apk(self._exact_entries()),
            EMULATOR_BADGING,
            self.legacy_root,
            self.arm64_root,
            self.arm64_contract,
        )

        self.assertEqual(report["status"], "identical")
        self.assertEqual(report["nativeCode"], ["arm64-v8a", "armeabi"])
        self.assertEqual(len(report["sha256"]), 6)

    def test_rejects_missing_or_extra_native_entries(self) -> None:
        entries = self._exact_entries()
        entries.pop("lib/arm64-v8a/libsatoriya.so")
        with self.assertRaisesRegex(PayloadError, "native entries changed"):
            verify_emulator_apk(
                self._apk(entries),
                EMULATOR_BADGING,
                self.legacy_root,
                self.arm64_root,
                self.arm64_contract,
            )

        entries = self._exact_entries()
        entries["lib/x86_64/libsurprise.so"] = b"\x7fELF-extra"
        with self.assertRaisesRegex(PayloadError, "native entries changed"):
            verify_emulator_apk(
                self._apk(entries),
                EMULATOR_BADGING,
                self.legacy_root,
                self.arm64_root,
                self.arm64_contract,
            )

    def test_rejects_a_payload_byte_mismatch(self) -> None:
        entries = self._exact_entries()
        entries["lib/arm64-v8a/libkawari8.so"] += b"changed"
        with self.assertRaisesRegex(PayloadError, "payload differs"):
            verify_emulator_apk(
                self._apk(entries),
                EMULATOR_BADGING,
                self.legacy_root,
                self.arm64_root,
                self.arm64_contract,
            )

    def test_rejects_badging_without_the_exact_two_abi_profile(self) -> None:
        badging = EMULATOR_BADGING.replace(
            "native-code: 'arm64-v8a' 'armeabi'", "native-code: 'arm64-v8a'"
        )
        with self.assertRaisesRegex(PayloadError, "package metadata changed"):
            verify_emulator_apk(
                self._apk(self._exact_entries()),
                badging,
                self.legacy_root,
                self.arm64_root,
                self.arm64_contract,
            )

    def test_rejects_candidate_and_apk_mutated_after_native_inspection(self) -> None:
        changed = b"\x7fELF-mutated-after-inspection"
        (self.arm64_root / "arm64-v8a" / "libkawari8.so").write_bytes(changed)
        entries = self._exact_entries()

        with self.assertRaisesRegex(PayloadError, "native contract hash differs"):
            verify_emulator_apk(
                self._apk(entries),
                EMULATOR_BADGING,
                self.legacy_root,
                self.arm64_root,
                self.arm64_contract,
            )


if __name__ == "__main__":
    unittest.main()
