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
    "lib/armeabi/libnarfs.so",
    "lib/armeabi/libsatoriya.so",
}
EXPECTED_NATIVE_LIBRARIES = sorted(
    entry for entry in REQUIRED_ENTRIES if entry.startswith("lib/")
)
REFERENCE_PROJECT_NATIVE_LIBRARIES = [
    "lib/armeabi/libkawari8.so",
    "lib/armeabi/libsatoriya.so",
]
COMPOSE_GRAPHICS_NATIVE_LIBRARIES = [
    "lib/arm64-v8a/libandroidx.graphics.path.so",
    "lib/armeabi-v7a/libandroidx.graphics.path.so",
    "lib/x86/libandroidx.graphics.path.so",
    "lib/x86_64/libandroidx.graphics.path.so",
]
COMPOSE_GRAPHICS_NATIVE_CODES = [
    "arm64-v8a",
    "armeabi",
    "armeabi-v7a",
    "x86",
    "x86_64",
]
FIREBASE_CRASHLYTICS_NATIVE_LIBRARIES = [
    "lib/arm64-v8a/libdatastore_shared_counter.so",
    "lib/armeabi-v7a/libdatastore_shared_counter.so",
    "lib/x86/libdatastore_shared_counter.so",
    "lib/x86_64/libdatastore_shared_counter.so",
]


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


def inspect_apk(
    apk: Path,
    badging: str,
    expected_target_sdk: str = "13",
    expected_min_sdk: str = "9",
    allow_compose_graphics_runtime: bool = False,
    allow_firebase_crashlytics_runtime: bool = False,
    frozen_reference_project: bool = False,
) -> dict[str, object]:
    """Return an artifact report, or raise when the contract is violated."""
    package = parse_badging(badging)
    expected_package = dict(
        EXPECTED_PACKAGE,
        minSdk=expected_min_sdk,
        targetSdk=expected_target_sdk,
    )
    expected_native_libraries = (
        REFERENCE_PROJECT_NATIVE_LIBRARIES
        if frozen_reference_project
        else EXPECTED_NATIVE_LIBRARIES
    )
    if allow_compose_graphics_runtime:
        expected_package["nativeCode"] = COMPOSE_GRAPHICS_NATIVE_CODES
        expected_native_libraries = sorted(
            EXPECTED_NATIVE_LIBRARIES + COMPOSE_GRAPHICS_NATIVE_LIBRARIES
        )
    if allow_firebase_crashlytics_runtime:
        expected_native_libraries = sorted(
            expected_native_libraries + FIREBASE_CRASHLYTICS_NATIVE_LIBRARIES
        )
    if package != expected_package:
        _fail(f"package metadata changed: expected {expected_package}, got {package}")

    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)

    try:
        with zipfile.ZipFile(apk) as archive:
            entries = set(archive.namelist())
            required_entries = {
                "AndroidManifest.xml", "classes.dex", "resources.arsc", *expected_native_libraries
            }
            missing = sorted(required_entries - entries)
            if missing:
                _fail("APK is missing required entries: " + ", ".join(missing))

            native_libraries = sorted(
                name
                for name in entries
                if name.startswith("lib/") and name.endswith(".so")
            )
            if native_libraries != expected_native_libraries:
                _fail(
                    "APK native entries changed: "
                    f"expected {expected_native_libraries}, got {native_libraries}"
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
        "requiredEntries": sorted(required_entries),
    }


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--aapt",
        type=Path,
        default=Path("/opt/android-sdk/build-tools/25.0.3/aapt"),
    )
    parser.add_argument(
        "--allow-firebase-crashlytics-runtime",
        action="store_true",
        help="allow only the audited Firebase DataStore native runtime libraries",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-min-sdk", default="9")
    parser.add_argument("--expected-target-sdk", default="13")
    parser.add_argument(
        "--frozen-reference-project",
        action="store_true",
        help="validate the exact pre-NarFS 027c971 Ant payload",
    )
    parser.add_argument(
        "--allow-compose-graphics-runtime",
        action="store_true",
        help="allow only the audited AndroidX Compose graphics-path libraries",
    )
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
        report = inspect_apk(
            args.apk,
            completed.stdout,
            args.expected_target_sdk,
            args.expected_min_sdk,
            args.allow_compose_graphics_runtime,
            args.allow_firebase_crashlytics_runtime,
            getattr(args, "frozen_reference_project", False),
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except subprocess.CalledProcessError as error:
        print(f"legacy APK validation failed: {error}", file=sys.stderr)
        if error.stderr:
            print(f"aapt error output:\n{error.stderr}", file=sys.stderr)
        return 1
    except (ArtifactError, OSError) as error:
        print(f"legacy APK validation failed: {error}", file=sys.stderr)
        return 1

    print(
        f"validated {report['artifact']} "
        f"({report['bytes']} bytes, sha256 {report['sha256']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
