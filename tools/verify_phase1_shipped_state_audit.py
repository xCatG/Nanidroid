#!/usr/bin/env python3
"""Verify the Phase 1 shipped-state audit and compatibility decision."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "modernization" / "phase1-shipped-state-ledger.json"
ALLOWED_PATHS = {"A", "B", "C"}
ALLOWED_CHANNEL_STATUS = {"none", "state-capable", "unknown"}
ALLOWED_EVIDENCE_TYPES = {
    "git-history",
    "github-metadata-observation",
    "repository-document",
    "owner-attestation",
}
REQUIRED_CHANNEL_IDS = {
    "github-releases",
    "github-actions-apk-after-writer-epoch",
    "google-play",
    "f-droid",
    "website",
    "drive-discord-direct-share",
    "other",
}
REQUIRED_WRITER_EPOCHS = {
    "nar-queue-workmanager",
    "durable-operation-store",
    "transactional-ghost-update",
}
REQUIRED_RESOURCES = {
    "nar-download-queue",
    "durable-operations",
    "workmanager-worker-fqcns",
    "workmanager-unique-work",
    "downloadmanager-rows",
    "persisted-uri-grants",
    "local-import-staging",
    "install-attempt-staging",
    "ghost-update-transaction",
    "runtime-last-ghost",
    "runtime-activation-counts",
    "shared-nar-storage",
}


def load_ledger(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def git_text(repo_root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo_root, text=True, capture_output=True
    )
    if result.returncode != 0:
        message = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"git {' '.join(args)} failed: {message}")
    return result.stdout.strip()


def read_app_identity(repo_root: Path, commit: str) -> tuple[str, int, str]:
    build = git_text(repo_root, "show", f"{commit}:build.gradle.kts")
    application_id = re.search(r'applicationId\s*=\s*"([^"]+)"', build)
    version_code = re.search(r"versionCode\s*=\s*(\d+)", build)
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', build)
    if not application_id or not version_code or not version_name:
        raise ValueError(f"application identity missing at {commit}")
    return application_id.group(1), int(version_code.group(1)), version_name.group(1)


def is_iso_calendar_date(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None and _parse_date(value)


def _parse_date(value: str) -> bool:
    try:
        date.fromisoformat(value)
    except ValueError:
        return False
    return True


def shallow_history_notice(repo_root: Path) -> str:
    try:
        if git_text(repo_root, "rev-parse", "--is-shallow-repository") == "true":
            return "; full Git history is required (shallow checkout is missing objects)"
    except RuntimeError:
        pass
    return ""


def validate_ledger(data: dict[str, Any], repo_root: Path) -> list[str]:
    failures: list[str] = []
    decision = data.get("decision", {})
    if not isinstance(decision, dict):
        failures.append("decision must be an object")
        decision = {}
    if data.get("schemaVersion") != 1:
        failures.append("schemaVersion must be 1")

    repository = data.get("repository", {})
    if not isinstance(repository, dict):
        failures.append("repository must be an object")
        repository = {}
    audited_head = repository.get("auditedHead")
    if not isinstance(audited_head, str) or not re.fullmatch(r"[0-9a-f]{40}", audited_head):
        failures.append("audited head must be 40 lowercase hexadecimal characters")
    else:
        try:
            git_text(repo_root, "cat-file", "-e", f"{audited_head}^{{commit}}")
        except RuntimeError as error:
            failures.append(f"audited head does not exist: {error}{shallow_history_notice(repo_root)}")

    writer_epochs = data.get("writerEpochs", [])
    if not isinstance(writer_epochs, list):
        failures.append("writerEpochs must be an array")
        writer_epochs = []
    valid_writer_epochs = []
    for epoch in writer_epochs:
        if not isinstance(epoch, dict):
            failures.append("writer epoch entries must be objects")
        else:
            valid_writer_epochs.append(epoch)
    writer_ids = [epoch.get("id") for epoch in valid_writer_epochs]
    writer_ids_set = {item for item in writer_ids if isinstance(item, str)}
    if len(writer_ids_set) != len(writer_ids):
        failures.append("writer epoch IDs must be strings and unique")
    for missing in sorted(REQUIRED_WRITER_EPOCHS - writer_ids_set):
        failures.append(f"missing writer epoch: {missing}")
    for epoch in valid_writer_epochs:
        commit = epoch.get("commit")
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            failures.append(f"writer commit must be 40 lowercase hexadecimal characters: {commit}")
            continue
        try:
            git_text(repo_root, "cat-file", "-e", f"{commit}^{{commit}}")
        except RuntimeError:
            failures.append(
                f"writer commit does not exist: {commit}{shallow_history_notice(repo_root)}"
            )
            continue
        if isinstance(audited_head, str) and re.fullmatch(r"[0-9a-f]{40}", audited_head):
            try:
                git_text(repo_root, "merge-base", "--is-ancestor", commit, audited_head)
            except RuntimeError:
                failures.append(
                    f"writer commit is not an ancestor: {commit}"
                    f"{shallow_history_notice(repo_root)}"
                )
        try:
            identity = read_app_identity(repo_root, commit)
            expected = (
                repository.get("applicationId"),
                repository.get("versionCode"),
                repository.get("versionName"),
            )
            if identity != expected:
                failures.append(f"application identity mismatch at {commit}")
        except (RuntimeError, ValueError):
            failures.append(f"application identity mismatch at {commit}")
        introduced_paths = epoch.get("introducedPaths", [])
        if not isinstance(introduced_paths, list):
            failures.append(f"introducedPaths must be an array: {commit}")
            continue
        if any(not isinstance(path, str) or not path for path in introduced_paths):
            failures.append(f"introducedPaths must contain nonempty strings: {commit}")
            continue
        for path in introduced_paths:
            try:
                git_text(repo_root, "cat-file", "-e", f"{commit}:{path}")
            except RuntimeError:
                failures.append(f"introduced path does not exist at {commit}: {path}")

    resources = data.get("persistentResources", [])
    if not isinstance(resources, list):
        failures.append("persistentResources must be an array")
        resources = []
    valid_resources = []
    for resource in resources:
        if not isinstance(resource, dict):
            failures.append("persistent resource entries must be objects")
        else:
            valid_resources.append(resource)
    resource_ids = [resource.get("id") for resource in valid_resources]
    resource_ids_set = {item for item in resource_ids if isinstance(item, str)}
    if len(resource_ids_set) != len(resource_ids):
        failures.append("persistent resource IDs must be strings and unique")
    for missing in sorted(REQUIRED_RESOURCES - resource_ids_set):
        failures.append(f"missing persistent resource: {missing}")
    evidence = data.get("evidence", [])
    if not isinstance(evidence, list):
        failures.append("evidence must be an array")
        evidence = []
    valid_evidence = []
    for item in evidence:
        if not isinstance(item, dict):
            failures.append("evidence entries must be objects")
        else:
            valid_evidence.append(item)
    evidence_ids = [item.get("id") for item in valid_evidence]
    evidence_set = {item for item in evidence_ids if isinstance(item, str)}
    if len(evidence_set) != len(evidence_ids):
        failures.append("duplicate evidence id or non-string evidence id")
    for item in valid_evidence:
        evidence_id = item.get("id")
        if not isinstance(evidence_id, str) or not evidence_id:
            failures.append("evidence IDs must be nonempty strings")
        if item.get("type") not in ALLOWED_EVIDENCE_TYPES:
            failures.append(f"unknown evidence type: {item.get('type')}")
        for field in ("claim", "source", "observedAt"):
            if not isinstance(item.get(field), str) or not item[field]:
                failures.append(f"evidence {evidence_id} requires nonempty {field}")
        if isinstance(item.get("observedAt"), str) and item["observedAt"] and not is_iso_calendar_date(item["observedAt"]):
            failures.append(f"evidence {evidence_id} observedAt must be an ISO calendar date")
    rationale_ids = decision.get("rationaleEvidenceIds", [])
    if not isinstance(rationale_ids, list):
        failures.append("decision.rationaleEvidenceIds must be an array")
        rationale_ids = []
    for evidence_id in rationale_ids:
        if not isinstance(evidence_id, str):
            failures.append(f"evidence reference IDs must be strings: {evidence_id}")
            continue
        if evidence_id not in evidence_set:
            failures.append(f"dangling evidence reference: {evidence_id}")

    path = decision.get("path")
    if path not in ALLOWED_PATHS:
        failures.append("decision.path must resolve to A, B, or C")

    distribution = data.get("distribution", {})
    if not isinstance(distribution, dict):
        failures.append("distribution must be an object")
        distribution = {}
    attestation = distribution.get("ownerAttestation", {})
    channels = distribution.get("channels", [])
    if not isinstance(attestation, dict):
        failures.append("ownerAttestation must be an object")
        attestation = {}
    if not isinstance(channels, list):
        failures.append("distribution channels must be an array")
        channels = []
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
        github = distribution.get("github", {})
        if not isinstance(github, dict):
            failures.append("Path A requires GitHub observations")
            github = {}
        github_observed_at = github.get("observedAt")
        if not isinstance(github_observed_at, str) or not github_observed_at:
            failures.append("Path A requires GitHub observation date")
        elif not is_iso_calendar_date(github_observed_at):
            failures.append("GitHub observation date must be an ISO calendar date")
        if github.get("releaseCount") != 0:
            failures.append("Path A requires zero GitHub releases")
        if github.get("tagCount") != 0:
            failures.append("Path A requires zero GitHub tags")
        if github.get("postWriterApkArtifactCount") != 0:
            failures.append("Path A requires zero post-writer GitHub APK artifacts")
        if "owner-attestation-2026-08-17" not in rationale_ids:
            failures.append("Path A decision must reference owner attestation")
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
