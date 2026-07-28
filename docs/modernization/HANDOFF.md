# Modernization handoff

**Authoritative branch:** `feature/modernization`
**Recorded head:** `df157f7adcacc785374a1b56346157ffb5be01e6` (PR #110)
**Stable branch:** `master` remains unchanged at `ee5249128687618e21ee24d127e13eb13f3ca653`.

This document is a handoff for continuing the modernization program.  It is
deliberately evidence-based: "complete" below means merged to
`feature/modernization` and verified at the stated boundary; it does **not**
mean the entire application is now Kotlin-only.

## Current, verified state

The app builds with AGP 9.3.0, Gradle 9.5.0, JDK 17, `compileSdk = 37`,
`targetSdk = 37`, and `minSdk = 31` (Android 12).  The active Gradle source
sets include both `src/` and `modern/src/`; Kotlin and Java are therefore both
compiled today.

The modernization branch includes:

- A containerized historical build/reference lane and a modern Gradle/CI lane.
- CMake-based native build work and packaged emulator/device native artifacts.
- Characterization tests for parsers, Sakura Script, SHIORI, surface data,
  rendering, animation, ghost switching, and NAR installation behavior.
- Hardened NAR installation boundaries: archive preflight and limits,
  path validation, staging, file-system inspection, retained-overlay handling,
  and clear user-facing failure categories.
- Compose-based ghost-stage presentation, including surface composition,
  animation, balloon rendering, hit testing, and interaction compatibility.
- Kotlin migrations for much of the domain, runtime, renderer, presentation,
  service, activity, networking, and simple-dialog code.
- A Firebase Crashlytics integration stub replacing the previous ACRA
  spreadsheet reporting path.  Release/project Firebase configuration and
  privacy/release policy still need product-owner completion before claiming
  production crash reporting.

PR #110 was built as both APK and AAB, installed on an API 36.1 emulator, and
launched through the Compose ghost stage.  This establishes a smoke-test
baseline, not exhaustive device compatibility.

## What remains

### 1. Finish the active Java-to-Kotlin migration

There are **29 active, compiled Java production files** under `src/`.  Each has
an issue so that the remaining work is reviewable and independently testable.
The frozen `legacy/` tree is a reference/build artifact and is intentionally
out of scope for this count.

| Area | Remaining classes | Tracking issues |
| --- | --- | --- |
| Debugging / shell | `ViewServer`, `SScriptRunner`, `ShellSurface` | [#111](https://github.com/xCatG/Nanidroid/issues/111), [#112](https://github.com/xCatG/Nanidroid/issues/112), [#113](https://github.com/xCatG/Nanidroid/issues/113) |
| Legacy dialogs | `DbgMsgDlg`, `EnterUrlDlg`, `ErrMsgDlg`, `GhostListDialogFragment`, `HelpFuncDlg`, `MoreGhostFuncDlg`, `NarPickDlg`, `NoReadmeSwitchDlg`, `NotImplementedDlg`, `ReadmeDialogFragment`, `UserInputDlg`, `UserSelectDlg` | [#114](https://github.com/xCatG/Nanidroid/issues/114)–[#125](https://github.com/xCatG/Nanidroid/issues/125) |
| NAR installer | `NarFilesystemInspector`, `NarInstallPlan`, `NarInstallPlanResult`, `NarInstallPlanValidator`, `NarStagedSource`, `NarStagedSourceCopyError`, `NarStagedSourceCopyResult`, `NarTransactionalInstaller`, `NarVerifiedInstallSession`, `NarZipCentralPreflight` | [#126](https://github.com/xCatG/Nanidroid/issues/126)–[#135](https://github.com/xCatG/Nanidroid/issues/135) |
| Native SHIORI bridges | `JNIShiori`, `Kawari`, `SatoriPosixShiori` | [#136](https://github.com/xCatG/Nanidroid/issues/136)–[#138](https://github.com/xCatG/Nanidroid/issues/138) |
| Utility | `NarUtil` | [#139](https://github.com/xCatG/Nanidroid/issues/139) |

For each migration, first add or retain characterization coverage, preserve
Java-visible and JNI-visible names/signatures where required, update callers,
then delete the active `.java` source.  Do not convert by mechanically changing
syntax alone: `ShellSurface`, the dialogs, and the SHIORI/native boundaries have
lifecycle and compatibility behavior that needs explicit design review.

### 2. Complete installer integration and recovery testing

The secure installer building blocks exist, but the continuation must verify
their use through every import path (local file, content URI, and network) and
real ghost update path.  Required test cases include interruption, corrupted
archive, duplicate/conflicting entries, destination collisions, insufficient
space, retained-overlay failure, cancellation, and retry.  A failure must leave
no selectable partial ghost and should present the most specific safe error
rather than only "incompatible ghost update".

The agreed compatibility baseline is conservative: reject an already-existing
target directory; validate the complete archive before mutation; enforce the
documented limits; stage the install; then commit atomically.  Atomic overwrite
or in-place upgrade support is future work, not an implied compatibility
promise.

### 3. Turn smoke validation into release validation

Keep CI green for the Gradle and legacy/reference lanes, then add repeatable
API 36/37 emulator coverage for launch, install/import, ghost selection,
surface interaction, balloon interaction, process recreation, and a native
SHIORI request.  Produce signed release APK/AAB artifacts only after signing,
Firebase configuration, privacy disclosure, and release/version policy are
explicitly decided.

### 4. Resolve remaining product and maintenance decisions

- Decide whether `ViewServer` remains a supported debug feature, is replaced,
  or becomes debug-only; do not silently preserve an insecure network listener.
- Audit exported components, incoming intents, URI grants, cleartext/network
  redirects, and dependency/update policy against Android 12+ requirements.
- Establish Crashlytics data handling, consent/privacy text, and a real
  Firebase project configuration before enabling production reporting.
- Document supported ABI/device scope and native-engine lifecycle guarantees;
  native code is intentionally not part of the Kotlin-only target.
- Once all active Java issues are closed, remove obsolete View/XML-era paths
  only after tests prove their Compose replacements cover the same behavior.

## Recommended execution order

1. Installer model and utility issues (#126–#135, #139), with failure/recovery
   tests before behavior changes.
2. SHIORI bridge issues (#136–#138), preserving JNI contracts and adding
   repeated load/request/unload coverage.
3. Dialog and shell issues (#112–#125), replacing modal behavior with Compose
   only where the UX contract is characterized.
4. `ViewServer` (#111), after deciding whether it should survive at all.
5. A final Kotlin-only source audit, emulator regression pass, release build,
   and documentation refresh.

Each PR should target `feature/modernization`, remain narrowly scoped, include
the relevant tests, pass CI, and receive the requested `@codex code review`
comment before merge.  Never merge modernization work directly to `master`.

## Useful commands

```powershell
# Inspect the authoritative branch without changing master.
git log --oneline origin/feature/modernization -10
git ls-tree -r --name-only origin/feature/modernization -- src |
  Select-String '\.java$'

# Build the modern emulator artifact from a feature-based worktree.
.\gradlew.bat assembleEmulator bundleEmulator

# Run the JVM characterization suite.
.\gradlew.bat test
```

If the host SDK tools are unavailable through `PATH`, use their absolute SDK
paths (especially `platform-tools/adb`) or the checked development-container
workflow.  The Windows host emulator was used for the API 36.1 smoke test;
Docker is useful for deterministic compilation but is not the emulator host.
