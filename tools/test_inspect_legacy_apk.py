#!/usr/bin/env python3
"""Contract tests for the legacy APK inspector."""

from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from inspect_legacy_apk import ArtifactError, inspect_apk, parse_badging


EXPECTED_BADGING = """\
package: name='com.cattailsw.nanidroid' versionCode='6' versionName='open_0.1'
sdkVersion:'9'
targetSdkVersion:'13'
native-code: 'armeabi'
"""


class ParseBadgingTest(unittest.TestCase):
    def test_extracts_the_upgrade_and_sdk_contract(self) -> None:
        self.assertEqual(
            parse_badging(EXPECTED_BADGING),
            {
                "packageName": "com.cattailsw.nanidroid",
                "versionCode": "6",
                "versionName": "open_0.1",
                "minSdk": "9",
                "targetSdk": "13",
                "nativeCode": ["armeabi"],
            },
        )


class InspectApkTest(unittest.TestCase):
    def _write_apk(self, entries: dict[str, bytes]) -> Path:
        path = Path(self.temp_dir.name) / "Nanidroid-debug.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, contents in entries.items():
                archive.writestr(name, contents)
        return path

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)

    def test_accepts_the_expected_legacy_contract(self) -> None:
        apk = self._write_apk(
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex",
                "resources.arsc": b"resources",
                "lib/armeabi/libkawari8.so": b"\x7fELF-kawari",
                "lib/armeabi/libsatoriya.so": b"\x7fELF-satori",
            }
        )

        report = inspect_apk(apk, EXPECTED_BADGING)

        self.assertEqual(report["package"]["targetSdk"], "13")
        self.assertEqual(
            report["nativeLibraries"],
            [
                "lib/armeabi/libkawari8.so",
                "lib/armeabi/libsatoriya.so",
            ],
        )
        self.assertEqual(len(report["sha256"]), 64)

    def test_rejects_a_missing_native_engine(self) -> None:
        apk = self._write_apk(
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex",
                "resources.arsc": b"resources",
                "lib/armeabi/libkawari8.so": b"\x7fELF-kawari",
            }
        )

        with self.assertRaisesRegex(
            ArtifactError, "lib/armeabi/libsatoriya.so"
        ):
            inspect_apk(apk, EXPECTED_BADGING)

    def test_rejects_a_non_elf_native_library(self) -> None:
        apk = self._write_apk(
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex",
                "resources.arsc": b"resources",
                "lib/armeabi/libkawari8.so": b"\x7fELF-kawari",
                "lib/armeabi/libsatoriya.so": b"MZ-windows",
            }
        )

        with self.assertRaisesRegex(ArtifactError, "not an ELF file"):
            inspect_apk(apk, EXPECTED_BADGING)

    def test_rejects_a_non_zip_artifact_cleanly(self) -> None:
        apk = Path(self.temp_dir.name) / "Nanidroid-debug.apk"
        apk.write_bytes(b"not a zip")

        with self.assertRaisesRegex(ArtifactError, "not a valid ZIP"):
            inspect_apk(apk, EXPECTED_BADGING)


if __name__ == "__main__":
    unittest.main()
