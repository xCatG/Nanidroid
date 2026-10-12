# Built-in SHIORI cleanup acceptance

This current record supplements, without rewriting, the previous
[default assistant acceptance](2026-10-11-default-ghost-assistant.md).
The archive-owned dialogue and generic host commands passed the local gate and
focused API 31 checks below. Independent whole-branch review and PR publication
are owned by the controller and are recorded separately after this device work.

## Source, device and artifact provenance

Workspace: `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`, branch
`codex/default-ghost-assistant`. Initial production source at verification was
`3060d8258e491c1a00d1b831408af99545fe829d`; Task 3 changes only instrumentation,
inventory and documentation. The initial compiled UI source SHA-256 was
`f93c07e391cfa689b7612608da42c6b87f8d5c93a6546f58523ef2e071ddead2`.
The exact per-file input hashes are retained in `source-file-hashes.txt` below;
the APK hashes identify the actual installed builds. The controller's pending
plan edit was preserved and excluded from this worker's commit.
The review-round correction and latest executable provenance are recorded at the
end of this document; the initial results below remain historical evidence.

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

## Review round 1 corrections and latest verification

The bounded review identified two test-integrity defects: fixture setup could
leak locale/callback state if setup threw before its cleanup block, and the input
case did not prove cancellation preserved the dialogue generation. Both were
corrected in instrumentation only; production/APK dialogue policy did not change.

The outer cleanup boundary now begins before locale mutations, callback
registration, cache creation, install/load, fixture writes and scope/runtime
construction. It restores the prior runtime delegate, cancels any created scope,
deletes only an exclusively created cache root, unregisters callbacks, and restores
process/app locales. It also restores the locales of every Activity Resources
instance touched by the fixture, because those instances can be reused by a later
ordinary Activity. Input cancellation now asserts the queued command is About
while input is visible, then compares the complete dialogue token and its session
when About opens.

A narrow failure-path assertion runs inside the existing English locale identity,
so the strict inventory remains unchanged. It feeds an invalid archive into the
real repository, requires its actual hash-mismatch exception, compares saved
Locale/LocaleList/app configuration/delegate/cache identities, and launches an
ordinary Activity to detect a leaked locale callback or cached Activity resources.
This is a private fixture input, with no production seam or broader test framework.
`review-round1-red.log` failed before the cleanup fix with expected en_US/actual
ja_JP. The affected two cases then passed in `review-round1-green.log`.

Fresh visual QA subsequently rejected
`screenshots-review-round1/ja-JP-help.png`: it showed the stage without the menu
although that run's semantics and all 17 test identities passed. That image is
preserved. Capture now waits for completed real playback and Compose idleness,
then allows 100 ms for the display compositor before UiAutomation reads the screen.
The wait is confined to evidence capture. The next three-locale run additionally
exposed cached Activity resources retaining ja-JP at ordinary startup despite
restored process/app defaults (`review-round1-capture-fixed.log`, two passes/one
failure). After restoring all touched Activity resource locales, the affected
four cases passed together (`review-round1-activity-cleanup-fixed.log`). These
failures and intermediate gates remain retained, rather than being recast as passes.

The final combined command above passed in **18 seconds**, **136 tasks: 12
executed, 124 up-to-date** (`review-round1-final2-gate.log`). Unit execution was
**UP-TO-DATE**, with unchanged production/unit inputs and the previous executed
XML result: **440 cases, 435 passes, zero failures/errors, five Windows skips**.
Lint remained **zero errors, 26 warnings, one hint**. Debug/test and unsigned
release assembly succeeded. Strict ListOnly/SelfCheck passed again at **134/180**
identities (`review-round1-final-inventory.txt`, `review-round1-final-selfcheck.txt`).

Latest exact device evidence: `review-round1-ui-final2.log` and its strict
`review-round1-ui-final2-summary.json`: **17 selected/17 unique terminal identities,
17 passes, zero failures/skips/incomplete**, **46.16 seconds**, `OK (17 tests)`,
one successful final code -1. No Android CLI layout server was invoked during
these screenshot runs. All nine corresponding PNGs in
`screenshots-review-round1-final/` were regenerated and visually inspected
individually: complete readable help/tools choices and debug title/Bounds/Switch/
Close, with the same clean Japanese wrapping and original artwork as above.
`review-round1-final-screenshot-hashes.json` identifies their bytes.

| Latest revised executable input/artifact | SHA-256 |
| --- | --- |
| BundledAssistantUiTest.kt compiled source | `c5a0dd35559a368d7f435208ba1ac0c2ddbf182b05fe794b1e3a073ec992dc60` |
| Debug instrumentation APK | `a3dfd5acf96fe52386a2fb7932516f6153ed38d378634cf28e819acf0b538e47` |

The debug app and unsigned release APK hashes remain exactly those in the initial
table; production is unchanged. `review-round1-final-artifact-hashes.json` and
`review-round1-final-source-file-hashes.txt` record matching inputs for this final
executable. The normal-factory preservation evidence above therefore still applies
to the identical production APK. Emulator-5580 remains running for the controller;
Pixel 7 remains untouched. Independent re-review/whole-branch review and external
PR actions remain the controller's work.


## Final branch review correction — imported switch-name provenance

The final independent review found one P2: an installed descriptor name containing
`Probe\![open,developer]` became code when the trusted bundled default substituted
that name into its archived `OnGhostChanged` greeting. The receiving session then
admitted Developer. A new real-runtime regression reproduced that exact path before
the fix: `final-fix/red.log` failed because the expected null request was instead
`HostUiRequest(id=1, command=Developer)`. This is preserved red evidence.

The correction keeps reference/name substitutions as literal text through both
SakuraScript parsing and the later name-macro pass. A small generic text serializer
escapes backslashes and percent signs. Playback emits escaped pairs as ordinary
text with normal text timing, without rescanning the emitted characters as tags.
The name-macro pass preserves those pairs and serializes replacement data through
the same boundary. No ghost-specific dispatcher, reply action metadata, permission
change, or APK dialogue overlay was added. The archive still authors greetings,
menus and legitimate developer commands. Ordinary Unicode and dollar names retain
the same reply bytes; only names containing backslash/percent need escaped wire
bytes to preserve their displayed literal value and prevent tags/macro expansion.

The real runtime test switches from the already activated actual bundled engine
through installed foreign descriptors and back. It covers the exact developer tag,
doubled/trailing backslashes, control tags, Unicode/dollar and macro-looking names;
requires the literal full greeting with no host request, then claims the legitimate
archived developer choice. Engine tests also cover both lifecycle substitutions,
input/choice/surface/close tags and literal control characters. Parser tests cover
normal timing and source-budget boundaries. `green.log` and `expanded-green.log`
pass those focused regressions. The one new inventoried API31 UI identity,
`importedSwitchNameStaysLiteralOnTrustedDefaultReturn`, verifies literal greeting
playback, absence of the developer dialog after return, and the real authored
developer tools choice still opening its dialog.

The final source is base `cb6c51c598231101f059e6ef22a17f89d7c5d891` plus the
literal-text correction and focused tests committed with this section. Exact
compiled inputs and all artifact bytes are pinned by
`app/build/built-in-shiori-cleanup/final-fix/artifact-hashes.json`:

| Revised executable input/artifact | SHA-256 |
| --- | --- |
| BuiltInShiori.kt | `167d3dac37aa7c817993301cccdc2118a6d7f9e00034b22792615eaad5629f6a` |
| ScriptPlayer.kt | `eb2e18925bfb3868095b759d82935cc869225f36836a2d596928c52d7946f3d7` |
| SakuraScriptText.kt | `244eeacb1138213e56fd9d5550c530451ed99b3dc08b37da725cb2f1a5e47b7a` |
| BundledAssistantUiTest.kt | `00cf3235bfa4c12b2b1871160d7989ed07e165496c3f656df523f8bc3e353090` |
| app-debug.apk | `4c0ea3e2392ad7fe8b12c9ea46be8ace4984c6282d0652360f80ce6e59421c26` |
| app-debug-androidTest.apk | `6fd019943cce2acc8db13ae6cb6e9c0f00825c80fae1a35597420d9d18a1e5fd` |
| app-release-unsigned.apk | `76c5d44888e63b6847fb87f5885990fea9faa66851373ffbb19e340b5a99ac97` |

Required full command executed successfully in **23 seconds**, **136 tasks: 32
executed, 104 up-to-date** (`final-fix/gate.log`):

```
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain
```

The unit task actually executed: **28 suites, 446 cases, 441 passes, zero failures
or errors, five Windows symlink privilege skips**. The five skips retain their
existing limited scope and are not passes; precise names/reasons are in
`final-fix/unit-summary.json` and preserved `unit-xml/`. Lint is **zero errors,
26 warnings, one hint** (`lint-summary.json` and preserved lint XML). Debug/test
and unsigned release assemblies succeeded. Strict inventory ListOnly/SelfCheck
passes at **135 self-contained / 181 total** identities (`inventory.log`,
`selfcheck.log`). No other device suites or private native fixtures were run.

Both final debug/test APKs were installed with `-r`, preserving application data,
only on explicitly identified **emulator-5580 / CodexShioriCleanup / API31 / x86_64**.
The original 17 exact cleanup identities plus the new provenance identity ran
together: **18 selected, 18 unique observed, 18 passes, zero failures/skips/
incomplete**, **45.949 seconds**, `OK (18 tests)`, one terminal code -1.
`final-fix/selectors.txt`, `ui.log`, `ui.stderr.log`, `ui-execution.json` and the
strictly parsed `ui-summary.json` retain selection/command/results. The Android
CLI layout process was absent before execution, and no layout server was started
during these UiAutomation captures.

All nine corresponding fresh images were regenerated and visually inspected
individually under `app/build/built-in-shiori-cleanup/final-fix/screenshots/`:

| Startup locale | Help | Tools | Debug |
| --- | --- | --- | --- |
| en-US | en-US-help.png | en-US-tools.png | en-US-debug.png |
| ja-JP | ja-JP-help.png | ja-JP-tools.png | ja-JP-debug.png |
| zh-TW | zh-TW-help.png | zh-TW-tools.png | zh-TW-debug.png |

All show complete readable help/tools menus and debug controls, retained artwork,
and no blank captures. Japanese stage controls wrap to two rows; the long Japanese
license choice wraps within the balloon. `final-fix/screenshot-hashes.json` pins
these final PNG bytes. The locale fixture/setup-cleanup/20× playback and startup
locale boundaries remain exactly as documented above. This is not a live system
locale-change journey or release-device execution claim.

Production and test-resource ZIPs remain identical at
`7ad4109c5362d3f068b1acf1ab642541b9dd8d2c5d9d95e5e7f2c9bf4eb9f46c`; dictionaries,
PNG bytes and notices were not changed by this correction. The earlier normal
factory/upgrade captures and their APK provenance remain historical evidence,
not newly repeated journeys against this changed production APK. All earlier
failures and rejected images remain preserved. Pixel 7 is untouched; no unsigned
release was installed, signing keys handled, release/publishing action or external
GitHub action performed. The root-owned emulator remains running for the controller.


## Scoped review correction 2 — literal bracket metadata

The scoped review of `8f0e779d` confirmed the original provenance bypass was fixed,
but found that escaped macro replacements inside choice/input arguments were
stored without decoding. `\q[Talk to %selfname,OnTalk,%selfname]` with `100%` thus
produced an extra backslash in the visible label and SHIORI Reference0; input IDs
and names containing backslashes had the same regression. The new focused
`substitutedChoiceAndInputMetadataRetainOriginalData` test failed before the
correction (`final-fix-round2/red.log`), then passed afterward (`green.log`). An
intermediate expanded-test compile error used `choice.id` instead of the existing
`choice.choiceId`; it is preserved in `expanded-green.log` and corrected in the
successful `expanded-green2.log`.

The correction identifies data boundaries before decoding. Serialized substitutions
also quote comma and bracket delimiters. Bracket scans preserve escaped closing
brackets, including an escape split across the bounded source-processing calls.
Choice/input consumers split only unescaped field separators and then decode each
field once. Numeric surface/animation/balloon/wait consumers decode their data
before the existing numeric validation; newline behavior is preserved. Host command
recognition keeps the encoded argument and exact allowlist, so decoded metadata
cannot create executable command structure. Ordinary text still decodes escapes
without rescanning the emitted characters. No permission/session change, content
overlay, event dispatcher or archive change was added.

Focused tests assert original labels, event references and input IDs for percent,
backslash and hostile bracket/comma names; legacy/event/Satori choice IDs; numeric,
newline and wait forms; and escaped argument/source-budget boundaries. A substituted
whole `open,developer` or `open,inputbox,created` field cannot manufacture structural
fields or a host command. Existing unknown/native-script and original cross-session
provenance regressions remain green. UI source and the exact 18-case inventory are
unchanged.

The required full command actually executed its unit task and succeeded in **22
seconds**, **136 tasks: 28 executed, 108 up-to-date** (`final-fix-round2/gate.log`):

```
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain
```

Result: **28 suites, 451 cases, 446 passes, zero failures/errors, five existing
Windows symlink privilege skips**. Exact skip names/reasons and copied final XMLs
are in `final-fix-round2/unit-summary.json` and `unit-xml/`. Lint remains **zero
errors, 26 warnings, one hint**; all three APK assemblies succeeded. The prior
inventory/ListOnly/SelfCheck remains applicable to unchanged UI identities and
inventory: **135 self-contained / 181 total**.

Executable source is `8f0e779df2e353ad2e4d1b6acb8eaf0c078a3d6c` plus the narrow
parser/helper/test correction committed with this section; exact compiled bytes
are in `final-fix-round2/artifact-hashes.json`:

| Revised executable input/artifact | SHA-256 |
| --- | --- |
| SakuraScriptText.kt | `e1dee956ef37f06c127c13f8ddf94e62f2e5df13b7c333a938366622e7b1c053` |
| ScriptPlayer.kt | `c97fead25264bede9327d43182dcfcdfa8247d611a1ee937f42ee795bbade780` |
| ScriptPlayerTest.kt | `b1682f5ca814b7df6f4d4479fc15b774c8026bfbad0cb6aad3b6e4da6e11ac81` |
| BundledAssistantUiTest.kt | `00cf3235bfa4c12b2b1871160d7989ed07e165496c3f656df523f8bc3e353090` |
| app-debug.apk | `629396d7411c9ee48547b400f0a809806fbe972c20a9ad47d00d55d8b8f3677e` |
| app-debug-androidTest.apk | `6fd019943cce2acc8db13ae6cb6e9c0f00825c80fae1a35597420d9d18a1e5fd` |
| app-release-unsigned.apk | `a6e2f39165dd38b9cfd0977d7a1cc81af7cc240e8f8ca8669e33572064291875` |

The root restarted the existing owned emulator. Before installation this worker
verified **emulator-5580 / CodexShioriCleanup / boot_completed=1 / API31 / x86_64**
and confirmed the Android CLI layout process was absent. Only these debug/test
APKs were installed `-r`, retaining app data. The unchanged exact 18 selectors ran
on this new debug app: **18 unique observed, 18 passes, zero failures/skips/
incomplete**, **46.871 seconds**, `OK (18 tests)`, one successful terminal -1.
Raw `ui.log`, `ui.stderr.log`, `install.log`, selection `selectors.txt`, command
`ui-execution.json` and strictly parsed `ui-summary.json` are retained under
`app/build/built-in-shiori-cleanup/final-fix-round2/`. No layout server was started.
The emulator remains running for the controller; Pixel 7 was untouched.

No new host-side image set was accepted or visually inspected for this round.
The nine previously inspected images at `final-fix/screenshots/` and their hashes
retain their **8f0e779d debug APK `4c0ea3e2392ad7fe8b12c9ea46be8ace4984c6282d0652360f80ce6e59421c26`**
provenance, rather than being attributed to the new debug APK. The existing locale
cases still generate routine device-side captures, but those captures were not
pulled or accepted as new image evidence. The parser correction changes metadata
escaping/decoding for special data characters, with no UI source, archived menu,
normal locale text or normal frame change; the fresh device assertions raised no
visual concern requiring a new image inspection round.

All prior failures, captures and evidence remain preserved. Both ZIPs, PNGs,
notices and dictionaries remain unchanged. Normal-factory history retains its
older APK provenance. No release installation/signing, full device suite/private
native fixtures, live-locale change, Pixel work, or external GitHub action is
claimed. The five Windows skips remain skips; the existing Android managed-link
case passed again in the exact 18-case run.
