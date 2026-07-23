#!/usr/bin/env python3
"""Contract tests for the opt-in ARM64 emulator APK."""

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_emulator_apk import PayloadError, verify_emulator_apk


EMULATOR_BADGING = """\
package: name='com.cattailsw.nanidroid' versionCode='6' versionName='open_0.1'
sdkVersion:'9'
targetSdkVersion:'13'
native-code: 'armeabi' 'arm64-v8a'
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
            (directory / "libsatoriya.so").write_bytes(b"\x7fELF-satori-" + suffix)

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
            for name in ("libkawari8.so", "libsatoriya.so")
        }

    def test_accepts_exact_additive_abi_profile_and_bytes(self) -> None:
        report = verify_emulator_apk(
            self._apk(self._exact_entries()),
            EMULATOR_BADGING,
            self.legacy_root,
            self.arm64_root,
        )

        self.assertEqual(report["status"], "identical")
        self.assertEqual(report["nativeCode"], ["armeabi", "arm64-v8a"])
        self.assertEqual(len(report["sha256"]), 4)

    def test_rejects_missing_or_extra_native_entries(self) -> None:
        entries = self._exact_entries()
        entries.pop("lib/arm64-v8a/libsatoriya.so")
        with self.assertRaisesRegex(PayloadError, "native entries changed"):
            verify_emulator_apk(
                self._apk(entries), EMULATOR_BADGING, self.legacy_root, self.arm64_root
            )

        entries = self._exact_entries()
        entries["lib/x86_64/libsurprise.so"] = b"\x7fELF-extra"
        with self.assertRaisesRegex(PayloadError, "native entries changed"):
            verify_emulator_apk(
                self._apk(entries), EMULATOR_BADGING, self.legacy_root, self.arm64_root
            )

    def test_rejects_a_payload_byte_mismatch(self) -> None:
        entries = self._exact_entries()
        entries["lib/arm64-v8a/libkawari8.so"] += b"changed"
        with self.assertRaisesRegex(PayloadError, "payload differs"):
            verify_emulator_apk(
                self._apk(entries), EMULATOR_BADGING, self.legacy_root, self.arm64_root
            )

    def test_rejects_badging_without_the_exact_two_abi_profile(self) -> None:
        badging = EMULATOR_BADGING.replace(
            "native-code: 'armeabi' 'arm64-v8a'", "native-code: 'arm64-v8a'"
        )
        with self.assertRaisesRegex(PayloadError, "package metadata changed"):
            verify_emulator_apk(
                self._apk(self._exact_entries()),
                badging,
                self.legacy_root,
                self.arm64_root,
            )


if __name__ == "__main__":
    unittest.main()
