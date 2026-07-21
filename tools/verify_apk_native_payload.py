#!/usr/bin/env python3
"""Verify that a Gradle APK contains the parity-approved native bytes."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from typing import NoReturn


EXPECTED_PAYLOAD = {
    "lib/armeabi/libkawari8.so": "armeabi/libkawari8.so",
    "lib/armeabi/libsatoriya.so": "armeabi/libsatoriya.so",
}


class PayloadError(ValueError):
    """The APK does not contain the approved native payload."""


def _fail(message: str) -> NoReturn:
    raise PayloadError(message)


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def verify_payload(apk: Path, candidate_root: Path) -> dict[str, object]:
    """Compare exact APK entry bytes with the approved candidate files."""
    if not candidate_root.is_dir():
        _fail(f"candidate native directory does not exist: {candidate_root}")
    expected_entries = sorted(EXPECTED_PAYLOAD)
    try:
        with zipfile.ZipFile(apk) as archive:
            observed_entries = sorted(
                name
                for name in archive.namelist()
                if name.startswith("lib/") and name.endswith(".so")
            )
            if observed_entries != expected_entries:
                _fail(
                    "APK native entries changed: "
                    f"expected {expected_entries}, got {observed_entries}"
                )

            hashes: dict[str, str] = {}
            for apk_entry, candidate_relative in EXPECTED_PAYLOAD.items():
                candidate = candidate_root / candidate_relative
                if not candidate.is_file():
                    _fail(f"candidate native library does not exist: {candidate}")
                apk_bytes = archive.read(apk_entry)
                candidate_bytes = candidate.read_bytes()
                if apk_bytes != candidate_bytes:
                    _fail(
                        f"APK native payload differs for {apk_entry}: "
                        f"candidate sha256 {_sha256(candidate_bytes)}, "
                        f"APK sha256 {_sha256(apk_bytes)}"
                    )
                hashes[apk_entry] = _sha256(apk_bytes)
    except (OSError, zipfile.BadZipFile) as error:
        raise PayloadError(f"cannot inspect APK {apk}: {error}") from error

    return {
        "status": "identical",
        "candidateRoot": str(candidate_root),
        "sha256": hashes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify_payload(args.apk, args.candidate_root)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except PayloadError as error:
        print(f"APK native payload validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
