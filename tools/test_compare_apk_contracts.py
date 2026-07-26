#!/usr/bin/env python3
"""Contract tests for comparing legacy and modern APK reports."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from compare_apk_contracts import ContractMismatch, compare_contracts


def report(**overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "artifact": "Nanidroid-debug.apk",
        "bytes": 1_117_391,
        "sha256": "a" * 64,
        "package": {
            "packageName": "com.cattailsw.nanidroid",
            "versionCode": "6",
            "versionName": "open_0.1",
            "minSdk": "9",
            "targetSdk": "13",
            "nativeCode": ["armeabi"],
        },
        "nativeLibraries": [
            "lib/armeabi/libkawari8.so",
            "lib/armeabi/libnarfs.so",
            "lib/armeabi/libsatoriya.so",
        ],
        "requiredEntries": [
            "AndroidManifest.xml",
            "classes.dex",
            "lib/armeabi/libkawari8.so",
            "lib/armeabi/libnarfs.so",
            "lib/armeabi/libsatoriya.so",
            "resources.arsc",
        ],
    }
    value.update(overrides)
    return value


class CompareContractsTest(unittest.TestCase):
    def test_accepts_contract_parity_despite_volatile_artifact_fields(self) -> None:
        legacy = report()
        modern = report(
            artifact="Nanidroid-modern-debug.apk",
            bytes=987_654,
            sha256="b" * 64,
        )

        comparison = compare_contracts(legacy, modern)

        self.assertEqual(comparison["status"], "equivalent-with-approved-target-sdk-upgrade")
        self.assertEqual(comparison["ignoredFields"], ["artifact", "bytes", "sha256"])

    def test_rejects_package_metadata_drift(self) -> None:
        modern_package = dict(report()["package"])
        modern_package["targetSdk"] = "37"

        with self.assertRaisesRegex(ContractMismatch, "package"):
            compare_contracts(report(), report(package=modern_package))

    def test_accepts_only_the_reviewed_target_sdk_upgrade(self) -> None:
        modern_package = dict(report()["package"])
        modern_package["targetSdk"] = "36"
        self.assertEqual(
            "equivalent-with-approved-target-sdk-upgrade",
            compare_contracts(report(), report(package=modern_package))["status"],
        )

    def test_rejects_native_library_drift(self) -> None:
        with self.assertRaisesRegex(ContractMismatch, "nativeLibraries"):
            compare_contracts(
                report(),
                report(nativeLibraries=["lib/armeabi/libkawari8.so"]),
            )

    def test_rejects_required_entry_drift(self) -> None:
        with self.assertRaisesRegex(ContractMismatch, "requiredEntries"):
            compare_contracts(
                report(),
                report(requiredEntries=["AndroidManifest.xml", "classes.dex"]),
            )


if __name__ == "__main__":
    unittest.main()
