# LOBO rendering observation (Task 1, 2026-09-23)

Task 5 ran LOBO persistence scenarios without changing rendering code. The observed authored surface fallback remains assigned to milestone four. **This LOBO observation is separate from the user's missing-rendered-part report, which concerns Earthquake Duo.** See [Earthquake rendering and cadence](earthquake-rendering-and-cadence.md) for that report, its identified missing parts and the focused fix.

## Reproduction and source evidence

The unchanged `LOBO_1.0.0.nar` was verified at SHA-256 `f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7`, extracted to `C:/tmp/nanidroid-task1-lobo-20260923`, and copied to the debug app's `files/ghost/lobo-task1`. The original NAR was untouched. Device: API 37 x86_64 `emulator-5554`; current debug APK SHA-256 `afecf82f34a695e43d342d5a091ed5281343c78a74faa7f2c4966f0bf16a2f7d`.

Exact UI sequence: restart the app to refresh its ghost list; tap **Ghosts** → **LOBO** → **Switch**; wait for 2elf's outgoing script to finish and LOBO to appear. Capture portrait. Run `adb shell cmd window user-rotation lock 1`, wait for the landscape orientation and capture. The original device rotation was `accelerometer_rotation=0`, `user_rotation=0`; rotation was returned to `lock 0` after the capture. No LOBO dictionary or image was edited.

| Capture | Local path outside repository | SHA-256 | Visible result |
| --- | --- | --- | --- |
| Portrait | `C:/tmp/nanidroid-task1-lobo-portrait2.png` | `7e75482013a336b4fee9735879764257125ba187063d628db2d115b4b471d93f` | Two grayscale wolf bodies at the bottom; dialogue appears. |
| Landscape | `C:/tmp/nanidroid-task1-lobo-landscape2.png` | `53d9b8cc0ba17a6274537f9898a7a155ca5d132bd883acc481d77c9528c122de` | Two grayscale wolf bodies again; app remains active. |

The authored `shell/master/surfaces.txt:13-39` defines **surface 0** as `element0,overlay,body1.png,0,0`, with talk-triggered and replacement animations referencing 100/101. Lines 43-46 define **surface 10** as `element0,overlay,menu_foreground.png,0,0`. The archive contains `body1.png`, `menu_foreground.png`, `surface100.png` and `surface101.png`, but **no** `surface0.png` or `surface10.png`. `shell/master/descript.txt:14-19` requests self alpha and free desktop alignment. The current `InstalledGhostRepository` collects only files named `surface<number>.png`; `GhostStage` falls back to the first/second numbered image when frame surface 0/10 is absent. This explains the visible fallback to wolf images from 100/101 instead of composing the authored base surface elements. The images themselves have transparent backgrounds in direct inspection; the screenshot does not establish an alpha defect.

## Classification and remaining detail

The observed fallback is an **unfinished surface composition/animation capability in the approved baseline**, assigned to **milestone four**. Task 1 makes no product fix. A separate focused fix would be appropriate only if a specific currently supported direct PNG, alpha, or placement path is shown to regress. Any requested syntax beyond the approved baseline is a scope question.

The user's missing-part report was subsequently attributed to Earthquake Duo: its base-only image omitted Ridge's eyes, mouth, eyebrows and crossed arms and Mantle's side arms. The LOBO captures above establish only LOBO's distinct fallback; they do not reproduce or explain the Earthquake report. LOBO's authored element composition remains outside the focused Earthquake static `always` layer fix.

## Milestone 4 Task 7 follow-up (2026-09-26)

The earlier fallback is fixed within the approved authored-surface scope. A fresh, SHA-verified disposable extraction of the same unchanged LOBO NAR was staged on API 31 x86_64. `Milestone4CorpusSurfaceTest.loboElementOnlyZeroAndTenUseTheirAuthoredImages` passed: composed surface 0 matches `body1.png` at 483×393 and surface 10 matches `menu_foreground.png` at 60×50, both at origin 0,0. The manual `C:/work/src/nanidroid-recreation/app/build/task7-20260926/lobo-after-switch.png` (SHA-256 `1642E79C93640D34B97F52D19894C00F33801FE9A58B85E3FAEC86A5158F7061`) visibly shows the authored wolf and small foreground instead of the two fallback wolves. Focused raw output is `app/build/task7-20260926/corpus-surface-focused.log`; the 8 s `lobo-stage-8s.mp4` (SHA-256 `61F0373EA2255A21B1581248154217B91CD255DD757F4EC1FE69DE0365311EB3`) shows dialogue progress.

The unchanged LOBO `surfaces.txt` uses `alternativestart` for talk tracks whose frames use `replace`, outside the approved `overlay`/`overlayfast`/`base`/`move` method set. Neither the static bitmap test nor the recording proves LOBO image animation. This unsupported native syntax remains a compatibility limit; no LOBO dictionary or image was modified.
