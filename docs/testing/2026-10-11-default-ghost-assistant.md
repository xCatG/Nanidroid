# Default ghost assistant acceptance, 2026-10-11

The default assistant passed the bounded local gate and focused API 31 checks
below. This record separates the normal application factory from the focused
instrumentation fixture. Independent whole-branch GPT-6-Sol review approved the
implementation with no actionable findings; the
[execution review](../review/2026-10-11-default-ghost-assistant-execution-review.md)
records the final gate and implementation decisions.

## Source, toolchain and artifacts

Production/test source: `a79ffab55ce6bf388b68d5b5bd87ceb8484e344a`, branch
`codex/default-ghost-assistant`, in
`C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`. The checkout was clean before
the gate; subsequent Task 4 changes are documentation only. APK provenance is
from the same combined build, before these documentation edits.

JDK: `C:/Program Files/Java/jdk-17`, Java 17.0.12. `ANDROID_HOME` and
`ANDROID_SDK_ROOT`: `C:/Users/yenchi/AppData/Local/Android/Sdk`. The pinned
Gradle/native/toolchain configuration was retained; offline execution used the
existing caches. Ignored raw evidence is retained under
`app/build/default-ghost-assistant-acceptance/`, independently of scratch task
reports. No signing key, release installation or user device was used.

| APK | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `60265b83b3bf8043ae99133bdf1157c4ece4f57cd10a1f48ffd918186038432f` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `177bb1e45dff04c417abf3e1cb7c87b8e92e2bb68a9bfd0e1eda53430fd4d870` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `ce7036ba8e62c922b1a877e24b5b958314ed6ab20321788c1f86dd971d46d395` |

## Combined local gate

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
$env:ANDROID_HOME='C:/Users/yenchi/AppData/Local/Android/Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_SERIAL='emulator-5580'
./gradlew.bat --offline --console=plain testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
```

Exit **0**, `BUILD SUCCESSFUL in 1m 31s`, 136 tasks: 51 executed, 13 from cache,
72 up-to-date. [Raw gate](../../app/build/default-ghost-assistant-acceptance/combined-gate.log).
The unit task was up-to-date against the same committed product/test sources;
Task 3's preceding full execution is separate evidence. Current XML aggregate:
**29 suites, 416 tests, zero failures/errors, four skipped** (412 executed passes).
Reports: `app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/test-results/testDebugUnitTest/TEST-*.xml`. Existing symlink skips:

- `InstalledGhostRepositoryTest#symlinkCandidatesCannotEscapePrivateRoot`
- `NativeProfileDirectoryTest#rejectsEscapingProfileSymlink`
- `ImportStagingTest#linkedRootAndLinkedAttemptAreRejectedWithoutTraversal`
- `ImportStagingTest#linkedChildInsideAttemptBlocksRecoveryWithoutDeletingOutside`

Lint debug XML reports **zero errors, 26 warnings and one hint**; reports are
`app/build/reports/lint-results-debug.{xml,html}`. Locations were checked against
the assistant change: none points at newly added assistant code. They concern
the existing StageViewModel constructor, stage layout/modifier/state/Canvas,
URI/preferences suggestions and launcher resources. This is not warning-free
evidence. Kotlin nullable-File warnings also remain in existing image/runtime
code. Debug app/test and unsigned release APK assembly succeeded; assembly is
not release readiness.

Strict inventory commands both exited **0**:
`pwsh -NoProfile -File tools/test-device-suite.ps1 -ListOnly -Suite self-contained`
selected **126**; `-SelfCheck` validated **172** total methods. The other groups
remain 29 host-orchestrated, 16 corpus-fixture and one diagnostic-device. The
full self-contained device suite was not executed for this enhancement.

## Normal application factory and installed-data checks

Only `emulator-5580`, AVD `CodexNanidroidAssistant_20261011`, was used. Initial
and final checks verified boot completion, **API 31/x86_64**, **1080×2400/420 dpi**,
font **1.0**, portrait and window/transition/animator scales **0**. The lead
owned the emulator process; this worker did not create, wipe or reset it.

These checks launched the unmodified application with
`adb -s emulator-5580 shell am start -W -n com.cattailsw.nanidroid/.MainActivity`.
No lazy delegate replacement or accelerated script clock was used here.
Before the first normal launch the app had only `files/profileInstalled` and no
bundled install. The actual first welcome and Introduction/help, App tools and
Dismiss choices were observed. After this launch, the original eight bundled
files were hashed, including the original English six-event table; assistance
came from the APK-owned assets.

Both current debug APKs were upgraded with explicit-serial `adb install -r`.
No installation/data deletion occurred. Package `firstInstallTime` remained
`2026-10-11 16:01:02`; `lastUpdateTime` advanced to `16:27:30`. All eight bundled
file hashes were identical before upgrade, after upgrade, after normal flows
and after instrumentation. `activated_nanidroid` and `onboarding_seen` remained
true. The first delayed post-upgrade capture had already missed the short
dialogue; prompt subsequent cold-launch captures observed **Welcome back!**,
rather than another first welcome. Hashes and preferences are retained in
`installed-*.txt`, `boot-state-*.xml` and `final-*.xml`.

Observed normal flows:

- Tap 郭嘉榆: all three help topics rendered actual introduction, fresh-import/
  refusal/no-migration policy and ghost-dependent interaction instructions.
  Back returned to the menu, and Dismiss cleared the balloons.
- Hide top controls by tapping empty stage; tap ドロイド君 still opened its tools
  balloon with Ghosts, About and licenses, Debug tools and Dismiss choices.
- Ghosts opened the existing selection dialog. Import .nar opened the real
  Android document picker; Back cancellation returned to the stage without an
  import. No actual NAR import is claimed by this check.
- About and licenses opened version 0.3.0 and rendered the Satori notice;
  device Back dismissed the dialog while retaining the stage. Traversal of all
  19 notices is not claimed by this manual check.
- Debug tools opened with its label, Switch and Close visible. Toggling enabled
  visible magenta touch bounds; Close retained that state, with the existing
  top control changing to Hide touch bounds. Reopening preserved the checked
  Switch. Actual portrait-to-landscape rotation dismissed the consumed dialog
  without replay; portrait was restored.
- A unique, invocation-owned ordinary built-in at
  `files/ghost/assistant_acceptance_20261011` supplied authored synthetic table
  content and copies of the unchanged bundled PNGs. After a cold restart to
  refresh the normal startup inventory, Ghosts selected this fixture through
  the real confirmation dialog. The stage rendered Acceptance Sakura/Kero.
  Switching back to Nanidroid restored both help and tools/About behavior.
  Its six files and empty directories, plus three host-staging text files,
  were removed individually; only the original nanidroid directory remained.
  `last_ghost` returned to nanidroid. The normal activation store retains the
  fixture's harmless activation marker on this private emulator; preferences
  were not edited to erase it. Fixture bytes/hashes and cleanup are recorded.

All UI operations and first snapshots are retained in `manual-commands.log`
and `normal-*.xml`. Selected PNGs were captured using explicit-serial
`adb shell screencap -p` and `adb pull`. The worker visually inspected
`normal-help-menu.png`, `normal-tools-menu.png` and `normal-debug-on.png`:
menu choices and dialog label/Switch/Close were readable on this viewport.
These and `normal-rotation-landscape.png` are available for the lead's visual QA.
The lead also inspected the three portrait screenshots and confirmed readable,
unclipped choices and debug controls on the recorded viewport.

Host acceptance mistakes are retained, rather than classified as product
failures: three initial helper invocations passed no adb arguments and returned
usage/exit 1; an Import selector expected `Import` instead of the observed
`Import .nar`; a script incorrectly assumed picker cancellation retained Ghosts
and sent an extra Back, correctly closing the stage before its next selector
failed. Synthetic staging had a host parse error before mutation, then a list
selector ran before refreshing the app's startup inventory. A missing aapt2
37.0.0 path and an invalid initial metadata revision were corrected. None
establishes a product pass or failure; subsequent intended outcomes remain
separate recorded observations.

## Focused instrumentation and debug policy boundary

After the normal checks and fixture-file cleanup, one invocation used
`ANDROID_SERIAL=emulator-5580` and explicit `adb -s emulator-5580 shell am
instrument -w -r -e class '<nine exact selectors>'
com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner`.
`focused-selectors.txt`, `focused-instrumentation.log` and `focused-summary.json`
retain the exact identities and statuses. **Nine selected, nine unique terminal
passes, zero failures/skips, OK (9 tests), 24.995 s, exit 0**. No retry was made.

The cases cover help topic navigation, Ghosts/About, shared bounds state,
modeled non-debug rejection, recomposition exactly-once delivery, real
orientation recreation without replay, native dialog Back dismissal, switching
away from a debug panel and inactive-stage claim gating. This fixture uses real
MainActivity/runtime/assistant assets, invocation-owned installed trees and
boot stores, test-lifetime lazy-delegate replacement and a 20× script clock.
It therefore supplements the separately recorded normal-factory checks.

The non-debug case clears a test context's `ApplicationInfo.FLAG_DEBUGGABLE`
and disables the real assistant policy on a **debug APK**. It checks hidden and
rejected debug actions, absent debug dialog/control and absent bounds overlay.
It is modeled policy evidence, not release-device UI execution. Installed
build-tools 36.0.0 `aapt2 dump xmltree` also exited 0 for both assembled APKs:
debug has `android:debuggable=true`; unsigned release omits that attribute
(default false). Static manifest metadata does not replace release execution.
The unsigned release was neither signed nor installed.

## Immutable inputs and remaining limits

Before/after hashes match exactly:

| Input | SHA-256 |
| --- | --- |
| `nanidroid.zip` | `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d` |
| Embedded `shell/master/surface0000.png` | `5c1c95e3f0c7de16d279c92d8ea5de01e4a80771bb270226588c8d1bd4316c9f` |
| Embedded `shell/master/surface0010.png` | `f2358bab6b34ac088ff6db3f73e39b9c7143ad627f7015a6efd49aaae7d56d53` |

The path-only diff from feature execution base `eaff9bc6` to verified source
contains no native, wrapper/catalog, build configuration or toolchain changes.
Task 2's passing `existingInstalledDefaultFilesUntouched` unit case also checks
assistance on an existing install without reopening the archive. Task 4's
normal-device hash evidence independently confirms file preservation.

Coverage is one minimum-supported API/ABI emulator and English device UI.
Task 1 tests cover Japanese/fallback text; no Japanese device pass is claimed
here. Task 3's earlier two targeted font-1.5 passes are separate evidence and
were not repeated here. No API 37/arm64 execution, private native/NAR corpus,
process-death suite, actual release-device UI, broader compatibility or repaired
historical M5 exceptions is claimed. No production/test APIs changed in Task 4.
