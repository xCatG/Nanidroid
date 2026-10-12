# Default ghost acceptance

[PR #442](https://github.com/xCatG/Nanidroid/pull/442), branch
`codex/default-ghost-assistant`; verified executable source `230902e2`.
[Design](../superpowers/specs/2026-10-11-built-in-shiori-cleanup-design.md).
This consolidates this PR's acceptance/review records. Earlier records remain in
Git; raw logs, fixtures, APKs and screenshots remain local under ignored
`app/build/built-in-shiori-cleanup/` and `app/build/default-ghost-assistant-acceptance/`.

## Final automated checks

Using JDK 17 and the pinned wrapper/SDK/NDK/CMake configuration:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain
```

At `230902e2`, the unit task executed: **451 cases, 446 passes, zero failures/errors,
five Windows symlink privilege skips**. Lint: **zero errors, 26 warnings, one hint**.
Debug, instrumentation and unsigned release APKs assembled successfully. Evidence:
`final-fix-round2/{gate.log,unit-summary.json,unit-xml,lint-summary.json,artifact-hashes.json}`.

The skipped Windows cases cover managed dictionary paths, installed-ghost candidates,
native profile paths, import roots and import children. The Android managed-file
and managed-parent symlink assertions passed; the other four Windows skips are
not relabeled as passes.

On explicit `emulator-5580`, AVD `CodexShioriCleanup`, API 31/x86_64,
1080×2400/420 dpi: **18 selected and uniquely observed UI cases, 18 passes,
zero failures/skips/incomplete**, 46.871 seconds. Strict inventory checks passed:
135 self-contained / 181 total methods. The full device suite was not run.
Selection, command and strict results are retained in
`final-fix-round2/{selectors.txt,ui-execution.json,ui.log,ui-summary.json}`.

Coverage includes tap/help topics; Ghosts/About/developer dialogs; ordinary-ghost
common commands and developer denial; FIFO/repeated commands; input-modal deferral;
Back, rotation, switching and inactive delivery; startup locales; Android managed
symlink rejection; and installed foreign-name → trusted-default return with literal
text and no developer request. The legitimate authored developer choice still works.
Metadata labels/references/input IDs and parser budget boundaries have unit coverage.

## Artifacts and visual evidence

| Final artifact | SHA-256 |
| --- | --- |
| Debug APK | `629396d7411c9ee48547b400f0a809806fbe972c20a9ad47d00d55d8b8f3677e` |
| Instrumentation APK | `6fd019943cce2acc8db13ae6cb6e9c0f00825c80fae1a35597420d9d18a1e5fd` |
| Unsigned release APK | `a6e2f39165dd38b9cfd0977d7a1cc81af7cc240e8f8ca8669e33572064291875` |
| Production and test bundled ZIP | `7ad4109c5362d3f068b1acf1ab642541b9dd8d2c5d9d95e5e7f2c9bf4eb9f46c` |

Both ZIPs match; original PNG bytes are preserved. Nine inspected help/tools/debug
screenshots for en-US, ja-JP and zh-TW are in `final-fix/screenshots/`, with hashes
in `final-fix/screenshot-hashes.json`. They belong to the preceding `8f0e779d` debug
APK (`4c0ea3e2392ad7fe8b12c9ea46be8ace4984c6282d0652360f80ce6e59421c26`), not the final
APK. Text and controls were readable without clipping or blank captures. Locale
fixtures use the real runtime/MainActivity with startup locale and accelerated
playback; they do not establish live language switching.

Earlier ordinary-factory checks in `task3/` verified welcome/taps/dialogs, recognized
en/ja upgrade, customized zh-TW and English authority after cold restart, preserved
PNG/state/unknown files, and return from a synthetic installed ghost. These retain
their earlier APK provenance; they were not repeated as a full final-APK journey.

## Review corrections and physical device

Independent Astra review confirmed archive ownership and found two corrected P2s:
foreign switch names could execute developer tags, and initial escaping altered
choice/input metadata. Each has a preserved failing regression and passing fix in
`final-fix/` or `final-fix-round2/`. Final scoped review at `230902e2` found no further
actionable defects. GitHub Codex review of `76067cb2` reported no major issues.

Fixture corrections also restore locale on setup failure and verify input token/
session identity across Cancel. Earlier locale/setup failures, a UiAutomation owner
conflict, rejected blank captures and an intermediate test compile error remain in
local evidence; they are not counted as passing runs.

On 2026-10-12, the author authorized installation on the connected Pixel 7. The
final debug APK above was installed as an update without clearing data; the process
was running with MainActivity resumed. The author subsequently tested it and
reported LGTM. This is a device smoke check, not the full instrumentation suite.
The disposable emulator was stopped; no release APK was installed.

Live dialogue locale reload, actual release-device behavior and private native
fixtures remain unverified. Release denial was modeled on the debug APK; unsigned
release assembly does not establish release readiness.
