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
