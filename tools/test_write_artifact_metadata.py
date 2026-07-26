#!/usr/bin/env python3
"""Contract tests for the release-artifact integrity manifest writer."""

from __future__ import annotations

import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("write_artifact_metadata.py")
SPEC = importlib.util.spec_from_file_location("artifact_metadata", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
metadata = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(metadata)


class ArtifactMetadataTest(unittest.TestCase):
    def test_records_name_size_and_sha256_in_stable_name_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "Nanidroid-debug.apk"
            aab = root / "Nanidroid-debug.aab"
            apk.write_bytes(b"apk payload")
            aab.write_bytes(b"bundle payload")

            report = metadata.build_report([apk, aab])

        self.assertEqual(
            report,
            {
                "artifacts": [
                    {
                        "bytes": len(b"bundle payload"),
                        "name": "Nanidroid-debug.aab",
                        "sha256": hashlib.sha256(b"bundle payload").hexdigest(),
                    },
                    {
                        "bytes": len(b"apk payload"),
                        "name": "Nanidroid-debug.apk",
                        "sha256": hashlib.sha256(b"apk payload").hexdigest(),
                    },
                ],
                "schemaVersion": 1,
            },
        )

    def test_rejects_a_missing_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            absent = Path(temporary) / "missing.aab"
            with self.assertRaisesRegex(metadata.ArtifactMetadataError, "missing"):
                metadata.build_report([absent])


if __name__ == "__main__":
    unittest.main()
