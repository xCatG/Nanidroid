# Replacement review map — 2026-10-03

## Frozen source

Accepted source: `90952d020a7bf5742dba685d7266a08755c076f6`, branch `codex/walking-skeleton`, subject `docs: record M5 acceptance with exceptions`. The [focused packet](../testing/2026-10-01-m5-focused-acceptance-packet.md) records author acceptance dated 2026-10-02, superseding historical OPEN checkpoints with explicit exceptions. Candidate inputs are committed files at this identity, never the working directory. The approved replacement plan is committed alongside this map by explicit lead assignment. No product edits, tests, devices, remote action or Task 2 review occur here.

The full mode/type/object/path inventory below is reproducible with `git ls-tree -r 90952d020a7bf5742dba685d7266a08755c076f6`. Read exact bytes using `git cat-file blob <source>:<path>`. These paths are eligible for explicit Task 3 disposition, not an instruction to transfer every document/test. Corpus inventories are evidence metadata; private archives/extracted fixtures are excluded and were not accessed.

## Identity verification

- Bundled archive committed and working SHA-256: `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d`, matching inputs.json. Committed test/resources/nanidroid.zip also matches.
- Native baseline `afc6a3a350fd10da35d085d9889c407ac5429a2b` is manifest metadata only. Map each `jni/<path>` to `app/src/main/jni/<path>`: all 297 committed blobs and 297 working files match their approved SHA-256 pins. Zero missing, extra or changed files; zero working-tree reparse points. No reference source/history was read.
- Manifest working CRLF SHA-256 matches inputs.json: `9a73ed596461a1d7bdb53fe8f389480f28c8230f23839641341f7b12a722a154`. Committed LF blob hash: `ec9fcd37e4297170d04ccb4041b95b3eaa1ad47cea3ba91c8b6360212f2dc470`. Replacing only CRLF with LF in working UTF-8 bytes reproduces the committed hash. This metadata newline transformation is explicitly accounted for; no native-byte transformation is accepted.
- [M2 evidence](../milestone-2-evidence.md) documents relocation and initial staging normalization of 295 native files, corrected with `.gitattributes` rule `app/src/main/jni/** -text`. All native committed bytes now match the pins. [Notice inventory](../testing/notice-inventory.md) records native notices and displayed attribution. Identity checks do not establish runtime compatibility or 16 KiB suitability.

## Inventory and excluded local state

503 tracked paths: 60 production Kotlin, 27 JVM tests/resources, 26 instrumentation tests/config, 3 tools, 35 documentation, 297 native, 20 main assets/notices and 35 other build/resources/admin paths. Native/data counts are separate. Task 2 measures lines, largest production files and reviewed changes.

Tracked entry modification: `docs/implementation-handoff.md`, +8/-0, excluded. All entry untracked paths below are preserved and excluded except the approved replacement plan added in this task. No other untracked input is demonstrated necessary; `gradle/gradle-daemon-jvm.properties` requires explicit future disposition if clean-build runtime verification needs it.

```text
.idea/.gitignore
.idea/.name
.idea/AndroidProjectSystem.xml
.idea/appInsightsSettings.xml
.idea/compiler.xml
.idea/deploymentTargetSelector.xml
.idea/gradle.xml
.idea/markdown.xml
.idea/misc.xml
.idea/runConfigurations.xml
.idea/vcs.xml
docs/review/2026-09-24-ghost-import-review-round2.md
docs/review/2026-09-25-import-failure-message-handoff.md
docs/review/2026-09-26-milestone-4-post-acceptance-triage.md
docs/review/2026-09-26-milestone-4-simplification-review-handoff.md
docs/superpowers/plans/2026-09-24-ghost-import.md
docs/superpowers/plans/2026-10-02-replacement-integration.md (approved addition)
docs/superpowers/specs/2026-09-29-balloon-compatibility-spike.md
docs/superpowers/specs/2026-09-29-option-a-builtin-bubble-design.md
docs/superpowers/specs/2026-09-29-option-a-builtin-bubble-preview.svg
docs/testing/2026-09-29-ghost-scale-measurements.md
gradle/gradle-daemon-jvm.properties
```

Ignored coordination files, local.properties, raw evidence, build outputs, APKs and private fixtures are outside this inventory. Historical docs may name local evidence paths as provenance; this does not authorize access or transfer. Git warned that the user-level ignore file was unreadable; explicit tree inventory and two-path staging do not depend on it.

## Baseline to implementation/evidence

Paths refer to frozen source. Historical checks retain their own source/APK/device provenance; no rerun occurred. [M5 evidence](../milestone-5-evidence.md), [reconciliation](../testing/2026-09-30-m5-acceptance-reconciliation.md) and the focused packet retain failed attempts and exact provenance.

| Baseline | Implementation | Existing evidence and limits |
| --- | --- | --- |
| §1 product; §2 screens/flows | MainActivity; ui/GhostStage, GhostInputDialog, BalloonContent, AboutDialog, StageViewModel | [M1](../milestone-1-evidence.md), [M4](../milestone-4-evidence.md), M5 UX scenarios cover stage, picker/readme, choices/input, About and close. Deterministic authored link gap remains; spoken TalkBack deferred. |
| §3 package | ghost/DescriptorReader, InstalledGhostRepository, ShellCatalog, NativeProfileDirectory; data/ stores | [M2](../milestone-2-evidence.md), [M3](../milestone-3-evidence.md), native persistence: private identity/master shell/CP932. No legacy discovery/migration. |
| §4 SHIORI; §4.1 built-in | engine/, shiori/, native host, bundled asset | M1/M2 and [native persistence](../native-persistence-evidence.md); Satori/Kawari/YAYA/fallback. AYA5-only scripts excluded, fixture/ABI provenance retained. |
| §5 events | runtime/GhostRuntime, engine/ShioriEvent, UI gestures | M4 [corrective evidence](2026-09-26-milestone-4-corrective-evidence.md), M5 authored reply checks; Pixel numeric mouse/status/lease attribution absent. |
| §6 script subset | runtime/ScriptPlayer, ScriptInteraction; choice/input UI | M4 parser corrections and M5 authored input; excluded SSP/audio unchanged, intermittent wrong-thread/IME risks unresolved. |
| §7 surfaces/animation | ghost/SurfaceDefinitions, SurfaceComposer; runtime/SurfaceAnimator; ui/AnimatedSurface | M4 corrective pixel results and M5 corpus; LOBO replace excluded, G01 surface-152/G06 pose-only random branches unsampled. |
| §8 layout | ui/StageGeometry, GhostStage, BalloonContent; runtime/RenderedMoveGeometry | Dated M5 viewport/font/rotation results, approved logical scale/built-in balloons. No external skins. |
| §9 lifecycle | application/runtime, StageViewModel, NativeShioriHost, stores | M2/persistence/M4 recreation-close; orderly unload and completed-write persistence, no interrupted-write/power-loss guarantee. |
| §10 import | install/, repositories, document-picker UI | M3 and [import triage](2026-09-25-import-followup-triage.md): staging/duplicates/failure/process-death evidence; no general/network importer or legacy conversion. |
| §11 native | pinned jni, wrappers, app build contract | M2 packaging and dated sampled x86_64/arm64 M5 evidence. Identity verified here; broad ABI parity/16 KiB release checks unproved. |
| §12 deliberate differences | private storage/picker/fallback policy | Baseline exclusions govern: no background service/network updates/ads/analytics/accounts/legacy intent import. |
| §13 retained modernization | Compose/lifecycle StateFlow, single module/manual injection | Approved design and scaffold/M1/M2 evidence; no extra DI/navigation architecture. |
| §14 milestones | M1–M5 integrated source | M5 accepted with exceptions at frozen source; no integration/release acceptance. |

Accepted LOBO Pixel gap is 0/3 setting-change/reload cycles: no authored restoration of original unset rate was proved. Blank is not 300 seconds or Never. Compose wrong-thread and keyboard visibility failures are accepted unresolved risks, not fixes/passes/diagnosed causes. Earlier API31 broad coverage, earlier-APK corpus/persistence and focused E37/Pixel cohorts retain separate provenance. Pixel data-preserving install observations do not establish legacy migration or broad arm64 parity.

## Prior reviews and sequential Task 2 assignments

Lead assigns fresh independent reviewers sequentially; none dispatched here. Reuse unchanged-code evidence, inspect interfaces/subsequent changes, require concrete behavioral defects or missed approved requirements.

| Group | Scope | Reusable bounded review coverage |
| --- | --- | --- |
| 1 | engine/, shiori/, GhostRuntime ownership/lifecycle, application wiring | M2/persistence/M4 corrective evidence and M5 reconciliation; inspect later changes without repeating accepted intermittent sampling. |
| 2 | install/, ghost repositories/descriptors/filesystem | Import triage records independent Sol review of `3cc0b52` and selected-root correction, device addition `9fe66f4`; triage committed `eb34226084d1f1262060c7bf0def380f04259921`, chronology `84695ba3a6c4b5304a8a4096c20d9733a9cf3fca`. |
| 3 | playback/animation, rendering, UI interactions/geometry | [Rendering/clock report](2026-09-24-rendering-clock-review-fixes.md): `465170b..70c47c3`, fixes `4e07e35..4d7e6de`, report `a29cdd6f8ceee392b5a4897079e8b05a73f29d34`. Corrective report covers `0825b91`, `5e78116`, `4619c77`, `f8d222b`, report/test commit `40c435f794b3862c3fcc0f8b942b3dd355a2a072` and described independent re-review. |
| 4 | Gradle/manifest, assets/notices, test/tool reproducibility | M2 pin/build/notice evidence, notice inventory, committed scenarios/corpus metadata; future clean-build checks use approved tools/fixtures only. |

Untracked old handoffs are not frozen review evidence. Abbreviated coverage IDs resolve within recreation Git only. Historical review is bounded, not universal approval of this source.

## Separate distribution prerequisites

Committed build: application ID/namespace com.cattailsw.nanidroid; minSdk31, compile/target37; versionCode1/versionName1.0; shrinking disabled; NDK28.2.13676358/CMake3.22.1; arm64-v8a+x86_64. No tracked README.md/.github; configured remote list empty. Task 3 authors README/CI dispositions and explicit transfer manifest.

Published maximum versionCode, signing identity/Play enrollment, current store eligibility and 16 KiB validation are unconfirmed release checks. Author owns signing recovery; upload-key reset cannot replace a lost self-managed app-signing key. Approved application ID and fresh-install/no-migration policy remain. Old external files are untouched and not imported; reimport cannot restore saved state. Incompatible signatures may require uninstall for fresh installation, which can remove app-owned data. No signing handling/release/real-user upgrade is authorized.

## Complete committed eligible inventory

```text
100644 blob 49eb1a8648065b74d8a4682cf60094905e322f79	.gitattributes
100644 blob e1d89d673bacd33d3d249fe2e9e1f232cb3d4ab1	.gitignore
100644 blob 2a9a357afb7cdd2114174ca242a681bae991e432	.superpowers/sdd/2026-09-22-walking-skeleton/task-2-report.md
100644 blob 3ffbee4784ede34fb62953b08a2953d6f215780e	.superpowers/sdd/2026-09-23-native-engines/task-2-report.md
100644 blob 4b14c4cd73d18e168cd1dc098c075b3314c54e09	.superpowers/sdd/2026-09-23-native-state-persistence/task-4-report.md
100644 blob af9141414f5f23a3ad162fa98f6fe550e50d4e19	AGENTS.md
100644 blob 42afabfd2abebf31384ca7797186a27a4b7dbee8	app/.gitignore
100644 blob e22a65dfc1e94b63ef252b2d43f2bc7d305e6b7c	app/build.gradle.kts
100644 blob cda38c92c69966d9eb7bbfb660fd0ed9d397efc3	app/src/androidTest/AndroidManifest.xml
100644 blob 13354c18f8b5ab651d1b7a7f8662009c36cbfd57	app/src/androidTest/java/com/cattailsw/nanidroid/StartupRecoveryTest.kt
100644 blob d395e345a5153f8c02a166507b889103265b1aed	app/src/androidTest/java/com/cattailsw/nanidroid/WalkingSkeletonInstrumentationTest.kt
100644 blob 468f92df30ee91b6e13ac4c021df526a5d37d2a8	app/src/androidTest/java/com/cattailsw/nanidroid/corpus/Milestone5CorpusTest.kt
100644 blob 1358e85c5d1268554e8028dcb76c35a23d7f5e1c	app/src/androidTest/java/com/cattailsw/nanidroid/engine/NativePersistenceTest.kt
100644 blob 6ad89b13f46e11c43023c2a9feb0855e8aec338a	app/src/androidTest/java/com/cattailsw/nanidroid/engine/NativeShioriRealTest.kt
100644 blob 99a105ff069c98beb2f12d43abadbc29d56ad96f	app/src/androidTest/java/com/cattailsw/nanidroid/ghost/Milestone4CorpusSurfaceTest.kt
100644 blob 1d931b8524e61f35a72b5751d8f18417ae17cf03	app/src/androidTest/java/com/cattailsw/nanidroid/ghost/NativeProfileDirectoryDeviceTest.kt
100644 blob 0dbcae73d67d2f78e5ebef60d72727b6a2ed586e	app/src/androidTest/java/com/cattailsw/nanidroid/ghost/SurfaceComposerTest.kt
100644 blob bf0d690102cfecba32b72d4a542defe9aaa3ee6c	app/src/androidTest/java/com/cattailsw/nanidroid/ghost/SurfaceImageLoaderTest.kt
100644 blob 9528aa48364c73fcd2e90be0f23ea733ee40ddc8	app/src/androidTest/java/com/cattailsw/nanidroid/install/GhostImportInstrumentationTest.kt
100644 blob 38d28b953622be00e3ab6f10764a59eee1eb81c4	app/src/androidTest/java/com/cattailsw/nanidroid/install/GhostImportTestProvider.java
100644 blob 01aadc179ae21f05b6044373fddd05fe36a0662c	app/src/androidTest/java/com/cattailsw/nanidroid/ui/AboutDialogTest.kt
100644 blob 81d361e55b82d36bc0cc756d49c290c599f28b61	app/src/androidTest/java/com/cattailsw/nanidroid/ui/AnimatedSurfaceTest.kt
100644 blob a752701807f36a64ef6a578df91bcec2d85e6042	app/src/androidTest/java/com/cattailsw/nanidroid/ui/AuthoredSurfaceUiTest.kt
100644 blob e087441686b5a1cd8e9d5e1424e72c0f6b18907c	app/src/androidTest/java/com/cattailsw/nanidroid/ui/CharacterGestureTest.kt
100644 blob bee96ccf0664312326ef6e79761a2b4385878df2	app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostImportUiTest.kt
100644 blob b10914043ccd21d9bca38ae40216cb6806088f4a	app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostInteractionUiTest.kt
100644 blob 62094ab1ed12b23a09d12843a527bd63a427e9f6	app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostStageAlwaysLayersTest.kt
100644 blob 96e8f55f90b5b33946ae8580143f1c25d85bd4e6	app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostStageTest.kt
100644 blob d823ed3996fe30d0d96b6b24a3531b16256a5f50	app/src/androidTest/java/com/cattailsw/nanidroid/ui/Milestone4CorpusDynamicUiTest.kt
100644 blob e9d4544c818ea63ae0be55402b7e13ef0f591d98	app/src/androidTest/java/com/cattailsw/nanidroid/ui/NativeRotationTest.kt
100644 blob e7f2f281519ab4bf60f21aea693aabe461c31d91	app/src/androidTest/java/com/cattailsw/nanidroid/ui/NativeTalkProbeTest.kt
100644 blob 70b46f3bf110054a9822d2c3efc52f396c463177	app/src/androidTest/java/com/cattailsw/nanidroid/ui/RealGhostInteractionTest.kt
100644 blob 31a60111a6ed1fdc015747e5ffcc36fe3ff585f5	app/src/androidTest/java/com/cattailsw/nanidroid/ui/RealSatoriDoubleTapUiTest.kt
100644 blob a955d0034563c625e9c95098cdc8cba8e9f7a54a	app/src/androidTest/java/com/cattailsw/nanidroid/ui/StagePolishTest.kt
100644 blob 4a4463e7be2f70ce11e4e1f72efa73155ab32281	app/src/main/AndroidManifest.xml
100644 blob f01abd7497107a075e7e6955741b11165b8bf277	app/src/main/assets/nanidroid.zip
100644 blob e454a52586f29b8ce8a6799163eac1f875e9ac01	app/src/main/assets/notices/androidx-apache.txt
100644 blob e54ea9b4ba50d04c53ca1e0f01bb3e967ab84c17	app/src/main/assets/notices/bundled-ghost.txt
100644 blob ff9ad4530f5716304e042cbac72cecd7c40f0e0e	app/src/main/assets/notices/commons-codec-LICENSE.txt
100644 blob d3b64e9e3008ab61bb1ad3e155bf514773a7b08d	app/src/main/assets/notices/commons-codec-NOTICE.txt
100644 blob b447376016c314574ee4d4b3d3ebd6eaf2972f07	app/src/main/assets/notices/commons-compress-LICENSE.txt
100644 blob fa969b3ce6ca657974f25411dbe3b273950121dc	app/src/main/assets/notices/commons-compress-NOTICE.txt
100644 blob c8118641cddd58f5b8f175c4ec91b8e69b65e97c	app/src/main/assets/notices/commons-io-LICENSE.txt
100644 blob 2a4682551b1f9b07147b82f7435661396f5267b8	app/src/main/assets/notices/commons-io-NOTICE.txt
100644 blob ff9ad4530f5716304e042cbac72cecd7c40f0e0e	app/src/main/assets/notices/commons-lang3-LICENSE.txt
100644 blob 9c0ea0be638b9b0ea11598bd6b20f42a23856434	app/src/main/assets/notices/commons-lang3-NOTICE.txt
100644 blob 7f9931e8060cf5e1d3398b8494517abb49280b65	app/src/main/assets/notices/kawari-mt19937.txt
100644 blob 04b86611bcc1cc5273cf080448745d0b8db3bce3	app/src/main/assets/notices/kawari.txt
100644 blob 127a5bc39ba030c7cb99cc0aedc4f280ffe27310	app/src/main/assets/notices/kotlin-stdlib-boost.txt
100644 blob 8dc8226f64f07dc5134d3674371cbfe109465cd9	app/src/main/assets/notices/kotlin-stdlib-copyright.txt
100644 blob d645695673349e3947e8e5ae42332d0ac3164cd7	app/src/main/assets/notices/kotlin-stdlib-gwt.txt
100644 blob bbed3563e938dd26bdc2daeb5f5cccdd0f03eb61	app/src/main/assets/notices/kotlin-stdlib-threetenbp.txt
100644 blob d3a04bc2a77d5df1d42097d27c7152552f584111	app/src/main/assets/notices/satori.txt
100644 blob 24916886fae410c825b2499d57168fb640f0c52c	app/src/main/assets/notices/yaya-mt19937.txt
100644 blob 7716b63908f965f88c2b9744c4178ace96bdc4ce	app/src/main/assets/notices/yaya.txt
100644 blob 55af158bb3565039ff8a40f81bfe72205deeb553	app/src/main/jni/CMakeLists.txt
100644 blob d48595e8f1beec6e273ddaed94795d13db32e549	app/src/main/jni/_/Dialog.cpp
100644 blob d023fa4f1bae863f6a342c9eeca21fd207311dae	app/src/main/jni/_/Dialog.h
100644 blob e84dc53c9bf0b1a70e7f9f1146d729753eb57ce3	app/src/main/jni/_/FMO.h
100644 blob c448f93af880ff24dc6ef842ff3df0dd1546f8d5	app/src/main/jni/_/Font.cpp
100644 blob c4c650d1784dcd2b7be67ba269e652c5e291ce46	app/src/main/jni/_/Font.h
100644 blob 7e630396eea84e14996d3e619cb2b7cd79b931a6	app/src/main/jni/_/Sender.cpp
100644 blob bb0f4c6630ff3c42508bd3e6ef2d624594a294e5	app/src/main/jni/_/Sender.h
100644 blob efa9bc339c63cba9e1bdda1f06f731175bd9aa13	app/src/main/jni/_/Thread.cpp
100644 blob 69b0ace79cf5bd15a854052f5d10aabfb67cbbba	app/src/main/jni/_/Thread.h
100644 blob e81855e54d76d75f38bde4f2d17265eb95b9d908	app/src/main/jni/_/Utilities.cpp
100644 blob 1675efeb60308e19030d09e47509deea06b1f68a	app/src/main/jni/_/Utilities.h
100644 blob 1286ac3f37b1aab57e1870f12139fdca42eabe1e	app/src/main/jni/_/Win32.cpp
100644 blob eb8e7e06a8ddb52a7f781ab9036525ce5b60d0ec	app/src/main/jni/_/Win32.h
100644 blob 9d553ac88a981bf78d32910e511438abaeb342a4	app/src/main/jni/_/Window.cpp
100644 blob 6a7ede087c0c131edde1a63a9da7b0b4bfe64969	app/src/main/jni/_/Window.h
100644 blob e1844ca69dbec7da8619bbbfd3fcda30424514f1	app/src/main/jni/_/calc.cpp
100644 blob 396277703ed95efc97cc80110ae1f223675d7ee8	app/src/main/jni/_/calc_float.cpp
100644 blob 7fbbcf28812cf13dabaf24ccf7bb1aba4a27c0fa	app/src/main/jni/_/simple_stack.h
100644 blob 74b35daf95622c7362b0f694432ae51d2daf90ed	app/src/main/jni/_/source-literal-manifest.json
100644 blob 4db2340bdaf67b6ca5eaae6dcd1f357bd0c368c5	app/src/main/jni/_/stltool.cpp
100644 blob bd3506c1b5cdce35fc7d2cc35d87a2ac38210f4b	app/src/main/jni/_/stltool.h
100644 blob 458563c8ade200fbd8149d28f818603398147a4c	app/src/main/jni/kawari8/Android.mk
100644 blob 077fe41670701581084f0298af77375dbeb453a0	app/src/main/jni/kawari8/bcc.mak
100644 blob e9a402cf0e85542f9834f778b03e4a76088d487f	app/src/main/jni/kawari8/config.h
100644 blob d8ea590d72392fea1b22a71e3ef3bb5b2a67380e	app/src/main/jni/kawari8/depend.mak
100644 blob 3ea39a0ee3888c4c2be0f40ae5f7da06a7e47048	app/src/main/jni/kawari8/files.mak
100644 blob a9d1fe4391aad07efe60ca6939134f2c7824a1eb	app/src/main/jni/kawari8/gcc-mach.mak
100644 blob dff6f89da535a9bda559ab4edb3253977a94f136	app/src/main/jni/kawari8/gcc.mak
100644 blob a8b592ed3aa8f5938da1e83a3b03a4c6af397ab2	app/src/main/jni/kawari8/include/old/shiori_posix.h
100644 blob 2f08f4394ce9efbde5d36e1d15e62c588bed7c36	app/src/main/jni/kawari8/include/shiori.h
100644 blob 5ba2116b4ff13f6cb6c39725f6ac69996f408f72	app/src/main/jni/kawari8/include/shiori_object.h
100644 blob 5746ad5ffa18859e9bbf6724aad06f7c2de856a5	app/src/main/jni/kawari8/kawari_jni.cpp
100644 blob 7fc0f739f45bdf75960dfd0481a2819a297ff772	app/src/main/jni/kawari8/kis/kis_base.h
100644 blob b975ee4293865ca800d732251bface8b7101839d	app/src/main/jni/kawari8/kis/kis_communicate.cpp
100644 blob 25068e5f348349e28f1bb5685e0d57ba7937f1c9	app/src/main/jni/kawari8/kis/kis_communicate.h
100644 blob dd8e4aaf4c96fff5aa9181bc325dbf8e277941c3	app/src/main/jni/kawari8/kis/kis_config.h
100644 blob dbb97baaca81e83bf60f567cb7f051a697ae9403	app/src/main/jni/kawari8/kis/kis_counter.cpp
100644 blob 07d64843572756f244ef73cfca0e110d77f1df1f	app/src/main/jni/kawari8/kis/kis_counter.h
100644 blob 2f73c8e76bef7b2926bf945bbd533ec004ca235b	app/src/main/jni/kawari8/kis/kis_date.cpp
100644 blob db795bd20181d57f72e95601a0da9f2d764e3b63	app/src/main/jni/kawari8/kis/kis_date.h
100644 blob 5ad1eadbced119a3b9c8da5874ce72944115c918	app/src/main/jni/kawari8/kis/kis_dict.cpp
100644 blob b3f202ce859b504ebb70002a30c34866e5c1b049	app/src/main/jni/kawari8/kis/kis_dict.h
100644 blob 7096c968ceb94f2ad23366ce3c3d751bdbbf9456	app/src/main/jni/kawari8/kis/kis_echo.cpp
100644 blob 9dcba3637a15b19846ccdc025df188ec404a3351	app/src/main/jni/kawari8/kis/kis_echo.h
100644 blob 491f1f4d6aee822433158307eafd8b32c358e19c	app/src/main/jni/kawari8/kis/kis_escape.cpp
100644 blob cb27367f4121f629535629f1389061cc06345071	app/src/main/jni/kawari8/kis/kis_escape.h
100644 blob 0eacfae431c775bcfb963db065165bfd87caf19a	app/src/main/jni/kawari8/kis/kis_file.cpp
100644 blob d9fb62c851cbd85ca4a0d46926dd24e4913aecf5	app/src/main/jni/kawari8/kis/kis_file.h
100644 blob c8ff2fae1cb257d6e058dab2a11df276e9517b77	app/src/main/jni/kawari8/kis/kis_help.cpp
100644 blob 2f58c42934977277159c780bfac72d923e198285	app/src/main/jni/kawari8/kis/kis_help.h
100644 blob d4e3915f45fa8d810ae953ba7f6fa4816a42e357	app/src/main/jni/kawari8/kis/kis_math.h
100644 blob d8173b82ca912fd94cf2bba8fbcfd45b127385f0	app/src/main/jni/kawari8/kis/kis_saori.cpp
100644 blob a10c5950e49f305917195ae436df9e099d63dcbb	app/src/main/jni/kawari8/kis/kis_saori.h
100644 blob 7272f26115cb35f65020b467514a1cac8d9b537a	app/src/main/jni/kawari8/kis/kis_split.cpp
100644 blob 8fc26fe8c3673b8b93987a8cf5cb8f167ba1868c	app/src/main/jni/kawari8/kis/kis_split.h
100644 blob 2ab199ffa3cbfe7760ad23e206d2ef02417f919d	app/src/main/jni/kawari8/kis/kis_string.cpp
100644 blob 51061c746613f705d0d047617a7ef24fc13d3ed9	app/src/main/jni/kawari8/kis/kis_string.h
100644 blob f53783302449e408162ecaa18540bcb6e824bfa9	app/src/main/jni/kawari8/kis/kis_substitute.cpp
100644 blob 527c60f8a02c7de0dd8f9c7d85b18f683c8d2ff8	app/src/main/jni/kawari8/kis/kis_substitute.h
100644 blob 3590fd69ab490e4d013d84a36db4aa2ff135d63c	app/src/main/jni/kawari8/kis/kis_system.cpp
100644 blob ca14dc1e5afc160691875ffda65fdf43b6f84361	app/src/main/jni/kawari8/kis/kis_system.h
100644 blob ab619c513ab904be6f2aec0897595f9cafb52714	app/src/main/jni/kawari8/kis/kis_urllist.cpp
100644 blob 16efb0fc53e5c6ba43787459a9dd899b5c8a917e	app/src/main/jni/kawari8/kis/kis_urllist.h
100644 blob 6b73a40356c7e62046cf76ce5f8de47cd981221a	app/src/main/jni/kawari8/kis/kis_xargs.cpp
100644 blob 7456a91aa4e67387cc9f3882b06d91ca17bc5095	app/src/main/jni/kawari8/kis/kis_xargs.h
100644 blob af1cad28d2fb71d6b2bb1242756b3f1c552f4cd0	app/src/main/jni/kawari8/libkawari/kawari_code.cpp
100644 blob fee212b70737060149c6d53dfe5e77949407ce29	app/src/main/jni/kawari8/libkawari/kawari_code.h
100644 blob 4bca57b02fc2312abf473d0c46726426412bc07e	app/src/main/jni/kawari8/libkawari/kawari_codeexpr.cpp
100644 blob 043637600d18739a7ea51433bd483900c19caabd	app/src/main/jni/kawari8/libkawari/kawari_codeexpr.h
100644 blob 643edb031d4ff55bf1a923571fc381635350d2d9	app/src/main/jni/kawari8/libkawari/kawari_codekis.cpp
100644 blob 05029bd489e10d73b2793ec390083d8d4063c246	app/src/main/jni/kawari8/libkawari/kawari_codekis.h
100644 blob 90a7a19c16451abbc8bd6a8da810bea7fbd8dc66	app/src/main/jni/kawari8/libkawari/kawari_codeset.cpp
100644 blob 77c9819b4275202d3b7f58c763a226e048273717	app/src/main/jni/kawari8/libkawari/kawari_codeset.h
100644 blob 85ee3286c4e12229a054f177154523f6b59e1c8c	app/src/main/jni/kawari8/libkawari/kawari_compiler.cpp
100644 blob 388a5a82c59f3e0f65f8824d81cfafab900116ad	app/src/main/jni/kawari8/libkawari/kawari_compiler.h
100644 blob fb2fe4347c638fcbd75afbab79a96879754d6caa	app/src/main/jni/kawari8/libkawari/kawari_crypt.cpp
100644 blob ddc4fe8cbd9abecb004cc7936970364564cc6e25	app/src/main/jni/kawari8/libkawari/kawari_crypt.h
100644 blob 33e331436f12409087fef9e9bbad60bd9990490d	app/src/main/jni/kawari8/libkawari/kawari_dict.cpp
100644 blob 5030f016eb8774ed6e94fa9cfcb29e0dd4e761b9	app/src/main/jni/kawari8/libkawari/kawari_dict.h
100644 blob 038645184f44a46a93e19bc719f4e3626c623940	app/src/main/jni/kawari8/libkawari/kawari_engine.cpp
100644 blob d29585737a0c076bbc821fbaaf9f7f1378ba4227	app/src/main/jni/kawari8/libkawari/kawari_engine.h
100644 blob 1fac92a530fbaf0983d75769f639edd976e42854	app/src/main/jni/kawari8/libkawari/kawari_lexer.cpp
100644 blob 62ace6db5d99db5fcd7b7ecf343e581ea4f643fe	app/src/main/jni/kawari8/libkawari/kawari_lexer.h
100644 blob 6f1b6bc52525d8a5b56266b9a42f12b18df500db	app/src/main/jni/kawari8/libkawari/kawari_log.cpp
100644 blob 876383cecdefcda02c1ee21c1e43dbcbe9f83023	app/src/main/jni/kawari8/libkawari/kawari_log.h
100644 blob 5ee44e29356157308a516b8a2edfd9fc0a2d1746	app/src/main/jni/kawari8/libkawari/kawari_ns.cpp
100644 blob bd7f4616d0537c16bc47861491e944895b110de9	app/src/main/jni/kawari8/libkawari/kawari_ns.h
100644 blob bb66d89472a45338338a81a74cfe8fc04d4f5cc2	app/src/main/jni/kawari8/libkawari/kawari_rc.cpp
100644 blob 5448d580d9acec17d4c3b15d0c88001918f63345	app/src/main/jni/kawari8/libkawari/kawari_rc.h
100644 blob 000271e1c47e3d5ad692002df8e964a07cd9d8a4	app/src/main/jni/kawari8/libkawari/kawari_rc.sjis
100644 blob 1a464d43e4e004ce543715fbf8cbb8b1a6475506	app/src/main/jni/kawari8/libkawari/kawari_rc_sjis_encoded.h
100644 blob 91fb62caadb1e5b57ee3447aa85b54f7ec12e767	app/src/main/jni/kawari8/libkawari/kawari_version.h
100644 blob 9e66001b6031c3d3223138a7a9f6ebf30723ea1b	app/src/main/jni/kawari8/libkawari/kawari_vm.cpp
100644 blob fc207594f65d7aff6362c4c9c66aa17d949db30a	app/src/main/jni/kawari8/libkawari/kawari_vm.h
100644 blob bc5a39d75aad1e7ba074de92fc40553feb3d90b9	app/src/main/jni/kawari8/libkawari/wordcollection.h
100644 blob 908706085519d8b9178ada29ab296294d697a5fd	app/src/main/jni/kawari8/makedepend.rb
100644 blob bc244617b12d28c6d12526b91d8237f7dcbf1054	app/src/main/jni/kawari8/misc/_dirent.cpp
100644 blob e11298bb1e3753d93535e3e73778c60ee654bbb8	app/src/main/jni/kawari8/misc/_dirent.h
100644 blob a5e105d5047f12b726b778585ac995c373225513	app/src/main/jni/kawari8/misc/base64.cpp
100644 blob f77a0916779369edd5a01c1b0929cda0f39507d6	app/src/main/jni/kawari8/misc/base64.h
100644 blob f9d7846207b6c0860b4ab5205c570099b38311d1	app/src/main/jni/kawari8/misc/l10n.cpp
100644 blob 215fe597f4d3b9b627339319a2e07a1e4d64c933	app/src/main/jni/kawari8/misc/l10n.h
100644 blob 3a4ae0922127a4aae8d708c7cfffea3cf4687a22	app/src/main/jni/kawari8/misc/misc.cpp
100644 blob f93287df8341628b6473e5f65a644f2afa714983	app/src/main/jni/kawari8/misc/misc.h
100644 blob 674ee2d7826f15067f1c400244cd114858dfd7e1	app/src/main/jni/kawari8/misc/mmap.h
100644 blob c0b959d65d8dcd80ad311e5d4bf18d7e7f59adfc	app/src/main/jni/kawari8/misc/mt19937ar.cpp
100644 blob c8c513abc35d353a062059b5a1b25e6f47dfcd48	app/src/main/jni/kawari8/misc/mt19937ar.h
100644 blob f4252c7ea20d126c28cdd874f6eee969d7127606	app/src/main/jni/kawari8/misc/phttp.cpp
100644 blob 05cde29611c439f94d5ebc1a6d0db14cc4c3c6eb	app/src/main/jni/kawari8/misc/phttp.h
100644 blob 1989cef65e4c640cdc987db1ebcb9f3ba5ae1e1c	app/src/main/jni/kawari8/saori/old/saori_libdl.cpp
100644 blob 2639cf151bea65674174088f4e3c2e0104d6f143	app/src/main/jni/kawari8/saori/old/saori_libdl.h
100644 blob 983a664889a526c92dce0ae176ef7479c79fd637	app/src/main/jni/kawari8/saori/old/saori_win32.h
100644 blob cb7eb57f962539c187219c01f598e1159a0fc20b	app/src/main/jni/kawari8/saori/saori.cpp
100644 blob e2712c83a5a3fbd6d7af644b873c4a8fdb8f4850	app/src/main/jni/kawari8/saori/saori.h
100644 blob 7ee13a492a0c06eefd98aaeb3b7a4fcdfc097cc7	app/src/main/jni/kawari8/saori/saori_java.cpp
100644 blob 7169479382fbe3cc22276925e31106f7de219f23	app/src/main/jni/kawari8/saori/saori_java.h
100644 blob 834bf6fbd013b10d52a3fa9f66619dfb9fdb15b9	app/src/main/jni/kawari8/saori/saori_module.cpp
100644 blob af0e4fb6d648f314eaf299ee5b2b103f72e8e59b	app/src/main/jni/kawari8/saori/saori_module.h
100644 blob 5a15de0e1f6a5ef09d2abb8f21af796a0ac0991b	app/src/main/jni/kawari8/saori/saori_native.cpp
100644 blob 6dadc5b1e49a68a6b7941c6eee830bf522d72b17	app/src/main/jni/kawari8/saori/saori_native.h
100644 blob fc07806e554b83c93ec8673696f5a87506a89e12	app/src/main/jni/kawari8/saori/saori_python.cpp
100644 blob 1264d88c9e5124d34778712bc3639101e5e3dc70	app/src/main/jni/kawari8/saori/saori_python.h
100644 blob 8f6e8f26a73640a9f7628fd60b20a2ff3658b084	app/src/main/jni/kawari8/saori/saori_unique.cpp
100644 blob cdc46064dbf0c7eb93a123745f1c50235397bfca	app/src/main/jni/kawari8/saori/saori_unique.h
100644 blob 32780f49c90272070e0661ebe8e9021dffbdda79	app/src/main/jni/kawari8/shiori/kawari_shiori.cpp
100644 blob aa1d103936cce5ae07a94fb471f3845c217afdc4	app/src/main/jni/kawari8/shiori/kawari_shiori.h
100644 blob abfadb346890b3549e916e276c59ab4c215009f9	app/src/main/jni/kawari8/shiori/old/shiori_posix.cpp
100644 blob a20776c1a48ec41057d99d0ad7df5b795a1f3537	app/src/main/jni/kawari8/shiori/py_shiori.cpp
100644 blob da003a831b4cf0661404a3750d7a9069854ca724	app/src/main/jni/kawari8/shiori/py_shiori.h
100644 blob 6c9df0ff16e8ca98d165da7ce590b18cd37cb820	app/src/main/jni/kawari8/shiori/shiori.cpp
100644 blob 63578416a9aad55f7f7adbfef90c61106b82ae92	app/src/main/jni/kawari8/shiori/shiori_object.cpp
100644 blob bce92c891d0dd293ab1763588d046a96c5fddfc5	app/src/main/jni/kawari8/sjis2ascii.rb
100644 blob c495cd6285730f03313f0f3597ff3509f962672b	app/src/main/jni/kawari8/tool/kawari_decode2.cpp
100644 blob e6ab7698f7b46cdd724eb7b5cb47a8b2acebc900	app/src/main/jni/kawari8/tool/kawari_encode.cpp
100644 blob 1ffea73751c20df447c30893741d29ee0fc35580	app/src/main/jni/kawari8/tool/kawari_encode2.cpp
100644 blob 6000519e6dd3279f9d043f3d1c780c9278f71424	app/src/main/jni/kawari8/tool/kawari_kosui.h
100644 blob 90afc6cc0d86a8e5a8abcd6ca9230e009a2bb957	app/src/main/jni/kawari8/tool/kdb.cpp
100644 blob 06c321cd5bd5ea87690b40d78e2a01b04c804056	app/src/main/jni/kawari8/tool/kdb.h
100644 blob e5ab96c8638b95753107c4af6752596c6dc4366d	app/src/main/jni/kawari8/tool/kosui.cpp
100644 blob 4705f5002883bceb30fe6ae9b8cd72f59128629a	app/src/main/jni/kawari8/tool/kosui_base.h
100644 blob 5291d0883d3f53a0ea9042c3f6d708f30cfaea54	app/src/main/jni/kawari8/tool/kosui_dsstp.cpp
100644 blob 72bbc13fd6fcdc3cfc2405396d4d60b927e501e2	app/src/main/jni/kawari8/tool/kosui_dsstp.h
100644 blob 85c17750d44a3235d19781172aeed923e2dc0c43	app/src/main/jni/kawari8/tool/logserver.cpp
100644 blob fd2141d8bb872c95912adad6044ed43e3d65aa6d	app/src/main/jni/kawari8/vc_kawari/vc_kawari.dsp
100644 blob 874d7c7a548dd993c081a77ebd393ef4d1aaba95	app/src/main/jni/kawari8/vc_kawari/vc_kawari.dsw
100644 blob 4395aba4fee467063e173978e46dbf9eeebdb779	app/src/main/jni/kawari8/vc_kawari/vc_kawari.sln
100644 blob b448a44faed6073cbccc010cb90bb27a5d1496da	app/src/main/jni/kawari8/vc_kawari/vc_kawari.vcproj
100644 blob 001582458467acc90dd16cdb526aa69be888fa20	app/src/main/jni/kawari8/vc_kawari/vc_kosui.dsp
100644 blob 229e1af892a3fd5923a59ff5ec957ebb3692db8d	app/src/main/jni/kawari8/vc_kawari/vc_kosui.dsw
100644 blob bd257f128d4505dc2b5b1d63873ac8e4883332b9	app/src/main/jni/kawari8/vc_kawari/vc_kosui.sln
100644 blob 7ee79ca7e74d5a97c017eaf217d29f7322d44bb7	app/src/main/jni/kawari8/vc_kawari/vc_kosui.vcproj
100644 blob c70a0574aefd79a07c914260b3ef836594bbae4c	app/src/main/jni/kawari8/win32jvm.def
100644 blob 0f96c2fe2a28de77e13cac30f9d6cff78d70172d	app/src/main/jni/satori/Android.mk
100644 blob b1cbd7efbbd9cf25c5b25cf66ffa5e04326c8c53	app/src/main/jni/satori/Families.h
100644 blob f0d8b843dafd47195c3ec77e739baa0a5ab0ef56	app/src/main/jni/satori/Family.h
100644 blob 7943b72201d0664bd9d15dd084cce20663f3c11a	app/src/main/jni/satori/OverlapController.h
100644 blob 0360b8d4362f51ea8095e4e7efc5cf90c0212125	app/src/main/jni/satori/SSTPClient.cpp
100644 blob 8682e66e452669bf4914652bc24a97407380c500	app/src/main/jni/satori/SSTPClient.h
100644 blob e6c8fb26f261916ccbe3bc4fa50c4bdfea8729cd	app/src/main/jni/satori/SakuraCS.cpp
100644 blob faf25936ac328c8d55d524c63f5acac6758edd0c	app/src/main/jni/satori/SakuraCS.h
100644 blob ea464afa112f049109ae89b5809eaf5b67528574	app/src/main/jni/satori/SakuraClient.cpp
100644 blob df94f20a34280614bf0bfcc685b897d1064b3c2b	app/src/main/jni/satori/SakuraClient.h
100644 blob 4d7a18a40a276006947b9dd23f11880cb4c10b2c	app/src/main/jni/satori/SakuraDLLClient.cpp
100644 blob 58f4cb9c07476772365a543d5099cd618d72cd08	app/src/main/jni/satori/SakuraDLLClient.h
100644 blob 0f59d54510c30fe48fdadd5c91fa68f509b55c33	app/src/main/jni/satori/SakuraDLLHost.cpp
100644 blob 979e821ba51a1c26a60d664c8f18fcf068dbe9e6	app/src/main/jni/satori/SakuraDLLHost.h
100644 blob 55dd548a8b858e93cca8c80247b706bd8ec42ba9	app/src/main/jni/satori/SaoriClient.cpp
100644 blob 2f4033dbde3c3f127a50ca40be60fd619ed4fd47	app/src/main/jni/satori/SaoriClient.h
100644 blob 16f021e63ded8517768956a9ea6387a34984a2bb	app/src/main/jni/satori/SaoriHost.cpp
100644 blob b0c29c5fa3cb6f6183cb0d48cd0cffbbc7826a65	app/src/main/jni/satori/SaoriHost.h
100644 blob 6e940414000de550974f08821bd0a15365fe167d	app/src/main/jni/satori/Selector.h
100644 blob e23cc62c3cdcfc428a9572c03a79a3b45cf5cae1	app/src/main/jni/satori/ShioriClient.cpp
100644 blob 33848035db087c893d4b6d4427ac110222ca849d	app/src/main/jni/satori/ShioriClient.h
100644 blob 995256e44cfd81b4c1addadfa3b31cedbdba5d76	app/src/main/jni/satori/TimeCommands.cpp
100644 blob 65637fbf53fc4c07534f8a2decf3c6c092eb1740	app/src/main/jni/satori/WinMain.cpp
100644 blob 83306c7f95e04c4b1c9871b36c4e3cc06e40d20d	app/src/main/jni/satori/console_application.h
100644 blob 5ec6aaed118ce52e6d32c185d4103b3623a5849e	app/src/main/jni/satori/index.html
100644 blob fd180dd903e20a95f1819e10bcb6ce83d3e1e180	app/src/main/jni/satori/main.cpp
100644 blob 97320ce35e4fd2cbdabffeb7a8028731ddab9c34	app/src/main/jni/satori/makefile.cygwin
100644 blob 75685e792950b050483f8553e3d5da60e3c6270d	app/src/main/jni/satori/makefile.posix
100644 blob 976da17907c89c22de5afd3a1a90e1e77c385491	app/src/main/jni/satori/posix_utils.h
100644 blob 7e3668b3dbe8cfe677caaf22068d244a8b15869c	app/src/main/jni/satori/satori.cpp
100644 blob 04abe552d5c146923930cbb6e4ed09e975dc4f71	app/src/main/jni/satori/satori.dsp
100644 blob ad06dbf13123e59db757f830bf6301ad51f6de18	app/src/main/jni/satori/satori.dsw
100644 blob 5427476168e911e290babee4f7df2822532686cc	app/src/main/jni/satori/satori.h
100644 blob 67868fccf9aff09a9f45f8045911ee7820688465	app/src/main/jni/satori/satoriFMO.cpp
100644 blob 9bd4978f10248168aad694b701c8ce02863bdf55	app/src/main/jni/satori/satoriTranslate.cpp
100644 blob 19f3dae0e0ae34de091942870c04a15621299961	app/src/main/jni/satori/satori_AnalyzeRequest.cpp
100644 blob 6c9ace5d2be76387222d3441688d4e8c242e47e5	app/src/main/jni/satori/satori_CreateResponce.cpp
100644 blob 61251b722e06399e3e08503db2674643c4357608	app/src/main/jni/satori/satori_EventOperation.cpp
100644 blob fdb3beebf07feaf81c73a0c2a2f1132f5e110810	app/src/main/jni/satori/satori_Kakko.cpp
100644 blob 625d37c3d8f8be07480814a2b3ee0a19fb230344	app/src/main/jni/satori/satori_jni.cpp
100644 blob fa420e5bcf51c7168b3d2616c43eaa558102b078	app/src/main/jni/satori/satori_load_dict.cpp
100644 blob 3ae0ff808576070147a6f792d3be3e60071d169c	app/src/main/jni/satori/satori_load_dict.h
100644 blob 8e7e0197ff578f0122db5f68be52836510eaea9c	app/src/main/jni/satori/satori_load_unload.cpp
100644 blob 9e22c5651104a9803f49e713db350a1a5b556ff2	app/src/main/jni/satori/satori_sentence.cpp
100644 blob 66eeb422f07d951ad7deb32823b0806338535281	app/src/main/jni/satori/satori_test.dsp
100644 blob 9bc38cb101c67ee190d1b8a2510857c77fc32bf8	app/src/main/jni/satori/satori_tool.cpp
100644 blob 522222227dc7c989255bad04decd1c0e92a28f1a	app/src/main/jni/satori/satorite.dsp
100644 blob 3563def11679b50162865306d2d6109d32c3fb45	app/src/main/jni/satori/shiori_plugin.cpp
100644 blob 14f6da4b355e42292feb9b439ee963d98302481c	app/src/main/jni/satori/shiori_plugin.h
100644 blob 7705bb30e986cd482f21bb87d31d18aea20dd318	app/src/main/jni/satori/source-literal-manifest.json
100644 blob 60b19f7b6d212c116353a2deebc8c3f22808dd60	app/src/main/jni/satori/ssu.cpp
100644 blob dc544a9f10f738e28254192a56f684fe7ec77233	app/src/main/jni/satori/ssu.dsp
100644 blob e7787ebe8ce7990bea9a57129493cd7f85f14087	app/src/main/jni/satori/ssu_anchor.cpp
100644 blob 923deb744ce023ddb207073497fbed6d22717dd4	app/src/main/jni/satori/test/characters.ini
100644 blob 5347ed0f5727a025096de6ee4d5904d4dbd036db	app/src/main/jni/satori/test/dic1.txt
100644 blob 8f37aaeadcd49b2cb78d501375095d2dfed966ab	app/src/main/jni/satori_compat.h
100644 blob f4e44f571ad3645a09fe65f3288183dc934881f9	app/src/main/jni/satori_license.txt
100644 blob 72ab1f2d6def819caad043b9c5ec5474857ec7a9	app/src/main/jni/yaya/.clang-format
100644 blob 2e400d07f8234c6b79cc0ad64ef093b619ae11e6	app/src/main/jni/yaya/.gitignore
100644 blob e7e702f8529aef92aafd22a45ea2fda7e6487128	app/src/main/jni/yaya/LICENSE
100644 blob 1c21682dd4dd95644673f626fab68136fc3cb133	app/src/main/jni/yaya/android_charset.cpp
100644 blob 892d8f09d9605e897707864da6b70cfc0e6aefe8	app/src/main/jni/yaya/android_charset.h
100644 blob b99a115cf56ae9b164058ce2207febce305fbec7	app/src/main/jni/yaya/aya5.cpp
100644 blob 668832d46b25ec52ef62af6802f040ad36efe123	app/src/main/jni/yaya/aya5.h
100644 blob 7d575065103d13d7e20d8946a5c5b7aa2cf0086f	app/src/main/jni/yaya/aya_profile.cpp
100644 blob 16cf10b82c1a048a9f3d952866781c59272b6d18	app/src/main/jni/yaya/ayavm.cpp
100644 blob a0383bd11c5f3637e4db153b49b36e5b3c6cb15a	app/src/main/jni/yaya/ayavm.h
100644 blob acea18f8cc4c33c4799b394626fb41b0028b957a	app/src/main/jni/yaya/basis.cpp
100644 blob c2eff0c12e0c853d4893b6f0af9889b33be2ee00	app/src/main/jni/yaya/basis.h
100644 blob 5ec808d7aadfb6d107b2e7233f1bbcbd741c88d5	app/src/main/jni/yaya/ccct.cpp
100644 blob ebe05d57b2a08f922dad97040b21d9f669d8a9e6	app/src/main/jni/yaya/ccct.h
100644 blob 07982d40d0053c069258bee54f8bce8e506dfd60	app/src/main/jni/yaya/cell.h
100644 blob 8753889e49375c20c9926714d90ec23367fc85e6	app/src/main/jni/yaya/comment.cpp
100644 blob 9fcb07318b51905ca3fab657b47e004282376663	app/src/main/jni/yaya/comment.h
100644 blob 1dff590bb08ba3ee41cdb87a1c8132a6679193da	app/src/main/jni/yaya/cpp.hint
100644 blob cffc96402f27be6f20f83ebc38b678759d620046	app/src/main/jni/yaya/crc32.c
100644 blob 91e25e4fea0e88407cfd67a46c9ee6a5980a82fe	app/src/main/jni/yaya/crc32.h
100644 blob ff6939c32f0a17c9a804ad3b44e5cc633313a086	app/src/main/jni/yaya/deelx.h
100644 blob 692497123cd03b1d75c6657d28286dd8d71e5410	app/src/main/jni/yaya/dir_enum.cpp
100644 blob 933ddaad200d55cb7d58471b3f0f4c1e207a3259	app/src/main/jni/yaya/dir_enum.h
100644 blob 70116310c079fe6ef6eed816210f98980ef69a44	app/src/main/jni/yaya/duplevinfo.cpp
100644 blob 29ba28243e65ed71312ca2c8babf12b77b509bc6	app/src/main/jni/yaya/em-post.js
100644 blob 0f951632b073ee4741f1cd744f5c68882ecf38a5	app/src/main/jni/yaya/em-pre.js
100644 blob 91f74b85b2684f993d364cdff6a2eb9069498a04	app/src/main/jni/yaya/file.cpp
100644 blob 956625c8d0ce69c2096f8c20456cb1dab29ef531	app/src/main/jni/yaya/file.h
100644 blob 72788c6908fd29f3cff031281c7888b24a7af79b	app/src/main/jni/yaya/file1.cpp
100644 blob 3a6bb5481294a9ac409cd657ef6ac576692e3ecd	app/src/main/jni/yaya/fix_unistd.h
100644 blob c65054591fd88d8691755aee9e0ea0e8731a54a0	app/src/main/jni/yaya/function.cpp
100644 blob a32726b43c4f7ef00cebd27d4562ddbcd441b3fe	app/src/main/jni/yaya/function.h
100644 blob 5ca98b06fd5534a53b6893e2bf069c25318b22c5	app/src/main/jni/yaya/global.h
100644 blob 69f0802effa471da34659633163624963763a827	app/src/main/jni/yaya/globaldef.h
100644 blob f48e02aae2b61766b7bd3b123c4b89dd6de90e25	app/src/main/jni/yaya/globalvariable.cpp
100644 blob fcc99fb74c85b6be552b479f8d174f68739cfcbe	app/src/main/jni/yaya/lib.cpp
100644 blob 50a3e67ebc0974538f544073c51dbc89dd813e70	app/src/main/jni/yaya/lib.h
100644 blob b531684ec22a766ccf3eb5a7a75f178a11a0ef87	app/src/main/jni/yaya/lib1.cpp
100644 blob e4dbd5ec7e59c64869392aa63e356f393497b4fb	app/src/main/jni/yaya/localvariable.cpp
100644 blob 308b87c3aad4e8a2fd78cdc36707420dfad35ff8	app/src/main/jni/yaya/log.cpp
100644 blob 0940e103a608b255a6c01c9c7a5e88b3715d5c39	app/src/main/jni/yaya/log.h
100644 blob 45ea7762d7397b1dcf0d35d30b02aacdfda26a92	app/src/main/jni/yaya/logexcode.cpp
100644 blob ca41129304d81aeab0875df63496a343bfeddba5	app/src/main/jni/yaya/logexcode.h
100644 blob af61f8e38b3b9f1a18600a03e4c46b499cfb45d5	app/src/main/jni/yaya/make_aya.bat
100644 blob dc6e1f8e4949490a814f3940e134aa9bcf06497c	app/src/main/jni/yaya/makefile.emscripten
100644 blob f0a9e6cdb3c0047b5685381122742c62745e25ec	app/src/main/jni/yaya/makefile.fc6
100644 blob 96c19283361871f8fe4686499ac725a7a9b6615e	app/src/main/jni/yaya/makefile.freebsd
100644 blob 9d2b1bacc7b4017c1eb7d29c556e2ceaf2b82611	app/src/main/jni/yaya/makefile.linux
100644 blob 7170394a14386a76cc65936573db692992eec646	app/src/main/jni/yaya/makefile.mingw32
100644 blob e94e5f56cd724410f7489b3acf603d9d88cd99ab	app/src/main/jni/yaya/makefile.posix
100644 blob 8f5617fed2d9d644706cb2531ba7681aa9925bcc	app/src/main/jni/yaya/manifest.cpp
100644 blob c84458f5de85b8aa5c4a7554b5aa8d186fd36729	app/src/main/jni/yaya/manifest.h
100644 blob c1f4d5178dd44fa7b5b9dfe06b1716b92266a8fd	app/src/main/jni/yaya/md5.h
100644 blob 0d654e5f95c362231239ae45d1fecaf75e733ac6	app/src/main/jni/yaya/md5c.c
100644 blob cc9af071ba1c5ae574b2b922aafc40afea62062d	app/src/main/jni/yaya/messages.cpp
100644 blob e186d1f405a0608a3a603bdbaafad953adc04f5b	app/src/main/jni/yaya/messages.h
100644 blob 70ef671baee286c2e9db7c175491f6f07cb74c9d	app/src/main/jni/yaya/misc.cpp
100644 blob 4f3089ecbc199f44481039bc423d3a342c734dca	app/src/main/jni/yaya/misc.h
100644 blob ba579881e606c159351de55d395fd06d70fd8e77	app/src/main/jni/yaya/mt19937ar.cpp
100644 blob c59146d5168ac0044baeba27a89d538798a1db76	app/src/main/jni/yaya/mt19937ar.h
100644 blob f59c9148d4e4e594adfd5d75622c30053723845e	app/src/main/jni/yaya/parser0.cpp
100644 blob 2563e9923f58c4b550a744711c1af7d85aef00ab	app/src/main/jni/yaya/parser0.h
100644 blob 21d15e7b74b7b87b5a16a96102e5d3cbbda7739d	app/src/main/jni/yaya/parser1.cpp
100644 blob 04b7e4ca598a194501d10ab6ae98d83c7b4b7d4d	app/src/main/jni/yaya/parser1.h
100644 blob aad27c7ee85171f5501a455381d6747a4d10d62f	app/src/main/jni/yaya/posix_utils.cpp
100644 blob 285f53356206bd01ee110b8609e465ef14939a90	app/src/main/jni/yaya/posix_utils.h
100644 blob e730638ad2581f227f275adc8e19624a2eba778b	app/src/main/jni/yaya/readme-original.txt
100644 blob b653a27fa7e8cec0c79686dd0ceb116be39eb652	app/src/main/jni/yaya/readme.txt
100644 blob ff5678d66cc78ebbe45136488a5c411bc4e577d5	app/src/main/jni/yaya/resource.h
100644 blob f34e89ac2e595555a3c1a1221da217dc17ab0914	app/src/main/jni/yaya/selecter.cpp
100644 blob 89e45a69cce5afc3a5971e892ebe66e896a17e24	app/src/main/jni/yaya/selecter.h
100644 blob a3bea6aea4742d1f94a1e825f94167d78c3ab379	app/src/main/jni/yaya/sha1.c
100644 blob c5042e23282cbc54ac2a1c91121ffa5c7f6beb9e	app/src/main/jni/yaya/sha1.h
100644 blob 3cb92cdaa217f4a66fbe1cb378867d1194621a95	app/src/main/jni/yaya/stdafx.cpp
100644 blob 3ad0e060c5f95bf6c0c6a9f2fe8583a09c1cc52f	app/src/main/jni/yaya/stdafx.h
100644 blob 6e22a89308388b5fd3c3b067eb3763a281177c0b	app/src/main/jni/yaya/sysfunc.cpp
100644 blob e9dc0627ad22eb7204b50acbf00b6c387e86a46d	app/src/main/jni/yaya/sysfunc.h
100644 blob c2eea0f4fed929e1b73360219830fc7c11ed4989	app/src/main/jni/yaya/timer.h
100644 blob 4508907b35b684f8ca42f64b781deef1b94930c8	app/src/main/jni/yaya/value.cpp
100644 blob c1530e2b50ee6d1178ffff6f3761a40463d7688d	app/src/main/jni/yaya/value.h
100644 blob 05f4e817630293ea83c1ea91ef487d843c264659	app/src/main/jni/yaya/valuesub.cpp
100644 blob 1535e7fad5772b54578c0fe861c57fceb91ee2b5	app/src/main/jni/yaya/variable.cpp
100644 blob c56df4f51706609ea9280cb3312811ebb14d1bac	app/src/main/jni/yaya/variable.h
100644 blob de64e9ed5a86c7b9d42cfa8f09357a82fbe4c232	app/src/main/jni/yaya/wsex.cpp
100644 blob e6a4b6afb02835fc2ad774d92b474752f2edfda7	app/src/main/jni/yaya/wsex.h
100644 blob c3bdddbe5f667656263c407ad97ab8798fb58c61	app/src/main/jni/yaya/yaya_jni.cpp
100644 blob a3833bd013c2cb6cd25f6b07c5b57695142b059c	app/src/main/jni/yaya/yayad.py
100644 blob 6f3b4b54d7a0e77e3a932a2f7797b1b3b7106782	app/src/main/kotlin/com/cattailsw/nanidroid/MainActivity.kt
100644 blob e0303637eb2b4f24d07355c6067e174e575b3e46	app/src/main/kotlin/com/cattailsw/nanidroid/NanidroidApplication.kt
100644 blob f94dfb924469fbd98125def11e28c923d84a5009	app/src/main/kotlin/com/cattailsw/nanidroid/data/BootStateStore.kt
100644 blob 3f527b5a99da9b4cd5c211eb07b8e59e5168ffd9	app/src/main/kotlin/com/cattailsw/nanidroid/data/LastGhostStore.kt
100644 blob 421883e413d01224a48649f7f6145b9b92113e06	app/src/main/kotlin/com/cattailsw/nanidroid/data/PreferencesBootStateStore.kt
100644 blob b0c916ce1bb67c9d4314bb57766a619f831b0e27	app/src/main/kotlin/com/cattailsw/nanidroid/data/PreferencesLastGhostStore.kt
100644 blob 8fd096959066c2e607ba8ba51014eb8820a80c12	app/src/main/kotlin/com/cattailsw/nanidroid/engine/BuiltInShiori.kt
100644 blob f1ec63bed0b7d5e85bdfc7a11f989112b78c47cf	app/src/main/kotlin/com/cattailsw/nanidroid/engine/EngineSelector.kt
100644 blob 9795faa5ae40f5d508dc010aaa37ea68c24a34d1	app/src/main/kotlin/com/cattailsw/nanidroid/engine/NativeShioriHost.kt
100644 blob 4b02149ff9a1613d5b94654101f31cbc3b0fb174	app/src/main/kotlin/com/cattailsw/nanidroid/engine/NativeShutdownResult.kt
100644 blob 2caefd41a9adaff91e426d003fc63e65176bca77	app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriCodec.kt
100644 blob 346e4535345014f348c97856238ad6563488b66a	app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriEngine.kt
100644 blob a7284da30ce32b44abdcd02c7eae5fe94d7ad856	app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriEvent.kt
100644 blob e049ff757195e567d9c485472719d3bfa0b0ea87	app/src/main/kotlin/com/cattailsw/nanidroid/engine/ShioriReply.kt
100644 blob 9448df5c3d9e6629002d0bf58d8c28945ad45d24	app/src/main/kotlin/com/cattailsw/nanidroid/engine/UnsupportedShiori.kt
100644 blob 832e96060b12f61f2ba07906e0193b72f285c1d8	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/BundledGhost.kt
100644 blob 0a8a9528f81389197391fb65d2715bf5a3cff1f0	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/BundledGhostRepository.kt
100644 blob 3a8b03340eb2249b4ca49557128b87a58914731b	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/DescriptorReader.kt
100644 blob 698062964c6de6036f3e4a4578bcf97a514f2d2b	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/GhostDescriptor.kt
100644 blob 34c578096910fdb75849d4bc1947920fbbe78ba9	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/InstalledGhostRepository.kt
100644 blob a9535621ec8815bce5f9e73b1da10cc2e739b5a1	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/NativeProfileDirectory.kt
100644 blob 5891599ee930210edbb0e8c77df8d47f3e59dae3	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/ShellCatalog.kt
100644 blob 4c7dc10930bb33317773198e0f4ae827ffaab38e	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/ShellFiles.kt
100644 blob 444da6f6df114f6b9a9f0998dc765f8d53123a58	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceCollisionHitTest.kt
100644 blob ec25dc723b9db66dd71fe5f00c6ee0aae04871df	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceComposer.kt
100644 blob fa6e3e86a42273f75d01fe6963be8b6be861593d	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceDefinition.kt
100644 blob 778064874083430b47f4db5b7f69bfac18d6e42d	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceDefinitions.kt
100644 blob 74f1abebc227eee71a89093e906085d2ea0996ee	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceFileName.kt
100644 blob a166871fd32f57cfca59e635da2732a8558daf2e	app/src/main/kotlin/com/cattailsw/nanidroid/ghost/SurfaceImageLoader.kt
100644 blob bcd1e4f353d80b2604b4b1335a434c7f3fd5f571	app/src/main/kotlin/com/cattailsw/nanidroid/install/ArchivePath.kt
100644 blob 9dbaa08b8933ae29c425a2e8b6dbe5eafb87b5a4	app/src/main/kotlin/com/cattailsw/nanidroid/install/CentralDirectoryEntryLimit.kt
100644 blob 2d08e4c6997a3ecc66df9515dfec645d909264f3	app/src/main/kotlin/com/cattailsw/nanidroid/install/GhostImporter.kt
100644 blob b3bb799cebfc8f077dad3a651a498b469171d9d5	app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportAttemptStore.kt
100644 blob 541ad04436bbbbfe0a39531e31a04da04d12f513	app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportCoordinator.kt
100644 blob 6a45df3fd8484cf96ca7bb391d7ad62d149e2537	app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportModels.kt
100644 blob 90f880b5596bdbcf47edfcf3be7dea2b09889077	app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportStaging.kt
100644 blob fc7b3049631997d8e101580975d8066088916059	app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportState.kt
100644 blob 3896d867b42b42fee69c6c908bc70f879ea8d28c	app/src/main/kotlin/com/cattailsw/nanidroid/install/NarArchiveReader.kt
100644 blob 6a22839d8acaaf90f5566eedc56c23b0f5d456b9	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostRuntime.kt
100644 blob 9be7cc160f62aa3766a632a3af20c8bfab2d0205	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/InteractionToken.kt
100644 blob 3789bd59c721d7004849d16e9fe4289e5a716c93	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/NativePort.kt
100644 blob 03693fa03e6ecedfa7ef59e2e2d7f57618a4b102	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/PlaybackFrame.kt
100644 blob 3237eacec9ec09f03a5c51eec72166c5ce7d37fc	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/RenderedMoveGeometry.kt
100644 blob e36fb9247b6268dc4639825ed7036113e5f78778	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/ScriptInteraction.kt
100644 blob a17c17c42d4486bf708108c50e1c66f36d8a9127	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/ScriptPlayer.kt
100644 blob 52235a636146cc85c2c4dd5ad876e23cfaf71cde	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/SpeakerFrame.kt
100644 blob 82aaadc5083eb2adb5e589b54f95c216d23c9f44	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/StageState.kt
100644 blob 95b58038c92d112a1cc1eaf0ae1e7ee47639fe0c	app/src/main/kotlin/com/cattailsw/nanidroid/runtime/SurfaceAnimator.kt
100644 blob a2f3adbb83203226d3749543ad1786bb39bcdded	app/src/main/kotlin/com/cattailsw/nanidroid/shiori/Kawari.kt
100644 blob 6aff15eb1f4710a0ae56e67136ed4f8983fb1406	app/src/main/kotlin/com/cattailsw/nanidroid/shiori/SatoriShiori.kt
100644 blob 8efb0475a976fcd858630cef0519c69a6f3b63a5	app/src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt
100644 blob ec96895baec0cde698e8a8914d9f855679de5b64	app/src/main/kotlin/com/cattailsw/nanidroid/ui/AboutDialog.kt
100644 blob a551c92b1030b3ef88e09f7bffa6407314f915ba	app/src/main/kotlin/com/cattailsw/nanidroid/ui/AnimatedSurface.kt
100644 blob baf902f544aa3e397181f0389231511bfdb82811	app/src/main/kotlin/com/cattailsw/nanidroid/ui/BalloonContent.kt
100644 blob 738ab15e24af468e42ff483993e6c578a880fa0a	app/src/main/kotlin/com/cattailsw/nanidroid/ui/BalloonLinks.kt
100644 blob 24f387a20e17c379d77958f26cba1adfdd6dd3bc	app/src/main/kotlin/com/cattailsw/nanidroid/ui/GhostInputDialog.kt
100644 blob d59e4c97387afdce1365179aebc5f1ffaa08a18a	app/src/main/kotlin/com/cattailsw/nanidroid/ui/GhostStage.kt
100644 blob 725b2e1c46a4a02af45ea5cb350bc0cf92724122	app/src/main/kotlin/com/cattailsw/nanidroid/ui/StageGeometry.kt
100644 blob 67cdeeca689a27cc2d5d4ea30ac139f404a6aceb	app/src/main/kotlin/com/cattailsw/nanidroid/ui/StageViewModel.kt
100644 blob dd00d776971d0c38e0e752d828cdfe665c7b93cf	app/src/main/kotlin/com/cattailsw/nanidroid/ui/Theme.kt
100644 blob 07d5da9cbf141911847041df5d7b87f0dd5ef9d4	app/src/main/res/drawable/ic_launcher_background.xml
100644 blob 2b068d11462a4b96669193de13a711a3a36220a0	app/src/main/res/drawable/ic_launcher_foreground.xml
100644 blob 6f3b755bf50c6b03d8714a9c6184705e6a08389f	app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
100644 blob 6f3b755bf50c6b03d8714a9c6184705e6a08389f	app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
100644 blob c209e78ecd372343283f4157dcfd918ec5165bb3	app/src/main/res/mipmap-hdpi/ic_launcher.webp
100644 blob b2dfe3d1ba5cf3ee31b3ecc1ced89044a1f3b7a9	app/src/main/res/mipmap-hdpi/ic_launcher_round.webp
100644 blob 4f0f1d64e58ba64d180ce43ee13bf9a17835fbca	app/src/main/res/mipmap-mdpi/ic_launcher.webp
100644 blob 62b611da081676d42f6c3f78a2c91e7bcedddedb	app/src/main/res/mipmap-mdpi/ic_launcher_round.webp
100644 blob 948a3070fe34c611c42c0d3ad3013a0dce358be0	app/src/main/res/mipmap-xhdpi/ic_launcher.webp
100644 blob 1b9a6956b3acdc11f40ce2bb3f6efbd845cc243f	app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp
100644 blob 28d4b77f9f036a47549d47db79c16788749dca10	app/src/main/res/mipmap-xxhdpi/ic_launcher.webp
100644 blob 9287f5083623b375139afb391af71cc533a7dd37	app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp
100644 blob aa7d6427e6fa1074b79ccd52ef67ac15c5637e85	app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp
100644 blob 9126ae37cbc3587421d6889eadd1d91fbf1994d4	app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp
100644 blob e0ff90761156ee8d5dc7c19983bbf9c3c331c385	app/src/main/res/values/strings.xml
100644 blob 174123413db2c756da126a984d07d0d1df168448	app/src/main/res/values/themes.xml
100644 blob b853a8d2452fe340b2e07d1899f3437e49fb123b	app/src/main/res/xml/data_extraction_rules.xml
100644 blob 46a40dccc8b0cb6f8b2cf9fa3676e39d1970f2cb	app/src/test/java/com/cattailsw/nanidroid/data/PreferencesLastGhostStoreTest.kt
100644 blob 28738e82bf2dec7cdf450f53b89550570a4a80fe	app/src/test/java/com/cattailsw/nanidroid/engine/BuiltInShioriTest.kt
100644 blob 49abcbe7f15c87dd0e783c6b1e4793c7a94f3683	app/src/test/java/com/cattailsw/nanidroid/engine/EngineSelectorTest.kt
100644 blob d032400ea1f926d2be33aea550192983c235646c	app/src/test/java/com/cattailsw/nanidroid/engine/NativeShioriHostTest.kt
100644 blob fb8df801639b178346eda3f0cf28173f0808e4cf	app/src/test/java/com/cattailsw/nanidroid/engine/ShioriCodecTest.kt
100644 blob 0d6697a4775ce72a54068da4e830898e555fe832	app/src/test/java/com/cattailsw/nanidroid/engine/UnsupportedShioriTest.kt
100644 blob 488f234004cdd6e8f32e55573ac0b68cc7e2a777	app/src/test/java/com/cattailsw/nanidroid/ghost/BundledGhostRepositoryTest.kt
100644 blob e2373ec020355d2489d6dd7848bd892c757a036d	app/src/test/java/com/cattailsw/nanidroid/ghost/DescriptorReaderTest.kt
100644 blob d3aee4a137619afebaf99021a74fca684cfe3e20	app/src/test/java/com/cattailsw/nanidroid/ghost/InstalledGhostRepositoryTest.kt
100644 blob 2c9fa980bda51364e5061c3a175dd119cc55ad4b	app/src/test/java/com/cattailsw/nanidroid/ghost/NativeProfileDirectoryTest.kt
100644 blob 027d03fef9249b514b7d7c039f3c29b7b57d4f72	app/src/test/java/com/cattailsw/nanidroid/ghost/SurfaceCollisionHitTestTest.kt
100644 blob bc45f850175cede93f367f18c722486f1b848fb4	app/src/test/java/com/cattailsw/nanidroid/ghost/SurfaceDefinitionsTest.kt
100644 blob 6e61720c28f72d5ae80e9edf7930e382c7b6ead8	app/src/test/java/com/cattailsw/nanidroid/install/ArchivePathTest.kt
100644 blob 549bfc319ff89a2d329af60beca57238b5fa2377	app/src/test/java/com/cattailsw/nanidroid/install/GhostImporterTest.kt
100644 blob f38a4346b77d334c267ca3bcf10bfca106cd03bd	app/src/test/java/com/cattailsw/nanidroid/install/ImportCoordinatorTest.kt
100644 blob 6515315f8bb8df5ffcf6d952957ae7778746efbc	app/src/test/java/com/cattailsw/nanidroid/install/ImportStagingTest.kt
100644 blob 35578d6e34b7b61e0537ea42ab6f23c14bbbb33d	app/src/test/java/com/cattailsw/nanidroid/install/NarArchiveReaderTest.kt
100644 blob d7e8108d43c2f605fe2100e4dafd45b0b9324dcb	app/src/test/java/com/cattailsw/nanidroid/runtime/GhostImportEventsTest.kt
100644 blob 4ba6b5fda5d9c2295deeb6dc9eb1d65b7fa68250	app/src/test/java/com/cattailsw/nanidroid/runtime/GhostInteractionTest.kt
100644 blob 44d861f9d70349ce3897fba8b329e1eea07bb3e3	app/src/test/java/com/cattailsw/nanidroid/runtime/GhostRuntimeTest.kt
100644 blob b0789df7a0aecdf531ecd6c6695b792c114975c5	app/src/test/java/com/cattailsw/nanidroid/runtime/GhostSwitchTest.kt
100644 blob 4a4daf001c48618854122dda11f7c790ec0f4ec9	app/src/test/java/com/cattailsw/nanidroid/runtime/ScriptPlayerTest.kt
100644 blob 1fb5ad321ff8f3b00aa039396b907dc400de495f	app/src/test/java/com/cattailsw/nanidroid/runtime/SurfaceAnimatorTest.kt
100644 blob 9e0565613f395b0d36b23c0d3c512c933df7e1a5	app/src/test/java/com/cattailsw/nanidroid/ui/BalloonLinksTest.kt
100644 blob d871b2e77d7d8750d09d827a54d0a76a20f53ab1	app/src/test/java/com/cattailsw/nanidroid/ui/StageGeometryTest.kt
100644 blob c550999ab218875b09256e67b1eee1a8ffedd302	app/src/test/java/com/cattailsw/nanidroid/ui/StageViewModelTest.kt
100644 blob f01abd7497107a075e7e6955741b11165b8bf277	app/src/test/resources/nanidroid.zip
100644 blob bb92a46fa18cf09dbd09ec4971e72bb2d6f6d0c6	build.gradle.kts
100644 blob 74b3ef397505dc933f59b8f992b3cd6167627e2b	docs/extra-corpus-scan-evidence.md
100644 blob d843c87a78e34c8d5da6845747924824cc0b8ae5	docs/implementation-handoff.md
100644 blob 0f28a7584a8c25d48a8d22ef6c45d73754c013fd	docs/milestone-1-evidence.md
100644 blob 78392672f73c0c8787d255f93487e197c2e24eec	docs/milestone-2-evidence.md
100644 blob bb0e4ee14299d3bc676920eaa432f0c9380d6094	docs/milestone-3-evidence.md
100644 blob 9fcc8a72129268b93d7fc2634376de717cc3b49e	docs/milestone-4-evidence.md
100644 blob 3cf177cee689c49cedb00d80b94e897905ee750e	docs/milestone-5-evidence.md
100644 blob 310dc6eff731825bcb2eef815480bd705264aa85	docs/native-persistence-evidence.md
100644 blob 72e83dff630674f91d35df0e2a0071047582b59c	docs/review/2026-09-24-rendering-clock-review-fixes.md
100644 blob 8cfcdcc5c3444fe1793747d9ce1cbdc4498cd4ca	docs/review/2026-09-25-import-followup-triage.md
100644 blob 37fe7f8c35dd66a4ed38de28333f3c04bf577fac	docs/review/2026-09-26-milestone-4-corrective-evidence.md
100644 blob 7618b530c2c2b8de4a727576878bcacbbb34b408	docs/review/2026-09-27-aya5-device-probe.md
100644 blob ab451b59d56fc0ca080a7bac5a4dbf5abd32d58f	docs/review/2026-09-27-gesture-coordinate-evidence.md
100644 blob 3190d93918062552e8c4806e1c0d4b250d792b56	docs/scaffold-verification.md
100644 blob fa74e5c1d60e8852f295642f4f69f6520d4df5d2	docs/superpowers/plans/2026-09-22-walking-skeleton.md
100644 blob e1767a5c5e52b845301d16158295c4579036d9aa	docs/superpowers/plans/2026-09-23-native-engines.md
100644 blob c7932eaf7ddecef8ac71e279e641df04638059c0	docs/superpowers/plans/2026-09-23-native-state-persistence.md
100644 blob 831c69ba6d3cb3a58717b9574e59d2592f3d54cb	docs/superpowers/plans/2026-09-25-milestone-4-surfaces-interactions.md
100644 blob e3890ddc59380b3eb9e0bf4bdd0150e903cb901f	docs/superpowers/plans/2026-09-27-milestone-5-corpus-polish.md
100644 blob cd1e1874b382d6b4fcc471818d8f44668f0d6813	docs/superpowers/plans/2026-09-29-milestone-5-real-ghost-ux-acceptance.md
100644 blob 1373c2ac69482df05c8b18c5a59fe79dcb037832	docs/superpowers/specs/2026-09-22-blind-recreation-design.md
100644 blob ff75fa6831a6c1e4ea13c169eec4d8016b80ce99	docs/superpowers/specs/2026-09-22-nanidroid-baseline-spec.md
100644 blob f07f1fd8222868e45845e5ddce5a4f6a527d7842	docs/superpowers/specs/2026-09-22-native-copy-manifest.json
100644 blob 85a8e2ffd7546e54c3e120d19e4cacb40c88e75d	docs/superpowers/specs/2026-09-23-native-state-persistence-design.md
100644 blob f20eea0cee8fa715b98864ae71570862732a53a2	docs/testing.md
100644 blob 54591f15fe0b2831d69d54457ef731ca50bf64e0	docs/testing/2026-09-30-m5-acceptance-reconciliation.md
100644 blob 0c3f76374edef231c07a3834a4b311900a5a23ec	docs/testing/2026-10-01-m5-focused-acceptance-packet.md
100644 blob 76892eaa06174b67ec23e80e8516731ef5c4aa23	docs/testing/earthquake-rendering-and-cadence.md
100644 blob a01d38304f0409351536b741b12706190dfd7e36	docs/testing/extra-corpus-manifest.json
100644 blob c6417dc3f77154af54b2c375ff2214253a80d024	docs/testing/lobo-rendering-reproduction.md
100644 blob 26a05a032c752ab29409b53187b81f2dbff06c30	docs/testing/milestone-5-corpus-inventory.md
100644 blob 4ea994d21ac4c4d5f4483f8cf6ae03072375b442	docs/testing/milestone-5-corpus.json
100644 blob 4eb9c91c32c6935d4a70bce163b655500995d164	docs/testing/milestone-5-ux-scenarios.md
100644 blob 776bcebf18e3689280c2f039fdfb75c34cae05ce	docs/testing/native-persistence-fixtures.md
100644 blob 80ed23562c82f27d4a65690b1bd2f81a69685b3b	docs/testing/notice-inventory.md
100644 blob 32d72a9399f3352f538bb845a29873d9edc42560	gradle.properties
100644 blob 76228548b0d9d0ac4c01bbe8b30be16eeddd8b39	gradle/libs.versions.toml
100644 blob f6b961fd5a86aa5fbfe90f707c3138408be7c718	gradle/wrapper/gradle-wrapper.jar
100644 blob c16a8a4a05e519a92f6e938e67c8b430f4ca2bd0	gradle/wrapper/gradle-wrapper.properties
100644 blob cccdd3d517fc5249beaefa600691cf150f2fa3e6	gradlew
100644 blob f9553162f122c71b34635112e717c3e733b5b212	gradlew.bat
100644 blob b89443dfe76ce5bd2d5bd72af15160a29ad194a0	inputs.json
100644 blob 8b3142137b4741344153fb3636a20f6238201c9c	settings.gradle.kts
100644 blob db7990810181a5db2aec18086b59b7e58484c1f0	tools/test-import-process-death.ps1
100644 blob 409ea15a236c7cd4921a91f9839eb502fad88cc8	tools/test-milestone5-corpus.ps1
100644 blob a95ed3f552739b2bfc1381c6fae575ea09ce3afa	tools/test-native-persistence.ps1
```
