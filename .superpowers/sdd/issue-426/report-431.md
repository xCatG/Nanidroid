# Issue 431: exact extracted payload bytes

Workspace: `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`.
Loaded instructions: workspace `AGENTS.md`, issue-431 spec and the 2026-10-07 issue-426 implementation plan.
Base: `7722b667216adc07eaff38796a5698d8ae5c80cd` (accepted issues 427–430).
Branch: `codex/431-exact-extracted-payload-bytes`.

## Change and oracle

Changed only `app/src/test/java/com/cattailsw/nanidroid/install/NarArchiveReaderTest.kt` and this public-safe report. Renamed the case to `extractionPreservesExactPayloadBytesAfterInspection`. Its independently specified 100,000-byte fixture mixes offset bits so adjacent bytes and successive 8 KiB chunks differ; explicit `0, 127, -128, -1` bytes cross offsets 8190–8193. JUnit 4 `assertArrayEquals` compares the fixture descriptor and full payload against their extracted files. Expectations come from archive inputs, never the reader. Full comparison detects changed, zeroed, truncated or swapped output. No extra entry is needed because descriptor and payload already have different bytes and lengths.

Removed the callback upper bound and the unsupported single-pass claim. The changed case uses JUnit's `TemporaryFolder` rule for its output, including failure cleanup. This is framework-owned lifecycle cleanup; no separate filesystem cleanup probe was run. Other fixture lifecycles and all existing cancellation, CRC, containment and byte-limit methods remain unchanged. The shared archive helper still uses its existing delete-on-exit lifecycle. No production/native/dependency/product changes.

## Verification

Toolchain: Zulu JDK `17.0.20.1+1-LTS`; pinned Gradle wrapper `9.3.1`; Android SDK `C:/Users/yenchi/AppData/Local/Android/Sdk`. Each build set `JAVA_HOME=C:/Program Files/Zulu/zulu-17`, `ANDROID_HOME` and `ANDROID_SDK_ROOT` to that SDK. Pinned NDK/CMake configuration was unchanged; native compilation was not selected.

Command: `.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.NarArchiveReaderTest' --offline --console=plain`.

| Run | Selected | Executed | Passed | Skipped | Failed | Incomplete | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Initial full class | 33 | 33 | 33 | 0 | 0 | 0 | 0 |
| Controlled one-byte mutation | 33 | 33 | 32 | 0 | 1 | 0 | 1 |
| Restored ordinary command | 33 | 0 (cached) | 33 cached results | 0 | 0 | 0 | 0 |
| Restored command with `--rerun-tasks` | 33 | 33 | 33 | 0 | 0 | 0 | 0 |
| Import-order cleanup verification | 33 | 0 (up-to-date) | 33 retained results | 0 | 0 | 0 | 0 |

First red was the deliberate mutation: after extraction, flip bit 0 of `large.bin` byte 8192 and rewrite the same 100,000-byte output. Only the renamed test failed, with `ArrayComparisonFailure: arrays first differed at element [8192]; expected:<-128> but was:<-127>`. The mutation was then removed. The restored ordinary build reused cached results, so the final command added `--rerun-tasks` and executed all 33 tests. No production change was required by this test-only oracle task. Two existing nullable-File compiler warnings in `BundledGhostRepositoryTest.kt` lines 91/104 appeared during the forced build; they did not fail compilation or the selected tests.

Ignored local evidence under `.superpowers/sdd/issue-426/431-evidence/`: `initial-green.log`, `mutation-red.log`, `mutation-red.xml`, `restored-green.log`, `restored-green.xml`, `final-executed-green.log`, `final-executed-green.xml`, `post-format-green.log`, `post-format-green.xml`. The last run recompiled after import ordering cleanup and retained the forced run's test results as up-to-date. Raw logs/XML remain outside Git. Self-review confirmed the scoped diff and independent expected bytes; controller owns independent review and any publication.

Remaining limits: this evidence covers the full archive-reader JVM class only. No device, private corpus, throughput/read-count measurement or M5 exception verification was performed or claimed. No push, PR, merge, signing or device installation performed.
