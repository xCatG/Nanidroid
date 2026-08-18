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
the authored choice `titlenone` with visible label `Nope`;
`Select.titlenone` delegates to `NameDone`, which returns the beginner-mode
choices `beginnerStart` and `beginnerEnd`. The source provenance is
`ghost/master/Sn_nameteach.dic`: the visible `titlenone` choice is at line 146
and `Select.titlenone`/`NameDone` is at lines 163-167 and 202-212.

The instrumentation sequence will therefore be:

1. `OnFirstBoot` with `Reference0=0`;
2. authored `choicefirsthehim` through primary `OnChoiceSelectEx`, with the
   current `OnChoiceSelect` fallback only when the primary is not playable;
3. direct `OnNameTeach` with `Reference0=Nanidroid` and empty `Reference1`;
4. only when the input response visibly contains `titlenone`, authored
   `titlenone` through the same primary/fallback transaction;
5. validate that the playable title response visibly contains the authored
   beginner-mode choice IDs `beginnerStart` and `beginnerEnd`.

The harness must never synthesize `faq`. Kotlin instrumentation tests, host
`Get-SnakeDialogueLifecycle`, aggregate sentinels, post-interaction evidence,
and DryRun fixtures all describe the same four-stage lifecycle.

## Paired-run comparator

Add `scripts/compare-nar-corpus-runs.ps1` as a standalone PowerShell 7 command.
It accepts `-ComparisonKind BaseBase|BaseCandidate`, base and candidate report
roots, the unchanged 23-NAR manifest, the
new comparison contract, explicit base/candidate production identities
(commit SHA and debug APK SHA-256), and one shared fixed-harness identity
(harness commit and tree, runner source SHA-256, instrumentation source SHA-256,
and test APK SHA-256). Each evidence root must match its declared production identity
and the same declared harness identity before behavioral comparison. Missing,
wrong, or swapped declarations fail. The validated identities remain visible
in the comparison report but are not behavioral-equality leaves.

`BaseCandidate` additionally requires `-BaseBaseReportPath` pointing to a
successful comparator report. Before reading candidate behavior, it verifies
that prerequisite report's pass status, base production identity, fixed
harness identity, manifest hash, comparison-contract hash, and device
fingerprint/API/ABI/density against the current comparison. A missing, failed,
or mismatched prerequisite exits before candidate comparison. `BaseBase`
produces that prerequisite report and requires both production identities to
be the same. It also records deterministic fingerprints of the exact summary,
23 raw results, and 23 screenshots, and `BaseCandidate` requires its current
base root to match the retained base fingerprint.

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

Each root must also pass independently before equality is considered:
`sentinels.passed=true` with zero failed checks, `failures=[]`,
`unexpectedAbort=false`, `abortedDueToTimeout=false`,
`cleanupVerification=verified`, and every result row must have `passed=true`,
`status=ok`, `cleanup.hostVerified=true`, and no remaining cleanup paths. Two
identically broken runs are a failed comparison, not evidence of equivalence.

JSON comparison is path-aware and compares object property sets, array lengths
and ordering, scalar types, and scalar values. It does not flatten away missing
properties or turn numbers, booleans, and strings into interchangeable text.

## Normalization and identities

The static contract enumerates every normalizable metadata path, category, and
accepted original JSON kind. The only normalizable categories are run IDs,
timestamps, durations, report roots, and run-owned paths. A value is normalized
only when its JSON path, metadata category, and scalar kind are declared. The
only dialogue value with run-ID normalization is `Yes Man-2.1.1`, and it must
contain the exact declared private per-run archive path shape. A run ID embedded
in any other dialogue value, a scalar-kind change, or a change in an adjacent or
undeclared leaf is a behavioral difference.

The runner records `production.commit`/`production.debugApkSha256` separately
from `harness.commit`, `harness.runnerSha256`,
`harness.instrumentationSourceSha256`, and `harness.testApkSha256`. The
comparator does not generically normalize or ignore any of these. It validates
all declared identity leaves first, requires the harness identity to be equal
across both roots, then excludes only those already validated leaves from
behavioral equality.

## Reusable fixed harness execution

The fixed runner accepts an explicit pristine production debug APK and an
explicit fixed-harness androidTest APK instead of always building both from its
own worktree. External-APK mode requires the production commit and harness
commit declarations together with both APK paths. DryRun validates the actual
caller-supplied group as well as its self-probes. The live runner verifies the
harness commit against its own checkout with no tracked or untracked overlays,
hashes the runner source and
`NarCorpusRuntimeTest.kt`, and records those values with the APK hashes.

For base/base and base/candidate, build each production debug APK in its clean
source checkout without overlay changes. Build the test APK once from the
committed fixed harness, then drive all runs with the same committed fixed
runner and that exact test APK. A dirty overlay, an old probe, a locally edited
runner, or a different test APK is not comparable evidence and must fail the
identity gate.

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
UTF-8 value SHA-256 hashes and archive-source provenance. A value hash means
SHA-256 over the decoded JSON string's UTF-8 bytes, without JSON quotes, escape
encoding, BOM, or newline. The reviewed hashes come only from the recovered
base/candidate evidence:

| Label | Base value SHA-256 | Candidate value SHA-256 |
| --- | --- | --- |
| 2elf-2.46 | `981a53a2d44016b31a690ec4e018409aedff65e14eb942fef50994a0f7c41fb9` | `04595b642bef25a3a20d13f22114bcbd7ab5cd895bfdb2fc1743ad4a5d81885b` |
| LOBO | `fa5db680dbf0afece5a61cfb5464bbb69112ed78aff7a972655983de16754b57` | `cf75298961469c0a4e8c8ff4825382b6fe4948fd334c1179d6b5d3d02ff7916e` |
| Snake and Otacon V1.2.1 | `2c1beab312330df48ae066342f3a167efcecc710490948e00d93ca003e1337a1` | `b06b55ab592d277b0d925dcded34927937871b9c267ca1ce34fe75e88d933d07` |
| Snake and Otacon V1.3.1 | `2be87f6181f60deb1018ffccade7ab3cc5ad81fc9fee9608d18447c653923a56` | `b06b55ab592d277b0d925dcded34927937871b9c267ca1ce34fe75e88d933d07` |
| Snake_Otacon_1.3.1b | `b06b55ab592d277b0d925dcded34927937871b9c267ca1ce34fe75e88d933d07` | `bc73531970d16a24ebce414288fb811b835c8a482242fca3631db537f1cca8e1` |
| Watchdog Bancho | `169a2b0b07526e9892419399a4fde6e4c3608c11ffe35b5ac59bf20a9a2609c1` | `bcdb3aeec739afa5e74dac507c8cc8ad21661a06ba2b81d81b707f3df844da2e` |

Provenance identifies the source entry and exact lines that expose the
alternate response(s):

- 2elf: `ghost/master/dic_1templ.txt` lines 3900-3920;
- LOBO: `ghost/master/ghost-bootend.kis` lines 50-52 and word pools in
  `ghost/master/ghost-aitalk.kis` lines 6-11 and 29-35;
- Snake V1.2.1: `ghost/master/Sn_bootend.dic` lines 386-390;
- Snake V1.3.1: `ghost/master/Sn_bootend.dic` lines 398-402;
- Snake 1.3.1b: `ghost/master/Sn_bootend.dic` lines 395-399;
- Watchdog: `ghost/master/ghost-bootend.kis` lines 44-45.

The contract does not claim to enumerate every theoretically reachable random
response. It enumerates every output that this comparator may accept. A run
that produces another legitimate but unreviewed value fails closed and reports
its UTF-8 SHA-256 for explicit source review before the contract can change.
Neighboring fields remain exact, and an allowed value hash is rejected when the
row's archive SHA does not match the contract. The 2elf contract also names its
summary mirrors: `requiredEvidencePayload.dialogueProbe.value` on the 2elf row
and the `observed` field of the sentinel selected by exact name
`slice2-2elf-dialogue-value-nonblank`. The comparator first proves each mirror
equals that root's raw decoded value, then applies the same reviewed hash. It
must never select a sentinel by its current array index.

## Host tests and reports

Add a focused PowerShell host test script that constructs a complete synthetic
23-label report pair and executes the real comparator process. The suite covers:

- exact equality;
- result-path and label-set mismatch;
- a raw difference hidden from `summary.json`;
- each enumerated normalization category and rejection of an undeclared field;
- a reviewed stochastic hash;
- the two reviewed 2elf hashes across raw, required-evidence, and named-sentinel mirrors;
- an unlisted value hash;
- stochastic archive-SHA mismatch;
- screenshot mismatch;
- missing, wrong, and swapped production identities;
- missing or mismatched shared harness identity.
- two identically failed run summaries.

The comparator writes a JSON report when requested and exits nonzero for every
contract violation or behavioral difference. Failure records include artifact,
label, JSON path, and reason without dumping long dialogue values. Success and
failure both atomically replace the requested output, so a later failure cannot
leave a stale passing report behind.

## Reproducibility protocol

Paired evidence is collected without selective retries:

1. Start from a clean emulator snapshot and run `BaseBase`, retaining its
   successful comparator report.
2. Restore the same clean snapshot and run `BaseCandidate` with that retained
   report as its required prerequisite.
3. Use the same emulator image/fingerprint, API, ABI, density, corpus bytes,
   manifest, harness parameters, and archive ordering for every run.
4. Preserve the first complete result for each side. If a run fails, report it;
   do not rerun only a differing archive or choose a favorable random output.
5. Supply each side's exact production commit/debug-APK identity and the shared
   fixed harness commit/runner-source/instrumentation-source/test-APK identity
   to the comparator. Retain the comparator report with both evidence roots.
6. Execute the documented sequence fail-fast: inspect the base/base exit code
   and do not invoke base/candidate after a failed prerequisite.

The PR #394 rolling metadata remains a separate record and is neither an input
to nor an output of fixed-23 behavioral equality.

## Verification boundary

Run the focused PowerShell host tests, the NAR runner DryRun with the exact
recovered corpus roots when accessible, `compileDebugAndroidTestKotlin`, and
`git diff --check`. Confirm the diff contains no changes under `src/main/jni`,
no CMake changes, no application-manifest changes, and no NAR payloads. Do not
run the full device corpus before implementation and host review are complete.
