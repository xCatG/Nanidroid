#!/usr/bin/env python3
"""Write a deterministic integrity manifest for distributable Android artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import NoReturn, Sequence


class ArtifactMetadataError(ValueError):
    """An input artifact cannot safely be described in the integrity manifest."""


def _fail(message: str) -> NoReturn:
    raise ArtifactMetadataError(message)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_report(artifacts: Sequence[Path]) -> dict[str, object]:
    """Describe regular files by name, byte length and SHA-256 in stable order."""
    entries: list[dict[str, object]] = []
    names: set[str] = set()
    for artifact in artifacts:
        if not artifact.is_file():
            _fail(f"artifact is missing or not a regular file: {artifact}")
        if artifact.name in names:
            _fail(f"artifact names must be unique: {artifact.name}")
        names.add(artifact.name)
        entries.append(
            {
                "name": artifact.name,
                "bytes": artifact.stat().st_size,
                "sha256": _sha256(artifact),
            }
        )
    if not entries:
        _fail("at least one artifact is required")
    return {"schemaVersion": 1, "artifacts": sorted(entries, key=lambda item: item["name"])}


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    try:
        report = build_report(args.artifact)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except (ArtifactMetadataError, OSError) as error:
        print(f"artifact metadata failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
