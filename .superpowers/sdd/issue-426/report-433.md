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
configuration >=600dp. A 45-minute job bounds provisioning/execution, with the
existing 30-minute instrumentation deadline. Numeric schema/counters, exact
selection/result identities, source/APK hashes, all-pass status and successful
cleanup are checked. No retries, broad connected task, skip-as-pass, private
fixtures or second APK/native build are introduced.

Always-run bounded diagnostics and teardown capture available first streams,
method summary/results, selection, emulator/boot/configuration, logcat/crash and
Gradle reports. App/serial teardown requires matching AVD identity; process
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
- First instrumentation invocation exits42 with one synthetic assertion failure:
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
The original 45-minute limit described above is superseded by the budget below.
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

Observed controls (all driver assertions passed; driver exit0):

- SDK install body exit124; first stdout and stderr remain in `sdk-install.log`.
- Runner execution body exit1 after the outer process timeout; first console
  stdout/stderr and `gate-error.log` remain. The logged timeout arguments confirm
  the unchanged one-runner `-SkipBuild` invocation is wrapped at 2460 seconds.
- Subsequent bounded teardown body exits0 in both no-emulator controls; it cannot
  erase either failed step. `timeout-artifact-inputs.tar.gz` retains report inputs
  for the always-run upload. Hosted upload remains unverified.
- Early-failure summary exit0 and labels verified identity unavailable. Verified
  API31/qemu control writes actual API31/x86_64 record and summary. API37 mismatch
  and non-qemu controls each exit1 and write no verified record. An independent
  API34/arm64-v8a record control appears as those actual values in the summary,
  proving the summary does not hardcode requested identity.

Raw drivers/output/archive: ignored
`.superpowers/sdd/issue-426/433-evidence/round1/`, including `results.log`, the
per-control logs and `timeout-artifact-inputs.tar.gz`. No real suite/device/build
was repeated. Actual integrated first-attempt device/GitHub gates remain
controller-owned and pending; M5 exceptions and parity gaps are unchanged.
