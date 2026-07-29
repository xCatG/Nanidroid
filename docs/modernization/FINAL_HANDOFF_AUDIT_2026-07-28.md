# Final modernization handoff completion audit

Date: 2026-07-28.  The authoritative handoff was inspected from
`codex/modernization-handoff:docs/modernization/HANDOFF.md`; this worktree no
longer carries that historical handoff file.

## Requirement status

| Handoff requirement | Status | Current evidence |
| --- | --- | --- |
| Finish active Java-to-Kotlin migration | Complete | `rg --files src modern | rg '\\.java$'` returned no active production Java files; 102 active Kotlin files remain. The frozen `legacy/` tree is deliberately outside this scope. |
| Preserve migration ABI/behavior with focused tests | Partially complete | Per-item characterization tests and Kotlin compilation exist; the host full suite compiles but cannot execute 32 Android-stub-dependent cases. The focused API-36 native staging class also cannot run because `libnarfs.so` is absent for x86_64. |
| Host artifact/security contracts | Complete | `python -m unittest discover -s tools -p 'test_*.py'` passed all 197 tests after `30e8bc3`; Kotlin-source and Windows path contracts are covered and are no longer a blocker. |
| Installer integration/recovery: local file, content URI, network update, interruption/corruption/conflict/cancel/retry/no partial state | Incomplete | JVM policy/staging/transaction tests are present and the focused host installer subset passed previously. Active production import accepts HTTPS only, and the test tree contains no deterministic local-file/content-URI/network-update integration suite covering the complete recovery matrix. Device staging execution is blocked by missing x86_64 `libnarfs.so`. |
| CI/Gradle and frozen legacy/reference lanes | Blocked externally | `verifyEmulatorNativeLibraries` passes with ARM64 artifacts. `verifyLegacyNativeLibraries` is blocked by missing frozen ARM libraries; `verifyDeviceNativeLibraries` is blocked by missing x86_64 libraries. Docker 29.6.2 is available, but regeneration was not run because it may download pinned toolchains. No current hosted-CI result was inspected. |
| Release APK/AAB validation | Partially complete | `assembleRelease -x verifyLegacyNativeLibraries` passed after the stale `adview` lint fix. This is explicitly a partial diagnostic; the unmodified full release gate remains blocked by the frozen legacy artifacts. No AAB validation, signing, or publishing was performed. |
| Repeatable API 36/37 release behavior: launch, import, interaction, recreation, native SHIORI | Incomplete | The API-36 AVD exists and was used for bounded instrumentation; the requested staging class ran 3/3 but failed to load `libnarfs.so`. No API-37 AVD run, complete launch/recreation flow, full installer matrix, or native SHIORI request has current passing evidence. |
| ViewServer product decision | Complete | The obsolete debug socket server and lifecycle adapter were removed from the active Android 12+ product. The frozen `legacy/` reference tree remains outside the production source set. |
| Exported-component, intent, URI, cleartext/network audit | Audited; decisions remain | `SECURITY_ALIGNMENT_AUDIT.md` records an HTTPS-only exported deep link, private service, no nested-intent forwarding/grant surface, and immutable notification intents. The manifest-wide cleartext/legacy telemetry and broad-host distribution decisions remain product-owned. |
| Firebase/Crashlytics privacy and release configuration | Incomplete product decision | The handoff explicitly retains this decision. No release Firebase project configuration, consent/privacy text, or production-reporting approval was added. |
| ABI/device/native lifecycle support and native engine characterization | Incomplete | Existing native documentation/contracts describe the build boundary, but the required real native SHIORI load/request/unload evidence is absent and x86_64 native packaging currently blocks the device path. |
| Remove obsolete View/XML-era paths only after Compose equivalence proof | Incomplete | Active Java migration is complete, but the handoff conditions removal on broader behavior evidence. The retained XML resources and legacy reference lane have not been removed. |

## Fresh local validation

* `assembleRelease -x verifyLegacyNativeLibraries` — **passed** (non-publishing; no release credentials configured).
* `lintVitalRelease -x verifyLegacyNativeLibraries` — **passed**.
* `python -m unittest discover -s tools -p 'test_*.py'` — **passed**: 197 host artifact/security contract tests.
* Full JVM host run — **blocked by Android framework stubs**, not compilation: 229 tests, 32 failures.
* Native preflights — ARM64 emulator **passed**; frozen ARM and x86_64 device profiles **blocked** by absent generated artifacts.

## Git state

At audit time, the intended worktree is clean. The only untracked path is the
known generated `gradle/gradle-daemon-jvm.properties`; it is not staged.

## Conclusion

The Kotlin-source conversion objective is complete, but the modernization
handoff objective is **not complete**. The 197-test host artifact/security
suite is complete and is not among the blockers. Completion is blocked by
missing native artifacts/device proof and requires explicit product decisions
for release, privacy/Crashlytics, cleartext/telemetry, and the supported native
ABI/lifecycle scope.

## Installer seam boundary

`NarTransactionalInstaller.install(File, File, String)` is the lowest
faithful host seam after a download has been materialized as a local file. Its
characterization covers corrupt-file cleanup followed by a successful retry,
without native artifacts. There is no equivalent host seam for a `content:`
grant or cancellation: the only public external entry is intentionally
HTTPS-only, while `NarDownloadTask` and `GhostUpdateTask` are private Android
`AsyncTask` implementations that call concrete `NetworkUtil` and `File`
operations directly. A future integration design should extract an
install-source/stream coordinator with explicit cancellation and grant
ownership, then exercise it under instrumentation; adding a fake provider or
pretending that unsupported `content:` input installs would not be faithful.
