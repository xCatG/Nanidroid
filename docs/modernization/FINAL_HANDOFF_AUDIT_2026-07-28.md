# Final modernization handoff completion audit

Date: 2026-07-28.  The authoritative handoff was inspected from
`codex/modernization-handoff:docs/modernization/HANDOFF.md`; this worktree no
longer carries that historical handoff file.

## Requirement status

| Handoff requirement | Status | Current evidence |
| --- | --- | --- |
| Finish active Java-to-Kotlin migration | Complete | `rg --files src modern | rg '\\.java$'` returned no active production Java files; 102 active Kotlin files remain. The frozen `legacy/` tree is deliberately outside this scope. |
| Preserve migration ABI/behavior with focused tests | Partially complete | Per-item characterization tests and Kotlin compilation exist; API 37 native filesystem and staged-tree instrumentation passed. The host full suite still cannot execute 32 Android-stub-dependent cases, and API 36 staging has not been rerun on regenerated payloads. |
| Host artifact/security contracts | Complete | `python -m unittest discover -s tools -p 'test_*.py'` passed all 196 tests after the ViewServer retirement; Kotlin-source and Windows path contracts are covered and are no longer a blocker. |
| Installer integration/recovery: local file, content URI, network update, interruption/corruption/conflict/cancel/retry/no partial state | Incomplete | API 37 passed native staged-tree ownership/cleanup (3 cases) and filesystem inspection. JVM policy/staging/transaction tests are present, but no deterministic end-to-end local-file/content-URI/network-update recovery matrix exists. The active public import remains HTTPS-only. |
| CI/Gradle and frozen legacy/reference lanes | Partially complete | The documented pinned Docker/Ant ARM regeneration and x86_64 profile both passed; all three native preflight gates pass. No current hosted-CI result was inspected. |
| Release APK/AAB validation | Partially complete | The exact non-publishing `assembleRelease` passed after native regeneration. No AAB validation, signing, or publishing was performed. |
| Repeatable API 36/37 release behavior: launch, import, interaction, recreation | Incomplete | API 37 passed the two bounded native instrumentation classes after x86 packaging was added. API 36 rerun, launch/recreation, import interaction, and the full installer matrix still lack passing evidence. Native SHIORI is intentionally unsupported. |
| ViewServer product decision | Complete | The obsolete debug socket server and lifecycle adapter were removed from the active Android 12+ product. The frozen `legacy/` reference tree remains outside the production source set. |
| Exported-component, intent, URI, cleartext/network audit | Audited; decisions remain | `SECURITY_ALIGNMENT_AUDIT.md` records an HTTPS-only exported deep link, private service, no nested-intent forwarding/grant surface, and immutable notification intents. The manifest-wide cleartext/legacy telemetry and broad-host distribution decisions remain product-owned. |
| Firebase/Crashlytics privacy and release configuration | Incomplete product decision | The handoff explicitly retains this decision. No release Firebase project configuration, consent/privacy text, or production-reporting approval was added. |
| ABI/device/native lifecycle support and native engine characterization | Complete product decision | Native SHIORI engines are unsupported in the modern Android product. `ShioriFactory` routes Kawari/Satori descriptors to the established `NotSupportedShiori` compatibility stub; simple `NanidroidShiori` ghosts remain supported. JNI symbols remain only for frozen artifact compatibility. |
| Remove obsolete View/XML-era paths only after Compose equivalence proof | Incomplete | Active Java migration is complete, but the handoff conditions removal on broader behavior evidence. The retained XML resources and legacy reference lane have not been removed. |

## Fresh local validation

* `assembleRelease -x verifyLegacyNativeLibraries` — **passed** (non-publishing; no release credentials configured).
* `lintVitalRelease -x verifyLegacyNativeLibraries` — **passed**.
* `python -m unittest discover -s tools -p 'test_*.py'` — **passed**: 196 host artifact/security contract tests.
* API 37 bounded native instrumentation — **passed**: `NarFilesystemInspectorInstrumentationTest` and `NarStagedTreeInstrumentationTest` (3/3).
* Full JVM host run — **blocked by Android framework stubs**, not compilation: 229 tests, 32 failures.
* Native preflights — frozen ARM, ARM64 emulator, and x86_64 device profiles **passed**.

## Git state

At audit time, the intended worktree is clean. The only untracked path is the
known generated `gradle/gradle-daemon-jvm.properties`; it is not staged.

## Conclusion

The Kotlin-source conversion objective is complete, but the modernization
handoff objective is **not complete**. The 196-test host artifact/security
suite is complete and is not among the blockers. Completion is blocked by
API-36/device-flow proof and requires explicit product decisions
for release, privacy/Crashlytics, and cleartext/telemetry.

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
