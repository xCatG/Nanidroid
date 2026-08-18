# Fixed 23-NAR Corpus Harness Design

## Scope

Repair only the fixed 23-archive regression harness. Production Kotlin, JNI,
CMake, the application manifest, the NAR manifest schema, and the PR #394
rolling metadata ledger remain unchanged. Original `.nar` payloads remain
ignored, local, and untouched.

The repair has two independently testable pieces: make the representative
Snake lifecycle follow the archive's authored dialogue, and add a fail-closed
paired-run comparator that examines every retained behavioral artifact.

## Snake lifecycle

`Snake and Otacon V1.3.2` exposes `choicefirsthehim` from `OnFirstBoot`. After
that choice, it opens `OnNameTeach`. The `OnNameTeach` response visibly exposes
the authored choice `titlenone`; `Select.titlenone` delegates to `NameDone`,
which returns the beginner-mode choices. The source provenance is
`ghost/master/Sn_nameteach.dic`: the visible `titlenone` choice is at line 146
and `Select.titlenone`/`NameDone` is at lines 163-167.

The instrumentation sequence will therefore be:

1. `OnFirstBoot` with `Reference0=0`;
2. authored `choicefirsthehim` through primary `OnChoiceSelectEx`, with the
   current `OnChoiceSelect` fallback only when the primary is not playable;
3. direct `OnNameTeach` with `Reference0=Nanidroid` and empty `Reference1`;
4. only when the input response visibly contains `titlenone`, authored
   `titlenone` through the same primary/fallback transaction;
5. validate that the playable title response visibly contains the authored
   beginner-mode choice IDs `beginyes` and `beginno`.

The harness must never synthesize `faq`. Kotlin instrumentation tests, host
`Get-SnakeDialogueLifecycle`, aggregate sentinels, post-interaction evidence,
and DryRun fixtures all describe the same four-stage lifecycle.

## Paired-run comparator

Add `scripts/compare-nar-corpus-runs.ps1` as a standalone PowerShell 7 command.
It accepts base and candidate report roots, the unchanged 23-NAR manifest, the
new comparison contract, and explicit base/candidate identity declarations:
commit SHA, debug APK SHA-256, and test APK SHA-256. Each evidence root must
match its declared identity before behavioral comparison. Missing, wrong, or
swapped declarations fail. The validated identities remain visible in the
comparison report but are not behavioral-equality leaves.

For each root the comparator requires:

- exactly the manifest's 23 labels in `summary.json`, with no duplicate labels;
- exactly one `<safeLabel>/result.json` per summary row and no additional raw
  result files;
- exactly one `screenshots/<safeLabel>.png` per summary row and no additional
  screenshot files;
- exact label and archive-SHA agreement between manifest, summary row, raw
  result, and static stochastic contract;
- for every manifest `requiredEvidence` name, a property in both the raw result
  and `summary.results[].requiredEvidencePayload`, with exact deep equality;
- exact screenshot SHA-256 equality between base and candidate;
- exact deep equality for `summary.json` and all 23 raw result JSON files after
  only the enumerated metadata normalization and reviewed stochastic handling.

JSON comparison is path-aware and compares object property sets, array lengths
and ordering, scalar types, and scalar values. It does not flatten away missing
properties or turn numbers, booleans, and strings into interchangeable text.

## Normalization and identities

The static contract enumerates all normalizable metadata paths. The only
normalizable categories are run IDs, timestamps, durations, report roots, and
run-owned paths. A value is normalized only when both its JSON path and its
metadata category are declared. Run-owned path replacement is limited to
known path-bearing leaves, including the Yes Man dialogue value that embeds its
private per-run archive path. A change in an adjacent or undeclared leaf is a
behavioral difference.

The comparator does not generically normalize or ignore commit/APK hashes. It
first validates `git.commit`, `apks.debugSha256`, and `apks.testSha256` against
the six explicit side declarations, then excludes only those six already
validated identity leaves from behavioral equality.

## Reviewed stochastic contract

Add `docs/testing/nar-corpus-comparison-contract.json`. It is separate from
`docs/testing/nar-corpus-manifest.json` and the PR #394 rolling metadata ledger.
It permits inequality only at `dialogueProbe.value` for these exact labels:

- `2elf-2.46`
- `LOBO`
- `Snake and Otacon V1.2.1`
- `Snake and Otacon V1.3.1`
- `Snake_Otacon_1.3.1b`
- `Watchdog Bancho`

Each entry binds the exact manifest archive SHA-256 to a finite set of reviewed
UTF-8 value SHA-256 hashes and archive-source provenance. The reviewed hashes
come only from the recovered base/candidate evidence. Provenance identifies the
source entry and exact lines that expose the alternate response(s):

- 2elf: `ghost/master/dic_1templ.txt` lines 3900-3920;
- LOBO: `ghost/master/ghost-bootend.kis` lines 50-52;
- Snake V1.2.1: `ghost/master/Sn_bootend.dic` lines 292 and 390;
- Snake V1.3.1: `ghost/master/Sn_bootend.dic` lines 400 and 402;
- Snake 1.3.1b: `ghost/master/Sn_bootend.dic` lines 395 and 399;
- Watchdog: `ghost/master/ghost-bootend.kis` lines 44-45.

The contract does not claim to enumerate every theoretically reachable random
response. It enumerates every output that this comparator may accept. A run
that produces another legitimate but unreviewed value fails closed and reports
its UTF-8 SHA-256 for explicit source review before the contract can change.
Neighboring fields remain exact, and an allowed value hash is rejected when the
row's archive SHA does not match the contract.

## Host tests and reports

Add a focused PowerShell host test script that constructs a complete synthetic
23-label report pair and executes the real comparator process. The suite covers:

- exact equality;
- result-path and label-set mismatch;
- a raw difference hidden from `summary.json`;
- each enumerated normalization category and rejection of an undeclared field;
- a reviewed stochastic hash;
- an unlisted value hash;
- stochastic archive-SHA mismatch;
- screenshot mismatch;
- missing, wrong, and swapped side identities.

The comparator writes a JSON report when requested and exits nonzero for every
contract violation or behavioral difference. Failure records include artifact,
label, JSON path, and reason without dumping long dialogue values.

## Reproducibility protocol

Paired evidence is collected without selective retries:

1. Start from a clean emulator snapshot and run base versus base first.
2. Restore the same clean snapshot and run base versus candidate.
3. Use the same emulator image/fingerprint, API, ABI, density, corpus bytes,
   manifest, harness parameters, and archive ordering for every run.
4. Preserve the first complete result for each side. If a run fails, report it;
   do not rerun only a differing archive or choose a favorable random output.
5. Supply the exact commit/debug-APK/test-APK identities for both sides to the
   comparator and retain the comparator report with both evidence roots.

The PR #394 rolling metadata remains a separate record and is neither an input
to nor an output of fixed-23 behavioral equality.

## Verification boundary

Run the focused PowerShell host tests, the NAR runner DryRun with the exact
recovered corpus roots when accessible, `compileDebugAndroidTestKotlin`, and
`git diff --check`. Confirm the diff contains no changes under `src/main/jni`,
no CMake changes, no application-manifest changes, and no NAR payloads. Do not
run the full device corpus before implementation and host review are complete.
