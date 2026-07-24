#!/usr/bin/env python3
"""Validate and inventory the headless D7 Android test APK."""

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
    "packageName": "com.cattailsw.nanidroid.test",
    "minSdk": "9",
    "targetSdk": "13",
}
EXPECTED_INSTRUMENTATION = {
    "runner": "android.test.InstrumentationTestRunner",
    "targetPackage": "com.cattailsw.nanidroid",
    "usesLibraries": ["android.test.runner"],
}
REQUIRED_ENTRIES = {"AndroidManifest.xml", "classes.dex"}
REQUIRED_DEX_MARKERS = (
    "Lcom/cattailsw/nanidroid/SurfaceRenderingCharacterizationTest;",
    "testRequiredMigrationInvariant_baseSurfaceUsesUpperLeftColorKeyAndPaddedFallback",
    "testRequiredMigrationInvariant_elementSurfaceComposesDeclaredLayersAtOffsets",
    "Lcom/cattailsw/nanidroid/SurfaceAnimationExecutionCharacterizationTest;",
    "testRequiredMigrationInvariant_animationAssemblesFramesInOrderWithExactDurationsAndPixels",
    "testRequiredMigrationInvariant_viewBindsResetsAndDispatchesSingleTalkingAnimation",
    "Lcom/cattailsw/nanidroid/install/NarFilesystemInspectorInstrumentationTest;",
    "testArm64NativeFilesystemContract",
)


class ArtifactError(ValueError):
    """The test APK does not satisfy the exact D7 runner contract."""


def _match(pattern: str, text: str, description: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    if match is None:
        raise ArtifactError(f"aapt output does not declare {description}")
    return match.group(1)


def parse_badging(output: str) -> dict[str, str]:
    """Extract the stable test package and SDK contract."""
    return {
        "packageName": _match(
            r"^package:.*\bname='([^']+)'", output, "package name"
        ),
        "minSdk": _match(r"^sdkVersion:'([^']+)'", output, "minimum SDK"),
        "targetSdk": _match(
            r"^targetSdkVersion:'([^']+)'", output, "target SDK"
        ),
    }


def _element_section(output: str, element: str) -> str:
    matches = list(re.finditer(
        rf"^(?P<indent>\s*)E: {re.escape(element)}\b.*?"
        rf"(?=^(?P=indent)E: |\Z)",
        output,
        re.MULTILINE | re.DOTALL,
    ))
    if len(matches) != 1:
        raise ArtifactError(
            f"manifest must declare exactly one {element}; found {len(matches)}"
        )
    return matches[0].group(0)


def _android_name(section: str, description: str) -> str:
    return _match(
        r'^\s*A: android:name(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"',
        section,
        description,
    )


def parse_manifest_tree(output: str) -> dict[str, str | list[str]]:
    """Extract instrumentation and platform test-library metadata from xmltree."""
    instrumentation = _element_section(output, "instrumentation")
    uses_libraries = re.findall(
        r'^\s*E: uses-library\b.*?\n'
        r'\s*A: android:name(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"',
        output,
        re.MULTILINE,
    )
    return {
        "runner": _android_name(instrumentation, "instrumentation runner"),
        "targetPackage": _match(
            r'^\s*A: android:targetPackage(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"',
            instrumentation,
            "instrumentation target package",
        ),
        "usesLibraries": sorted(uses_libraries),
    }


def _fail(message: str) -> NoReturn:
    raise ArtifactError(message)


def inspect_apk(
    apk: Path, badging: str, manifest_tree: str
) -> dict[str, object]:
    """Return an artifact report, or raise when the contract is violated."""
    package = parse_badging(badging)
    if package != EXPECTED_PACKAGE:
        _fail(f"package metadata changed: expected {EXPECTED_PACKAGE}, got {package}")
    instrumentation = parse_manifest_tree(manifest_tree)
    if instrumentation != EXPECTED_INSTRUMENTATION:
        _fail(
            "instrumentation metadata changed: "
            f"expected {EXPECTED_INSTRUMENTATION}, got {instrumentation}"
        )

    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)

    try:
        with zipfile.ZipFile(apk) as archive:
            entries = set(archive.namelist())
            missing = sorted(REQUIRED_ENTRIES - entries)
            if missing:
                _fail("test APK is missing required entries: " + ", ".join(missing))
            dex = archive.read("classes.dex")
            missing_markers = [
                marker
                for marker in REQUIRED_DEX_MARKERS
                if marker.encode("ascii") not in dex
            ]
            if missing_markers:
                _fail(
                    "test APK is missing required D7 test marker(s): "
                    + ", ".join(missing_markers)
                )
            native_libraries = sorted(
                name
                for name in entries
                if name.startswith("lib/") and name.endswith(".so")
            )
            if native_libraries:
                _fail(
                    "test APK must not package native libraries: "
                    + ", ".join(native_libraries)
                )
    except zipfile.BadZipFile as error:
        raise ArtifactError(f"{apk.name} is not a valid ZIP") from error

    return {
        "artifact": apk.name,
        "bytes": apk.stat().st_size,
        "sha256": digest.hexdigest(),
        "package": package,
        "instrumentation": instrumentation,
        "nativeLibraries": native_libraries,
        "requiredDexMarkers": list(REQUIRED_DEX_MARKERS),
        "requiredEntries": sorted(REQUIRED_ENTRIES),
    }


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--aapt",
        type=Path,
        default=Path("/opt/android-sdk/build-tools/36.0.0/aapt"),
    )
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    try:
        badging = subprocess.run(
            [str(args.aapt), "dump", "badging", str(args.apk)],
            check=True,
            capture_output=True,
            text=True,
        )
        manifest_tree = subprocess.run(
            [
                str(args.aapt),
                "dump",
                "xmltree",
                str(args.apk),
                "AndroidManifest.xml",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        report = inspect_apk(args.apk, badging.stdout, manifest_tree.stdout)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except subprocess.CalledProcessError as error:
        print(f"Android test APK validation failed: {error}", file=sys.stderr)
        if error.stderr:
            print(f"aapt error output:\n{error.stderr}", file=sys.stderr)
        return 1
    except (ArtifactError, OSError) as error:
        print(f"Android test APK validation failed: {error}", file=sys.stderr)
        return 1

    print(
        f"validated {report['artifact']} "
        f"({report['bytes']} bytes, sha256 {report['sha256']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
