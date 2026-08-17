# Phase 1 shipped-state audit

## Decision

Path A is selected. The owner attestation establishes that no state-capable APK
or release was distributed through any channel, so compatibility handling for a
distributed predecessor is not required. Later cleanup remains subject to the
authorization and non-authorization boundaries below. The machine-readable
ledger is [`phase1-shipped-state-ledger.json`](phase1-shipped-state-ledger.json),
checked by [`verify_phase1_shipped_state_audit.py`](../../tools/verify_phase1_shipped_state_audit.py),
and the decision is tracked with [issue #382](https://github.com/xCatG/Nanidroid/issues/382).

## Owner attestation

On 2026-08-17 the owner attested: “No APK built from 19da89d3f4d1faaaaaae3e000b8bc852f73c2c38 or later was released or distributed; the signing key has not been recovered.” This is authoritative for deleted, private, or otherwise unrecorded distribution.

## Effective writer epochs

| Epoch | Full commit | Effective writer |
| --- | --- | --- |
| NAR queue / WorkManager | `19da89d3f4d1faaaaaae3e000b8bc852f73c2c38` | queue persistence, worker identities, recovery receivers |
| Durable operation store | `ec78fcc282c0a528f371609fca0e66fbf773b5ff` | durable operation records and supervisor |
| Transactional ghost update | `19956d7f5f2406e045c819593e761a4c1fb08ae6` | journaled ghost update and recovery |

All epochs retain application ID `com.cattailsw.nanidroid`, version code `6`,
and version name `open_0.1`.

## Distribution evidence

The dated GitHub observation recorded zero releases and zero tags, 192 Actions
artifacts, zero post-writer APK-like artifacts, and two later report-only
artifacts. Version identity was reused across writer epochs. The historical APK,
signing identity/hash, and release toolchain are unavailable; see
[`PR_A_BASELINE.md`](PR_A_BASELINE.md). These observations are recorded in the
ledger evidence IDs `github-releases-tags-empty-2026-08-17`,
`github-actions-no-post-writer-apk-2026-08-17`, and
`baseline-artifacts-unavailable`.

## Persisted-state capability

The NAR queue persists `v1` through `v4` in
`nar-download-queue.xml#records-v1`, but its production decoder returns an empty
view for an unknown version and skips malformed rows. The inventory therefore
classifies it as lossy and fail-closed: an empty decode is never evidence that
the preference contains no state. The durable-operation store remains a strict
`v1` through `v6` contract, including its quarantine value and recovery marker.

The WorkManager inventory records all four worker FQCNs, their exact unique-work
names, and UUID history per kind: legacy/random install requests; early random
local-stage and ghost-update requests; current deterministic
`durableWorkManagerId-v1` requests for local-stage, install, and update; and
random recovery-worker requests. It also records the manifest identities of the
download-complete, boot/package-replaced, durable-attention, and update-service
components, plus the exact component/action/data/request-code identities of the
notification `PendingIntent`s that may outlive a process.

Install staging is split by topology. Cache attempts use
`nar-install-attempts/<64hex-item-hash>/<UUID>/nar-import-<24hex>.zip`; external
ghost installation uses
`.nanidroid-install-staging/candidate-<32hex>/{staged-<32hex>.nar,tree}`. A ghost
update owns neither `.nanidroid-update-*` nor `.nanidroid-staging-*` by prefix.
Ownership requires the exact operation and transaction digests, canonical
candidate and backup paths, a readable matching `journal.v1` or complete
`journal.v1.tmp`, the per-ghost lock, a matching private owner marker, and a
valid live/candidate/backup topology. Ambiguous topology and
`.nanidroid-update-writing-*` residue are preserved.

Runtime keys `lastrunghost`, `createcount_ghost*`, and `keylaunchtime` are
recorded in `CATTAILSW_NANIDROID_PREFS.xml`. The co-resident default-preference
keys `enable_analytics` and `firstRun` are inventoried separately in
`com.cattailsw.nanidroid_preferences.xml`, preventing whole-file cleanup from
silently removing unrelated state. The ledger also binds the manifest's full
backup and data-extraction rules: the queue and durable-operation preference
files are excluded from full backup, cloud backup, and device transfer, while
unlisted state retains Android's default inclusion behavior.

DownloadManager row IDs and files, persisted URI grants, private local imports,
and shared `/sdcard/nar` storage remain inventoried. The shared temporary name is
the actual `File.createTempFile("nanidroid", "tmp", ...)` shape—there is no dot
inserted before `tmp`—and names or prefixes never establish cleanup ownership.
All app-owned, platform-owned, runtime-retained, journaled, and foreign-preserve
resources remain protected by the ledger's no-cleanup policies.

## Why Path B and Path C are not selected

Path B's sequential-upgrade requirement and Path C's compatibility-removal
floor are unnecessary because no state-capable distributed build exists within
the supported population. The absence of a recovered signing key reinforces
that the owner attestation, rather than an APK hash comparison, is governing
distribution evidence.

## Limitations

Deleted or private distribution is established by owner attestation rather than
Git metadata. GitHub observations are dated snapshots, not permanent facts, and
cannot disprove deletion or private distribution.

## Cleanup authorization

Later PRs may remove compatibility handling, but each deletion still needs
reachability, security, and current-state tests.

## Non-authorization

This audit does not authorize deleting runtime preferences, user-owned archives,
installed ghosts, or weakening the transactional installer.

## Verification

The exact focused checks are:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
python tools/check_repository_hygiene.py
python tools/verify_environment.py
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug
```

The first two commands validate this audit's ledger and evidence contract;
hygiene, environment, unit, and assembly checks remain repository-level gates.
