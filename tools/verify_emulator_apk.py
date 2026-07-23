#!/usr/bin/env python3
"""Verify the exact additive native contract of the opt-in emulator APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import NoReturn

from inspect_legacy_apk import parse_badging


EXPECTED_PACKAGE = {
    "packageName": "com.cattailsw.nanidroid",
    "versionCode": "6",
    "versionName": "open_0.1",
    "minSdk": "9",
    "targetSdk": "13",
    "nativeCode": ["arm64-v8a", "armeabi"],
}
EXPECTED_PAYLOAD = {
    "lib/armeabi/libkawari8.so": ("legacy", "armeabi/libkawari8.so"),
    "lib/armeabi/libsatoriya.so": ("legacy", "armeabi/libsatoriya.so"),
    "lib/arm64-v8a/libkawari8.so": ("arm64", "arm64-v8a/libkawari8.so"),
    "lib/arm64-v8a/libsatoriya.so": ("arm64", "arm64-v8a/libsatoriya.so"),
}


class PayloadError(ValueError):
    """The emulator APK does not satisfy the exact additive ABI contract."""


def _fail(message: str) -> NoReturn:
    raise PayloadError(message)


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _approved_arm64_hashes(contract: Path) -> dict[str, str]:
    expected_paths = {
        "arm64-v8a/libkawari8.so",
        "arm64-v8a/libsatoriya.so",
    }
    try:
        report = json.loads(contract.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PayloadError(
            f"cannot read ARM64 native contract {contract}: {error}"
        ) from error
    hashes = report.get("sha256") if isinstance(report, dict) else None
    if not isinstance(hashes, dict) or set(hashes) != expected_paths or any(
        not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None
        for value in hashes.values()
    ):
        _fail(
            "ARM64 native contract hashes changed: expected exact paths "
            f"{sorted(expected_paths)}, got {hashes}"
        )
    return hashes


def verify_emulator_apk(
    apk: Path,
    badging: str,
    legacy_root: Path,
    arm64_root: Path,
    arm64_contract: Path,
) -> dict[str, object]:
    """Require exactly two engines in each approved ABI and identical bytes."""
    package = parse_badging(badging)
    if package != EXPECTED_PACKAGE:
        _fail(f"package metadata changed: expected {EXPECTED_PACKAGE}, got {package}")

    roots = {"legacy": legacy_root, "arm64": arm64_root}
    approved_arm64 = _approved_arm64_hashes(arm64_contract)
    for relative, expected_hash in approved_arm64.items():
        candidate = arm64_root / relative
        if not candidate.is_file():
            _fail(f"candidate native library does not exist: {candidate}")
        actual_hash = _sha256(candidate.read_bytes())
        if actual_hash != expected_hash:
            _fail(
                f"ARM64 native contract hash differs for {relative}: "
                f"expected {expected_hash}, got {actual_hash}"
            )
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
            for apk_entry, (root_name, relative) in EXPECTED_PAYLOAD.items():
                candidate = roots[root_name] / relative
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
        "nativeCode": package["nativeCode"],
        "nativeLibraries": expected_entries,
        "sha256": hashes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--legacy-root", type=Path, required=True)
    parser.add_argument("--arm64-root", type=Path, required=True)
    parser.add_argument("--arm64-contract", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        completed = subprocess.run(
            [str(args.aapt), "dump", "badging", str(args.apk)],
            check=True,
            capture_output=True,
            text=True,
        )
        report = verify_emulator_apk(
            args.apk,
            completed.stdout,
            args.legacy_root,
            args.arm64_root,
            args.arm64_contract,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except subprocess.CalledProcessError as error:
        detail = error.stderr.strip() if error.stderr else str(error)
        print(f"emulator APK validation failed: {detail}", file=sys.stderr)
        return 1
    except (PayloadError, OSError) as error:
        print(f"emulator APK validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
