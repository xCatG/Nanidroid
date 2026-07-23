#!/usr/bin/env python3
"""Tests for exact Gradle APK native-payload verification."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_apk_native_payload import PayloadError, verify_payload


class VerifyPayloadTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.candidate = self.root / "candidate" / "armeabi"
        self.candidate.mkdir(parents=True)
        (self.candidate / "libkawari8.so").write_bytes(b"kawari")
        (self.candidate / "libnarfs.so").write_bytes(b"narfs")
        (self.candidate / "libsatoriya.so").write_bytes(b"satori")

    def _apk(self, entries: dict[str, bytes]) -> Path:
        apk = self.root / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        return apk

    def test_accepts_byte_identical_candidate_entries(self) -> None:
        report = verify_payload(
            self._apk(
                {
                    "lib/armeabi/libkawari8.so": b"kawari",
                    "lib/armeabi/libnarfs.so": b"narfs",
                    "lib/armeabi/libsatoriya.so": b"satori",
                }
            ),
            self.root / "candidate",
        )

        self.assertEqual(report["status"], "identical")
        self.assertEqual(
            sorted(report["sha256"]),
            [
                "lib/armeabi/libkawari8.so",
                "lib/armeabi/libnarfs.so",
                "lib/armeabi/libsatoriya.so",
            ],
        )

    def test_rejects_a_missing_apk_entry(self) -> None:
        with self.assertRaisesRegex(PayloadError, "native entries changed"):
            verify_payload(
                self._apk({"lib/armeabi/libkawari8.so": b"kawari"}),
                self.root / "candidate",
            )

    def test_rejects_a_byte_mismatch(self) -> None:
        with self.assertRaisesRegex(PayloadError, "payload differs"):
            verify_payload(
                self._apk(
                    {
                        "lib/armeabi/libkawari8.so": b"changed",
                        "lib/armeabi/libnarfs.so": b"narfs",
                        "lib/armeabi/libsatoriya.so": b"satori",
                    }
                ),
                self.root / "candidate",
            )

    def test_report_is_json_serializable(self) -> None:
        report = verify_payload(
            self._apk(
                {
                    "lib/armeabi/libkawari8.so": b"kawari",
                    "lib/armeabi/libnarfs.so": b"narfs",
                    "lib/armeabi/libsatoriya.so": b"satori",
                }
            ),
            self.root / "candidate",
        )
        json.dumps(report)


if __name__ == "__main__":
    unittest.main()
