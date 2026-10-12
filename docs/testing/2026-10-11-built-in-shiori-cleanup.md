# Built-in SHIORI cleanup acceptance

This current record supplements, without rewriting, the previous
[default assistant acceptance](2026-10-11-default-ghost-assistant.md).
The archive-owned dialogue and generic host commands passed the local gate and
focused API 31 checks below. Independent whole-branch review and PR publication
are owned by the controller and are recorded separately after this device work.

## Source, device and artifact provenance

Workspace: `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`, branch
`codex/default-ghost-assistant`. Production source at verification was
`3060d8258e491c1a00d1b831408af99545fe829d`; Task 3 changes only instrumentation,
inventory and documentation. The compiled UI source SHA-256 was
`f93c07e391cfa689b7612608da42c6b87f8d5c93a6546f58523ef2e071ddead2`.
The exact per-file input hashes are retained in `source-file-hashes.txt` below;
the APK hashes identify the actual installed builds. The controller's pending
plan edit was preserved and excluded from this worker's commit.

Only explicitly selected `emulator-5580`, AVD `CodexShioriCleanup`, was used:
API **31**, ABI **x86_64**, **1080×2400**, **420 dpi**. The identity is retained
in `app/build/built-in-shiori-cleanup/emulator/identity.json`. Pixel 7 was not
installed, configured or otherwise used. The controller owns the emulator;
it remains running for final review.

JDK: `C:/Program Files/Zulu/zulu-17`; SDK:
`C:/Users/yenchi/AppData/Local/Android/Sdk`. Existing wrapper/catalog/NDK/CMake
configuration was retained, and Gradle ran offline. Raw outputs, APKs, fixtures
and PNGs remain ignored under `app/build/built-in-shiori-cleanup/task3/`.

| Artifact | SHA-256 |
| --- | --- |
| Debug app APK | `9836961c7a1999c24da69783ced3dffed06aef294f284fb984a10abf8c6f4527` |
| Debug instrumentation APK | `df1ea1901bbb650d7ccc12cec25e30ce4e9509f9cc572c4fbce7621da1ec8799` |
| Unsigned release APK | `e85b7ccb774bb07d978ba90e2214c3bb7ddb7b86f51f53cb0da1416fced80c36` |
| Production and test-resource bundled ZIP | `7ad4109c5362d3f068b1acf1ab642541b9dd8d2c5d9d95e5e7f2c9bf4eb9f46c` |
| Original surface0000.png | `5c1c95e3f0c7de16d279c92d8ea5de01e4a80771bb270226588c8d1bd4316c9f` |
| Original surface0010.png | `f2358bab6b34ac088ff6db3f73e39b9c7143ad627f7015a6efd49aaae7d56d53` |

ZIP equality, its production pin, installed dictionary paths, and both original
PNG hashes were checked. Task 2 compared every archive entry with the prior ZIP:
only English/Japanese dictionary bytes changed and zh-TW/content.txt was added.
The production search for BundledAssistantContent, BundledAssistantShiori,
BuiltInAppAction, PresentedAppAction, assistantLabels and appAction had no matches
(`removed-production-references.txt`, rg exit 1).

## Local gate and strict inventory

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 1m 6s`: **136** tasks, **34 executed**,
**4 from cache**, **98 up-to-date**. `testDebugUnitTest` executed, rather than
using an up-to-date or cached unit result. Aggregate XML: **28 suites, 440 cases,
435 passes, zero failures/errors, five skips**. Raw gate: `final-gate.log`;
counts and identities: `unit-summary.json`. Existing Windows symlink privilege
skips remain visible:

- BundledGhostRepositoryTest#managedFileAndEscapedParentLinksAreRejected
- InstalledGhostRepositoryTest#symlinkCandidatesCannotEscapePrivateRoot
- NativeProfileDirectoryTest#rejectsEscapingProfileSymlink
- ImportStagingTest#linkedRootAndLinkedAttemptAreRejectedWithoutTraversal
- ImportStagingTest#linkedChildInsideAttemptBlocksRecoveryWithoutDeletingOutside

The new Android instrumentation executes the specific bundled managed-file and
managed-parent symlink assertions on the real repository with `Os.symlink`.
Both rejection paths passed, and the linked target bytes remained unchanged.
This closes the bundled-refresh platform gap; it neither converts Windows skips
into passes nor claims coverage of the other four skipped link tests.

Lint debug: **zero errors, 26 warnings, one hint**, with no locations in the new
UI test. Existing GhostRuntime nullable-File warnings appeared during release
Kotlin compilation. Assembly is not warning-free evidence or release readiness.
Lint reports remain under `app/build/reports/lint-results-debug.{xml,html}`;
unit XML/HTML are under the normal `app/build/test-results` and `reports/tests`.

Strict `tools/test-device-suite.ps1 -ListOnly -Suite self-contained` selected
**134** identities. `-SelfCheck` passed against **180** total methods: 134
self-contained, 29 host-orchestrated, 16 corpus-fixture, one diagnostic-device.
The first child Windows PowerShell attempt failed its script authorization check;
the recorded successful invocation used `pwsh -NoProfile -ExecutionPolicy Bypass`.
The complete self-contained device suite was not run.

## Focused Android gate and preserved first failures

The nine existing BundledAssistantUiTest identities were preserved; eight were
added and strictly inventoried. `final-selectors.txt` contains the exact **17**
`class#method` selectors passed together to explicit-serial `adb shell am instrument
-w -r -e class ...`. Final evidence is `ui-final-verified.log` and
`ui-final-verified-summary.json`: **17 selected, 17 unique observed terminal
identities, 17 passes, zero failures/skips/incomplete**, `OK (17 tests)`, one
successful `INSTRUMENTATION_CODE: -1`. The checked-in strict transcript parser
was extracted unchanged for the focused selection; adb exit alone was not used
as the pass criterion.

Coverage includes both tap menus/topics, Ghosts/About/developer dialogs, common
commands from an ordinary ghost with developer denied, Ghosts→About FIFO,
repeated About delivery twice, recomposition/exactly-once behavior, inactive
stage claims, modal Back, rotation, old-session dismissal on switch, startup
locale/resource selection and Android managed path links.
The input case emits About then inputbox in one real player frame: About remains
unclaimed while input is visible, then appears after Cancel resolves that same
dialogue generation. It does not weaken explicit replacement invalidation or
claim survival across an input reply that replaces the dialogue.

Failures were retained and corrected against specific causes:

1. `compile.log`: the new cleanup used unavailable `Os.unlink`; changed to
   `Files.delete` of the exact owned link. `compile-fixed.log` succeeded.
2. `ui-first.log`: 17 cases, 15 passes and two Japanese/zh-TW debug-label failures.
   Application Resources configuration alone did not override the Activity's
   emulator-English resources. `ui-locale-fixed.log` retained an intermediate
   fixture crash because `applyOverrideConfiguration` was called after resources
   had been accessed. The fixture now applies locale resources before MainActivity
   composition in `onActivityPreCreated`; `ui-locale-fixed2.log` passed only the
   two affected cases. No production locale code changed.
3. `ui-final.log`: all three screenshot cases failed after normal Android CLI
   layout inspection left `com.android.cli.interact.instrumentation` holding
   UiAutomation. The other 14 terminal method statuses were passes, but the strict
   result remained **3 failed/14 incomplete**, including the unsuccessful process
   terminal. The identified CLI inspection process was force-stopped only on
   emulator-5580. `ui-layout-conflict-fixed.log` then passed the affected three
   cases; the one corrected final 17-selector run above followed. These failed
   runs are not passing evidence or unexplained retries.

## Normal application factory and installed data

Separate manual checks launched the ordinary, unmodified NanidroidApplication
factory with explicit-serial `am start -W`. They used its real startup loading,
preferences, inventory and script timing, with no delegate replacement or clock
acceleration. `normal-first-layout.json` showed the initial **Welcome to
Nanidroid!**; `normal-repeat-layout.json` and `normal-repeat-prompt.json` showed
**Welcome back!** on subsequent cold launches. Activated/onboarding markers
remained true in the retained preferences.

Both actual character taps produced help/tools menus. From the authored tools,
Ghosts opened the installed picker; About displayed Version 0.3.0 and the Satori
notice; Debug tools displayed the Bounds switch and Close. Back dismissed About
and Debug tools without closing the stage; the focused UI test additionally
executes Ghosts Back. Complete license traversal, actual NAR
import and real native-ghost compatibility were outside these manual checks.

The actual `files/ghost/nanidroid` was seeded with exact recognized previous en/ja
bytes, an invocation-owned customized zh-TW table, saved-state sentinel and unknown
file. Normal process startup refreshed en from `b4621c...` to `618986...` and ja
from `3b1fbe...` to `2dae08...`; custom zh-TW stayed `c02ae6...`, both sentinels
stayed `cead1b...`, and both PNGs retained the full hashes above. See
`normal-upgrade-before.txt`/`normal-upgrade-after.txt` and `stage-upgrade.sh`.
An additional custom English table stayed authoritative after cold startup and
rendered **Customized English table remains authoritative** with unchanged hash
`5cb8410d5674d642214e6c35de9f38efd1d54fc9cbb6f82884f6f8a087ea81b9`.
The first short custom dialogue snapshot missed its ended text; an authored
10-second wait made the observation explicit (`normal-custom-held-layout.json`).

A unique ordinary installed fixture `cleanup_probe_task3` used authored synthetic
content and copies of the two original PNGs. Normal Ghosts/confirmation switched
to its Cleanup Sakura/Kero, then returned to Nanidroid and restored the authored
tools. The first return tap mistakenly reused the shorter fixture confirmation's
coordinates; the retained layout still showed the Nanidroid confirmation, and
the observed Switch coordinates corrected it. Only fixture files/sentinels were
removed individually, followed by their empty directories; the shipped tables
were restored from verified current archive bytes. Final inventory has only
`nanidroid`, and `normal-final-last-ghost-corrected.xml` records `nanidroid`.
The harmless fixture activation marker remains in this disposable app's store.

## Multilingual visual evidence and locale boundary

All nine final PNGs in `screenshots-final/` were captured on emulator-5580 through
Instrumentation UiAutomation and visually inspected:

| Startup locale | Help | Tools | Debug dialog |
| --- | --- | --- | --- |
| en-US | en-US-help.png | en-US-tools.png | en-US-debug.png |
| ja-JP | ja-JP-help.png | ja-JP-tools.png | ja-JP-debug.png |
| zh-TW | zh-TW-help.png | zh-TW-tools.png | zh-TW-debug.png |

Each menu's choices and debug title/Bounds label/Switch/Close are readable and
unclipped at the recorded viewport. Japanese stage controls wrap cleanly to two
rows; Japanese license-choice text wraps within its balloon. Original character
art and the speaker/balloon placement remain visible. `screenshot-hashes.json`
identifies the final bytes. Normal-factory `normal-help.png`, `normal-tools.png`
and `normal-debug.png` were also visually inspected separately.

Every multilingual image uses the existing real-runtime/MainActivity fixture:
the real bundled archive is installed in an invocation-owned cache tree, the
generic `BuiltInShiori.bundled` parses that selected table, and the actual runtime,
ViewModel and Compose UI run. The application runtime lazy delegate is replaced
only for this fixture, first activation is modeled as repeat boot, and the real
script clock is accelerated 20×. Locale/LocaleList and resource configuration are
set before core construction; Activity startup resources are set before Compose.
The locale callbacks, defaults, configuration and runtime delegate are restored
afterward; the emulator's global locale is unchanged. This is startup locale
rendering evidence, not a system Settings language-change journey.

Built-in dialogue is selected on process startup/restart; Android host dialog
labels use resources. The earlier PR request for live dialogue locale refresh is
outside this approved design, and is not claimed fixed. Imported ghosts retain
their authored language. The non-debug policy test models a cleared debuggable
flag on the debug APK; no release APK was installed, signed, published or run on
a physical device, and no signing keys were used.
