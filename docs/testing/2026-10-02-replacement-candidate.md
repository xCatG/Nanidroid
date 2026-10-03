> Current status — 2026-10-03: The author has now authorized PR publication and merge. The historical report below is reproduced verbatim from the standalone recreation workspace and verifies original candidate `aec25945f7642a74bf7db30a38233a8a79f054ef`, before the subsequent CI/documentation-only fix. Its pending-authorization and hosted-CI statements describe that earlier verification session. This update does not claim new build or device validation.

# Replacement candidate verification — 2026-10-03

## Decision and exact inputs

**Approved local candidate for the author's integration decision.** No unresolved integration product/build blocker was reproduced. Independent final review approved the local candidate. External submission remains unauthorized and awaits author review of the concrete local diff. This is not store/release readiness.

Candidate: `aec25945f7642a74bf7db30a38233a8a79f054ef`, tree `b844c548bc77ff62862c5b384cae06d5b5e3ef4e`, branch `codex/replacement-integration`, target base `afc6a3a350fd10da35d085d9889c407ac5429a2b`. Retained checkout: `C:/work/src/nanidroid-recreation/.integration-artifacts/replacement-2026-10-03/candidate`. HEAD/tree and clean status were checked before and after validation. No candidate product/config/native/test file changed.

Exact recreation debug source: `49a68e28b006de3e440041579c7bcd87212fdc4d`, recovered from the previously verified retained history bundle in the sibling `recovery` directory. Recovery initially had an unborn `master` HEAD and no materialized files; the verified named source ref existed. Explicit detached checkout of the exact frozen commit materialized its files before building. The bundle remains SHA-256 `f8a0dad3868e7a0eb7c8c7b2aaa41d3655fde330b25852c0c38943457473b767`. The candidate's absolute read-only alternates still depend on retained source/target object stores; do not retire them until a separately authorized self-contained archive/dissociation is verified.

Workspace instructions loaded: source `C:/work/src/nanidroid-recreation/AGENTS.md` and candidate's approved isolated-workspace `AGENTS.md`. No original target instructions, superseded application contents/history/build scripts or signing key were read. Unrelated source edits/untracked files were preserved.

## Clean candidate gate

In the initially clean retained candidate, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` both `C:/tools/android.sdk`:

```text
./gradlew.bat clean testDebugUnitTest lint assembleDebug assembleDebugAndroidTest assembleRelease --console=plain
```

Pinned wrapper: Gradle 9.3.1, distribution SHA-256 `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`; AGP 9.1.1, Kotlin 2.3.20, JVM 17 (Zulu 17.0.20.1), NDK 28.2.13676358/r28c, CMake 3.22.1; installed platform `android-37.0` revision 2/API 37.0. One app, compile/target 37, minSdk 31, two specified ABIs. No tool/SDK blanket update or modernization occurred.

Exit **0**, measured command wall **144.545157 s**, Gradle reports 2m24s. 137 actionable tasks: **114 executed / 22 FROM-CACHE / 1 UP-TO-DATE**. `testDebugUnitTest`, `lintAnalyzeDebug`, `lintAnalyzeDebugAndroidTest`, `lintAnalyzeDebugUnitTest` and `lintReportDebug` executed without cache labels. Compilation/resource tasks include cache reuse; clean checkout/build does not mean every dependency/task ran without caching. `lintVitalReportRelease` and `lintVitalRelease` were SKIPPED; ordinary lint executed.

Unit XML: **26 suites / 380 tests / 4 skips / 0 errors / 0 failures** (376 non-skipped tests). All four skips are host symbolic-link privilege assumptions: one InstalledGhostRepositoryTest, one NativeProfileDirectoryTest, two ImportStagingTest. These are not passing symlink cases.

Lint XML: **0 errors / 21 warnings / 1 hint**. IDs: AndroidGradlePluginVersion 1; GradleDependency 5; NewerVersionAvailable 3; ConfigurationScreenWidthHeight 2; ModifierParameter 1; ObsoleteSdkInt 1; UseKtx 6; VisibleForTests 2; AutoboxingStateCreation 1 (hint). Complete messages/locations remain in raw XML. Build also reports SDK XML v4-versus-v3 tooling warning and three release Kotlin warnings: always-true condition in SurfaceComposer; nullable receiver diagnostics in SurfaceImageLoader and GhostRuntime. No native compiler error occurred. These existing warnings were not fixed under integration scope.

Initial sandbox gate failed before tasks because the wrapper download hit `SocketException: Permission denied: connect`. Its complete output is retained; the authorized escalated gate above is the successful result. SDK/configuration was unchanged.

Exact recreation source build: `./gradlew.bat assembleDebug --console=plain`, same SDK variables, exit **0**, **39.8646543 s** wall; 40 actionable tasks, **19 executed / 21 FROM-CACHE**. No source clean erased historical evidence. Initial invocation before materialization could not find the wrapper; an initial checkout of unborn HEAD failed. These setup errors are distinct from a build failure.

| APK | SHA-256 |
| --- | --- |
| candidate app-debug.apk | `b650bab69a90b064908bb3ea968b5d8f3cd51613233e124be9a597862bca36b0` |
| candidate app-debug-androidTest.apk | `aa0d6770800c2812bfd5877c2cce51d93f53cc807acd8db9237862898ef4fbe4` |
| candidate app-release-unsigned.apk | `ec9a611ae70ea775212550ba31a0ccfa1acf7e33239cf0f8c69b0076d9001497` |
| recovered recreation app-debug.apk | `73761e299798ffdf2dd65a10d32f67787c76c4b3de79251783a7725ab25310a8` |

Release is unsigned; successful assembly is not distribution eligibility.

## Identity and packaging

Fresh working-byte SHA checks: **297/297 native pins match**, native manifest SHA-256 `9a73ed596461a1d7bdb53fe8f389480f28c8230f23839641341f7b12a722a154`; inputs.json SHA-256 `3a2e0cab58d4a2977d26bef3d22e4034f0cff0935b87bb96aae9b60a98e3eb16`. Both bundled ZIP working copies and ZIP asset extracted from each candidate app APK match `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d`.

Both debug and unsigned release APKs contain exactly `arm64-v8a` and `x86_64` library directories. Each contains libsatoriya.so, libyaya.so, libkawari8.so, libssu.so plus dependency libandroidx.graphics.path.so. The test APK has no native library directory. Every APK's full ZIP path list was retained; **zero .nar entries**. Candidate tracked path check found no NAR, APK, local.properties, .jks or .keystore. No private archive was added to Git/APK. Native source identity and ABI packaging do not prove broad compatibility or 16 KiB suitability.

The [integration manifest](../review/2026-10-02-integration-manifest.md) and independent Task 3 review retain the 701 target-path dispositions, 508 final paths, 504 exact frozen blobs plus explicit README override, all notices/license, rollback tag and original-target preservation proof. Task 4 makes no new original-target content inspection or mutation. The accepted unchanged-input whitespace exception remains default diff-check exit 2 / 70,152 diagnostics / 304 exact frozen paths; the Task 3 scoped authored-file check passes. No full whitespace pass is claimed.

## One disposable emulator flow

Owned serial **emulator-5554** only, API 37 x86_64 Google Play image, 1080×2400/420 dpi, system fingerprint retained in `emulator-fingerprint.log`. Existing Nanidroid_API_37 system image launched headlessly at port 5554 with `-datadir` pointing to ignored `verification/disposable.avd`, `-no-snapshot -wipe-data`; this disposable data directory was used throughout. Emulator run: **2026-10-03 15:28:52.056–15:39:59.783 PDT, 667.728 s** wall, including boot/waits/interaction, not active labor. Owned emulator stopped with explicit `adb -s emulator-5554 emu kill`, returned OK. Physical Pixel `2A291FDH200E5E` was never selected, installed, queried or changed. No global adb reset occurred.

Fixture: existing allowed `C:/tmp/Nanidroid-corpus-recovery/2elf/2elf-2.46.nar`, verified SHA-256 `a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0`, 2,405,498 bytes. No Earthquake/LOBO/other corpus was tested. Archive stayed outside Git; device copy only in disposable Downloads. Actual Ghosts → Import .nar → Google DocumentsUI → Downloads → 2elf selection produced original readme/switch confirmation and installed `files/ghost/2elf`. This was not an injected provider import. Bundled startup had both named characters. Imported 2elf later rendered リエール/ソフィ and Japanese dialogue. Observed character taps produced a later different Japanese dialogue; this UI sample does not isolate taps from timer-driven talk. Native setting interaction is independently asserted below.

Existing tests used without code changes:

| Focused result | Exact method in com.cattailsw.nanidroid.ui.NativeRotationTest | Result |
| --- | --- | --- |
| native flag interaction, away/back, orderly close | twoelfRuntimeSwitchBackAndCloseWritesFlag | **1/1**, 13.053 JUnit s, 14.718 wall s |
| recreation/display rotation, same lease, same flag and no duplicate lifecycle events | twoelfKeepsFlagAndLeaseAcrossRecreationAndDisplayRotation | **1/1**, 3.540 JUnit s, 4.732 wall s after tool collision resolution |

Arguments: `fixtureId=2elf`, `runId=replacement` for switch/close; `runId=replacement-rotation` for rotation; runner `com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner`, always explicit serial. Switch test sets 見切れOFF, asserts status/menu state, checks new lease on switch back, saved flag, two loads/unloads and two OnDestroy events. Rotation checks same lease, OFF state and no additional native initialization/close/destroy lifecycle events. Final app Back returned to launcher; the native close test supplies orderly teardown assertions.

Retained attempts: initial combined command specified rotation in the wrong engine class, so only switch/close ran (1 test); no 2-test pass claimed. Corrected rotation then crashed with `UiAutomationService ... already registered!`: Retained logcat identifies Android CLI starting its separate InstrumentationServer process (PID 4604); the error establishes a competing registered automation client, but does not name that client. Attribution to the still-running CLI inspection session is an inference supported by that sequence. After stopping only `com.android.cli.interact.instrumentation` on owned serial, one scoped rotation retry passed. This is a retained automation-ownership failure with a bounded environmental explanation, not a diagnosed product defect or product correction or accepted IME-risk rerun. Initial picker layout request during transition had no root; later inspect succeeded. Initial Android CLI invocation was sandbox access-denied; authorized elevated invocation succeeded. Initial apksigner path 37.0.0 did not exist; installed 36.0.0 verified certificates. All these attempts remain disclosed.

## Recreation-data preservation, not old-version migration

Standard debug certificates independently verified with installed apksigner 36.0.0: both APKs use `C=US, O=Android, CN=Android Debug`, certificate SHA-256 **46d817f4af0104a5732acd4febc811297d30b6c87fff0207e98bdbe778da8ba6**. This certificate is unrelated to an unverified published signing identity. No original key was read/copied/used and no signing configuration was added.

The real archive was initially imported under candidate, then installed exact recovered recreation debug with `install -r`. Recreation loaded the preserved real ghost and wrote/read OFF through existing NativePersistenceTest#satoriOrderlyUnloadRestoresChangedFlag (**1/1**, 0.614 JUnit s), including orderly unload/reload. After stopping source app, hashed every regular file in `files` and `shared_prefs`, installed candidate debug `-r`, and rehashed **before candidate launch**: **198/198 same path/hash, zero differences**. Complete final inventory files each have SHA-256 `8d2ff19aa4e33860132e4451148356573e934b3155a5619ea3c5da032da536bd`. Save SHA-256 at source baseline and candidate prelaunch: `e3934c764b1d1373744bfc5d81406ca2b1f2ed23bd1e447836babed7707af9aa`. Candidate existing twoelfReadsAfterRuntimeBackInFreshProcess read the saved OFF state (**1/1**, 0.364 JUnit s). Last-ghost preference and imported payload were included in the byte comparison; candidate UI also restored the named real ghost.

The earlier preservation attempt also had 198/198 byte equality (inventory file SHA-256 `08e622dce9f92914fefd56739c7a8b3937ddcfe1956e54689d8528d2b1821955`), and recreation fresh-read passed (1/1, 0.443 s). A candidate fresh-read failed (1 failure, 0.125 s) because its disposable test marker still recorded the older switch-close hash `c4d286…`; source's later orderly unload had legitimately rewritten save to `264d448…`, already identical in both pre/post install inventories. Failure preserved. The final comparison used source's completed write/read save baseline: only disposable assertion marker backHash was updated to that measured post-source-unload hash; no ghost save/product/test source was overwritten. Both inventories include that marker. Candidate semantic read then passed. Marker correction does not erase the earlier failure.

This validates an already installed recreation app's byte retention under compatible debug replacement and its native saved flag. It does not test the original published application, legacy discovery/conversion, uninstall preservation, backup restoration, original signing, real-user upgrades or migration. Approved no-migration policy remains: old external files are untouched and not discovered/imported; NAR reimport does not restore old saved state. Uninstalling may remove app-owned data.

## Historical exceptions and separate release checklist

The [M5 acceptance packet](2026-10-01-m5-focused-acceptance-packet.md) retains original source/APK/device provenance, including E37 app/test `b7cd2737…` / `226ca6b8…`, Pixel app `618579ae…`; those are historical, not this candidate's APKs. Author accepted M5 on 2026-10-02 with LOBO Pixel unset-cycle gap (0/3 setting cycles) and unresolved intermittent Compose wrong-thread/IME failures. They were not rerun, diagnosed or upgraded to passes. Unsampled authored branches, native broad parity, SSP/audio/AYA5 and other approved exclusions remain unchanged.

Release-only, **unconfirmed in this task**:

- Eligible release versionCode/versionName; current code 1/name 1.0 does not establish published update eligibility.
- Published maximum versionCode, version/store metadata and current listing eligibility.
- Original/published signing certificate identity/use, fingerprint match, Play App Signing enrollment/upload key and signed release CI. Author reports recovered key; this task did not verify it and has no key-search blocker.
- Distribution/signing decision and current store requirements, to consult official guidance during separately authorized release work.
- 16 KiB ELF/load/ZIP alignment and native validation, including all shipped libraries; two-ABI packaging is insufficient proof.

No push, PR, merge, release/store work or paused monitoring/goal resume occurred. Hosted CI has not run. Before eventual submission, recheck target origin/master and required contexts; changed target requires reconciliation and affected validation. Independent final review is complete; author local-diff decision remains pending.

## Evidence retention and effort

Complete raw build commands/results, gate failed attempt, recreation build, unit XML, lint XML, APK path lists/hashes/certificates, native/input hashes, focused stdout/result timings, full emulator logcat, system-picker/UI JSON, save markers and both before/after inventories are retained under ignored `.integration-artifacts/replacement-2026-10-03/verification/`, outside build cleanup. Some setup errors exist in this task's command transcript rather than standalone files; no fabricated pass replaces them. Raw data/fixture contents are not committed.

Task 4: **zero product/native/build-config fixes**, zero new production/test/committed-tool or host harness lines. Existing Task 3 host plumbing remains 171 physical lines; direct one-off commands were used. Prior inventory remains 60 production Kotlin files/5,743 lines; 51 test-source files/15,240 lines; three tools/775 lines. Largest production files remain GhostRuntime 1,282 and GhostStage 779. Documentation added only; no interface changes. +200 new test/tool-line and three-product-fix checkpoints were not reached.

Active labor approximately **25 minutes**, an unmeasured estimate including reporting. Measured unattended/command times are separately above; emulator 667.728 s overlaps build/interaction and must not be added as labor. Exact gate + recreation build command wall totals **184.410 s**; focused successful two-test command walls total **19.449 s**; failed rotation wall **3.723 s**. Preservation multi-command flow wall and JUnit times are recorded in transcript/raw results, not estimated as active work.

## Final independent review and retained decision

Independent final review on 2026-10-03 approved the exact unchanged local candidate for the author's integration decision, with no high-confidence actionable defect. It checked source report commit `57353480d360d3df145b2a6b2cc440d203e1d366`, retained command/results, existing test preconditions, preservation ordering and independent OFF semantic assertion. Initial automation and stale-marker failures remain visible; approval does not authorize external submission or establish release readiness. The review and progress ledger are retained in ignored `verification/task-4-review.md` and `verification/progress.md` beside the raw evidence.

Final local PR draft: `C:/work/src/nanidroid-recreation/.integration-artifacts/replacement-2026-10-03/pr-description.md`, SHA-256 `ada46b4bc57cda7de34d10d4330187770aba6231e3f7d0743afb211079c7d2a4`. This is a local review artifact; no PR was created or published.

The progress ledger contains one Ruling, retained exhaustively: preserve verified immutable-input whitespace and report the full failing check alongside scoped authored checks. Exact native/data/license preservation takes precedence over formatting normalization. Cost if wrong: inherited whitespace-related tooling noise remains and must be reassessed before submission if warning containment is incorrect. No further validation or candidate change was made for this reporting update.
