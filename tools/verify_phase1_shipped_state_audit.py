#!/usr/bin/env python3
"""Verify the Phase 1 shipped-state audit and compatibility decision."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "modernization" / "phase1-shipped-state-ledger.json"
ALLOWED_PATHS = {"A", "B", "C"}
ALLOWED_CHANNEL_STATUS = {"none", "state-capable", "unknown"}
REQUIRED_CHANNEL_IDS = {
    "github-releases",
    "github-actions-apk-after-writer-epoch",
    "google-play",
    "f-droid",
    "website",
    "drive-discord-direct-share",
    "other",
}


def load_ledger(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_ledger(data: dict[str, Any], repo_root: Path) -> list[str]:
    failures: list[str] = []
    if data.get("schemaVersion") != 1:
        failures.append("schemaVersion must be 1")

    decision = data.get("decision", {})
    path = decision.get("path")
    if path not in ALLOWED_PATHS:
        failures.append("decision.path must resolve to A, B, or C")

    distribution = data.get("distribution", {})
    attestation = distribution.get("ownerAttestation", {})
    channels = distribution.get("channels", [])
    channel_ids = [channel.get("id") for channel in channels]
    if set(channel_ids) != REQUIRED_CHANNEL_IDS or len(channel_ids) != len(set(channel_ids)):
        failures.append("distribution channel IDs must exactly match the required unique set")
    if path == "A":
        if attestation.get("confirmed") is not True:
            failures.append("Path A requires confirmed owner attestation")
        if attestation.get("stateCapableApkDistributed") is not False:
            failures.append("Path A forbids state-capable APK distribution")
        if not channels or any(channel.get("status") != "none" for channel in channels):
            failures.append("Path A requires every distribution channel to be none")
    if path == "B" and decision.get("sequentialUpgradeEnforced") is not True:
        failures.append("Path B requires enforced sequential upgrade")
    if path == "C" and not decision.get("compatibilityRemovalFloor"):
        failures.append("Path C requires compatibilityRemovalFloor")

    for channel in channels:
        if channel.get("status") not in ALLOWED_CHANNEL_STATUS:
            failures.append(f"unknown distribution status for {channel.get('id')}")
    return failures


def main() -> int:
    data = load_ledger(LEDGER)
    failures = validate_ledger(data, ROOT)
    if failures:
        print("Phase 1 shipped-state audit verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    path = data.get("decision", {}).get("path")
    print(f"Phase 1 shipped-state audit verified: compatibility Path {path}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
