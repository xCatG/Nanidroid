# Milestone 5 focused acceptance packet — 2026-10-01

## Author acceptance — 2026-10-02

**Milestone 5 is ACCEPTED with explicit exceptions.** The author accepted the two remaining unknowns: (1) the missing LOBO Pixel setting-change/reload cycles, because the original unset rate has no proven authored restoration route; and (2) the unresolved intermittent Compose wrong-thread and keyboard-visibility failures. These are accepted evidence gaps/risks, not passing tests, diagnosed causes, or fixes.

Existing approved exclusions and unsampled branches retain their recorded scope. Earlier OPEN statements below describe historical checkpoints and are superseded by this acceptance. Device restoration is complete as recorded in the focused packet. No additional M5 validation is required for these accepted exceptions. This decision does not authorize merge, release, or the next milestone; monitoring remains paused.


**Status: M5 OPEN.** This packet adds two one-shot API 37 emulator journeys and one corrected continuous Pixel input capture to the [2026-09-30 reconciliation](2026-09-30-m5-acceptance-reconciliation.md). It does not replace earlier cohort provenance or assert a blanket final gate.

## Covered in the dated follow-ups

| Route | Evidence and exact scope |
| --- | --- |
| G02 Earthquake/YAYA | One `authoredEightFamilyJourney` on E37 x86_64 at portrait 360×640 dp/font 2: **1/1**, 43.509 JUnit seconds. Original NAR bytes matched SHA-256 `06db71e7…`; the fixture gate matched 111 installed original files. Monotonic native event counts, same lease across rotation, one status-200 `OnAiTalk` tied to visible text, changed rate after reload and close passed. |
| I01 2elf/Satori | One `satoriAuthoredInputWithImeAndRotation` on E37 x86_64 at landscape 640×360 dp/font 2, IME Done: **1/1**, 27.476 JUnit seconds. Original NAR bytes matched SHA-256 `a50830e1…`; the gate matched 184 installed original files. Draft/IME/rotation and same-lease checks passed; exactly one native-bound `[ユー名, I01Oct01A]` reply had status 200 and the later runtime frame contained `I01Oct01Aさん`. The reply PNG shows the name prefix, not the complete honorific sentence. |
| Pixel 2elf input | On unchanged Pixel 7 API 37 arm64 app APK, one corrected **74.961733-second continuous video** shows exact `Pixel1001V` draft in landscape (frame 03 s) and return portrait (07 s), one OK, one `さん`, then both complete named replies (24 s). The first main attempt had an empty draft from host IME timing; an intermediate restoration input also raced IME readiness. Both are retained host errors, not established product failures. The video does not prove keyboard focus stayed continuous across rotations. This closes the previously split **visible** rotation→submission→reply attribution, using injected touches. |
| Earlier Pixel setting cohorts | Three complete 2elf flag cycles and three complete Earthquake rate cycles with changed fresh-load and restored readbacks remain closed under their dated evidence. The continuous input capture neither repeats nor reopens them. |

Current E37 app/test APK SHA-256: `b7cd273716698fe4397aa0a6f690049b1f3d1076a0ef62ebe5c49364a8d43fd8` / `226ca6b855d9d418f99a297ac2c8948c16bcf7b2cfc9168393aef07297c769cb`. Direct explicit-serial instrumentation produced raw JUnit/logs, not Gradle XML. Pixel retained app SHA-256 `618579aef7c00d16d2e9677f2722567058e73cc6b74d41cd8374c692f4ce3d47`; no Pixel install or test runner occurred. [E37 raw summary](../../app/build/evidence/focused-authored-2026-10-01/summary.md) and [Pixel raw summary](../../app/build/evidence/pixel-input-continuous-2026-10-01/summary.md) retain arguments, hashes, captures and first failures.

## Remaining evidence and risk

- **Diagnostic limit, not a new user blocker:** Pixel native gesture request/status/lease attribution remains absent. E37 same-runtime lease and native-bound reply checks do not transfer to the Pixel process. The Pixel video proves visible continuity, not native reference decoding or lease continuity; this does not reopen the completed Pixel 2elf/Earthquake setting cycles.
- **LOBO:** Three Pixel fresh-load/menu/blank `Talkrate:  seconds` readbacks remain, but **0/3 setting cycles**. No authored return to the original unset value was proved, so no setter was selected. The authored `OnNotifyUserInfo` initialization supplies 300 seconds, which would change the original unset value; no proven UI path restores blank. Blank must not be inferred as 300 seconds or Never.
- **Unresolved intermittent risk:** Earlier G02/I01/G07 wrong-thread stacks and an IME visibility red remain retained. The source producer and production immunity were not proved; these one-shot passes do not localize or fix them.
- **Unsampled or approved limits:** G01 surface-152 dynamic action/readback and G06 pose-only rendered pixels remain unsampled random branches. A deterministic supported authored link remains an explicit gap. Spoken TalkBack traversal is author-deferred. LOBO `replace`, AYA5 authored scripts, audio and excluded SSP commands remain outside the approved milestone-one subset; retain the existing import, native and distribution limits.
- **Cohort boundaries:** Historical API 31 broad coverage, earlier-APK corpus/deep/persistence checks and the current focused E37/Pixel results retain separate source/APK provenance. No literal blanket connected suite or broad arm64 parity claim follows.

## Device restoration and time

E37 prior app/test APKs, 1080×2400/420 dpi/font 1.0/rotation, `bancho_jet` preference and its exact hash were restored. Earthquake's rate was restored through authored UI to `aitalkinterval=180`, `talktime="3 minutes"`. **Historical first cleanup failure:** its E37 saved user name remained `I01Oct01A`/`I01Oct01Aさん` instead of preflight `M5I01Done02`/`M5I01Done02さん`; gender stayed `789`. No save was overwritten. The worker reported stopping its emulator; its unsaved shutdown listing was transcribed in the raw summary.

Pixel restored all original `＄ユー*` values, including `＄ユー名=ユーザ`, `＄ユーザ名=旅人さん`, gender `789` and absent `ユーザ名リ`, through authored UI after native unload/final Back. Its 343-file inventory had **342 identical entries**; only 2elf `satori_savedata.txt` changed through normal metadata. Other ghosts, LOBO save, APK, preferences, display, font, rotation and keyboard matched entry.

A separate user-authorized [authored UI cleanup](../../app/build/evidence/e37-name-cleanup-2026-10-01/summary.md) on the prior E37 APK later restored `M5I01Done02` and `M5I01Done02さん`: exact focused draft preceded one OK, one さん yielded both full replies in XML (the later PNG has empty balloons), and after unload **all original `＄ユー*` lines**, gender `789` and absent `ユーザ名リ` matched the retained baseline. Normal boot/choice metadata changed; original APKs, display, bancho_jet selection/preference hash, Earthquake 3-minute rate and Bounds OFF were preserved. Its owned emulator was stopped; earlier failed restoration remains historical. M5 stays OPEN.

E37 baseline-to-postflight snapshot spans 07:49:10–08:51:25 UTC; two instrument command walls total 74.526 s. Pixel collection spans 01:54:46.6781330–02:16:14.4655043 PDT, **21m27.787s wall**. Neither wall window measures active labor. No acceptance, merge or release is claimed.
