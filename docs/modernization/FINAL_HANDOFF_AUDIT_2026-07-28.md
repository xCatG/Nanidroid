# Final modernization handoff completion audit

Date: 2026-07-28.  The authoritative handoff was inspected from
`codex/modernization-handoff:docs/modernization/HANDOFF.md`; this worktree no
longer carries that historical handoff file.

## Requirement status

| Handoff requirement | Status | Current evidence |
| --- | --- | --- |
| Finish active Java-to-Kotlin migration | Complete | `rg --files src modern | rg '\\.java$'` returned no active production Java files; 100 active Kotlin files remain. The frozen `legacy/` tree is deliberately outside this scope. |
| Preserve migration ABI/behavior with focused tests | Complete | Per-item characterization tests, production Kotlin compilation, and all 232 JVM tests pass. All seven allowlisted device classes also passed on API 36.1 and API 37. |
| Host artifact/security contracts | Complete | `python -m unittest discover -s tools -p 'test_*.py'` passed all 196 tests after the ViewServer retirement; Kotlin-source and Windows path contracts are covered and are no longer a blocker. |
| Installer integration/recovery: local file, content URI, network update, interruption/corruption/conflict/cancel/retry/no partial state | Partially complete | Fresh-install JVM coverage now includes corrupt archives plus deterministic write/space, extraction-I/O, and publication failures: each leaves no target or staging residue and permits retry. Archive conflicts, collisions, limits, and retained-overlay policy are characterized. The active public import remains HTTPS-only; `content:` and cancellable network/update flows require separate product contracts. |
| CI/Gradle and frozen legacy/reference lanes | Partially complete | The pinned Docker lane now validates a complete immutable `027c971:legacy` project plus the checksum-pinned historical AdMob binary from `0390a86^`, builds its Kawari/Satori-only Ant APK, and separately verifies the current three-library ARM native lane. All native preflight gates pass. No current hosted-CI result was inspected. |
| Release APK/AAB validation | Partially complete | The exact non-publishing `assembleRelease` passed after native regeneration. No AAB validation, signing, or publishing was performed. |
| Repeatable API 36/37 release behavior: launch, import, interaction, recreation | Partially complete | Both x86_64 AVDs passed the native filesystem/staged-tree, lifecycle/recreation, Preferences, Compose-shell, surface-rendering, and animation classes. The HTTPS download/import interaction and full installer recovery matrix remain outside that bounded device suite. Native SHIORI is intentionally unsupported. |
| ViewServer product decision | Complete | The obsolete debug socket server and lifecycle adapter were removed from the active Android 12+ product. The frozen `legacy/` reference tree remains outside the production source set. |
| Exported-component, intent, URI, cleartext/network audit | Audited; decisions remain | `SECURITY_ALIGNMENT_AUDIT.md` records an HTTPS-only exported deep link, private service, no nested-intent forwarding/grant surface, and immutable notification intents. The manifest-wide cleartext/legacy telemetry and broad-host distribution decisions remain product-owned. |
| Firebase/Crashlytics privacy and release configuration | Incomplete product decision | The handoff explicitly retains this decision. No release Firebase project configuration, consent/privacy text, or production-reporting approval was added. |
| ABI/device/native lifecycle support and native engine characterization | Complete product decision | Native SHIORI engines are unsupported in the modern Android product. `ShioriFactory` routes Kawari/Satori descriptors to the established `NotSupportedShiori` compatibility stub; simple `NanidroidShiori` ghosts remain supported. JNI symbols remain only for frozen artifact compatibility. |
| Remove obsolete View/XML-era paths only after Compose equivalence proof | Incomplete | Active Java migration is complete, but the handoff conditions removal on broader behavior evidence. The retained XML resources and legacy reference lane have not been removed. |

## Fresh local validation

* `assembleRelease -x verifyLegacyNativeLibraries` — **passed** (non-publishing; no release credentials configured).
* `lintVitalRelease -x verifyLegacyNativeLibraries` — **passed**.
* `python -m unittest discover -s tools -p 'test_*.py'` — **passed**: 198 host artifact/security contract tests, including frozen-reference provenance and payload-profile checks.
* API 36.1 and API 37 device instrumentation — **passed**: native filesystem; staged tree (3/3); main-activity lifecycle/recreation (1/1); Preferences (1/1); Compose shell including ghost-list routing and visible balloon rendering (3/3); surface rendering (2/2); and surface animation (2/2), on each x86_64 AVD.
* Full JVM host run — **passed**: 232 tests. Test-only deterministic clock/log/geometry seams avoid host Android stubs; production paths retain direct Android calls.
* Post-remediation API 37 device surface re-run — **passed** on provisioned x86_64 `Nanidroid_API_37`: `SurfaceRenderingCharacterizationTest` (2/2) and `SurfaceAnimationExecutionCharacterizationTest` (2/2). The emulator was stopped afterward.
* Native preflights — frozen ARM, ARM64 emulator, and x86_64 device profiles **passed**. The frozen Ant project and current NarFS native verifier use distinct disposable roots and do not overlay modern product inputs.

## Git state

At audit time, the intended worktree is clean. The only untracked path is the
known generated `gradle/gradle-daemon-jvm.properties`; it is not staged.

## Conclusion

The Kotlin-source conversion objective is complete, but the modernization
handoff objective is **not complete**. The 196-test host artifact/security
suite and the bounded API-36.1/API-37 device suite are complete and are not
among the blockers. Completion requires the separately scoped installer-flow
work and explicit product decisions for signing/release, privacy/Crashlytics,
and cleartext/telemetry.

## Installer seam boundary

`NarTransactionalInstaller.install(File, File, String)` is the lowest
faithful host seam after a download has been materialized as a local file. Its
characterization covers corrupt-file cleanup and deterministic extraction/publish
failures followed by a successful retry, without native artifacts. There is no equivalent host seam for a `content:`
grant or cancellation: the only public external entry is intentionally
HTTPS-only, while `NarDownloadTask` and `GhostUpdateTask` are private Android
`AsyncTask` implementations that call concrete `NetworkUtil` and `File`
operations directly. A future integration design should extract an
install-source/stream coordinator with explicit cancellation and grant
ownership, then exercise it under instrumentation; adding a fake provider or
pretending that unsupported `content:` input installs would not be faithful.
