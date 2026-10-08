# Issue #433 — self-contained device CI

Workspace: `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`.
Loaded instructions: workspace-root `AGENTS.md`, supplied session instructions,
`.superpowers/sdd/issue-426/issue-433.md`, and
`docs/superpowers/plans/2026-10-07-issue-426-test-suite-improvement.md`.
Branch: `codex/433-self-contained-device-ci`; base/source input:
`a1398b527b10549ca7fdea052088a053921dfe09`.

## Change and contract

Only `.github/workflows/android-build.yml`, the new CI usage/results section in
`docs/testing.md`, and this public-safe report change. Existing build name,
runner, triggers, tasks, read-only permissions, reports and `android-apks` upload
remain. The build adds schemaVersion 1 provenance for exact relative app/test
APK paths, hashes and actual checkout HEAD. The dependent Ubuntu 24.04 device
job verifies source/path/file/hash and rejects symlink components before install;
it consumes that same-run artifact with the stable runner's `-SkipBuild`.

One job-owned Google APIs API31 x86_64 AVD uses port5554/`emulator-5554`, headless
SwiftShader and required KVM acceleration. Initial occupied/offline serials are
rejected. Bounded boot checks verify emulator process, state/boot/API/ABI/qemu,
AVD identity, actual 1080×2400/420dpi/portrait/font1 settings and usable window
configuration >=600dp. A 90-minute outer job safety limit contains explicitly
bounded provisioning/execution steps and reserved teardown/upload time; the
existing 30-minute instrumentation deadline remains unchanged. Numeric schema/counters, exact
selection/result identities, source/APK hashes, all-pass status and successful
cleanup are checked. No retries, broad connected task, skip-as-pass, private
fixtures or second APK/native build are introduced.

Always-run bounded diagnostics and teardown capture available first streams,
method summary/results, selection, emulator/boot/configuration and logcat/crash
logs in the device artifact. Build-job Gradle reports are retained separately in
`android-reports`. App/serial teardown requires matching AVD identity; process
fallback verifies recorded PID ownership and treats exited zombies as stopped.
Failures remain failed even when teardown succeeds; teardown failure is also
nonzero. A unique artifact and readable Actions summary expose evidence and
actual results. Supported `-gpu swiftshader` follows the official
[Android graphics acceleration documentation](https://developer.android.com/studio/run/emulator-acceleration).

Toolchain pins remain JDK17, platform `android-37.0`, build tools `37.0.0`,
NDK `28.2.13676358`, CMake `3.22.1`, and the existing wrapper/catalog. The device
job installs/logs its emulator and exact Google APIs API31 x86_64 image; it does
not require the compilation toolchain or invoke Gradle.

## Local evidence

Raw evidence and saved validation drivers are ignored under
`.superpowers/sdd/issue-426/433-evidence/`. Commands:

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/validate.sh
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/synthetic.sh
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/final-check.sh
```

The first driver uses Python3/PyYAML6.0.1 to parse workflow YAML, extracts actual
run bodies, runs `bash -n` and PowerShell AST parsing, then runs on Linux:

```powershell
pwsh -NoProfile -File tools/test-device-suite.ps1 -ListOnly -Suite self-contained
pwsh -NoProfile -File tools/test-device-suite.ps1 -SelfCheck
```

Linux `pwsh` is the supplied `/tmp/nanidroid-426-pwsh/pwsh`. Host discovery reports
**163 inventoried methods, 117 self-contained selections**. YAML/Bash/PowerShell
syntax and Linux host checks exit zero. No Git is needed for these host modes.

The second driver executes actual workflow step bodies in a new synthetic Linux
Git checkout of the accepted runner/inventory/test declarations, with fake
SDK/adb/emulator executables and deliberately non-installable APK bytes. The
real portable runner runs there with `-SkipBuild`; no Android installation,
Gradle/native build or real device suite occurs. Controlled results:

- Boot process exits deliberately: actual boot step exits1, emulator/boot logs
  remain. Unknown owned-AVD identity also makes teardown exit1; the boot failure
  remains the primary failure.
- First instrumentation invocation exits 42 with one synthetic assertion failure:
  workflow exits1. Selected117, observed1, passed0, skipped0, failed1,
  incomplete116; runner cleanup passed. Raw stdout/stderr, summary and logcat
  remain. Later workflow teardown exits0 and cannot erase the original failure.
- A synthetic all-pass control runs through the real runner and strict summary
  validator; selected/observed/passed117, skipped/failed/incomplete0, cleanup
  passed. A separate unchanged-summary positive control proves validator tests
  are executable rather than parser failures. These are **synthetic transcripts**,
  not executed Android method passes.
- Duplicate identity, skipped result, wrong summary source/hash, failed cleanup
  and missing counter each exit1. Wrong APK source/hash and escaping relative
  path each exit1 before runner installation.
- Local `boot-failure-artifact-inputs.tar.gz` and
  `runner-failure-artifact-inputs.tar.gz` and final `boot-final-artifact-inputs.tar.gz` preserve the same report paths supplied
  to the always-run upload step. The driver verifies the always-run upload/cleanup
  configuration, unique artifact contract and absence of continue-on-error.
  This proves retained **upload inputs**, not a hosted GitHub artifact upload.

Additional actual display-check controls accept physical-baseline and valid override
outputs, and reject wrong active size/density overrides (exit1). Original trigger,
build-task/name/runner, artifact-name and read-only permission preservation checks
also pass. These drivers are retained beside the other ignored evidence.

One read-only `emulator-5580` window dump was inspected to check the API31 usable
viewport parser's format. No app/device changes were made. Its window
configuration exposes usable width/height in dp; the workflow validates that
actual configuration rather than assuming total screen pixels equal usable
fixture space.

## Remaining execution gates and limits

Controller owns independent review, one final committed-source first-attempt
117-method run on the already authorized `emulator-5580`, and actual GitHub
publication/workflow execution. They were not executed by this implementer.
Real selected/executed/passed/skipped/failed/incomplete device counts and real
emulator cleanup results remain **pending**; local synthetic results cannot fill
those gates. GitHub image installation, hardware acceleration, actual boot,
artifact upload and complete hosted job behavior remain unverified until that
run. No push, PR, merge or user-device installation was performed here.

M5's accepted Compose/IME exceptions and missing LOBO Pixel setting cycles remain
accepted exceptions; no API37/arm64/private-corpus or screenshot parity is claimed.

## Review fix round 1 — bounded failure before evidence deadline

Input review: `.superpowers/sdd/issue-426/review-433.md`; input revision
`b5b624caa7ce8bb8007de339cbc95e5dbcb245f1`. The Important timeout finding and
same-file Minor identity finding are addressed in the owned workflow/docs/report.
The original implementation's 45-minute limit was superseded by the budget below.
No runner, instrumentation predicate, APK/native build, dependency or retry changes.

The stable runner's existing process bounds can total 2400 seconds: five
30-second preflight calls, two 180-second installations, 1800-second instrumentation
and three 30-second logcat/cleanup/health calls. A 2460-second GNU `timeout` wrapper
with 10-second kill escalation allows those existing bounds plus 60 seconds of
host/reap overhead; the execution step itself is bounded at 42 minutes. An
unexpected runner/process stall fails this step and leaves the subsequent
always-run job teardown responsible for the owned emulator.

Every device-job step now has an explicit supported step timeout. All steps
before teardown total at most 75 minutes. The outer job safety limit is 90 minutes,
reserving 15 minutes: bounded teardown3, upload5, summary1 and scheduling margin6.
The SDK installation command has a 300-second timeout plus 10-second kill
escalation inside an eight-minute step; other setup commands are bounded too.
This lets normal process/step timeouts fail earlier than job cancellation and
leaves time for the diagnostics artifact attempt. The outer deadline remains a
final safety limit, not the primary SDK/test timeout.

Boot records captured API/ABI into `verified-device.json` only after AVD, API,
ABI and qemu checks pass. The Actions summary reads those actual values. Before
identity verification it states `unavailable`, while API31/x86_64 is explicitly
labeled as requested configuration. Device identity does not imply test success.

Command:

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/round1/check.sh
```

The saved driver parses the amended YAML, Bash and PowerShell bodies, asserts
75+9+6=90 minutes and the exact production process deadlines, then executes actual
installation/execution/summary bodies with fake SDK/runner inputs. A timeout test
double records the production timeout arguments and shortens only the exact
300s/2460s intervals to 0.5 seconds; real GNU `timeout` terminates sleeping test
processes. This is an accelerated local control, not a real SDK install or hosted
step cancellation. Linux host checks were not repeated because runner/manifest
and list/self-check invocation bodies did not change.

Observed controls (all driver assertions passed; driver exit 0):

- SDK install body exit124; first stdout and stderr remain in `sdk-install.log`.
- Runner execution body exit1 after the outer process timeout; first console
  stdout/stderr and `gate-error.log` remain. The logged timeout arguments confirm
  the unchanged one-runner `-SkipBuild` invocation is wrapped at 2460 seconds.
- Subsequent bounded teardown body exits0 in both no-emulator controls; it cannot
  erase either failed step. `timeout-artifact-inputs.tar.gz` retains report inputs
  for the always-run upload. Hosted upload remains unverified.
- Early-failure summary exit 0 and labels verified identity unavailable. Verified
  API31/qemu control writes actual API31/x86_64 record and summary. API37 mismatch
  and non-qemu controls each exit1 and write no verified record. An independent
  API34/arm64-v8a record control appears as those actual values in the summary,
  proving the summary does not hardcode requested identity.

Raw drivers/output/archive: ignored
`.superpowers/sdd/issue-426/433-evidence/round1/`, including `results.log`, the
per-control logs and `timeout-artifact-inputs.tar.gz`. No real suite/device/build
was repeated. Actual integrated first-attempt device/GitHub gates remain
controller-owned and pending; M5 exceptions and parity gaps are unchanged.

## Controller final local gates — tested source 8df2d74d

The controller supplied the following actual first-attempt integrated evidence,
then this implementer read the retained build/count/provenance/summary/sentinel
files and verified the summary's exact identity set, 117 passing result records,
and source/APK hash correspondence without executing tests or touching a device.
Tested source was clean `8df2d74d4a2bf65b1a8f161a89796f1b09dc5caa`.
The later report-only commit records this evidence; it is **not** the source hash
recorded in the executed suite. Production/test/runner/workflow inputs are
unchanged by that metadata commit.

Controller build command (JDK17 and the pinned local Android SDK):

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain --rerun-tasks
```

Actual build result: successful in 33 seconds, all 85 actionable tasks executed.
JUnit reports contain 26 classes and 380 total cases: 376 passed, four Windows
symlink prerequisite skips, zero failures/errors. Lint: zero errors, 26 warnings.
These four host/JVM skips are distinct from the device gate's zero skips.
`final-local-apk-provenance.json` binds the debug APKs to tested source `8df2d74d`:

| APK path relative to APK root | SHA-256 |
| --- | --- |
| `debug/app-debug.apk` | `e03ae86f3897576808e14210d46b95700ea7dfcd0caeb7a4190046c204942906` |
| `androidTest/debug/app-debug-androidTest.apk` | `8b52bc20de266d568eb912e21cfbc7c5e58e8af390f5d1c21ba733928abcdbac` |

The controller invoked the stable runner **once**, with `-Suite self-contained`,
`-Serial emulator-5580`, explicit installed SDK adb, fresh output directory
`.superpowers/sdd/issue-426/device-final-8df2d74d`, and `-SkipBuild`.
Actual API 31/x86_64 result: 117 selected, 117 observed, 117 passed, zero skipped,
failed or incomplete; elapsed 148.7149062 seconds; cleanupStatus/outcome passed.
Sorted observed identities equal selected identities exactly once, all 117 result
records passed, and summary source/app/test hashes match the local provenance.
There is no first failure in this final first-attempt device run.

Before/after fixture sentinel hashes are identical. The retained
`final-owned-roots-after.log` is empty (zero bytes), confirming the final
invocation-owned fixture-root listing has no entries; the controller recorded
adb exit zero for that read-only listing. The stable runner's
cleanup result covers its app force-stop/health contract. This local existing
emulator remains controller-owned; this evidence does not claim execution of the
CI job's separate job-owned emulator teardown. Git status was clean after the
controller's gates and before this report-only change.

Retained ignored evidence under `.superpowers/sdd/issue-426/`:
`final-build-gate.ps1`, `final-build.log`, `final-jvm-counts.json`,
`final-lint-counts.json`, `final-local-apk-provenance.json`,
`final-device-console.log`, `device-final-8df2d74d/summary.json` and its first raw
runner streams/diagnostics, and `final-sentinels-before.log` /
`final-sentinels-after.log` and empty `final-owned-roots-after.log`. No build, test or device invocation was repeated for this
metadata reconciliation.

The final local first-attempt suite gate is now passed on the source above.
Actual GitHub job execution, image/KVM provisioning, same-run hosted artifact
consumption/upload and CI job-owned emulator cleanup remain pending. The local
run and controlled failure inputs do not establish those hosted results. M5
accepted exceptions, LOBO Pixel setting-cycle gap and parity limits remain.

## First hosted failure and host-runtime correction

[Hosted run 37732038414](https://github.com/xCatG/Nanidroid/actions/runs/37732038414)
failed at `Install emulator and image`, exit 127. The first red is retained in
`.superpowers/sdd/issue-426/ci-440-37732038414-failed.log` and the downloaded
`device-self-contained-37732038414-1` artifact (id 11529837764), under ignored
`.superpowers/sdd/issue-426/433-evidence/hosted-37732038414/reports/device-self-contained/`.
That unique diagnostic artifact was actually uploaded and downloaded; this is
hosted failure-retention evidence, not a successful hosted device gate.

The exact failing command was:

```bash
timeout 30 "$ANDROID_HOME/emulator/emulator" -version > "$DEVICE_REPORT/emulator-version.log" 2>&1
```

`emulator-version.log` retains the actual dynamic-loader error:

```text
/usr/local/lib/android/sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64: error while loading shared libraries: libpulse.so.0: cannot open shared object file: No such file or directory
```

`sdk-install.log` reached 100%; the emulator/image download was not the failing
operation. Host list/self-check and same-run APK verification had passed.
The checkout/provenance source was the PR merge revision
`67fcae53f4e3973f55b3f4577507c9634c3058e9`, not the local report-only branch head.
The artifact contains the 117-method selection and no instrumentation summary:
execution/pass/skip/test-failure/incomplete result counts were not produced,
because the infrastructure failure occurred before emulator boot or tests.
Owned-emulator teardown had no launched emulator to stop and recorded exit 0.
This first failed run remains failed; no unchanged retry was requested.

Root-cause fix: the device setup installs Ubuntu `libpulse0` before invoking the
emulator. This is an ephemeral CI host runtime library, not an Android product,
catalog or native-source dependency. Ubuntu's
[libpulse0 package](https://packages.ubuntu.com/en/noble/libs/libpulse0) provides
the missing client runtime. The combined apt update/install command has a
90-second process deadline plus five-second kill escalation and its own retained
`emulator-runtime-install.log`. SDK installation keeps its 300-second deadline
plus ten-second kill escalation; six metadata commands now have ten-second
bounds. Their total worst-case process allocation is 465 seconds inside the
existing 480-second setup step. Pre-teardown 75 / evidence 9 / scheduling 6 still
fit the 90-minute outer safety limit. Test predicates, one runner invocation,
read-only repository permissions and pinned Android/JDK toolchain are unchanged.

Review P3 inline 4215139203 is also corrected: this fresh device job downloads
only `android-apks`. Build-job Gradle/JVM/lint reports live in the separate
`android-reports` artifact (first hosted run id 11530068181), while
`device-self-contained-<run-id>-<attempt>` holds device/host-selection/setup
and instrumentation diagnostics. Docs/report now direct readers to both rather
than claiming the device artifact contains build-job reports.

Focused validation commands:

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/hosted-fix/runtime-real.sh
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/hosted-fix/check.sh
```

The first driver downloaded the official stable Linux emulator archive, verified
its repository-published SHA1, and ran only `-version` and linked-library checks.
Actual emulator 37.2.12.0/build 16428233 version command exited0; `ldd` resolved
`libpulse.so.0` to `/lib/x86_64-linux-gnu/libpulse.so.0`. The Ubuntu validation host
already has real `libpulse0` version 1:16.1+dfsg1-2ubuntu10.1 installed. This
version-only check is not an emulator boot, system-image install or hosted run,
and does not claim identical emulator bytes to the earlier hosted failure.

The second driver parses the amended workflow YAML/Bash/PowerShell, validates
setup ordering and 465<480-second allocation, and executes the actual old/fixed
install-step bodies. Explicit test doubles cover apt/image/adb/JDK/KVM operations
to avoid host mutation and image/device provisioning. The emulator's version
operation invokes the actual downloaded ELF executable after the control confirms
real `libpulse0` package availability. Results:

- Old body with an empty-runtime marker reproduces the retained loader error and
  exit 127; the original hosted red remains unchanged. This local empty-runtime
  marker is a synthetic prerequisite control, not removal of a host library.
- Fixed body performs the bounded libpulse install call before emulator invocation,
  then the real emulator version operation passes; whole step exit 0.
- A controlled apt failure exits 42 before emulator invocation and retains first
  package-error output in `emulator-runtime-install.log`.
- YAML/Bash/PowerShell parsing, command ordering and time-budget assertions pass.

Raw output and saved drivers are ignored under
`.superpowers/sdd/issue-426/433-evidence/hosted-fix/`, including old/fixed-step logs,
`actual-emulator-version.log`, `actual-qemu-ldd.log` and package-failure logs.
No full build, app/native build, device suite, emulator boot, push or workflow
retry was performed for this correction. The earlier local 117/117 gate remains
valid for its recorded 8df2d74d source; a corrected hosted job is still pending
controller publication/review/execution. M5 exceptions and parity gaps remain.

## Second hosted failure — explicit job-owned AVD location

[Hosted run 37733916735](https://github.com/xCatG/Nanidroid/actions/runs/37733916735)
passed Build/local checks and emulator/image installation, confirming the
`libpulse0` correction on the hosted runner. `emulator-version.log` reports
37.2.12.0/build 16428233. Boot preparation then failed exit 1 before emulator
launch or instrumentation. Same-run checkout source was
`8f52fc38cdeb224ae2304f23377a2e38411f9a9f`.

The first boot failure is retained in
`.superpowers/sdd/issue-426/ci-440-37733916735-failed.log` and actual downloaded
`device-self-contained-37733916735-1` artifact id 11530834559, under ignored
`.superpowers/sdd/issue-426/433-evidence/hosted-37733916735/reports/device-self-contained/`.
`boot.log` shows successful AVD creation, followed by Bash line 14 failing to
append hardware settings at the workflow's assumed path:

```text
/home/runner/.android/avd/nanidroid-ci-37733916735-1.avd/config.ini: No such file or directory
```

The artifact establishes that the expected config was absent after the creator
succeeded; the creator's actual default location was not logged. The failing
workflow assumed that SDK tool creation and emulator discovery always use
`$HOME/.android/avd`. No emulator PID was recorded, teardown exited 0, and no
runner result counts were produced. Both earlier hosted failures remain retained
and failed; no unchanged retry was performed.

The workflow now sets shared job-owned `ANDROID_USER_HOME`,
`ANDROID_EMULATOR_HOME` and `ANDROID_AVD_HOME` under `RUNNER_TEMP`, logs those
paths, passes the exact AVD content directory through `avdmanager -p`, and reads
`config.ini` from that directory. The background emulator inherits the same
registry settings. The documented
[AVD path option](https://developer.android.com/tools/avdmanager) and
[Android environment variables](https://developer.android.com/tools/variables)
provide these explicit creation/discovery inputs. Port/serial, image, toolchain,
display checks, one runner invocation and timeout/evidence reserve are unchanged.

Focused validation command:

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/avd-path-fix/check.sh
```

The saved driver parses amended YAML/Bash/PowerShell and executes the actual old
and fixed preparation boundaries through config copy, stopping before emulator
launch. An explicit avdmanager test double models successful creation at a
non-HOME default path and honors `-p`; this is a **synthetic path control**, not an
actual SDK AVD/image creation or boot. Old body exits 1 with the missing config
error; fixed body exits 0, uses the explicit shared registry/content path, and
retains 1080×2400/420dpi/portrait settings. A creator failure exits 42 before
configuration, with its first error retained in `boot.log`. Driver exit 0;
raw scripts/logs remain in ignored `433-evidence/avd-path-fix/`.

No local build, device suite, emulator launch, runner/test/product/native change,
push or workflow retry was performed. Corrected hosted boot/instrumentation and
job-owned emulator cleanup remain pending controller publication/review/execution.
Build reports remain in `android-reports`; device setup/test diagnostics remain
in the unique device artifact. M5 exceptions and parity limits are unchanged.

## Hosted baseline readback failure and bounded convergence

Hosted run 37743603204 (source e0c587c6547cb5444ea29f228d07ab4658f5cf26) passed build and APK provenance verification but failed the immediate `accelerometer_rotation == 0` boot guard before instrumentation. The downloaded first artifact remains in ignored `hosted-final-37743603204/device/reports/device-self-contained/`; boot.log retains the failed guard, verified-device.json identifies API 31/x86_64, and physical size/density were 1080x2400/420dpi. Teardown exited 0. No runner summary or instrumentation result exists. The old assertion discarded its returned value, so asynchronous settling or another settings writer is a hypothesis, not a proven cause.

Starting from rebased source c8fa2efa7976fa4539704c3566b9b9b063794f8a, the workflow now gives only the owned emulator's three baseline settings a 30-second configuration convergence window. It retains every raw read/write stream, parsed sample, and first mismatch/error. Rotations accept only 0 or 1; font values must be ordinary positive decimals, with numeric 1 normalized to 1.0. Malformed responses and command failures fail immediately. Valid mismatches reapply only the three existing idempotent baseline writes. Success requires two consecutive desired 0/0/1.0 samples; a later mismatch resets the streak. Individual commands have 3-second deadlines within the overall bound. Instrumentation still runs once, and viewport predicates, job deadlines and evidence reserve remain unchanged.

Focused validation command:

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/yenchi/.codex/worktrees/bd56/Nanidroid/.superpowers/sdd/issue-426/433-evidence/settings-convergence/check.sh
```

The driver extracts the actual amended workflow configuration body and uses a synthetic adb settings control, not a real emulator. YAML parsing, Bash syntax and Linux PowerShell AST parsing passed. Settling passed with 9 reads/3 writes (2.355 seconds); a mismatch interrupting the streak passed with 15 reads/6 writes (4.613 seconds). Read and write failures exited 7 and 9. Null, invalid rotation, escaped-newline rotation, NaN, zero, exponent, internal carriage return and extra-line font responses each exited 1 without reapplication. A command timeout exited 124 in 3.042 seconds with partial stdout/stderr retained. Permanent mismatch exited 1 in 29.191 seconds with raw samples and the first mismatch retained and no verified-settings file. The covering driver exited 0. Extracted scripts, control outputs and raw diagnostic files remain in ignored `433-evidence/settings-convergence/`.

No local build, JVM/device suite, real emulator boot or instrumentation was repeated. The underlying hosted readback cause remains unproven; corrected hosted execution is pending controller review/publication. Gradle/JVM/lint evidence remains in `android-reports`, while these setup diagnostics belong to the unique device artifact. M5 exceptions and parity limits remain unchanged.