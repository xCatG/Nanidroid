# Remove Obsolete App Chrome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete Nanidroid's obsolete Help, Feedback, About, and Ghost Town app chrome while preserving every ghost/runtime/install path and the two ghost-authored outgoing-link seams.

**Architecture:** Keep this as one tightly coupled UI deletion in the single Android app module. Remove the obsolete producers, dialog variants, saved-state discriminators, first-run copy, localized resources, and callback plumbing together; retain the generic Readme document renderer/restoration model and the two useful More Ghost actions. Convert source-text assertions into executable Readme behavior tests where possible.

**Tech Stack:** Kotlin, Jetpack Compose, Android saved instance state, JUnit 4, Compose instrumentation tests, Gradle screenshot tests, Python source-contract tests.

## Global Constraints

- Work only under issue #382; do not enter #384, #385, or #386.
- Preserve local NAR picker/import, remote URL entry, archive queue, update, durable work, runtime, SHIORI, rendering, switching, and debug behavior.
- Preserve `MoreGhost`, `UrlEntry`, `DIALOG_MORE_GHOST`, and `DIALOG_URL_ENTRY`; remove only the Ghost Town callback and third menu action.
- Preserve `TextDocument`, `SwitchConfirmation`, `PlainTextDocument`, installed/current Readme restoration, and their switch/link actions.
- Preserve SakuraScript `\j` external URLs as HTTP/HTTPS only with a nonblank host and failure-safe `ACTION_VIEW` launch.
- Preserve Readme link activation for explicit HTTP, HTTPS, and `mailto:` links; reject other schemes.
- Do not change `AndroidManifest.xml`, dependencies, permissions, services, receivers, installer, runtime, update, durable, debug, network, or native code.
- Unknown obsolete saved-dialog discriminator strings restore no dialog; do not remap them to a retained dialog.
- All three localized first-run scripts must stop promising Help or Feedback UI.
- No WebView, HTML execution, relative-file link, or embedded browser may be introduced.

---

### Task 1: Delete obsolete app chrome and retain executable document/link contracts

**Files:**

- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Setup.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt`
- Modify: `src/main/res/values/strings.xml`
- Modify: `src/main/res/values-ja/strings.xml`
- Modify: `src/main/res/values-zh-rTW/strings.xml`
- Modify: `src/main/res/raw/first_run_script.txt`
- Modify: `src/main/res/raw-ja/first_run_script.txt`
- Modify: `src/main/res/raw-zh-rTW/first_run_script.txt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellUiAutomatorTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/RenderedTransformContractTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/TextDocumentRestoreSnapshotInstrumentationTest.kt`
- Modify: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotRenderer.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/NanidroidGhostStartupTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/compose/PlainTextDocumentTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/DialogueExternalUriLaunchTest.kt`
- Delete: `tools/test_kotlin_about_dialog_contract.py`
- Delete: `tools/test_kotlin_nanidroid_activity_contract.py`

**Interfaces:**

- Consumes: existing `NanidroidSimpleDialog.TextDocument`, `PlainTextDocument`, `tryLaunchDialogueExternalUri`, `ACTION_OPEN_DOCUMENT`, `MoreGhost`, `UrlEntry`, and saved-state helpers.
- Produces: a shell with no Help row; a two-action `MoreGhost`; Readme-only `TextDocumentRestoreKind`; executable decoding/link/restoration coverage; exactly two production outgoing `ACTION_VIEW` launch sites in `Nanidroid.kt`.

- [ ] **Step 1: Make the Compose tests express the retained menu contract**

  In `NanidroidComposeShellTest.kt`, remove every `onHelp` argument. Change the primary toolbar test so that after opening overflow it asserts no `help` node while still exercising Update and Readme. Replace the current More Ghost assertion with both retained callbacks and absence of a third action:

  ```kotlin
  @Test
  fun more_ghost_keeps_url_and_local_picker_without_ghost_town() {
      var selected = ""
      composeRule.setContent {
          NanidroidSimpleDialogHost(
              dialog = NanidroidSimpleDialog.MoreGhost(
                  onEnterUrl = { selected = "url" },
                  onInstallFromSdCard = { selected = "local" },
              ),
              onDismiss = {},
          )
      }

      composeRule.onNodeWithTag("simple-action-0").performClick()
      composeRule.runOnIdle { assertEquals("url", selected) }
      composeRule.onNodeWithTag("simple-action-1").performClick()
      composeRule.runOnIdle { assertEquals("local", selected) }
      assertNoNodeWithTag("simple-action-2")
  }
  ```

  Replace the combined Help/notice test with a notice-only test that still proves `notice-confirm` invokes its callback. Remove obsolete Help dialog construction. Remove `onHelp` arguments from UI Automator, rendered-transform, and screenshot fixtures.

- [ ] **Step 2: Add retained Readme behavior and saved-state coverage**

  Create `PlainTextDocumentTest.kt` in package `com.cattailsw.nanidroid.compose`. Use temporary files to assert UTF-8 BOM decoding and Shift_JIS decoding, and assert `linkPattern` recognizes HTTP, HTTPS, and `mailto:` but does not match `file:`, `content:`, `javascript:`, or bare text.

  Extend `TextDocumentRestoreSnapshotInstrumentationTest.kt` with an installed-ghost case:

  ```kotlin
  @Test
  fun installedGhostReadmeRoundTripsWithSwitchIdentity() {
      val document = NanidroidSimpleDialog.TextDocument(
          title = "Installed",
          text = "Installed ghost documentation",
          onOpenLink = {},
          sourceId = "installed-ghost",
          onSwitch = {},
      )

      assertEquals(
          TextDocumentRestoreSnapshot(
              kind = TextDocumentRestoreKind.INSTALLED_GHOST_README,
              title = "Installed",
              text = "Installed ghost documentation",
              sourceId = "installed-ghost",
          ),
          document.toTextDocumentRestoreSnapshot(),
      )
  }
  ```

  Keep the existing current-ghost case. Delete `tools/test_kotlin_about_dialog_contract.py`: its retained decoding/link claims now have executable JVM coverage, while the Compose and restoration claims remain covered by instrumentation. Delete the already-obsolete `tools/test_kotlin_nanidroid_activity_contract.py`; it contradicts the current Activity Result API and pins stale source formatting unrelated to behavior.

  Extend `DialogueExternalUriLaunchTest.kt` to cover a new pure `tryLaunchDocumentExternalUrl(value, launch)` boundary: accept lower- and upper-case HTTP, HTTPS, and `mailto:` schemes; accept the IDN URL `https://例え.テスト/readme`; reject `file:`, `content:`, `javascript:`, hostless web URLs, blank `mailto:`, and malformed values; return false rather than throwing when the launch lambda raises a runtime/security failure.

- [ ] **Step 3: Run the focused tests to establish the expected red state**

  Run:

  ```powershell
  .\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin compileDebugScreenshotTestKotlin
  ```

  Expected before production edits: Android/screenshot compilation fails on the removed callback/constructor expectations after the tests are updated. Decoding tests that exercise unchanged `PlainTextDocument` may already pass; the new document-launch policy test fails because its helper does not yet exist.

- [ ] **Step 4: Delete Help from the Compose shell**

  Remove `onHelp` from `NanidroidComposeShell`, `NanidroidToolbar`, preview wiring, and the overflow `DropdownMenuItem` tagged `help`. Retain Update, Readme, Archive Queue, Debug, toolbar blocking, and overflow behavior.

- [ ] **Step 5: Delete obsolete dialog variants without touching retained models**

  In `NanidroidSimpleDialogs.kt`, delete `HelpMenu`, `GeneralHelp`, their host branches, and their private composables. Change `MoreGhost` to exactly:

  ```kotlin
  data class MoreGhost(
      val onEnterUrl: () -> Unit,
      val onInstallFromSdCard: () -> Unit,
  ) : NanidroidSimpleDialog
  ```

  Render exactly two actions: Enter URL and Install from SD card. Change `TextDocument.sourceId` from nullable/defaulted to required `String`, because every retained document is ghost-owned. Keep `ActionMenuDialog`, `UrlEntry`, `TextDocument`, `SwitchConfirmation`, `GhostList`, and archive/durable dialogs otherwise unchanged.

- [ ] **Step 6: Delete Activity chrome and narrow document restoration to Readmes**

  In `Nanidroid.kt`:

  - remove `onHelp` shell wiring;
  - remove `onHelp`, `showHelp`, `createHelpMenuDialog`, `createGeneralHelpDialog`, `openHelpPage`, `showFeedback`, `showAbout`, `createAboutDialog`, and `showGhostTown`;
  - remove only `onGhostTown` from `createMoreGhostDialog`;
  - remove `DIALOG_HELP_MENU`, `DIALOG_GENERAL_HELP`, and `DIALOG_ABOUT` save/restore branches and constants;
  - remove `TextDocumentRestoreKind.ABOUT` and make `toTextDocumentRestoreSnapshot()` classify only installed and current Readmes;
  - update every `TextDocument` call site for the required ghost identity and keep installed/current classification based on `onSwitch`;
  - keep both Readme restore branches, Readme switching, `openDocumentLink`, `openDialogueExternalUrl`, `tryLaunchDialogueExternalUri`, More Ghost, URL entry, and local picker logic;
  - add `internal fun tryLaunchDocumentExternalUrl(value: String, launch: (String) -> Unit): Boolean` using `java.net.URI`, a case-insensitive HTTP/HTTPS/`mailto:` allowlist, `URI.toURL().host` (or equivalent IDN-aware normalization) for nonblank web hosts, nonblank mail address content, and `tryLaunchDialogueExternalUri` for failure containment;
  - reduce `openDocumentLink` to calling that helper and creating the `ACTION_VIEW` intent only inside its launch lambda.

  In `Setup.kt`, delete only `DLG_NOT_IMPL`, `DLG_ABOUT`, and `DLG_GEN_HELP`. Retain URL-entry, More Ghost, Readme, and no-Readme identifiers.

  Do not edit the manifest or any incoming archive-intent code.

- [ ] **Step 7: Remove resources and revise first-run narration**

  Delete these resource names from every locale where present:

  ```text
  help_btn_text
  menu_help
  menu_about
  menu_feedback
  feedback_url
  about_title
  help_install
  url_help_install
  help_supported_ops
  url_support_ops
  more_g_ghost_town_text
  not_implemented
  not_implemeted_title
  ```

  Revise each `first_run_script.txt` to retain the welcome, early-alpha caveat, toolbar discovery, ghost-switch/download guidance, and closing thanks while removing sentences that advertise Help or Feedback. Preserve SakuraScript syntax and each file's locale.

- [ ] **Step 8: Align remaining structural contracts and prove no stale chrome survives**

  Delete the two obsolete Python source-text contracts named in this task. Update the nonterminal-notice assertion in `NanidroidGhostStartupTest.kt` only if it names `R.string.not_implemented`.

  Run:

  ```powershell
  rg -n "HelpMenu|GeneralHelp|createAboutDialog|showFeedback|showGhostTown|DIALOG_HELP|DIALOG_ABOUT|DLG_NOT_IMPL|DLG_ABOUT|DLG_GEN_HELP|onHelp|onGhostTown|help_btn_text|menu_help|menu_about|menu_feedback|feedback_url|about_title|help_install|url_help_install|help_supported_ops|url_support_ops|more_g_ghost_town_text|not_implemented|not_implemeted_title" src tools
  rg -n "startActivity\(Intent\(Intent.ACTION_VIEW" src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt
  rg -n "providing us with feedback|Or get help|ヘルプのボタン|フィードバックを提供|意見反應功能|使用說明" src/main/res/raw/first_run_script.txt src/main/res/raw-ja/first_run_script.txt src/main/res/raw-zh-rTW/first_run_script.txt
  ```

  Expected: the first and third searches return no matches. The second returns only the dialogue and Readme launch sites. Manually read all three short first-run scripts to confirm their retained welcome/toolbar/ghost guidance is coherent in each locale.

- [ ] **Step 9: Run the focused and full local gates**

  Run:

  ```powershell
  python tools/verify_phase1_shipped_state_audit.py
  .\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin compileDebugScreenshotTestKotlin assembleDebug
  .\gradlew.bat lint validateDebugScreenshotTest
  git diff --check
  ```

  Compare any lint or screenshot failure with `0da7c5e86a7a572020f5f0ecdfa271275a7122b8`; do not normalize an unrelated baseline failure into this PR. Run `connectedDebugAndroidTest` when an API 31+ device is available.

- [ ] **Step 10: Commit the focused slice**

  Stage only the files listed by this task and commit:

  ```powershell
  git commit -m "Remove obsolete app chrome"
  ```

  The task report must record the exact commit, production/test LOC delta, commands and outcomes, baseline-only failures, residual `ACTION_VIEW` sites, and any concern about saved-state or first-run copy.
