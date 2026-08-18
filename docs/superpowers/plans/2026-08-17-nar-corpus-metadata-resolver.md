# NAR Corpus Metadata Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a synthetic-fixture-only PowerShell resolver that emits a deterministic, policy-safe NAR catalog acquisition ledger without network or payload access.

**Architecture:** `scripts/nar-corpus-metadata-resolver.psm1` contains pure normalization, classification, validation, and canonical-JSON functions. `scripts/build-nar-corpus-metadata-ledger.ps1` is the narrow CLI adapter that reads JSON fixtures and writes a ledger inside an explicit output root. A PowerShell integration test runs the CLI against test fixtures and asserts output behavior.

**Tech Stack:** PowerShell 7 and JSON fixtures; no new dependencies, HTTP requests, archive libraries, or Android runtime code.

## Global Constraints

- Do not download, open, hash, cache, inspect, or execute a NAR payload.
- Do not modify the 23-row manifest or its audit runner.
- Fixtures contain public metadata only and reject payload-shaped fields and `.nar` paths.
- Ledger JSON is deterministic and ordered by `snapshotId`, then `sourceRowOrdinal`.
- Every row has exactly one approved acquisition disposition.

---

### Task 1: Establish resolver integration tests and fixtures

**Files:**
- Create: `scripts/tests/build-nar-corpus-metadata-ledger.tests.ps1`
- Create: `scripts/tests/fixtures/nar-corpus-metadata-resolver/phase-one-synthetic.json`
- Create: `scripts/tests/fixtures/nar-corpus-metadata-resolver/payload-shaped.json`

**Interfaces:**
- Consumes: `scripts/build-nar-corpus-metadata-ledger.ps1 -FixturePath <json> -OutputRoot <directory>`.
- Produces: executable tests that assert `ledger.json` rows and unsafe-input rejection.

- [ ] Write a failing local integration test with five rows: observed initial-NAR link, manifest-only, unavailable, excluded despite a link, and a duplicate. Assert the literal five dispositions in ledger output.
- [ ] Run `pwsh -NoProfile -File scripts/tests/build-nar-corpus-metadata-ledger.tests.ps1` and confirm it fails because the CLI is absent.
- [ ] Add a second failing case containing `archivePath: "fixture.nar"`; assert a non-zero exit naming the disallowed field.

### Task 2: Implement pure validation and classification

**Files:**
- Create: `scripts/nar-corpus-metadata-resolver.psm1`
- Create: `scripts/build-nar-corpus-metadata-ledger.ps1`
- Test: `scripts/tests/build-nar-corpus-metadata-ledger.tests.ps1`

**Interfaces:**
- Consumes: normalized rows with `snapshotId`, `sourceRowOrdinal`, `title`, `author`, `landingUrl`, and public metadata evidence.
- Produces: `Resolve-NarCorpusMetadataRows -Rows <object[]>`, returning `catalogRecordId`, `canonicalRecordId`, `duplicateOf`, `disposition`, `reasonCode`, `confidence`, and `evidenceUrls`.

- [ ] Reject null/malformed rows, duplicate ordinals in one snapshot, missing identity fields, payload fields (`narPath`, `archivePath`, `sha256`, `bytes`, `objectKey`), and any fixture path ending in `.nar`.
- [ ] Classify exclusion first. Then duplicate matching normalized title, author, and canonical landing URL; then observed title-specific link; then manifest; otherwise unavailable.
- [ ] Render recursively key-sorted JSON with stable row order and no source fixture path, current time, local cache key, payload hash, or local payload field.
- [ ] Implement the CLI: import the module, validate before resolving, read only the explicit fixture, create only the explicit output root, write only `ledger.json`, and return non-zero on validation failure.
- [ ] Run the integration test and confirm it passes.

### Task 3: Add the narrow CLI boundary and deterministic ordering regression

**Files:**
- Modify: `scripts/tests/build-nar-corpus-metadata-ledger.tests.ps1`

**Interfaces:**
- Consumes: `-FixturePath` and `-OutputRoot`.
- Produces: `<OutputRoot>/ledger.json` only.

- [ ] Add a failing test that reverses fixture-row order and requires byte-identical ledger output sorted by snapshot and ordinal.
- [ ] Extend the CLI only if the deterministic-order regression needs an observable correction; otherwise leave Task 2's boundary unchanged.
- [ ] Run the complete test script and confirm it passes.

### Task 4: Document and verify the local-only boundary

**Files:**
- Modify: `docs/testing/nar-corpus.md`
- Test: `scripts/tests/build-nar-corpus-metadata-ledger.tests.ps1`

- [ ] Document the local fixture command, five dispositions, and the prohibition on payload/network input.
- [ ] Run the resolver test script.
- [ ] Run `./gradlew.bat testDebugUnitTest`.
- [ ] Run `git diff --check` and `git status --short`; verify that scope contains only the resolver, CLI, fixtures, test, documentation, specification, and plan.
- [ ] Commit with subject `test: add metadata-only NAR corpus resolver` once the managed worktree has an appropriate branch.

## Self-review

- Scope is limited to the approved metadata-only checkpoint; acquisition, archive triage, and runtime execution are excluded.
- The fixture, module, and CLI share one `FixturePath`, `OutputRoot`, and `ledger.json` contract.
