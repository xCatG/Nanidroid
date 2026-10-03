# Extra corpus scan: gate assessment and evidence

Status: **blocked; 39 approved runtime rows not run**. Task 1a assessed the gates below on 2026-09-27/28 after Task 1 commit `4760e35`. Several required controls are absent or unverified. No private archive was unpacked, staged, installed, or executed for this scan.

## Lead restatement of issue #383 runtime prerequisites

Source: issue #383 runtime security gate comment, read by the lead. This transfers requirements only; implementers must not copy or read reference application/harness code.

- Inspect the actual test APK/merged manifest: no INTERNET, account access, telemetry or unrelated workers/receivers. Do not assume the recreation APK is safe because the reference APK differed.
- Host-side isolation denies Internet, LAN, DNS and emulator host alias 10.0.2.2. A trusted sentinel must demonstrate denied egress before and after each row; guest Wi-Fi/mobile toggles alone do not establish this.
- Dedicated non-root, SELinux-enforcing, secret-free disposable AVD; no accounts, shared folders, clipboard integration, host proxy, ADB forward/reverse/redirection or writable host mounts.
- Bounded ZIP central-directory preflight before general archive processing. Preserve existing importer limits and verify their applicability to the scan input path.
- Clean snapshot or demonstrably equivalent package/data reset per row; verify owned data absence after teardown.
- Enforce and report bounds for wall-clock time, CPU, RSS, disk, process count, compressed/expanded input, decoded images, SHIORI response, logs and screenshots. Do not claim current tooling enforces an unimplemented bound.
- Failed sentinel, transport loss, cleanup residue or unknown sandbox state stops/quarantines that device. Do not continue cleanup through a hung transport or reuse an uncertain device.

Assess available controls and retain evidence paths per gate before running the pilot. If any required control cannot be established within Task 1a's three active hours, record the gap and leave the scan blocked/unrun while continuing Task 2. Do not construct a large new orchestration system or bypass a control. Phase-two samples remain deferred while the issue's phase-one ordering requirements are unmet; M5 does not require completing the 132-row census.

## Inputs and results

Verified by lead: private root `C:/Users/yenchi/Nanidroid-private-nar-corpus`, original approved batch of 39 distinct NAR hashes matching filenames, 101,568,266 bytes, no overlap with the 22 recovered baseline NARs. This establishes availability only. Runtime rows: **39 not run**; the three phase-two rows are also deferred pending phase-one disposition. No pilot row was selected or executed, and there are no import, boot, render, dialogue, or close observations for this batch.

## Task 1a gate verdicts

These verdicts concern the current recreation APKs and Task 1 test path, not the reference application. The APKs inspected were the Task 1 binaries: app SHA-256 `7AF2E201EDCDC60D5DC70F24281D7561D2704FCB281C2AF52700CBDF82DE1561`; test SHA-256 `23DCC65883B99FEB234FDA040EA936A010C897C2EE06EB0FBE322832C12E0445`. The hash-provenance and prior emulator row evidence are in [Task 1 evidence](milestone-5-evidence.md). `aapt dump permissions` and the packaged/merged manifests were inspected locally; no new build or device test was run.

| Required gate | Verdict and local evidence |
| --- | --- |
| Actual APK permissions and components | **Not passed.** The APK permission dumps show no INTERNET or account permission: app has only its signature dynamic-receiver permission; test APK has `REORDER_TASKS`. Yet the [debug merged manifest](../app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml) contains the exported `androidx.profileinstaller.ProfileInstallReceiver` and startup provider, and the [packaged test manifest](../app/build/intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml) contains an exported test document provider and instrumentation activities. The profile receiver is unrelated to the private scan; a clean component audit has not passed. The debug app also has backup enabled. |
| Host network isolation and trusted egress sentinels | **Not established.** [Task 1 host script](../tools/test-milestone5-corpus.ps1) has no host-side Internet/LAN/DNS/`10.0.2.2` deny setup or trusted before/after-row egress sentinel. Task 1's synthetic import/hash/cleanup gates do not test egress. No sentinel result path exists for this scan. |
| Disposable AVD properties and host integration | **Not established.** Task 1 proved `emulator-5554` had QEMU, boot-complete, API 31, and x86_64 identity in [its evidence](milestone-5-evidence.md). It did not establish non-root, SELinux enforcing, no accounts/secrets, shared folders, clipboard, proxy, ADB forward/reverse/redirection, or writable mounts. An emulator serial alone does not satisfy this gate. |
| ZIP preflight and importer limits | **Implemented on the current importer path, but not validated on the private batch.** [NarArchiveReader](../app/src/main/kotlin/com/cattailsw/nanidroid/install/NarArchiveReader.kt) checks archive length and [central-directory entry count](../app/src/main/kotlin/com/cattailsw/nanidroid/install/CentralDirectoryEntryLimit.kt) before Commons Compress opens the ZIP. [ImportLimits](../app/src/main/kotlin/com/cattailsw/nanidroid/install/ImportModels.kt) bounds compressed archive, expanded total, file, descriptor and entry count. Task 1 calls the importer, but no original-39 row reached it. |
| Fresh state and teardown proof | **Not established.** The [Task 1 host script](../tools/test-milestone5-corpus.ps1) calls `pm clear` before a row, force-stops afterward, removes its `/data/local/tmp` copy, and checks emulator identity. It does not restore a clean snapshot or verify absence of app-owned data after teardown. A failed/uncertain transport still enters the cleanup attempts. |
| Resource and artifact bounds | **Partially implemented; gate not passed.** The script enforces a 180-second wall deadline and the importer limits compressed/expanded input. There are no enforced/reportable per-row CPU, RSS, disk, process-count, decoded-image, SHIORI-response, log-size, or screenshot-size limits. PNG signature validation is not a screenshot-size bound. |
| Stop and quarantine on uncertain sandbox | **Partial.** The script stops the row loop on recorded cleanup or emulator identity errors and retains logs; Task 1 exercised a simulated cleanup failure. It has no pre/post egress sentinel, and transport loss or unknown sandbox state cannot be certified as quarantined under the issue #383 requirement. |

**Disposition:** Required gates cannot be established from the current test environment without new isolation and resource controls. The bounded Task 1a scan therefore stops before its pilot. This optional diagnostic does not block Task 2 or other Milestone 5 acceptance work. Any resumed private runtime scan needs a separately reviewed gate implementation and budget; its rows remain unknown, not compatible or incompatible.

## Approved-batch identification

The user-authorized acquisition chat supplied the exact original 39 title/hash/source-page mapping. It is frozen in [the extra-corpus manifest](testing/extra-corpus-manifest.json): 36 phase-one and three phase-two rows. All 39 provenance URLs are **source pages**, including two GitHub release-tag pages; none is asserted to be an exact NAR asset URL. Local read-only verification found 39 unique title/hash pairs, each filename matching its SHA-256 content hash, all files present in the private root, totaling **101,568,266 bytes**. This verifies inputs only; no archive was opened or run. The current directory's later acquisitions were excluded by exact handed-off hash, never by directory order.

Each manifest row remains **not run**. Phase-two ordering is additionally deferred. Manifest order below identifies the row; its exact path, hash and source page are in the manifest.

| Row | Title | Phase | Runtime status |
| ---: | --- | --- | --- |
| 01 | 鬼見城荘の一號室 | 1 | not run |
| 02 | 鬼見城荘の四號室 | 1 | not run |
| 03 | 鬼見城荘の五號室 | 1 | not run |
| 04 | 鬼見城荘の八號室 | 1 | not run |
| 05 | 4月-プワソン＝ダヴリル | 1 | not run |
| 06 | 5月-メーデー＝メーデー・メーデー | 1 | not run |
| 07 | 6月-ジェーン＝ブライト | 1 | not run |
| 08 | choke-cherry-candy | 1 | not run |
| 09 | lost_and_found | 1 | not run |
| 10 | ミサネグチさま | 1 | not run |
| 11 | ユーザ好き！＝好きゴースト | 1 | not run |
| 12 | 晴れのちぽこぽん、ときどきこんこん | 1 | not run |
| 13 | ほしをみおろす | 1 | not run |
| 14 | イースターの月 | 1 | not run |
| 15 | もう息の仕方を忘れてしまった | 1 | not run |
| 16 | 勤め先の社長令息が亀になってしまいました | 1 | not run |
| 17 | 飼い主の理解が早い | 1 | not run |
| 18 | 廻り星に願いを | 1 | not run |
| 19 | Apple of my eye | 1 | not run |
| 20 | 絶対に契約しないでください。 | 1 | not run |
| 21 | チョコレイトボックス | 1 | not run |
| 22 | 魔女と聖女 | 1 | not run |
| 23 | 子犬のような恋だとしても | 1 | not run |
| 24 | 調子乗ってんじゃないわよ | 1 | not run |
| 25 | イヌネイター | 2 | not run; deferred |
| 26 | ふくふくアフターライフ（仮） | 1 | not run |
| 27 | ぐれすけいる | 1 | not run |
| 28 | 紺乃ちゃんと。 | 1 | not run |
| 29 | ニャッとウォーク | 1 | not run |
| 30 | ひとよひとよにひとみごろ | 1 | not run |
| 31 | マキヤマサン | 1 | not run |
| 32 | あくのそしき | 1 | not run |
| 33 | ちいさなニコ | 1 | not run |
| 34 | ゆーこちゃん | 1 | not run |
| 35 | 怠惰な佐野さん | 1 | not run |
| 36 | Emily/Phase4.5 汉化版 | 1 | not run |
| 37 | さくら 汉化版 | 1 | not run |
| 38 | 追想の窓辺 | 2 | not run; deferred |
| 39 | FLELE | 2 | not run; deferred |

## Acquisition growth notice (lead verification)

At its earlier 2026-09-27 verification, the acquisition task reported 47 additional files via issue #383 comment https://github.com/xCatG/Nanidroid/issues/383#issuecomment-5863836710 . The lead then rehashed **86 distinct NAR hashes**, all matching filenames, **400,704,416 bytes**. Cohorts were acquisition-task reported as 83 phase-one plus three phase-two; runtime compatibility remained untested. This was a dated directory snapshot, not the approved Task 1a batch or budget. Acquisition has since reported further growth, but those files remain backlog and were not included in the frozen 39 or run. An expanded runtime batch requires its own scope and estimate.
