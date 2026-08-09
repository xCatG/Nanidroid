# SakuraScript Interaction Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SakuraScript choices, legacy callbacks, unsupported-scope inputs, and passive-mode execution agree with visible and safely actionable runtime state.

**Architecture:** Add a bounded source-ordered interaction stream alongside speaker-grouped dialogue. The runner derives global pending choice order from that stream; bubbles retain speaker ownership from grouped content. A shared parser supplies tokenizer, legacy callback lookahead, and passive execution.

**Tech Stack:** Kotlin, Android runtime/Compose, JUnit 4, Gradle.

## Global Constraints

- Implement #196, #218, #215, then #200; every behavior is Red -> Green -> Refactor.
- Preserve action identity/claiming, input generations, session fencing, timing, and supported-scope IME behavior.
- Scope >= 2 input commands create no pending input, callback, host, or playback pause.
- Callback parity is eligibility and source order, not instantaneous Compose revelation.
- Do not touch #195, #213, #199, #202, timers, or stage/dialog layout.
- Simplify only touched code after Green; no public-contract, order, timing, or security changes.
- Use `./gradlew.bat`; run connected tests only on an available API 31–37 device.

---

## Planned File Structure

| File | Responsibility |
| --- | --- |
| `runtime/dialogue/SakuraScriptCommandParser.kt` | Shared bracket, quote, escape, argument, and scope parsing. |
| `runtime/dialogue/SakuraScriptInteractionStream.kt` | Immutable tokenization result and source-ordered choice records. |
| `runtime/dialogue/SakuraScriptTokenizer.kt` | Produces grouped content/stream and callback lookahead. |
| `SScriptRunner.kt` | Retains stream, projects choices, publishes callback, executes passive mode. |
| `SakuraScriptTokenizerTest.kt` | Syntax, stream, and scope tests. |
| `SScriptRunnerDialogueTimingTest.kt` | Order and identity-claim tests. |
| `SScriptRunnerDialogueObserverTest.kt` | Legacy callback lookahead test. |
| `SScriptRunnerPresentationTest.kt` | Hidden-input and passive execution tests. |

## Task 1: Add source-ordered interaction records (#196)

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptInteractionStream.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt`

**Interfaces:**
```kotlin
internal data class SakuraScriptTokenization(
    val contents: List<DialogueContent>,
    val interactions: List<SakuraScriptInteraction>,
)
internal data class SakuraScriptInteraction(
    val sourceEnd: Int,
    val scope: Int,
    val speaker: GhostSpeaker,
    val action: DialogueAction,
)
internal fun SakuraScriptTokenizer.tokenizeWithInteractions(
    script: String,
    onDiagnostic: (String) -> Unit = {},
): SakuraScriptTokenization
```

- [ ] Write a failing tokenizer test for `\\h\\q[A,a]\\u\\q[B,b]\\h\\q[C,c]`; assert stream labels A/B/C, owners Sakura/Kero/Sakura, strictly increasing `sourceEnd`.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizerTest.tokenizationRetainsChoiceSourceOrderAcrossSpeakerReturns`; expect failure because the API is absent.
- [ ] Add the stream types and make the tokenizer record the exact visible `DialogueAction` instance after each valid `\\q`; scope >= 2 contributes neither visible content nor stream records. Keep `tokenize()` and `tokenizeRevealed()` return types unchanged.
- [ ] Rerun the focused test; expect PASS.
- [ ] Refactor to one named `recordChoice(action, sourceEnd)` helper, then run `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizerTest`; expect PASS.
- [ ] Commit: `git add src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptInteractionStream.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt; git commit -m "fix: retain SakuraScript choice source order"`.

## Task 2: Project runner choices from that stream (#196)

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueSpeakerOwnership.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt`

**Interfaces:** Extend `AuthoredDialogueScript` with `interactions: List<SakuraScriptInteraction>`. Pending choices are exact stream action instances ordered by `sourceEnd`.

- [ ] Write a failing runner test using `\\h\\q[A,a]\\u\\q[B,OnB]\\h\\q[C,script:\\hDone]\\e`; assert A/B/C order, then claim B and assert all pending choices retire and B cannot dispatch again.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest.pendingChoicesKeepAlternatingSpeakerSourceOrderAndIdentityClaims`; expect grouped A/C/B failure.
- [ ] In `recordDialogueScript`, retain `tokenizeWithInteractions`. In `publishDialogueProjection`, use entries with `sourceEnd <= revealed`, visible scope, and non-retired action; retain `projectDialogue` solely for rendered contents. Do not reconstruct choices from grouped segments.
- [ ] Rerun the focused test; expect PASS.
- [ ] Name the local `revealedPendingChoices`; retain ownership filtering as an identity-preserving view, then run the timing and observer classes; expect PASS.
- [ ] Commit: `git add src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueSpeakerOwnership.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt; git commit -m "fix: project authored choice order"`.

## Task 3: Share parsing and replace legacy regex lookahead (#218)

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptCommandParser.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt`

**Interfaces:**
```kotlin
internal object SakuraScriptCommandParser {
    data class Bracket(val value: String, val nextIndex: Int)
    fun readBracket(script: String, start: Int): Bracket?
    fun splitArguments(value: String): List<String>
    fun parseScope(script: String, start: Int): Pair<Int, Int>?
}
internal data class LegacyChoice(val label: String, val id: String)
internal fun SakuraScriptTokenizer.remainingVisibleChoices(
    script: String, commandStart: Int, initialScope: Int,
): List<LegacyChoice>
```

- [ ] Write failing tests for `\\q[A,a]\\p2\\q[H,h]\\p0\\q[B,b]` returning/publishing A/B exactly once, plus a text/wait before B proving callback lookahead precedes Compose revelation.
- [ ] Run the two focused tests; expect absent parser API and current callback including H.
- [ ] Move exact tokenizer bracket/quote/escape/scope semantics into the parser. Implement linear lookahead from `commandStart`, advancing scope on `\\h`, `\\u`, `\\0`, `\\1`, and `\\p`; collect balanced `\\q` first-two-argument label/ID only at scope < 2. Do not create capabilities.
- [ ] Replace `handleSelection`'s remaining regex loop with the API, retaining once-per-playback publication.
- [ ] Rerun focused tests, then all tokenizer/observer tests; expect PASS.
- [ ] Commit: `git add src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptCommandParser.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt; git commit -m "fix: align legacy choice visibility"`.

## Task 4: Lock in hidden-input non-pause behavior (#215)

**Files:**
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`

- [ ] Write `hiddenScopeInputDoesNotPausePublishOrBlockTrailingVisibleText`: drive `\\p2\\![open,inputbox,hidden]\\hAfter\\e`; assert visible text is After without `resumeEvt()`, pending input is null, and a `UICallback` recorder saw no input ID.
- [ ] Run its focused test. Expected: PASS (a characterization exception: current code already implements the issue's allowed safe resolution).
- [ ] Only if it fails, minimally preserve the `publishSelection == false` branch so the command consumes once, never pauses/publishes, and reaches After. Never add `PendingInputState`, `InputBox`, or a fallback dialog.
- [ ] Run the new test and `authoredInputsPublishInOrderWithDistinctGenerationsAsPlaybackReachesEachOne`; expect PASS.
- [ ] Commit test-only work, or include any minimal production correction: `git commit -m "test: preserve hidden input non-pause"`.

## Task 5: Execute passive mode through shared parsing (#200)

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt`

- [ ] Write table-driven failing runner/tokenizer tests for `\\!["enter",passivemode]` => true and `\\!["leave",passivemode]` => false; assert malformed or non-equivalent escaped forms leave passive false.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.SScriptRunnerPresentationTest.quotedPassiveModeCommandsMatchTokenizerSemantics`; expect quoted forms to fail against `PASSIVE_MODE`.
- [ ] At the runner `\\!` branch, call shared `readBracket`/ `splitArguments`; when decoded values are exactly enter|leave and passivemode, advance to `nextIndex` and set `passive`. Preserve input and malformed recovery behavior. Remove `PASSIVE_MODE` only once unused.
- [ ] Rerun focused tests and all tokenizer tests; expect PASS.
- [ ] Simplify to one named runner-local passive decision helper; run `./gradlew.bat testDebugUnitTest lint`; expect PASS.
- [ ] Commit: `git add src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt; git commit -m "fix: share SakuraScript passive parsing"`.

## Task 6: Verify and review

- [ ] Run `./gradlew.bat testDebugUnitTest lint`; expect PASS.
- [ ] If an API 31–37 emulator/device is connected, run `./gradlew.bat connectedDebugAndroidTest`; otherwise record it as unavailable.
- [ ] Run `git diff --check` and inspect the final diff. Confirm production edits are parser/tokenizer/runner/projection only, #215 added no dialog host, and duplicate scanning/parsing was removed rather than replicated.
- [ ] Request adversarial review focused on source order, scope transitions, lookahead, capability identity, quoted passive syntax, and excluded boundaries.
