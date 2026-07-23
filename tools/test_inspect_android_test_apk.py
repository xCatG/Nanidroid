#!/usr/bin/env python3
"""Contract tests for the D7a Android instrumentation-test APK inspector."""

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from inspect_android_test_apk import (
    ArtifactError,
    inspect_apk,
    parse_badging,
    parse_manifest_tree,
)


EXPECTED_BADGING = """\
package: name='com.cattailsw.nanidroid.test' versionCode='1' versionName='1.0'
sdkVersion:'9'
targetSdkVersion:'13'
"""

EXPECTED_MANIFEST_TREE = """\
N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: package="com.cattailsw.nanidroid.test" (Raw: "com.cattailsw.nanidroid.test")
    E: instrumentation (line=5)
      A: android:name(0x01010003)="android.test.InstrumentationTestRunner" (Raw: "android.test.InstrumentationTestRunner")
      A: android:targetPackage(0x01010021)="com.cattailsw.nanidroid" (Raw: "com.cattailsw.nanidroid")
    E: application (line=9)
      E: uses-library (line=10)
        A: android:name(0x01010003)="android.test.runner" (Raw: "android.test.runner")
"""


class ParseMetadataTest(unittest.TestCase):
    def test_extracts_exact_package_and_sdk_contract(self) -> None:
        self.assertEqual(
            {
                "packageName": "com.cattailsw.nanidroid.test",
                "minSdk": "9",
                "targetSdk": "13",
            },
            parse_badging(EXPECTED_BADGING),
        )

    def test_extracts_exact_instrumentation_contract(self) -> None:
        self.assertEqual(
            {
                "runner": "android.test.InstrumentationTestRunner",
                "targetPackage": "com.cattailsw.nanidroid",
                "usesLibraries": ["android.test.runner"],
            },
            parse_manifest_tree(EXPECTED_MANIFEST_TREE),
        )


class InspectApkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)

    def _write_apk(self, entries: dict[str, bytes]) -> Path:
        path = Path(self.temp_dir.name) / "Nanidroid-debug-androidTest.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, contents in entries.items():
                archive.writestr(name, contents)
        return path

    def test_accepts_the_exact_headless_platform_runner_contract(self) -> None:
        apk = self._write_apk(
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex",
            }
        )

        report = inspect_apk(apk, EXPECTED_BADGING, EXPECTED_MANIFEST_TREE)

        self.assertEqual(
            "android.test.InstrumentationTestRunner",
            report["instrumentation"]["runner"],
        )
        self.assertEqual([], report["nativeLibraries"])
        self.assertEqual(64, len(report["sha256"]))

    def test_rejects_the_wrong_target_package(self) -> None:
        apk = self._write_apk(
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex",
            }
        )
        wrong_tree = EXPECTED_MANIFEST_TREE.replace(
            '"com.cattailsw.nanidroid" (Raw: "com.cattailsw.nanidroid")',
            '"example.wrong" (Raw: "example.wrong")',
        )

        with self.assertRaisesRegex(ArtifactError, "instrumentation metadata changed"):
            inspect_apk(apk, EXPECTED_BADGING, wrong_tree)

    def test_rejects_native_payload_in_the_test_apk(self) -> None:
        apk = self._write_apk(
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex",
                "lib/arm64-v8a/libunexpected.so": b"\x7fELF",
            }
        )

        with self.assertRaisesRegex(ArtifactError, "must not package native libraries"):
            inspect_apk(apk, EXPECTED_BADGING, EXPECTED_MANIFEST_TREE)

    def test_rejects_missing_test_dex(self) -> None:
        apk = self._write_apk({"AndroidManifest.xml": b"manifest"})

        with self.assertRaisesRegex(ArtifactError, "classes.dex"):
            inspect_apk(apk, EXPECTED_BADGING, EXPECTED_MANIFEST_TREE)


if __name__ == "__main__":
    unittest.main()
