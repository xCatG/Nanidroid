# Rendering and clock review fixes — for independent verification

Date: 2026-09-24. Reviewed range: `465170b..70c47c3`. Fix commits: `4e07e35..4d7e6de`.

A `/code-review` pass and a `/simplify` pass over the always-layer rendering and clock work produced the changes below. Each finding was checked against the code before it was fixed; the review itself was single-pass and unverified. Please verify each item independently. Where a fix is a judgement call, the question to answer is stated.

## Commits

| Commit | Kind | Change |
| --- | --- | --- |
| `4e07e35` | cleanup | One `ShellFiles.isDirectChild` replaces three copies of the "regular, non-linked file directly in a non-linked shell" check (composer `safeFile`/`requireSafeBase`, stage `surfaces.txt`). The 3-argument `compose` hands empty layer lists to the 4-argument overload, so the virtual-key check exists once. The ticker's `lastClockAt` always equalled the previous `lastTickAt` and was removed. No behavior change intended. |
| `14a6f70` | fix 1, part of 5 | Single-frame `always` patterns are kept whatever their wait; waits may be a number or SSP `min-max` range; a non-numeric wait is still rejected. A UTF-8 BOM in `surfaces.txt` is stripped and makes UTF-8 the fallback charset. |
| `585b6d2` | fix 2 | `interpolate` draws the existing image over the layer (Porter-Duff destination-over), instead of drawing the layer over it with alpha scaled by base transparency. |
| `ea297a4` | fixes 3, 4 | Resumed Active clock time accumulates on `Session` instead of a ticker-local counter, so pause/resume and cancelled switches no longer restart the minute. Timer and touch request failures keep an open switch prompt. |
| `4d7e6de` | fix 5 | The stage finds `surfaces.txt` case-insensitively (exact name preferred), and the lookup and containment check run inside the fallback `try`. |

## Items to verify

1. **Single-frame waits (`SurfaceDefinitions.kt`).** The earlier design deliberately limited static flattening to wait `0` (the old test `skipsMultiFrameAndNonzeroWaitAnimationsWithoutSuppressingOtherIds` asserted it). UKADOC says the wait is the delay *until* the frame is drawn; a single `always` frame therefore appears after its wait and then redraws itself unchanged. The fix treats it as static from the start. **Question:** is showing the part immediately (instead of after the first wait) acceptable for this static subset? Check the Earthquake Duo fixture for any single-frame `always` pattern with a nonzero wait that should not be visible at rest.

2. **`interpolate` semantics (`AlwaysSurfaceComposer.kt`).** UKADOC: "ベースレイヤの透明度に応じて新規レイヤを重ねる". Destination-over was chosen because it keeps the base at full weight and adds the layer only through base transparency; the old formula also reduced the base's weight and left a semi-transparent halo (alpha 191 instead of 255 for an opaque layer under a 50% base pixel). Fully opaque and fully transparent base pixels give the same result both ways; only partial alpha changes. **Verify on device:** Mantle's 1501 `interpolate` layer in Earthquake Duo shows no halo and no visible regression versus the "after" screenshot in `docs/testing/earthquake-rendering-and-cadence.md`. `EarthquakeAlwaysLayersTest` was **skipped** in this run because its fixture was not staged.

3. **Minute clock (`GhostRuntime.kt`).** Baseline spec: second/minute events fire "each second / minute while resumed", and only in Active. The counter now lives on the session and only Active resumed time is added. Time spent paused, loading, or in a switch (including a cancelled one) is not counted. A new session starts at zero. **Check:** that this is the intended reading, rather than wall-clock minute boundaries; and that the first Active tick after a new session or a resume adds at most one tick interval. New test: `pausesDelayButDoNotRestartTheMinute` (two 40 s resumed spans separated by 30 s pauses give 80 seconds and one minute event).

4. **Switch prompt on request errors.** Timer failures (`dispatchClock`) and touch failures (`pointerEvent`) now copy the prompt into the new Active state. The invalid-selection paths in `selectGhost` still clear a previous prompt, which looks intended because a new selection replaces it. **Check** the remaining `SessionState.Active(...)` constructions for other paths that should keep the prompt. New test: `failedTouchAndTimerRequestsKeepOpenSwitchPrompt`.

5. **`surfaces.txt` lookup (`GhostStage.kt`).** An exact-name file wins; otherwise the first case-insensitive match from `listFiles()` is used, then the shared containment check. `IOException`/`SecurityException` from the lookup now fall back to base images instead of failing the stage. New tests: `readsUtf8FileWithByteOrderMark`, `stageReadsDefinitionsWhateverTheirNameCase`.

## Not fixed (need a decision)

- **Other `surfaces*.txt` files** (for example `surfaces2.txt`) are still ignored. Merging needs defined override rules between files. Decide whether the fixtures require it.
- **Eager composition and memory.** `loadSurfaces` still composes every surface in the shell before anything is shown, one after another. The loader also caches every base bitmap while the stage map holds the composites. Composing on demand for the displayed ids, or caching composites instead of bases, would cut both start-up time and memory. The handoff doc already declined a composite cache without measurement. Measure Earthquake Duo load time and heap first.
- **Shared layers are redone per base.** One layer used by many surfaces is re-checked and has its pixels copied once for each surface. This only matters if the eager-composition item stays.
- **Charset detection** in `SurfaceDefinitions.read` still differs from `DescriptorReader`. The defaults are Shift_JIS vs windows-31j, and `SurfaceDefinitions` searches every line for the charset declaration where `DescriptorReader` checks only the first. Unifying them would change descriptor behavior on invalid charset names, so it was left alone.
- **PNA mask containment** in `CachedSurfaceImageLoader` still uses its own check. It throws instead of skipping and doesn't check the shell folder itself, so switching it to `ShellFiles` would change behavior.

## Verification run

- `./gradlew testDebugUnitTest`: 131 tests, 2 skipped, 0 failures.
- `connectedDebugAndroidTest` on the API 37 x86_64 emulator, limited to `AlwaysSurfaceComposerTest`, `EarthquakeAlwaysLayersTest`, `GhostStageAlwaysLayersTest`, `GhostStageTest` and `SurfaceImageLoaderTest`: 30 tests, 0 failures, 1 skipped (the unstaged Earthquake fixture).
- The full connected suite and the native persistence gate (`tools/test-native-persistence.ps1`) were **not** re-run. The clock change affects when LOBO's OnMinuteChange save runs, so re-run that gate as well.
