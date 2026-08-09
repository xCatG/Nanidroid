# SakuraScript Input Presentation Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Present supported SakuraScript input boxes through one typed, accessible model without changing the runtime-owned pending-input identity or delivery.

**Architecture:** The tokenizer remains the only raw-option interpreter and adds an immutable `InputPresentation` to `InputBoxSpec`. The existing dialog binding transports that exact live spec/generation to Compose; Compose consumes only typed properties.

**Tech Stack:** Kotlin, JUnit4 JVM tests, Compose Material 3, Compose instrumentation tests.

## Global Constraints

- Scope is #213 only: do not change #195, #199, #201, #202, or #267 source-order, hidden-input, and passive behavior.
- Every production behavior follows a watched Red → Green → Refactor test cycle.
- Preserve `PendingInputState` generation, exact `spec` identity, owner, deadline, and carried-input behavior.
- Keep unknown/malformed author options in `unknownOptions`, and ignore them in Compose: no Toast and no raw-text telemetry.
- Normalize `open,passwordinput` and the existing `--option=password` spelling to password presentation. Retain existing `multiline`, `noempty`, and `nocancel` aliases. Keep SSP `noclose` and `noclear` unknown because they change post-submit delivery rather than presentation.
- Retain the current IME-safe fullscreen dialog, 560 dp expanded cap, scrolling content, 48 dp actions, pane title, heading, and presentation-only outside/back dismissal.

## File Structure

- `runtime/dialogue/DialogueContent.kt`: immutable typed presentation attached to the spec.
- `runtime/dialogue/SakuraScriptTokenizer.kt`: normalized command/options parsing.
- `DialogueDialogBinding.kt` and `Nanidroid.kt`: exact-live-spec handoff.
- `compose/NanidroidSimpleDialogs.kt`: typed password, multiline, validation, cancel, and IME behavior.
- `SakuraScriptTokenizerTest.kt`, `DialogueDialogBindingTest.kt`, and `compose/NanidroidSimpleDialogsTest.kt`: parsing, fencing, and accessible UI coverage.

---

### Task 1: Normalize runtime presentation

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt`

**Interfaces:**

```kotlin
data class InputPresentation(
    val obscured: Boolean = false,
    val multiline: Boolean = false,
    val requireNonEmpty: Boolean = false,
    val allowCancel: Boolean = true,
)
// InputBoxSpec gains: val presentation: InputPresentation
```

- [ ] **Step 1: Write a failing table test**

```kotlin
val password = input("\\![open,passwordinput,pw,--option=multiline,--option=noempty,--option=nocancel]")
assertEquals(InputPresentation(true, true, true, false), password.presentation)
val alias = input("\\![open,inputbox,pw,--option=password,--option=future,--option=noclose]")
assertEquals(InputPresentation(obscured = true), alias.presentation)
assertEquals(listOf("future", "noclose"), alias.unknownOptions)
```

- [ ] **Step 2: Verify Red**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizerTest.inputFormsNormalizePresentationAndKeepUnsupportedOptions`

Expected: FAIL because `passwordinput` is unsupported and no presentation exists.

- [ ] **Step 3: Implement minimal normalization**

Recognize `open,passwordinput` using the same positional/named grammar as `inputbox`, force `obscured = true`, and derive all typed fields once from the canonical `InputBehavior` set. Update every `InputBoxSpec` constructor explicitly. Preserve malformed/unknown values in order.

- [ ] **Step 4: Verify Green and simplify**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizerTest`

Expected: PASS, including existing escaping, direct-event, scope, and malformed recovery coverage. Keep all raw-string mapping in the tokenizer, then run `git diff --check`.

- [ ] **Step 5: Commit**

```text
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt
git commit -m "feat: normalize input presentation options"
```

### Task 2: Bind the exact live spec to the dialog

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/DialogueDialogBinding.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/DialogueDialogBindingTest.kt`

**Interfaces:** `NanidroidSimpleDialog.UserInput` gains `presentation: InputPresentation`. `DialogueDialogBinding.userInput` accepts `PendingInputState`, binds only matching generation and identical spec, and restoration reads presentation only from the matching live pending input.

- [ ] **Step 1: Write failing generation/identity tests**

```kotlin
val first = pending(4, spec(InputPresentation(obscured = true)))
snapshot = stateWith(first)
val dialog = binding.userInput(first)
snapshot = stateWith(pending(5, spec(InputPresentation(multiline = true))))
assertEquals(InputPresentation(obscured = true), dialog.presentation)
dialog.onSubmit(dialog.id, "secret")
assertTrue(submissions.isEmpty())
```

Add a paired carried-input/choice-only-talk restoration test asserting the same spec instance and presentation reopen; a replacement generation must not submit or cancel.

- [ ] **Step 2: Verify Red**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.DialogueDialogBindingTest`

Expected: FAIL because the current binding accepts only ID/generation and no dialog presentation.

- [ ] **Step 3: Implement minimal handoff**

`openDialogueInput` verifies `pending.spec === input.spec` and passes `pending` to the binding. Preserve current runner/owner/generation fencing and dispatch ID selection; do not reconstruct presentation from saved state or raw strings.

- [ ] **Step 4: Verify Green and simplify**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.DialogueDialogBindingTest --tests com.cattailsw.nanidroid.SScriptRunnerPresentationTest --tests com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest`

Expected: PASS with unchanged owner, deadline, generation, and carried-input routing. Remove ID-only presentation reconstruction, then run `git diff --check`.

- [ ] **Step 5: Commit**

```text
git add src/main/kotlin/com/cattailsw/nanidroid/DialogueDialogBinding.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt src/test/java/com/cattailsw/nanidroid/DialogueDialogBindingTest.kt
git commit -m "fix: bind input presentation to live generation"
```

### Task 3: Render accessible typed behavior

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt`
- Create or Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogsTest.kt`
- Modify only if constructor fixes require it: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostBubbleInteractionTest.kt`

- [ ] **Step 1: Write failing Compose tests**

```kotlin
composeRule.setContent { UserInputDialog(passwordDialog, {}) }
composeRule.onNodeWithTag("script-user-input").assert(hasPasswordVisualTransformation())
composeRule.setContent { UserInputDialog(requiredNoCancelDialog, {}) }
composeRule.onNodeWithTag("script-user-input-confirm").assertIsNotEnabled()
composeRule.onNodeWithTag("script-user-input-cancel").assertDoesNotExist()
```

Add multiline/newline and explicit Confirm assertions. Use `DeviceConfigurationOverride` for compact, expanded, and 1.5 font-scale configurations; assert field, pane title/heading, and enabled action remain discoverable.

- [ ] **Step 2: Verify Red**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.NanidroidSimpleDialogsTest`

Expected: FAIL because current input is always plain, single-line, enabled, and cancellable.

- [ ] **Step 3: Implement the smallest typed branches**

Use `PasswordVisualTransformation` plus `KeyboardType.Password` for obscured input. Use `singleLine = !multiline`, `ImeAction.Default` for multiline, and the existing explicit Confirm action. When `requireNonEmpty && value.isBlank()`, expose error semantics and disable Confirm. Omit only explicit Cancel when `allowCancel` is false; outside/back remains presentation-only.

- [ ] **Step 4: Verify Green and simplify**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.NanidroidSimpleDialogsTest,com.cattailsw.nanidroid.compose.stage.GhostBubbleInteractionTest`

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizerTest --tests com.cattailsw.nanidroid.DialogueDialogBindingTest --tests com.cattailsw.nanidroid.SScriptRunnerPresentationTest`

Expected: PASS. If device execution is unavailable, record the exact failure and do not claim device semantics. Keep one local typed `presentation` value in Compose and no raw option interpretation.

- [ ] **Step 5: Final verification, review, and publish**

Run: `./gradlew.bat testDebugUnitTest lint` and `git diff --check`; request independent review of `origin/master..HEAD`, address verified P0/P1/P2 findings test-first, then push `codex/input-presentation-options`. Open a separate draft PR titled `feat: model SakuraScript input presentation options` with `Fixes #213` and request `@codex review`.

## Plan Self-Review

- Task 1 covers normalized options and deterministic compatibility retention.
- Task 2 covers exact pending identity, stale generation fencing, and carried input.
- Task 3 covers keyboard, password, multiline, non-empty, cancellation, compact/expanded, large-font, and accessibility presentation.
- Raw option parsing has one owner, no excluded issue is touched, and no task has a placeholder.
