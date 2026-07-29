#!/usr/bin/env python3
"""Reject incomplete or altered inputs for the frozen Ant reference lane."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


REFERENCE_COMMIT = "027c971"
REFERENCE_FILE_COUNT = 327
REFERENCE_MANIFEST_SHA256 = "f0a53396003999164465a673a5595ab9bd87fb3037e77f78558822c5a623bca6"
ADMOB_SOURCE = "0390a86^:libs/GoogleAdMobAdsSdk-6.0.1.jar"
ADMOB_SHA256 = "378f6757e9d881af1369377da431651e12a3c08fa8e565096e268dacacb491af"
ADMOB_NAME = "GoogleAdMobAdsSdk-6.0.1.jar"


def manifest(root: Path) -> str:
    entries = sorted(
        f"{path.relative_to(root).as_posix()}  {hashlib.sha256(path.read_bytes()).hexdigest()}"
        for path in root.rglob("*")
        if path.is_file()
    )
    if len(entries) != REFERENCE_FILE_COUNT:
        raise ValueError(f"expected {REFERENCE_FILE_COUNT} reference files, found {len(entries)}")
    return hashlib.sha256("\n".join(entries).encode("utf-8")).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    parser.add_argument("third_party", type=Path)
    args = parser.parse_args()
    if not args.project.is_dir():
        parser.error(f"missing frozen reference project: {args.project}")
    if not args.third_party.is_dir():
        parser.error(f"missing frozen reference third-party directory: {args.third_party}")
    try:
        observed_manifest = manifest(args.project)
    except ValueError as error:
        parser.error(str(error))
    if observed_manifest != REFERENCE_MANIFEST_SHA256:
        parser.error(f"reference project drifted from {REFERENCE_COMMIT}: {observed_manifest}")
    jar = args.third_party / ADMOB_NAME
    if not jar.is_file():
        parser.error(f"missing declared historical dependency: {jar}")
    observed_jar = hashlib.sha256(jar.read_bytes()).hexdigest()
    if observed_jar != ADMOB_SHA256:
        parser.error(f"historical dependency drifted from {ADMOB_SOURCE}: {observed_jar}")
    extras = sorted(path.name for path in args.third_party.iterdir() if path.is_file() and path.name != ADMOB_NAME)
    if extras:
        parser.error(f"unexpected third-party reference files: {extras}")
    print(f"validated frozen reference project {REFERENCE_COMMIT} ({REFERENCE_FILE_COUNT} files) and {ADMOB_NAME} from {ADMOB_SOURCE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
