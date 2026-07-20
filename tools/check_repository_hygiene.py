#!/usr/bin/env python3
"""Validate tracked artifacts against PR A modernization policy."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs" / "modernization" / "binary-inventory.json"

FORBIDDEN_PARTS = {
    ".gradle",
    ".idea",
    ".kotlin",
    ".cxx",
    "bin",
    "build",
    "captures",
    "obj",
    "test-results",
}
FORBIDDEN_NAMES = {"local.properties", ".DS_Store", "Thumbs.db"}
FORBIDDEN_SUFFIXES = {
    ".aab",
    ".apk",
    ".ap_",
    ".class",
    ".iml",
    ".log",
    ".o",
    ".o.d",
    ".so.dbg",
}
INVENTORIED_SUFFIXES = {".exe", ".jar", ".so", ".zip"}


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        capture_output=True,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"git ls-files failed: {message}")
    return [item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    tracked = tracked_files()
    failures: list[str] = []

    for relative in tracked:
        path = Path(relative)
        if path.name in FORBIDDEN_NAMES:
            failures.append(f"forbidden tracked file: {relative}")
        if FORBIDDEN_PARTS.intersection(path.parts):
            failures.append(f"forbidden tracked output directory: {relative}")
        if any(relative.endswith(suffix) for suffix in FORBIDDEN_SUFFIXES):
            failures.append(f"forbidden tracked generated artifact: {relative}")

    data = json.loads(INVENTORY.read_text(encoding="utf-8"))
    inventory = {item["path"]: item for item in data["artifacts"]}
    opaque = {
        relative
        for relative in tracked
        if any(relative.lower().endswith(suffix) for suffix in INVENTORIED_SUFFIXES)
    }

    missing_entries = sorted(opaque - set(inventory))
    stale_entries = sorted(set(inventory) - opaque)
    for relative in missing_entries:
        failures.append(f"tracked opaque artifact missing from inventory: {relative}")
    for relative in stale_entries:
        failures.append(f"inventory entry is not a tracked opaque artifact: {relative}")

    for relative, expected in inventory.items():
        path = ROOT / relative
        if not path.is_file():
            continue
        actual_size = path.stat().st_size
        actual_hash = sha256(path)
        if actual_size != expected["bytes"]:
            failures.append(
                f"size changed for {relative}: expected {expected['bytes']}, got {actual_size}"
            )
        if actual_hash != expected["sha256"]:
            failures.append(
                f"sha256 changed for {relative}: expected {expected['sha256']}, got {actual_hash}"
            )

    if failures:
        print("Repository hygiene check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(
        f"Repository hygiene check passed: {len(tracked)} tracked files, "
        f"{len(inventory)} inventoried opaque artifacts."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
