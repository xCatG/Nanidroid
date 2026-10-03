# Bounded replacement review — 2026-10-03

## Identity and checkpoint

All four bounded review groups are complete. No unresolved candidate-blocking finding remains; the Task 2 checkpoint permits proceeding to Task 3's local integration preparation.

Reviewed source is `f582167293c1d1c389e0a851ae50dae7b7d558e3`; accepted product source is `90952d020a7bf5742dba685d7266a08755c076f6`. Their only committed differences are the replacement plan and [review map](2026-10-02-replacement-review-map.md): +721/-0 documentation lines. Production, tests, tools, native, assets and build configuration have zero changed lines. Candidate inputs remain committed files, excluding unrelated local changes recorded in the map.

The lead dispatched four fresh independent reviewers sequentially. This consolidation records their returned bounded source reviews; it is not an additional product review. Historical executions keep their original source/APK/device provenance. No M5 rerun, private corpus access, build, device action or test execution occurred in Task 2.

## Returned reviews

| Group | Assigned responsibility | Result and disposition |
| --- | --- | --- |
| 1 | engine/, shiori/, GhostRuntime ownership/lifecycle, application wiring | Complete: no finding at confidence 80/100 or higher; no corrective product change requested. Inspected later native reply observation, AYA5/YAYA selection cache, install-event quarantine recovery, attachment/close/switch interfaces. |
| 2 | install/, ghost repositories/descriptors/filesystem boundaries | Complete: no finding at confidence 80/100 or higher; no corrective product change requested. Inspected later ZIP root-entry handling, secondary Sakura name, bounded reads and shell resolution interfaces. |
| 3 | playback/animation, rendering, UI interaction/geometry | Complete: no finding at confidence 80/100 or higher; no corrective product change requested. Compared post-M4 changes in authored alternatives, move coordinates, choices/input drafts, logical scaling, balloons and gestures against the corrective report. |
| 4 | Gradle/manifest, bundled assets/notices, test/tool reproducibility | Complete: no finding at confidence 80/100 or higher; no corrective product change requested. Inspected pinned build contract, backup exclusions, 19 notice references, tracked ZIP/tools and later scoped observer/fixture-helper/Ready polling interfaces. |

No new defect with severity, reproducible trigger or correction commit was returned by any of the four groups. Zero corrective product tasks were required. Their source observations are not current behavioral passes or an exhaustive security/compatibility audit. All four returned reviews satisfy the bounded Task 2 checkpoint; accepted risks below carry forward to integration.

Prior evidence reused: [native persistence](../native-persistence-evidence.md), [M2](../milestone-2-evidence.md), [M3](../milestone-3-evidence.md), [import triage](2026-09-25-import-followup-triage.md), [M4 corrective report](2026-09-26-milestone-4-corrective-evidence.md), and the [focused M5 packet](../testing/2026-10-01-m5-focused-acceptance-packet.md). Exact historical review coverage and correction identities are in the review map. Group 2 also consulted an untracked local round-two import handoff as historical context; that handoff is not frozen transferable evidence and is not selected for the candidate.

## Committed source inventory

Counts use the committed blobs at the reviewed source, counting physical lines including blank lines and comments. These are size/inventory figures, not executable statements, coverage, executed test counts or pass counts. Count test methods by literal `@Test` annotations; parameterization, prerequisites and runtime selection are not expanded.

| Category | Files | Physical lines | `@Test` annotations |
| --- | ---: | ---: | ---: |
| Production Kotlin under app/src/main/kotlin | 60 | 5,743 | 0 |
| JVM source under app/src/test/java | 26 | 7,676 | 380 |
| Instrumentation source under app/src/androidTest/java, including Java provider | 25 | 7,564 | 165 |
| Authored PowerShell tools under tools/ | 3 | 775 | — |
| Total test source | 51 | 15,240 | 545 |

Instrumentation manifest and bundled JVM resource are excluded from test source lines. Native/data/generated inventory is reported separately below and is excluded from these totals. No new product/test/tool lines were added for Task 2 at this checkpoint; the +200 net test/tool-line and three-product-fix scope checkpoints have not been reached.

| Largest production file, relative to app/src/main/kotlin/com/cattailsw/nanidroid/ | Lines |
| --- | ---: |
| runtime/GhostRuntime.kt | 1,282 |
| ui/GhostStage.kt | 779 |
| engine/NativeShioriHost.kt | 404 |
| runtime/ScriptPlayer.kt | 310 |
| install/NarArchiveReader.kt | 193 |
| ui/StageViewModel.kt | 190 |
| install/GhostImporter.kt | 180 |
| runtime/SurfaceAnimator.kt | 176 |
| ghost/SurfaceDefinitions.kt | 171 |
| install/ImportCoordinator.kt | 162 |

File length alone is not a finding and does not authorize refactoring.

Separate inventory: 297 pinned native-tree files; one main bundled ZIP and its identical JVM-test copy, each 74,537 bytes; 19 main notice files. The native tree contains 70,264 physical lines (all 297 files, including its scripts/notices); main notices contain 1,498 physical lines. These retained inputs are counted separately from authored Kotlin and tools. Generated build outputs and APKs are excluded. The committed Gradle wrapper JAR and launcher resources are build/distribution inputs, not authored production/test/tool source. Task 1 verified all native identities and the bundled archive hash; no new native compatibility conclusion follows from size or identity.

## Build contract inventory

One `:app` module; namespace/application ID `com.cattailsw.nanidroid`; minSdk 31, compile/target 37; arm64-v8a and x86_64. Committed configuration specifies Gradle 9.3.1 with distribution checksum, AGP 9.1.1, Kotlin 2.3.20, JVM/toolchain 17, NDK 28.2.13676358 and CMake 3.22.1. Compose BOM 2026.03.01, JUnit 4.13.2 and coroutines-test 1.10.2 are catalog entries. Release minification is disabled; versionCode 1/versionName 1.0. These are inspected settings, not clean-build results or update eligibility. The untracked daemon JVM properties are outside this committed contract.

## Accepted limits and release boundary

M5 remains author-accepted with exceptions. LOBO Pixel setting-change/reload restoration remains 0/3 cycles; the original unset rate was not restored by authored evidence. Blank is not 300 seconds or Never. Intermittent Compose wrong-thread and keyboard/IME visibility failures remain unresolved accepted risks, with no diagnosed cause or passing rerun asserted here.

Prior source/APK/device cohorts remain distinct. No broad native/ABI parity, 16 KiB suitability, interrupted-write persistence, deterministic authored-link completion or spoken TalkBack acceptance follows from this review. The approved fresh-install/no-migration policy remains: old external files are untouched and not discovered/imported; reinstalling a NAR does not restore saved state. Signing identity, published maximum versionCode, Play enrollment/store eligibility and distribution readiness remain separate release checks. Task 3 integration and Task 4 clean-candidate verification remain future work; no push, PR, merge or release is authorized.

## Effort

Groups 1, 2 and 3 each self-estimate about 20 active minutes; group 4 estimates 15 minutes. Consolidation/inventory preparation and finalization are approximately 10 active minutes. Combined Task 2 effort is therefore approximately 85 active minutes, excluding lead coordination. These are unmeasured self-estimates, not precise labor timers. Task 2 unattended build/test/device runtime is zero: none was started. Idle waiting and tool elapsed time are excluded from active labor. Full-plan cumulative effort belongs in the lead's checkpoint report.
