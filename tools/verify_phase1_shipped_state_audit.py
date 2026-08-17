#!/usr/bin/env python3
"""Verify the Phase 1 shipped-state audit and compatibility decision."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import date
from functools import lru_cache
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
    "nar-queue-workmanager": {
        "commit": "19da89d3f4d1faaaaaae3e000b8bc852f73c2c38",
        "introducedPaths": {
            "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt",
            "src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt",
            "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadReceiver.kt",
            "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRecoveryReceiver.kt",
        },
    },
    "durable-operation-store": {
        "commit": "ec78fcc282c0a528f371609fca0e66fbf773b5ff",
        "introducedPaths": {
            "src/main/kotlin/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStore.kt",
            "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationSupervisor.kt",
        },
    },
    "transactional-ghost-update": {
        "commit": "19956d7f5f2406e045c819593e761a4c1fb08ae6",
        "introducedPaths": {
            "src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateWorker.kt",
            "src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateRepository.kt",
            "src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateJournal.kt",
        },
    },
}
REQUIRED_RESOURCE_FIELDS = {"id", "ownership", "locations", "formats", "cleanupPolicy"}
REQUIRED_RESOURCES = {
    "nar-download-queue": {
        "ownership": "APP_OWNED_LOSSY_DECODE_FAIL_CLOSED",
        "locations": ["shared_prefs/nar-download-queue.xml#records-v1"],
        "formats": ["v1", "v2", "v3", "v4"],
        "cleanupPolicy": "Preserve on unknown version or malformed row; never infer absence from an empty production decode",
    },
    "durable-operations": {
        "ownership": "APP_OWNED_STRICT_DECODE",
        "locations": [
            "shared_prefs/durable_operations_v1.xml#records",
            "shared_prefs/durable_operations_v1.xml#records_corruption_quarantine",
            "shared_prefs/durable_operations_v1.xml#records_corruption_recovery_required",
        ],
        "formats": ["v1", "v2", "v3", "v4", "v5", "v6"],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "workmanager-worker-fqcns": {
        "ownership": "PLATFORM_OWNED_EXACT_IDENTITY",
        "locations": [
            "com.cattailsw.nanidroid.install.InstallNarWorker",
            "com.cattailsw.nanidroid.install.StageLocalNarWorker",
            "com.cattailsw.nanidroid.durable.GhostUpdateWorker",
            "com.cattailsw.nanidroid.durable.GhostUpdateRecoveryWorker",
        ],
        "formats": [],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "workmanager-unique-work": {
        "ownership": "PLATFORM_OWNED_EXACT_IDENTITY",
        "locations": [
            "install-nar-<itemId>",
            "stage-local-nar-<itemId>",
            "ghost-update-<32hex:sha256(canonical-ghost-root)>",
            "ghost-update-recovery-<32hex:sha256(canonical-target-or-storage-root)>",
        ],
        "formats": ["ExistingWorkPolicy.KEEP"],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "workmanager-request-uuids": {
        "ownership": "PLATFORM_OWNED_HISTORICAL_EXACT_IDENTITY",
        "locations": [
            "InstallNarWorker",
            "StageLocalNarWorker",
            "GhostUpdateWorker",
            "GhostUpdateRecoveryWorker",
        ],
        "formats": [
            "InstallNarWorker:legacy-WorkManager-random,current-durableWorkManagerId-v1(NAR_INSTALL,attempt,itemId)",
            "StageLocalNarWorker:early-WorkManager-random,current-durableWorkManagerId-v1(LOCAL_NAR,attempt,itemId)",
            "GhostUpdateWorker:early-UUID.randomUUID,current-durableWorkManagerId-v1(GHOST_UPDATE,attempt,canonical-operation-id)",
            "GhostUpdateRecoveryWorker:WorkManager-random",
        ],
        "cleanupPolicy": "Preserve every historical and current request identity; no cleanup in audit PR",
    },
    "downloadmanager-rows": {
        "ownership": "PLATFORM_OWNED_EXACT_IDENTITY",
        "locations": ["external-files/Downloads/nar-downloads/<itemId>.nar"],
        "formats": ["exact-row-id", "binding-history"],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "persisted-uri-grants": {
        "ownership": "PLATFORM_OWNED_EXACT_IDENTITY",
        "locations": [
            "queue-source-uri",
            "queue-retained-uri",
            "pendingPersistedGrantReleaseUri",
            "pending-grant-release",
        ],
        "formats": ["read-grant"],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "local-import-staging": {
        "ownership": "APP_OWNED_CANONICAL_PATH",
        "locations": ["filesDir/nar-local-imports"],
        "formats": ["nar-local-<24hex>.nar"],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "install-attempt-staging": {
        "ownership": "APP_OWNED_CANONICAL_PATH",
        "locations": ["cacheDir/nar-install-attempts/<64hex:sha256(itemId)>/<UUID>/nar-import-<24hex>.zip"],
        "formats": [
            "64-lowercase-hex-item-directory",
            "canonical-UUID-attempt-directory",
            "24-lowercase-hex-archive-token",
        ],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "external-ghost-install-staging": {
        "ownership": "APP_OWNED_CANONICAL_PATH",
        "locations": [
            "<ghost-install-root>/.nanidroid-install-staging/candidate-<32hex>/staged-<32hex>.nar",
            "<ghost-install-root>/.nanidroid-install-staging/candidate-<32hex>/tree",
        ],
        "formats": [
            "32-lowercase-hex-candidate-token",
            "32-lowercase-hex-staged-archive-token",
        ],
        "cleanupPolicy": "No cleanup in audit PR",
    },
    "ghost-update-unpublished-staging": {
        "ownership": "APP_OWNED_EXACT_STAGING_JOURNAL_AND_SIBLING_MARKER",
        "locations": [
            "<ghost-storage>/.nanidroid-staging-<32hex:sha256(operationId)>",
            "<staging>/journal.v1",
            "<staging>/journal.v1.tmp",
            "<ghost-storage>/.nanidroid-update-owner-<UUID>.tmp",
            "<ghost-storage>/.nanidroid-update-writing-<File.createTempFile-random>.tmp",
        ],
        "formats": [
            "operationId=ghost-update-<64hex:sha256(canonical-ghost-root)>",
            "phase=PREPARED",
            "journal=journal.v1-or-complete-journal.v1.tmp",
            "sibling-owner-marker=readable-exact-journal-match",
            "prefix-alone-is-not-ownership",
        ],
        "cleanupPolicy": "Preserve incomplete writing residue and ambiguous or unmatched staging; no cleanup from a prefix; no cleanup in audit PR",
    },
    "ghost-update-transaction": {
        "ownership": "APP_OWNED_EXACT_PUBLISHED_JOURNAL_LOCK_AND_TOPOLOGY",
        "locations": [
            "<ghost-storage>/.nanidroid-update-<32hex:sha256(operationId)>",
            "<transaction>/candidate",
            "<transaction>/backup",
            "<transaction>/journal.v1",
            "<transaction>/journal.v1.tmp",
            "<ghost-storage>/.nanidroid-update-lock-<24hex:sha256(canonical-ghost-root)>",
        ],
        "formats": [
            "operationId=ghost-update-<64hex:sha256(canonical-ghost-root)>",
            "phases=PREPARED|BACKED_UP|PUBLISHED|CLEANED|ROLLBACK_CLASSIFIED|NO_CHANGES_PENDING",
            "topologies=LIVE_CANDIDATE|CANDIDATE_BACKUP|LIVE_BACKUP|LIVE_ONLY|INVALID",
            "owner-marker=deleted-after-publish-and-not-required",
            "prefix-alone-is-not-ownership",
        ],
        "cleanupPolicy": "Preserve ambiguous topology; no cleanup from a prefix; no cleanup in audit PR",
    },
    "runtime-last-ghost": {
        "ownership": "APP_RUNTIME_STATE_RETAIN",
        "locations": ["shared_prefs/CATTAILSW_NANIDROID_PREFS.xml#lastrunghost"],
        "formats": ["string"],
        "cleanupPolicy": "Never delete as workflow cleanup or by deleting the containing preference file",
    },
    "runtime-activation-counts": {
        "ownership": "APP_RUNTIME_STATE_RETAIN",
        "locations": ["shared_prefs/CATTAILSW_NANIDROID_PREFS.xml#createcount_ghost*"],
        "formats": ["long"],
        "cleanupPolicy": "Never delete as workflow cleanup or by deleting the containing preference file",
    },
    "runtime-launch-time": {
        "ownership": "APP_RUNTIME_STATE_RETAIN",
        "locations": ["shared_prefs/CATTAILSW_NANIDROID_PREFS.xml#keylaunchtime"],
        "formats": ["long"],
        "cleanupPolicy": "Never delete as workflow cleanup or by deleting the containing preference file",
    },
    "default-preference-analytics": {
        "ownership": "APP_RUNTIME_STATE_RETAIN",
        "locations": ["shared_prefs/com.cattailsw.nanidroid_preferences.xml#enable_analytics"],
        "formats": ["boolean"],
        "cleanupPolicy": "Never delete by deleting the co-resident default preference file",
    },
    "default-preference-first-run": {
        "ownership": "APP_RUNTIME_STATE_RETAIN",
        "locations": ["shared_prefs/com.cattailsw.nanidroid_preferences.xml#firstRun"],
        "formats": ["boolean"],
        "cleanupPolicy": "Never delete by deleting the co-resident default preference file",
    },
    "shared-nar-storage": {
        "ownership": "FOREIGN_PRESERVE",
        "locations": ["/sdcard/nar"],
        "formats": ["File.createTempFile(prefix=nanidroid,suffix=tmp)=>nanidroid<implementation-random>tmp"],
        "cleanupPolicy": "Never infer ownership or delete from name or prefix",
    },
    "backup-device-transfer-boundaries": {
        "ownership": "ANDROID_BACKUP_POLICY_EXACT_BOUNDARY",
        "locations": [
            "AndroidManifest.xml#application@fullBackupContent=@xml/backup_rules",
            "AndroidManifest.xml#application@dataExtractionRules=@xml/data_extraction_rules",
            "backup_rules.xml#exclude:sharedpref/durable_operations_v1.xml",
            "backup_rules.xml#exclude:sharedpref/nar-download-queue.xml",
            "data_extraction_rules.xml#cloud-backup/exclude:sharedpref/durable_operations_v1.xml",
            "data_extraction_rules.xml#cloud-backup/exclude:sharedpref/nar-download-queue.xml",
            "data_extraction_rules.xml#device-transfer/exclude:sharedpref/durable_operations_v1.xml",
            "data_extraction_rules.xml#device-transfer/exclude:sharedpref/nar-download-queue.xml",
        ],
        "formats": [
            "otherwise eligible unlisted state remains governed by Android default backup eligibility; no inclusion claim for cache, code-cache, no-backup, shared, or out-of-domain storage",
        ],
        "cleanupPolicy": "Preserve full-backup, cloud-backup, and device-transfer exclusions; no cleanup in audit PR",
    },
    "durable-android-components": {
        "ownership": "ANDROID_COMPONENT_EXACT_IDENTITY",
        "locations": [
            "com.cattailsw.nanidroid.NanidroidService|service|exported=false|foregroundServiceType=dataSync",
            "com.cattailsw.nanidroid.install.NarDownloadReceiver|receiver|exported=false|android.intent.action.DOWNLOAD_COMPLETE",
            "com.cattailsw.nanidroid.install.NarDownloadRecoveryReceiver|receiver|exported=false|android.intent.action.BOOT_COMPLETED|android.intent.action.MY_PACKAGE_REPLACED",
            "com.cattailsw.nanidroid.durable.DurableOperationAttentionReceiver|receiver|exported=false|explicit-only",
        ],
        "formats": ["android.permission.RECEIVE_BOOT_COMPLETED"],
        "cleanupPolicy": "No component or intent-filter removal in audit PR",
    },
    "durable-pending-intents": {
        "ownership": "PLATFORM_OWNED_EXACT_IDENTITY",
        "locations": [
            "broadcast|requestCode=0|component=com.cattailsw.nanidroid.durable.DurableOperationAttentionReceiver|actions=com.cattailsw.nanidroid.action.DURABLE_KEEP_WAITING,com.cattailsw.nanidroid.action.DURABLE_STOP,com.cattailsw.nanidroid.action.DURABLE_RETRY_STOP|data=nanidroid://durable-operation/<encoded-operationId>/<attemptId>|package=com.cattailsw.nanidroid",
            "activity|requestCode=0|component=com.cattailsw.nanidroid.Nanidroid|action=android.intent.action.MAIN|data=nanidroid://durable-operation/open",
            "activity|requestCode=0|component=com.cattailsw.nanidroid.Nanidroid|action=<null>|data=<null>|intentFlags=FLAG_ACTIVITY_CLEAR_TOP|FLAG_ACTIVITY_SINGLE_TOP",
        ],
        "formats": ["FLAG_UPDATE_CURRENT", "FLAG_IMMUTABLE"],
        "cleanupPolicy": "Preserve persisted PendingIntent identity until its owning notification/component is deliberately migrated",
    },
}
REQUIRED_OWNER_ATTESTATION = {
    "confirmed": True,
    "date": "2026-08-17",
    "stateCapableApkDistributed": False,
    "signingKeyRecovered": False,
    "statement": "No APK built from 19da89d3f4d1faaaaaae3e000b8bc852f73c2c38 or later was released or distributed; the signing key has not been recovered.",
}
REQUIRED_OWNER_ATTESTATION_EVIDENCE = {
    "id": "owner-attestation-2026-08-17",
    "type": "owner-attestation",
    "claim": "No state-capable APK was distributed and the signing key was not recovered.",
    "source": "Owner attestation",
    "observedAt": "2026-08-17",
}
REQUIRED_PATH_A_SUPPORTED_UPGRADE_FLOOR = (
    "No distributed state-capable modernization build"
)
REQUIRED_PATH_A_RATIONALE_EVIDENCE_IDS = {
    "git-writer-epochs",
    "git-app-identity-reuse",
    "github-releases-tags-empty-2026-08-17",
    "github-actions-no-post-writer-apk-2026-08-17",
    "baseline-artifacts-unavailable",
    "owner-attestation-2026-08-17",
}


def load_ledger(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


@lru_cache(maxsize=None)
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


def validate_ledger(data: Any, repo_root: Path) -> list[str]:
    if not isinstance(data, dict):
        return ["ledger must be a top-level object"]

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
    if writer_ids_set != set(REQUIRED_WRITER_EPOCHS):
        failures.append("writer epoch contracts must exactly match the required unique set")
    for missing in sorted(set(REQUIRED_WRITER_EPOCHS) - writer_ids_set):
        failures.append(f"missing writer epoch: {missing}")
    for epoch in valid_writer_epochs:
        epoch_id = epoch.get("id")
        commit = epoch.get("commit")
        expected_epoch = REQUIRED_WRITER_EPOCHS.get(epoch_id) if isinstance(epoch_id, str) else None
        introduced_paths_value = epoch.get("introducedPaths")
        introduced_paths_set = (
            set(introduced_paths_value)
            if isinstance(introduced_paths_value, list) and
            all(isinstance(path, str) for path in introduced_paths_value)
            else set()
        )
        if expected_epoch is not None and (
            commit != expected_epoch["commit"] or
            introduced_paths_set != expected_epoch["introducedPaths"] or
            not isinstance(introduced_paths_value, list) or
            len(introduced_paths_set) != len(introduced_paths_value)
        ):
            failures.append(f"writer epoch contract mismatch: {epoch_id}")
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
        try:
            commit_and_parents = git_text(
                repo_root, "rev-list", "--parents", "-n", "1", commit,
            ).split()
            if len(commit_and_parents) != 2:
                failures.append(f"writer commit must have exactly one parent: {commit}")
                continue
            parent = commit_and_parents[1]
        except RuntimeError:
            failures.append(f"writer commit parent cannot be resolved: {commit}")
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
                status = git_text(
                    repo_root,
                    "diff-tree",
                    "--no-commit-id",
                    "--name-status",
                    "-r",
                    parent,
                    commit,
                    "--",
                    path,
                ).split("\t", 1)[0]
            except RuntimeError:
                status = ""
            if status != "A":
                failures.append(f"introduced path must be an addition at {commit}: {path}")

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
    if resource_ids_set != set(REQUIRED_RESOURCES):
        failures.append("persistent resource contracts must exactly match the required unique set")
    for missing in sorted(set(REQUIRED_RESOURCES) - resource_ids_set):
        failures.append(f"missing persistent resource: {missing}")
    for resource in valid_resources:
        resource_id = resource.get("id")
        expected_resource = REQUIRED_RESOURCES.get(resource_id) if isinstance(resource_id, str) else None
        if expected_resource is None:
            continue
        expected_contract = {"id": resource_id, **expected_resource}
        if set(resource) != REQUIRED_RESOURCE_FIELDS or resource != expected_contract:
            failures.append(f"persistent resource contract mismatch: {resource_id}")
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
    valid_channels = []
    for channel in channels:
        if not isinstance(channel, dict):
            failures.append("distribution channel entries must be objects")
        else:
            valid_channels.append(channel)
            if (
                not isinstance(channel.get("id"), str) or
                not isinstance(channel.get("status"), str)
            ):
                failures.append(
                    "distribution channel entries must be objects with string id and status"
                )
    channel_ids = [channel.get("id") for channel in valid_channels]
    channel_ids_set = {item for item in channel_ids if isinstance(item, str)}
    if channel_ids_set != REQUIRED_CHANNEL_IDS or len(channel_ids) != len(channel_ids_set):
        failures.append("distribution channel IDs must exactly match the required unique set")
    if path == "A":
        rationale_ids_are_unique_strings = (
            isinstance(rationale_ids, list) and
            all(isinstance(evidence_id, str) for evidence_id in rationale_ids) and
            len(rationale_ids) == len(set(rationale_ids))
        )
        if (
            not rationale_ids_are_unique_strings or
            set(rationale_ids) != REQUIRED_PATH_A_RATIONALE_EVIDENCE_IDS
        ):
            failures.append(
                "Path A rationale evidence IDs must exactly match the required unique set"
            )
        if decision.get("supportedUpgradeFloor") != REQUIRED_PATH_A_SUPPORTED_UPGRADE_FLOOR:
            failures.append("Path A supportedUpgradeFloor must exactly match the current value")
        if decision.get("sequentialUpgradeEnforced") is not False:
            failures.append("Path A sequentialUpgradeEnforced must exactly match false")
        if decision.get("compatibilityRemovalFloor") is not None:
            failures.append("Path A compatibilityRemovalFloor must exactly match null")
        if attestation != REQUIRED_OWNER_ATTESTATION:
            failures.append("Path A owner attestation must exactly match the approved statement")
        if attestation.get("confirmed") is not True:
            failures.append("Path A requires confirmed owner attestation")
        if attestation.get("stateCapableApkDistributed") is not False:
            failures.append("Path A forbids state-capable APK distribution")
        if not valid_channels or any(channel.get("status") != "none" for channel in valid_channels):
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
        owner_evidence = [
            item for item in valid_evidence
            if item.get("id") == "owner-attestation-2026-08-17"
        ]
        if owner_evidence != [REQUIRED_OWNER_ATTESTATION_EVIDENCE]:
            failures.append("Path A owner-attestation evidence must exactly match the approved claim")
    if path == "B" and decision.get("sequentialUpgradeEnforced") is not True:
        failures.append("Path B requires enforced sequential upgrade")
    if path == "C" and not decision.get("compatibilityRemovalFloor"):
        failures.append("Path C requires compatibilityRemovalFloor")

    if path in {"B", "C"}:
        if not any(channel.get("status") == "state-capable" for channel in valid_channels):
            failures.append(f"Path {path} requires state-capable distribution evidence")
        if (
            attestation.get("confirmed") is True and
            attestation.get("stateCapableApkDistributed") is False
        ):
            failures.append(f"Path {path} contradicts the confirmed no-distribution attestation")
        github = distribution.get("github", {})
        if not isinstance(github, dict):
            github = {}
        channels_by_id = {
            channel.get("id"): channel
            for channel in valid_channels
            if isinstance(channel.get("id"), str)
        }
        if (
            channels_by_id.get("github-releases", {}).get("status") == "state-capable" and
            (
                not isinstance(github.get("releaseCount"), int) or
                isinstance(github.get("releaseCount"), bool) or
                github["releaseCount"] <= 0
            )
        ):
            failures.append(
                "state-capable github-releases channel requires positive releaseCount"
            )
        if (
            channels_by_id.get(
                "github-actions-apk-after-writer-epoch",
                {},
            ).get("status") == "state-capable" and
            (
                not isinstance(github.get("postWriterApkArtifactCount"), int) or
                isinstance(github.get("postWriterApkArtifactCount"), bool) or
                github["postWriterApkArtifactCount"] <= 0
            )
        ):
            failures.append(
                "state-capable GitHub Actions channel requires positive "
                "postWriterApkArtifactCount"
            )

    for channel in valid_channels:
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
