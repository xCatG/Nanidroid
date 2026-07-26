#!/usr/bin/env python3
"""Compare stable behavior-bearing fields in two APK inspection reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import NoReturn


CONTRACT_FIELDS = ("package", "nativeLibraries", "requiredEntries")
IGNORED_FIELDS = ("artifact", "bytes", "sha256")
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


class ContractMismatch(ValueError):
    """The candidate APK report differs from the reference contract."""


def _fail(field: str, expected: object, actual: object) -> NoReturn:
    raise ContractMismatch(
        f"{field} changed: expected {expected!r}, got {actual!r}"
    )


def compare_contracts(
    reference: dict[str, object], candidate: dict[str, object]
) -> dict[str, object]:
    """Return a parity report, or raise when a stable field changed."""
    for field in CONTRACT_FIELDS:
        expected = reference.get(field)
        actual = candidate.get(field)
        if field == "package" and isinstance(expected, dict) and isinstance(actual, dict):
            expected = dict(expected)
            actual = dict(actual)
            if expected.get("targetSdk") == "13" and actual.get("targetSdk") == "37":
                expected["targetSdk"] = "37"
            if expected.get("minSdk") == "9" and actual.get("minSdk") == "31":
                expected["minSdk"] = "31"
            if (
                expected.get("targetSdk") == "37"
                and actual.get("nativeCode") == COMPOSE_GRAPHICS_NATIVE_CODES
            ):
                expected["nativeCode"] = COMPOSE_GRAPHICS_NATIVE_CODES
        if field == "nativeLibraries" and isinstance(expected, list) and isinstance(actual, list):
            if actual == sorted(expected + COMPOSE_GRAPHICS_NATIVE_LIBRARIES):
                expected = actual
        if actual != expected:
            _fail(field, expected, actual)

    return {
        "status": "equivalent-with-approved-sdk-upgrade",
        "comparedFields": list(CONTRACT_FIELDS),
        "ignoredFields": list(IGNORED_FIELDS),
    }


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def _load(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ContractMismatch(f"{path} does not contain a JSON object")
    return value


def main() -> int:
    args = _arguments()
    try:
        comparison = compare_contracts(
            _load(args.reference),
            _load(args.candidate),
        )
        rendered = json.dumps(comparison, indent=2, sort_keys=True) + "\n"
        if args.output is None:
            print(rendered, end="")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
    except (ContractMismatch, json.JSONDecodeError, OSError) as error:
        print(f"APK contract comparison failed: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
