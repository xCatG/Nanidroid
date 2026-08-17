# Phase 1 Shipped-State Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an evidence-only, offline-verifiable audit that records why Nanidroid may select compatibility Path A before removing the unshipped WorkManager, archive-queue, and transactional-update workflows.

**Architecture:** Store the audit decision and its evidence in one versioned JSON ledger, validate it with a Python standard-library tool, and explain the human-readable conclusion in one modernization document. The verifier proves repository facts from Git and enforces the owner-attestation requirements for Path A; it does not inspect or mutate devices, WorkManager, DownloadManager, URI grants, APKs, or ghost files.

Schema version 1 closes the key sets for the top-level ledger, repository,
writer-epoch, decision, distribution, GitHub, and channel objects. Unrelated
generic-valid evidence objects remain the sole explicit extension point.

**Tech Stack:** Python 3 standard library, `unittest`, JSON, Git CLI, Markdown, Gradle wrapper.

## Global Constraints

- This is the first slice of canonical phase `#382`; no later phase may begin.
- Make no production Kotlin, resource, manifest, dependency, JNI, NARFS, Worker, receiver, service, or cleanup change in this pull request.
- Do not download APKs, AABs, NARs, signing material, or corpus payloads.
- Record Path A: the owner confirmed on 2026-08-17 that no APK built from `19da89d3f4d1faaaaaae3e000b8bc852f73c2c38` or later was released or distributed, because the signing key has not been recovered.
- Current GitHub metadata is supporting evidence only; it cannot disprove deleted releases or private distribution without the owner attestation.
- Preserve runtime preferences `lastrunghost`, `createcount_ghost*`, and `keylaunchtime` in `CATTAILSW_NANIDROID_PREFS.xml`, plus `enable_analytics` and `firstRun` in the co-resident default-preference file, even though this PR performs no cleanup.
- Classify user-owned shared `/sdcard/nar` content as `FOREIGN_PRESERVE`; a name or prefix is never cleanup ownership.
- The deterministic 23-NAR corpus and rolling corpus `#383` are not needed for this evidence-only pull request.
- Use four-space Python indentation and the repository's existing Kotlin/Android conventions where referenced.
- Every task ends in a focused commit and must leave the worktree clean.

---

## File Map

- Create `docs/modernization/phase1-shipped-state-ledger.json`: versioned machine-readable evidence, persisted-resource inventory, owner attestation, and selected Path A.
- Create `tools/verify_phase1_shipped_state_audit.py`: offline Path-A-only ledger, Git-history, app-identity, exact-evidence, and persistent-resource verifier.
- Create `tools/test_verify_phase1_shipped_state_audit.py`: positive and negative `unittest` coverage for every verifier decision rule.
- Create `docs/modernization/phase1-shipped-state-audit.md`: human-readable provenance, limitations, capability timeline, Path A rationale, and next cleanup authorization.
- Modify `docs/testing.md`: document the focused audit verification commands and scope.

---

### Task 1: Define the audit ledger contract and fail-closed decision validation

**Files:**
- Create: `tools/verify_phase1_shipped_state_audit.py`
- Create: `tools/test_verify_phase1_shipped_state_audit.py`
- Create: `docs/modernization/phase1-shipped-state-ledger.json`

**Interfaces:**
- Consumes: repository root resolved from `Path(__file__).parents[1]` and JSON from `docs/modernization/phase1-shipped-state-ledger.json`.
- Produces: `load_ledger(path: Path) -> object`, `validate_ledger(data: object, repo_root: Path) -> list[str]`, and `main() -> int`; a valid JSON top-level non-object is a structured validation failure.
- Produces ledger schema version `1` with stable IDs used by later tasks.

- [ ] **Step 1: Write the failing schema and Path A prerequisite tests**

Create `tools/test_verify_phase1_shipped_state_audit.py` with imports and helpers that load the committed ledger, deep-copy it, and validate a mutation against the real repository:

```python
from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "verify_phase1_shipped_state_audit.py"
SPEC = importlib.util.spec_from_file_location("phase1_audit", TOOL)
assert SPEC is not None and SPEC.loader is not None
phase1_audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(phase1_audit)


class Phase1ShippedStateAuditTest(unittest.TestCase):
    def ledger(self) -> dict[str, object]:
        return copy.deepcopy(phase1_audit.load_ledger(phase1_audit.LEDGER))

    def assert_failure(self, data: dict[str, object], text: str) -> None:
        failures = phase1_audit.validate_ledger(data, ROOT)
        self.assertTrue(
            any(text in failure for failure in failures),
            f"expected {text!r} in {failures!r}",
        )

    def test_committed_path_a_ledger_is_valid(self) -> None:
        self.assertEqual([], phase1_audit.validate_ledger(self.ledger(), ROOT))

    def test_rejects_unknown_schema_version(self) -> None:
        data = self.ledger()
        data["schemaVersion"] = 99
        self.assert_failure(data, "schemaVersion must be 1 (a true JSON integer)")

    def test_rejects_path_a_without_confirmed_owner_attestation(self) -> None:
        data = self.ledger()
        data["distribution"]["ownerAttestation"]["confirmed"] = False
        self.assert_failure(data, "Path A requires confirmed owner attestation")

    def test_rejects_path_a_when_state_capable_apk_was_distributed(self) -> None:
        data = self.ledger()
        data["distribution"]["ownerAttestation"][
            "stateCapableApkDistributed"
        ] = True
        self.assert_failure(data, "Path A forbids state-capable APK distribution")

    def test_rejects_unknown_distribution_channel_for_path_a(self) -> None:
        data = self.ledger()
        data["distribution"]["channels"][0]["status"] = "unknown"
        self.assert_failure(data, "Path A requires every distribution channel")

    def test_rejects_non_path_a_decision(self) -> None:
        data = self.ledger()
        data["decision"]["path"] = "B"
        self.assert_failure(data, "schemaVersion 1 requires decision.path exactly A")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and verify the missing-tool failure**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
```

Expected: failure while importing `tools/verify_phase1_shipped_state_audit.py` because the verifier does not exist.

- [ ] **Step 3: Add the ledger skeleton and minimal fail-closed verifier**

Create `docs/modernization/phase1-shipped-state-ledger.json` with these top-level fields and exact decision data. Populate the full `writerEpochs`, `persistentResources`, and `evidence` arrays in Tasks 2 and 3; use empty arrays only during this first commit:

```json
{
  "schemaVersion": 1,
  "auditDate": "2026-08-17",
  "repository": {
    "applicationId": "com.cattailsw.nanidroid",
    "versionCode": 6,
    "versionName": "open_0.1",
    "auditedHead": "f7d037bc066ff648d73b4c2d403a890765b44523"
  },
  "writerEpochs": [],
  "persistentResources": [],
  "distribution": {
    "channels": [
      {"id": "github-releases", "status": "none"},
      {"id": "github-actions-apk-after-writer-epoch", "status": "none"},
      {"id": "google-play", "status": "none"},
      {"id": "f-droid", "status": "none"},
      {"id": "website", "status": "none"},
      {"id": "drive-discord-direct-share", "status": "none"},
      {"id": "other", "status": "none"}
    ],
    "ownerAttestation": {
      "confirmed": true,
      "date": "2026-08-17",
      "stateCapableApkDistributed": false,
      "signingKeyRecovered": false,
      "statement": "No APK built from 19da89d3f4d1faaaaaae3e000b8bc852f73c2c38 or later was released or distributed; the signing key has not been recovered."
    }
  },
  "decision": {
    "path": "A",
    "rationaleEvidenceIds": [],
    "supportedUpgradeFloor": "No distributed state-capable modernization build",
    "sequentialUpgradeEnforced": false,
    "compatibilityRemovalFloor": null
  },
  "evidence": []
}
```

Create `tools/verify_phase1_shipped_state_audit.py` with the pure validation entry point and decision rules:

```python
#!/usr/bin/env python3
"""Verify the Phase 1 shipped-state audit and compatibility decision."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "modernization" / "phase1-shipped-state-ledger.json"
ALLOWED_CHANNEL_STATUS = {"none", "state-capable", "unknown"}


def load_ledger(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_ledger(data: dict[str, Any], repo_root: Path) -> list[str]:
    failures: list[str] = []
    schema_version = data.get("schemaVersion")
    if type(schema_version) is not int or schema_version != 1:
        failures.append("schemaVersion must be 1 (a true JSON integer)")

    decision = data.get("decision", {})
    path = decision.get("path")
    if path != "A":
        failures.append("schemaVersion 1 requires decision.path exactly A")

    distribution = data.get("distribution", {})
    attestation = distribution.get("ownerAttestation", {})
    channels = distribution.get("channels", [])
    if path == "A":
        if attestation.get("confirmed") is not True:
            failures.append("Path A requires confirmed owner attestation")
        if attestation.get("stateCapableApkDistributed") is not False:
            failures.append("Path A forbids state-capable APK distribution")
        if not channels or any(channel.get("status") != "none" for channel in channels):
            failures.append("Path A requires every distribution channel to be none")
    for channel in channels:
        if channel.get("status") not in ALLOWED_CHANNEL_STATUS:
            failures.append(f"unknown distribution status for {channel.get('id')}")
    return failures


def main() -> int:
    failures = validate_ledger(load_ledger(LEDGER), ROOT)
    if failures:
        print("Phase 1 shipped-state audit verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("Phase 1 shipped-state audit verified: compatibility Path A.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

Remove unused imports from this initial version; `subprocess` becomes used in Task 2.

- [ ] **Step 4: Run the focused tests and verifier**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
```

Expected: both commands pass and the verifier prints `Phase 1 shipped-state audit verified: compatibility Path A.`

- [ ] **Step 5: Commit the ledger contract**

```powershell
git add docs/modernization/phase1-shipped-state-ledger.json tools/verify_phase1_shipped_state_audit.py tools/test_verify_phase1_shipped_state_audit.py
git commit -m "test: define shipped state audit contract"
```

---

### Task 2: Prove writer epochs, ancestry, app identity, and required inventory

**Files:**
- Modify: `tools/verify_phase1_shipped_state_audit.py`
- Modify: `tools/test_verify_phase1_shipped_state_audit.py`
- Modify: `docs/modernization/phase1-shipped-state-ledger.json`

**Interfaces:**
- Consumes: `validate_ledger`, ledger schema version `1`, and full Git object database.
- Produces: `git_text(repo_root: Path, *args: str) -> str` and deterministic verification of commit existence, ancestry, introduction paths, app identity, required epoch IDs, required resource IDs, and evidence references.

- [ ] **Step 1: Add failing Git and inventory tests**

Add these tests to `Phase1ShippedStateAuditTest`:

```python
    def test_rejects_nonexistent_writer_commit(self) -> None:
        data = self.ledger()
        data["writerEpochs"][0]["commit"] = "0" * 40
        self.assert_failure(data, "writer commit does not exist")

    def test_rejects_writer_commit_outside_audited_head(self) -> None:
        data = self.ledger()
        data["repository"]["auditedHead"] = data["writerEpochs"][0]["commit"]
        data["writerEpochs"][-1]["commit"] = phase1_audit.git_text(
            ROOT, "rev-parse", "HEAD"
        )
        self.assert_failure(data, "writer commit is not an ancestor")

    def test_rejects_incorrect_app_identity(self) -> None:
        data = self.ledger()
        data["repository"]["applicationId"] = "example.invalid"
        self.assert_failure(data, "application identity mismatch")

    def test_rejects_missing_required_writer_epoch(self) -> None:
        data = self.ledger()
        data["writerEpochs"] = data["writerEpochs"][1:]
        self.assert_failure(data, "missing writer epoch: nar-queue-workmanager")

    def test_rejects_missing_required_persistent_resource(self) -> None:
        data = self.ledger()
        data["persistentResources"] = [
            resource
            for resource in data["persistentResources"]
            if resource["id"] != "workmanager-worker-fqcns"
        ]
        self.assert_failure(data, "missing persistent resource: workmanager-worker-fqcns")

    def test_rejects_dangling_decision_evidence_reference(self) -> None:
        data = self.ledger()
        data["decision"]["rationaleEvidenceIds"].append("missing-evidence")
        self.assert_failure(data, "dangling evidence reference: missing-evidence")
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
```

Expected: failures because Git, inventory, and evidence-reference validation are not implemented and the ledger arrays are incomplete.

- [ ] **Step 3: Add Git and required-set validation**

Add these constants and helpers to the verifier:

```python
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
    "workmanager-request-uuids",
    "downloadmanager-rows",
    "persisted-uri-grants",
    "local-import-staging",
    "install-attempt-staging",
    "external-ghost-install-staging",
    "ghost-update-unpublished-staging",
    "ghost-update-transaction",
    "installed-live-ghost-trees",
    "runtime-last-ghost",
    "runtime-activation-counts",
    "runtime-launch-time",
    "default-preference-analytics",
    "default-preference-first-run",
    "shared-nar-storage",
    "backup-device-transfer-boundaries",
    "durable-android-components",
    "durable-pending-intents",
    "durable-attention-notifications",
    "nanidroid-service-foreground-notification",
}


def git_text(repo_root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=repo_root,
        text=True,
        capture_output=True,
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
```

Import `re`. Extend `validate_ledger` so it:

1. validates every commit as exactly 40 lowercase hexadecimal characters;
2. runs `git cat-file -e <commit>^{commit}`;
3. runs `git merge-base --is-ancestor <writer> <auditedHead>`;
4. validates the three identity fields at each writer commit;
5. checks required epoch/resource IDs by set difference;
6. rejects duplicate IDs;
7. requires every `decision.rationaleEvidenceIds` entry to exist in `evidence`;
8. reports a clear full-history requirement if Git objects are absent from a shallow checkout.

Use failure strings matching the tests exactly.

Schema version 1 is Path-A-only. Bind `supportedUpgradeFloor` exactly to
`No distributed state-capable modernization build`, require
`sequentialUpgradeEnforced` to be `false`, require
`compatibilityRemovalFloor` to be `null`, and require exactly the six unique
rationale evidence IDs committed in the ledger. Reject every other decision
path rather than implementing partial alternate-path prerequisites.

- [ ] **Step 4: Populate the effective writer epochs**

Replace `writerEpochs` with the three effective `origin/master` epochs, not their pre-squash feature commits:

```json
[
  {
    "id": "nar-queue-workmanager",
    "commit": "19da89d3f4d1faaaaaae3e000b8bc852f73c2c38",
    "introducedPaths": [
      "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt",
      "src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt",
      "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadReceiver.kt",
      "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRecoveryReceiver.kt"
    ]
  },
  {
    "id": "durable-operation-store",
    "commit": "ec78fcc282c0a528f371609fca0e66fbf773b5ff",
    "introducedPaths": [
      "src/main/kotlin/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStore.kt",
      "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationSupervisor.kt"
    ]
  },
  {
    "id": "transactional-ghost-update",
    "commit": "19956d7f5f2406e045c819593e761a4c1fb08ae6",
    "introducedPaths": [
      "src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateWorker.kt",
      "src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateRepository.kt",
      "src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateJournal.kt"
    ]
  }
]
```

Bind each epoch ID to the full commit and exact `introducedPaths` set above.
Verify every listed path is an `A` addition against that commit's only parent;
presence at the commit is not sufficient.

- [ ] **Step 5: Populate the persistent-resource inventory**

Add one object for every required resource ID. Each object has exactly `id`,
`ownership`, `locations`, `formats`, and `cleanupPolicy`. The verifier's
`REQUIRED_RESOURCES` mapping and the committed ledger hold the complete exact
values; mutation tests bind every ID and field. The required IDs are:

```text
nar-download-queue
durable-operations
workmanager-worker-fqcns
workmanager-unique-work
workmanager-request-uuids
downloadmanager-rows
persisted-uri-grants
local-import-staging
install-attempt-staging
external-ghost-install-staging
ghost-update-unpublished-staging
ghost-update-transaction
installed-live-ghost-trees
runtime-last-ghost
runtime-activation-counts
runtime-launch-time
default-preference-analytics
default-preference-first-run
shared-nar-storage
backup-device-transfer-boundaries
durable-android-components
durable-pending-intents
durable-attention-notifications
nanidroid-service-foreground-notification
```

The queue contract is lossy/fail-closed for unknown versions and malformed
rows. Cache-attempt and external-ghost staging are separate topologies. An
unpublished ghost update is the exact `.nanidroid-staging-<digest>` with a
matching `journal.v1` or complete `journal.v1.tmp` and matching private owner
marker as a sibling under ghost storage. A published
`.nanidroid-update-<digest>` is bound by its journal, lock, candidate/backup
paths, and topology; its marker is deleted after publish and is not required.
Ambiguous topology, ambiguous or unmatched staging, and incomplete writing
residue are preserved. Installed live ghost trees under
`external-files/ghost/<validated-targetId>` are retained product state and may
be changed only through exact transactional publication/recovery or explicit
user removal. Published usability never establishes cleanup ownership.
DownloadManager rows bind the singular Android
`external-files/Download/nar-downloads/<itemId>.nar` directory. Work
UUID history is recorded per worker kind, runtime/default preference containers
are explicit, and backup/device-transfer plus Android component and persisted
`PendingIntent` identities are part of the required inventory. The exact
durable action identities are
`com.cattailsw.nanidroid.action.DURABLE_KEEP_WAITING`,
`com.cattailsw.nanidroid.action.DURABLE_STOP`, and
`com.cattailsw.nanidroid.action.DURABLE_RETRY_STOP`. Durable-attention
notifications bind channel `nanidroid_operation_attention`, app-declared
initial `IMPORTANCE_DEFAULT`, its description resource, exact
`durable:<operationId>::<attemptId>` tag, and ID `43`, while preserving user
channel configuration rather than freezing runtime sound/vibration values.
`NanidroidService` binds channel `nanidroid_downloads`, app-declared initial
`IMPORTANCE_LOW`, and foreground notification ID `41`. Otherwise eligible
unlisted state remains governed by Android default backup eligibility; this
does not classify cache, code-cache, no-backup, shared, or out-of-domain
storage as included. Shared NAR
temporary names use the actual `File.createTempFile("nanidroid", "tmp", ...)`
shape, and a name or prefix never proves ownership.

- [ ] **Step 6: Run the verifier tests**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
```

Expected: all tests pass, `repository.auditedHead` is exactly
`f7d037bc066ff648d73b4c2d403a890765b44523`, all three commits exist and are
ancestors of that commit, and all three have `com.cattailsw.nanidroid`, code
`6`, name `open_0.1`.

- [ ] **Step 7: Commit Git-backed verification**

```powershell
git add docs/modernization/phase1-shipped-state-ledger.json tools/verify_phase1_shipped_state_audit.py tools/test_verify_phase1_shipped_state_audit.py
git commit -m "test: verify shipped state provenance"
```

---

### Task 3: Record GitHub observations, owner attestation, limitations, and Path A rationale

**Files:**
- Modify: `docs/modernization/phase1-shipped-state-ledger.json`
- Create: `docs/modernization/phase1-shipped-state-audit.md`
- Modify: `tools/verify_phase1_shipped_state_audit.py`
- Modify: `tools/test_verify_phase1_shipped_state_audit.py`

**Interfaces:**
- Consumes: writer/resource IDs from Task 2 and the owner's 2026-08-17 attestation.
- Produces: unique evidence IDs referenced by `decision.rationaleEvidenceIds` and a human-readable audit whose conclusion matches the ledger.

- [ ] **Step 1: Add failing evidence-shape and release-observation tests**

Add tests that reject duplicate evidence IDs, unknown evidence types, missing source/observation date, a nonzero post-writer APK count under Path A, and a ledger whose human-readable decision evidence omits the owner attestation:

```python
    def test_rejects_duplicate_evidence_ids(self) -> None:
        data = self.ledger()
        data["evidence"].append(copy.deepcopy(data["evidence"][0]))
        self.assert_failure(data, "duplicate evidence id")

    def test_rejects_unknown_evidence_type(self) -> None:
        data = self.ledger()
        data["evidence"][0]["type"] = "guess"
        self.assert_failure(data, "unknown evidence type")

    def test_rejects_path_a_with_post_writer_apk_artifact(self) -> None:
        data = self.ledger()
        data["distribution"]["github"]["postWriterApkArtifactCount"] = 1
        self.assert_failure(data, "Path A requires zero post-writer GitHub APK artifacts")

    def test_rejects_path_a_without_attestation_evidence_reference(self) -> None:
        data = self.ledger()
        data["decision"]["rationaleEvidenceIds"].remove("owner-attestation-2026-08-17")
        self.assert_failure(data, "Path A decision must reference owner attestation")
```

- [ ] **Step 2: Run the tests and verify evidence validation is missing**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
```

Expected: the new tests fail because evidence types, GitHub counts, and the required attestation reference are not validated.

- [ ] **Step 3: Add evidence and GitHub observation validation**

Allow only these evidence types:

```python
ALLOWED_EVIDENCE_TYPES = {
    "git-history",
    "github-metadata-observation",
    "repository-document",
    "owner-attestation",
}
```

Require every evidence object to have a unique nonempty `id`, allowed `type`, nonempty `claim`, nonempty `source`, and ISO date `observedAt`. Bind all six required Path A evidence objects exactly by ID while allowing unrelated evidence only when it remains generic-valid. Require:

- top-level `auditDate == "2026-08-17"` as an exact string;
- integer `distribution.github.releaseCount == 0`;
- integer `distribution.github.tagCount == 0`;
- integer `distribution.github.actionsArtifactCount == 192`;
- integer `distribution.github.postWriterApkArtifactCount == 0`;
- integer `distribution.github.postWriterReportOnlyArtifactCount == 2`;
- `distribution.github.observedAt == "2026-08-17"` in addition to ISO-date shape;
- `distribution.github.limitation == "Current metadata cannot disprove deleted releases or private distribution."`;
- owner-attestation `confirmed is true`, `stateCapableApkDistributed is false`,
  and `signingKeyRecovered is false` using strict JSON-boolean identity checks;
- `owner-attestation-2026-08-17` in `decision.rationaleEvidenceIds`;
- every rationale ID to resolve to one evidence object.

Do not make network calls from the verifier. It validates the recorded, dated observation and explicitly leaves live refresh to a future deliberate audit update.

- [ ] **Step 4: Populate evidence and decision references**

Add:

```json
"github": {
  "observedAt": "2026-08-17",
  "releaseCount": 0,
  "tagCount": 0,
  "actionsArtifactCount": 192,
  "postWriterApkArtifactCount": 0,
  "postWriterReportOnlyArtifactCount": 2,
  "limitation": "Current metadata cannot disprove deleted releases or private distribution."
}
```

Add evidence objects for:

- `git-writer-epochs`: the three effective mainline writer commits;
- `git-app-identity-reuse`: code 6/name open_0.1 at every writer epoch;
- `github-releases-tags-empty-2026-08-17`: current release/tag API arrays empty;
- `github-actions-no-post-writer-apk-2026-08-17`: APK-like artifacts predate 19da; two later artifacts contain reports only;
- `baseline-artifacts-unavailable`: `docs/modernization/PR_A_BASELINE.md` records missing historical APK, signing identity/hash, and toolchain;
- `owner-attestation-2026-08-17`: no state-capable APK distributed and signing key not recovered.

Set `decision.rationaleEvidenceIds` to all six IDs.

- [ ] **Step 5: Write the human-readable audit**

Create `docs/modernization/phase1-shipped-state-audit.md` with these exact sections:

1. `Decision` — Path A selected and what it authorizes.
2. `Owner attestation` — date and exact distribution/signing statement.
3. `Effective writer epochs` — table for commits 19da, ec78, and 1995.
4. `Distribution evidence` — zero releases/tags, artifact chronology, version reuse, missing baseline artifacts.
5. `Persisted-state capability` — summary of worker identities, preference formats, external IDs, grants, staging, journals, runtime preferences, and shared storage.
6. `Schema version 1 scope` — the checked-in schema accepts only the selected Path A decision and requires an explicit schema revision for any alternate decision.
7. `Limitations` — deleted/private distribution is established by owner attestation rather than Git metadata; GitHub observations are dated, not permanent facts.
8. `Cleanup authorization` — later PRs may remove compatibility handling, but each deletion still needs reachability, security, and current-state tests.
9. `Non-authorization` — this audit does not authorize deleting runtime preferences, user-owned archives, installed ghosts, or weakening the transactional installer.
10. `Verification` — exact Python, hygiene, unit, and assembly commands.

Use the full 40-character hashes in the table and link the ledger, verifier, `#382`, and `PR_A_BASELINE.md`.

- [ ] **Step 6: Run focused verification**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
```

Expected: all tests pass and the document and ledger both select Path A.

- [ ] **Step 7: Commit the completed audit evidence**

```powershell
git add docs/modernization/phase1-shipped-state-audit.md docs/modernization/phase1-shipped-state-ledger.json tools/verify_phase1_shipped_state_audit.py tools/test_verify_phase1_shipped_state_audit.py
git commit -m "docs: record shipped state audit"
```

---

### Task 4: Document verification, run the complete evidence-only gate, and prepare review

**Files:**
- Modify: `docs/testing.md`
- Verify: `docs/modernization/phase1-shipped-state-audit.md`
- Verify: `docs/modernization/phase1-shipped-state-ledger.json`
- Verify: `tools/verify_phase1_shipped_state_audit.py`
- Verify: `tools/test_verify_phase1_shipped_state_audit.py`

**Interfaces:**
- Consumes: completed audit files and verifier from Tasks 1–3.
- Produces: one documented command block and a review-ready evidence-only pull request linked to `#382`.

- [ ] **Step 1: Add the audit command to the testing guide**

Insert a `## Phase 1 shipped-state audit` section before `## Full verification` in `docs/testing.md`:

````markdown
## Phase 1 shipped-state audit

The compatibility decision for removing the unshipped durable workflows is
recorded in `docs/modernization/phase1-shipped-state-ledger.json`. Verify its
Path-A-only schema, exact audited head, Git ancestry, writer epochs, application
identity, exact audit/observation dates and GitHub limitation, exact required
evidence, closed schema-v1 object keys, persistent-resource contracts, and
owner-attestation requirement offline. Unrelated generic-valid evidence remains
the explicit extension point:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
```

The verifier requires full Git history for the three effective writer commits.
It makes no network, device, APK, WorkManager, DownloadManager, URI-grant, or
filesystem-cleanup calls. Refreshing dated GitHub observations requires an
explicit schema revision and is not part of routine verification.
````

Use four backticks around the outer plan snippet while editing so the nested PowerShell fence remains valid Markdown.

- [ ] **Step 2: Run placeholder, JSON, and diff checks**

Run:

```powershell
python -m json.tool docs/modernization/phase1-shipped-state-ledger.json > $null
rg -n "TB[D]|TO[D]O|implement[ ]later|fill[ ]in[ ]details" docs/modernization/phase1-shipped-state-audit.md docs/modernization/phase1-shipped-state-ledger.json tools/verify_phase1_shipped_state_audit.py tools/test_verify_phase1_shipped_state_audit.py docs/testing.md
git diff --check
```

Expected: JSON validation succeeds; `rg` returns no matches; `git diff --check` succeeds.

- [ ] **Step 3: Run the complete evidence-only verification gate**

Run:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
python tools/check_repository_hygiene.py
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected:

- audit unit tests pass;
- verifier reports Path A;
- `tools/check_repository_hygiene.py` reproduces its current pre-existing missing-`binary-inventory.json` failure; record that unchanged baseline failure in the pull request and do not repair it before phase `#374`;
- Gradle unit tests and debug assembly pass.

Do not run connected tests, screenshots, NAR corpus, or UI audit for this evidence-only pull request.

- [ ] **Step 4: Perform coordinator and multi-agent reviews**

Dispatch:

- a release/provenance reviewer to challenge the Path A evidence and owner-attestation boundary;
- a durability reviewer to compare the resource inventory against queue v1–v4, durable v1–v6, Worker identities, grants, DownloadManager bindings, staging, journals, and runtime preferences;
- an adversarial reviewer to search for any claim that GitHub metadata alone proves non-distribution or that Path A authorizes unsafe data deletion.

The coordinator reviews the full diff after those reports and accepts only findings supported by repository evidence or the approved owner statement.

- [ ] **Step 5: Commit the testing documentation**

```powershell
git add docs/testing.md
git commit -m "docs: document shipped state verification"
```

- [ ] **Step 6: Prepare the focused pull request**

Create a draft pull request linked to `#382` with:

- the Path A conclusion;
- the owner's signing/distribution attestation;
- the three effective writer epochs;
- a statement that no production or cleanup code changed;
- commands and exact results;
- any pre-existing hygiene failure clearly separated from this PR;
- the three multi-agent review outcomes;
- the next authorized slice: plan the first independent non-core deletion PR without activating phase `#384`.

Mark it ready only after local and multi-agent reviews pass. Inspect GitHub automatic reviews, CI, aggregate reviews, and every inline thread before coordinator merge.

---

## Plan Completion Gate

This plan is complete only when:

- the committed ledger selects Path A and passes its verifier;
- schema version 1 rejects every non-Path-A decision and binds the exact audited head;
- the exact owner attestation is recorded;
- all six required evidence objects are exact and ID-bound while unrelated evidence remains generic-valid;
- every writer epoch and required persistent resource is machine-checked;
- the audit document states both authorization and non-authorization boundaries;
- no production file or dependency changed;
- local, multi-agent, coordinator, GitHub automatic, and CI reviews have no unresolved actionable finding;
- the merged default branch is verified before planning the next pull request under phase `#382`.
