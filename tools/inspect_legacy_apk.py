#!/usr/bin/env python3
"""Validate and inventory the APK produced by the frozen Ant build."""

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


EXPECTED_PACKAGE = {
    "packageName": "com.cattailsw.nanidroid",
    "versionCode": "6",
    "versionName": "open_0.1",
    "minSdk": "9",
    "targetSdk": "13",
    "nativeCode": ["armeabi"],
}

REQUIRED_ENTRIES = {
    "AndroidManifest.xml",
    "classes.dex",
    "resources.arsc",
    "lib/armeabi/libkawari8.so",
    "lib/armeabi/libsatoriya.so",
}


class ArtifactError(ValueError):
    """The APK does not satisfy the frozen legacy artifact contract."""


def _match(pattern: str, text: str, description: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    if match is None:
        raise ArtifactError(f"aapt output does not declare {description}")
    return match.group(1)


def parse_badging(output: str) -> dict[str, str | list[str]]:
    """Extract the stable package contract from ``aapt dump badging``."""
    native_line = _match(r"^native-code:\s*(.+)$", output, "native ABIs")
    return {
        "packageName": _match(r"^package:.*\bname='([^']+)'", output, "package name"),
        "versionCode": _match(
            r"^package:.*\bversionCode='([^']+)'", output, "version code"
        ),
        "versionName": _match(
            r"^package:.*\bversionName='([^']+)'", output, "version name"
        ),
        "minSdk": _match(r"^sdkVersion:'([^']+)'", output, "minimum SDK"),
        "targetSdk": _match(
            r"^targetSdkVersion:'([^']+)'", output, "target SDK"
        ),
        "nativeCode": re.findall(r"'([^']+)'", native_line),
    }


def _fail(message: str) -> NoReturn:
    raise ArtifactError(message)


def inspect_apk(apk: Path, badging: str) -> dict[str, object]:
    """Return an artifact report, or raise when the contract is violated."""
    package = parse_badging(badging)
    if package != EXPECTED_PACKAGE:
        _fail(f"package metadata changed: expected {EXPECTED_PACKAGE}, got {package}")

    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)

    try:
        with zipfile.ZipFile(apk) as archive:
            entries = set(archive.namelist())
            missing = sorted(REQUIRED_ENTRIES - entries)
            if missing:
                _fail("APK is missing required entries: " + ", ".join(missing))

            native_libraries = sorted(
                name
                for name in entries
                if name.startswith("lib/") and name.endswith(".so")
            )
            for library in native_libraries:
                with archive.open(library) as stream:
                    if stream.read(4) != b"\x7fELF":
                        _fail(f"{library} is not an ELF file")
    except zipfile.BadZipFile as error:
        raise ArtifactError(f"{apk.name} is not a valid ZIP") from error

    return {
        "artifact": apk.name,
        "bytes": apk.stat().st_size,
        "sha256": digest.hexdigest(),
        "package": package,
        "nativeLibraries": native_libraries,
        "requiredEntries": sorted(REQUIRED_ENTRIES),
    }


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--aapt",
        type=Path,
        default=Path("/opt/android-sdk/build-tools/25.0.3/aapt"),
    )
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    try:
        completed = subprocess.run(
            [str(args.aapt), "dump", "badging", str(args.apk)],
            check=True,
            capture_output=True,
            text=True,
        )
        report = inspect_apk(args.apk, completed.stdout)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except (ArtifactError, OSError, subprocess.CalledProcessError) as error:
        print(f"legacy APK validation failed: {error}", file=sys.stderr)
        return 1

    print(
        f"validated {report['artifact']} "
        f"({report['bytes']} bytes, sha256 {report['sha256']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
