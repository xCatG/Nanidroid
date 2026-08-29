package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ShioriRequestIntent
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Hashtable

class SakuraScriptPlayerTest {
    // Mutation caught: enqueue forgets to mint and schedule a playback token.
    @Test
    fun enqueueStartsOnlyThroughATaggedAdvance() {
        val initial = PlayerState.initial(generation = 4)
        val enqueued = SakuraScriptPlayer.reduce(initial, PlayerCommand.Enqueue("\\0hello\\e", parent = null))

        assertEquals("", enqueued.state.presentation.sakura.text)
        assertEquals(listOf(PlayerEffect.SchedulePlayback(enqueued.state.playbackToken, 0L)), enqueued.effects)

        val stale = SakuraScriptPlayer.reduce(enqueued.state, PlayerCommand.Advance(0L, 0L))
        assertEquals(enqueued.state, stale.state)
        assertTrue(stale.effects.isEmpty())

        val started = SakuraScriptPlayer.reduce(
            enqueued.state,
            PlayerCommand.Advance(enqueued.state.playbackToken, 0L),
        )
        assertEquals("h", started.state.presentation.sakura.text)
        assertEquals(
            PlayerEffect.SchedulePlayback(started.state.playbackToken, 50L),
            started.effects.last(),
        )
        assertTrue(PlayerEffect::class.java.declaredClasses.none {
            it.simpleName in setOf("PublishSnapshot", "CallUi")
        })
    }

    // Mutation caught: normal reveal consumes more than one character per playback step.
    @Test
    fun perCharacterRevealUsesLegacyWaitUnit() {
        val first = firstAdvance("\\hAB\\e")
        val second = advance(first.state, elapsedMillis = 50L)

        assertEquals("A", first.state.presentation.sakura.text)
        assertEquals("AB", second.state.presentation.sakura.text)
        assertEquals(PlayerEffect.SchedulePlayback(second.state.playbackToken, 50L), second.effects.last())
    }

    // Mutation caught: explicit and digit waits use one default delay.
    @Test
    fun authoredWaitsPreserveExactDelays() {
        var transition = firstAdvance("\\hA\\_w[321]B\\w4C\\e")
        transition = advance(transition.state, 50L)
        assertEquals(321L, transition.schedule().delayMillis)
        transition = advance(transition.state, 371L)
        assertEquals("AB", transition.state.presentation.sakura.text)
        transition = advance(transition.state, 421L)
        assertEquals(200L, transition.schedule().delayMillis)
    }

    // Mutation caught: terminal \e falls back to the ordinary 50 ms cadence.
    @Test
    fun terminalYenEUsesExactLegacyDelay() {
        val terminal = firstAdvance("\\e")

        assertEquals(1_000L, terminal.schedule().delayMillis)
        assertEquals(terminal.state.current?.payload?.script?.length, terminal.state.current?.charIndex)
    }

    // Mutation caught: a surface response does not suspend authored playback or is requested with an untyped origin.
    @Test
    fun authoredSurfaceRequestSuspendsAndExactResponseResumes() {
        val enqueued = SakuraScriptPlayer.reduce(
            PlayerState.initial(7),
            PlayerCommand.Enqueue("\\s[42]A\\e", null),
        )
        val suspended = advance(enqueued.state, 10L)
        val request = suspended.effects.single() as PlayerEffect.RequestShiori

        assertEquals("42", suspended.state.presentation.sakura.surfaceId)
        assertEquals(RuntimeRequestOrigin.Playback(suspended.state.playbackToken), request.origin)
        assertEquals("OnSurfaceChange", request.intent.eventId())
        assertEquals(request.origin, suspended.state.authoredRequest)

        val blocked = advance(suspended.state, 11L)
        assertEquals(suspended.state, blocked.state)
        assertTrue(blocked.effects.isEmpty())

        val token = RuntimeRequestToken(7, 9, null, request.origin)
        val resumed = SakuraScriptPlayer.reduce(
            suspended.state,
            PlayerCommand.NativeResponse(token, PlayerResponse.Returned(response(204))),
        )
        assertNull(resumed.state.authoredRequest)
        assertEquals(0L, resumed.schedule().delayMillis)
    }

    // Mutation caught: a response from another request/generation changes the suspended cursor or queue.
    @Test
    fun staleNativeResponseIsEffectFree() {
        val suspended = advance(
            SakuraScriptPlayer.reduce(
                PlayerState.initial(7),
                PlayerCommand.Enqueue("\\s[42]A\\e", null),
            ).state,
            0L,
        )
        val wrongOrigin = RuntimeRequestOrigin.Playback(suspended.state.playbackToken + 1)
        val wrong = SakuraScriptPlayer.reduce(
            suspended.state,
            PlayerCommand.NativeResponse(
                RuntimeRequestToken(7, 10, null, wrongOrigin),
                PlayerResponse.Returned(response(200, "\\hwrong\\e")),
            ),
        )
        val wrongGeneration = SakuraScriptPlayer.reduce(
            suspended.state,
            PlayerCommand.NativeResponse(
                RuntimeRequestToken(8, 11, null, suspended.state.authoredRequest!!),
                PlayerResponse.StaleGeneration,
            ),
        )

        assertEquals(suspended.state, wrong.state)
        assertTrue(wrong.effects.isEmpty())
        assertEquals(suspended.state, wrongGeneration.state)
        assertTrue(wrongGeneration.effects.isEmpty())
    }

    // Mutation caught: a playable authored response replaces or rewinds the current cursor rather than queueing.
    @Test
    fun playableNativeResponseQueuesWithoutChangingCurrentCursorTuple() {
        val suspended = advance(
            SakuraScriptPlayer.reduce(
                PlayerState.initial(7),
                PlayerCommand.Enqueue("\\s[42]Original\\e", null),
            ).state,
            0L,
        )
        val cursor = suspended.state.current
        val token = RuntimeRequestToken(7, 12, null, suspended.state.authoredRequest!!)
        val resumed = SakuraScriptPlayer.reduce(
            suspended.state,
            PlayerCommand.NativeResponse(token, PlayerResponse.Returned(response(200, "\\hIncoming\\e"))),
        )

        assertEquals(cursor, resumed.state.current)
        assertEquals(listOf(PlayerPayload("\\hIncoming\\e", null)), resumed.state.queue)
    }

    // Mutation caught: a replayable request failure resumes its parent and later completes it successfully.
    @Test
    fun replayableParentFailureIsTerminalWithoutLaterCompletion() {
        val parent = PlayerParent.Switch(31)
        val suspended = advance(
            SakuraScriptPlayer.reduce(
                PlayerState.initial(7),
                PlayerCommand.Enqueue("\\s[42]A\\e", parent),
            ).state,
            0L,
        )
        val token = RuntimeRequestToken(7, 12, 31, suspended.state.authoredRequest!!)
        val other = PlayerParent.Exit(32)
        val withOtherOwner = SakuraScriptPlayer.reduce(
            suspended.state,
            PlayerCommand.Enqueue("\\hOther\\e", other),
        ).state
        val replayable = SakuraScriptPlayer.reduce(
            withOtherOwner,
            PlayerCommand.NativeResponse(token, PlayerResponse.ReplayableFailure),
        )
        val fatal = SakuraScriptPlayer.reduce(
            suspended.state,
            PlayerCommand.NativeResponse(token, PlayerResponse.FatalFailure),
        )

        assertEquals(PlayerEffect.Failure(parent, RuntimeNoticeCode.REQUEST_FAILED), replayable.effects.first())
        assertTrue(replayable.effects.last() is PlayerEffect.SchedulePlayback)
        assertNull(replayable.state.current)
        assertEquals(listOf(PlayerPayload("\\hOther\\e", other)), replayable.state.queue)
        val afterFailure = advance(replayable.state, 1_000L)
        assertTrue(afterFailure.effects.none { it == PlayerEffect.ParentCompleted(parent) })
        assertEquals(PlayerEffect.Failure(parent, RuntimeNoticeCode.RUNTIME_POISONED), fatal.effects.single())
        assertNull(fatal.state.current)
        assertTrue(fatal.state.queue.isEmpty())
    }

    // Mutation caught: a poisoned runtime preserves another owner's queue and schedules playback.
    @Test
    fun fatalRuntimePoisoningClearsEveryOwnerAndEmitsOnlyFailure() {
        val failedParent = PlayerParent.Switch(41)
        val otherParent = PlayerParent.Exit(42)
        var suspended = advance(
            SakuraScriptPlayer.reduce(
                PlayerState.initial(7),
                PlayerCommand.Enqueue("\\s[42]A\\e", failedParent),
            ).state,
            0L,
        ).state
        suspended = SakuraScriptPlayer.reduce(
            suspended,
            PlayerCommand.Enqueue("\\hOther\\e", otherParent),
        ).state
        val token = RuntimeRequestToken(7, 15, 41, requireNotNull(suspended.authoredRequest))

        val poisoned = SakuraScriptPlayer.reduce(
            suspended,
            PlayerCommand.NativeResponse(token, PlayerResponse.FatalFailure),
        )

        assertNull(poisoned.state.current)
        assertTrue(poisoned.state.queue.isEmpty())
        assertNull(poisoned.state.authoredRequest)
        assertEquals(
            listOf(PlayerEffect.Failure(failedParent, RuntimeNoticeCode.RUNTIME_POISONED)),
            poisoned.effects,
        )
    }

    // Mutation caught: an invalid required presentation argument creates a partially valid frame.
    @Test
    fun parserFailureClearsCapturedWorkAndFailsParent() {
        val parent = PlayerParent.Exit(44)
        val failed = firstAdvance("\\s[]never", parent = parent)

        assertNull(failed.state.current)
        assertTrue(failed.state.queue.isEmpty())
        assertTrue(failed.state.dialogue.choices.isEmpty())
        assertEquals(PlayerEffect.Failure(parent, RuntimeNoticeCode.PLAYER_FAILED), failed.effects.single())
    }

    // Mutation caught: clearing one parent can destroy another parent's authored playback.
    @Test
    fun clearRequiresMatchingParentAndInvalidatesPriorPlaybackToken() {
        val parent = PlayerParent.Switch(5)
        val running = firstAdvance("\\hA\\e", parent)
        val rejected = SakuraScriptPlayer.reduce(running.state, PlayerCommand.Clear(PlayerParent.Switch(6)))
        assertEquals(running.state, rejected.state)

        val cleared = SakuraScriptPlayer.reduce(running.state, PlayerCommand.Clear(parent))
        assertNull(cleared.state.current)
        assertTrue(cleared.state.queue.isEmpty())
        assertTrue(cleared.state.playbackToken > running.state.playbackToken)
    }

    // Mutation caught: clearing one owner drops every queued payload or null crosses a parent fence.
    @Test
    fun clearRemovesOnlyExactOwnerAndNullCannotCrossParentFence() {
        val ownerA = PlayerParent.Switch(5)
        val ownerB = PlayerParent.Exit(6)
        var state = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue("\\hA\\e", ownerA),
        ).state
        state = SakuraScriptPlayer.reduce(state, PlayerCommand.Enqueue("\\hB\\e", ownerB)).state
        state = advance(state, 0L).state

        val clearedA = SakuraScriptPlayer.reduce(state, PlayerCommand.Clear(ownerA))
        assertNull(clearedA.state.current)
        assertEquals(listOf(PlayerPayload("\\hB\\e", ownerB)), clearedA.state.queue)
        assertEquals(
            listOf(PlayerEffect.SchedulePlayback(clearedA.state.playbackToken, 0L)),
            clearedA.effects,
        )

        var fenced = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue("\\hParent\\e", ownerA),
        ).state
        fenced = advance(fenced, 0L).state
        fenced = SakuraScriptPlayer.reduce(fenced, PlayerCommand.Enqueue("\\hOrdinary\\e", null)).state
        val nullClear = SakuraScriptPlayer.reduce(fenced, PlayerCommand.Clear(null))
        assertEquals(fenced, nullClear.state)

        var queued = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue("\\hOrdinary one\\e", null),
        ).state
        queued = SakuraScriptPlayer.reduce(queued, PlayerCommand.Enqueue("\\hFence\\e", ownerA)).state
        queued = SakuraScriptPlayer.reduce(queued, PlayerCommand.Enqueue("\\hOrdinary two\\e", null)).state
        val clearedLeadingNull = SakuraScriptPlayer.reduce(queued, PlayerCommand.Clear(null))
        assertEquals(
            listOf(
                PlayerPayload("\\hFence\\e", ownerA),
                PlayerPayload("\\hOrdinary two\\e", null),
            ),
            clearedLeadingNull.state.queue,
        )
    }

    // Mutation caught: ordinary clear is a no-op after the cursor completes and leaves stale actions/passive state.
    @Test
    fun ordinaryClearRetiresCompletedDialogueInventoryWithoutACursor() {
        val opened = drive(
            "\\q[Choice,choice-id]\\_a[anchor-id]Anchor\\_a" +
                "\\![open,inputbox,input-id,0,initial]\\![enter,passivemode]\\e",
            stopOnAction = true,
        ).state
        val completed = opened.copy(current = null, queue = emptyList(), authoredRequest = null, passive = true)
        assertNull(completed.current)
        assertEquals(1, completed.dialogue.choices.size)
        assertEquals(1, completed.dialogue.anchors.size)
        assertTrue(completed.dialogue.input != null)
        assertTrue(completed.passive)

        val cleared = SakuraScriptPlayer.reduce(completed, PlayerCommand.Clear(null))

        assertNull(cleared.state.current)
        assertTrue(cleared.state.dialogue.choices.isEmpty())
        assertTrue(cleared.state.dialogue.anchors.isEmpty())
        assertNull(cleared.state.dialogue.input)
        assertFalse(cleared.state.passive)
        assertEquals(completed.playbackToken + 1L, cleared.state.playbackToken)
    }

    // Mutation caught: terminal playback completes a parent before the final authored delay or more than once.
    @Test
    fun switchAndExitParentsCompleteExactlyAtPlaybackTerminal() {
        listOf<PlayerParent>(PlayerParent.Switch(9), PlayerParent.Exit(10)).forEach { parent ->
            val trace = drive("\\hA\\e", parent = parent)
            assertEquals(listOf(PlayerEffect.ParentCompleted(parent)), trace.effects.filterIsInstance<PlayerEffect.ParentCompleted>())
            assertEquals(1_000L, trace.effects.filterIsInstance<PlayerEffect.SchedulePlayback>().maxOf { it.delayMillis })
        }
    }

    // Mutation caught: passive enter/leave parsing ignores quoting or changes presentation text.
    @Test
    fun passiveModeIsPurePlayerState() {
        val entered = firstAdvance("\\![enter,passivemode]A\\e")
        assertTrue(entered.state.passive)
        assertEquals("A", entered.state.presentation.sakura.text)
        val left = driveFrom(entered.state, "\\![leave,passivemode]\\e")
        assertFalse(left.state.passive)
    }

    // Mutation caught: action IDs are reassigned at every incremental projection.
    @Test
    fun dialogueActionIdsAreAssignedOncePerAdoptedPayload() {
        var transition = firstAdvance("\\hA\\q[One,id]BC\\e")
        while (transition.state.dialogue.choices.isEmpty()) transition = advance(transition.state, 0L)
        val key = transition.state.dialogue.choices.single().key
        transition = advance(transition.state, 0L)

        assertEquals(key, transition.state.dialogue.choices.single().key)
        assertEquals(4L, key.generation)
        assertEquals(transition.state.dialogue.state.incarnation, key.incarnation)
    }

    // Mutation caught: an input deadline is based on zero rather than the adoption command's monotonic time.
    @Test
    fun inputTimeoutUsesAbsoluteElapsedTimeAndIsOneShot() {
        val enqueued = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue("\\![open,inputbox,name,500]\\e", null),
        )
        val opened = advance(enqueued.state, 10_000L)
        val input = opened.state.dialogue.input!!
        assertEquals(10_500L, input.pending.deadlineElapsedMillis)

        val early = SakuraScriptPlayer.reduce(opened.state, PlayerCommand.InputExpired(input.key, 10_499L))
        assertEquals(opened.state, early.state)
        val expired = SakuraScriptPlayer.reduce(opened.state, PlayerCommand.InputExpired(input.key, 10_500L))
        assertNull(expired.state.dialogue.input)
        val request = expired.effects.filterIsInstance<PlayerEffect.RequestShiori>().single()
        assertEquals("OnUserInputCancel", request.intent.eventId())
        assertEquals("OnUserInput", request.fallback!!.eventId())
        assertTrue(SakuraScriptPlayer.reduce(expired.state, PlayerCommand.InputExpired(input.key, 11_000L)).effects.isEmpty())
    }

    // Mutation caught: input deadlines use marker reveal time or zero instead of each payload's adoption time.
    @Test
    fun inputDeadlinesUseEachPayloadAdoptionTime() {
        var delayed = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue("\\hA\\_w[500]\\![open,inputbox,delayed,100]", null),
        ).state
        delayed = advance(delayed, 1_000L).state
        delayed = advance(delayed, 1_050L).state
        delayed = advance(delayed, 1_550L).state
        assertEquals(
            1_100L,
            requireNotNull(delayed.dialogue.input).pending.deadlineElapsedMillis,
        )

        var second = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue("\\e", null),
        ).state
        second = SakuraScriptPlayer.reduce(
            second,
            PlayerCommand.Enqueue("\\![open,inputbox,second,100]", null),
        ).state
        second = advance(second, 1_000L).state
        second = advance(second, 2_000L).state
        second = advance(second, 2_050L).state
        assertEquals(
            2_100L,
            requireNotNull(second.dialogue.input).pending.deadlineElapsedMillis,
        )
    }

    // Mutation caught: non-positive or overflowing input timeouts create expiring deadlines.
    @Test
    fun unlimitedInputDeadlinesMatchRunnerOverflowPolicy() {
        val zero = firstAdvance("\\![open,inputbox,zero,0]", elapsedMillis = 12L)
        val overflow = firstAdvance("\\![open,inputbox,huge,100]", elapsedMillis = Long.MAX_VALUE - 10)
        assertEquals(Long.MAX_VALUE, zero.state.dialogue.input!!.pending.deadlineElapsedMillis)
        assertEquals(Long.MAX_VALUE, overflow.state.dialogue.input!!.pending.deadlineElapsedMillis)
    }

    // Mutation caught: the unlimited MAX_VALUE sentinel expires at the largest elapsed value.
    @Test
    fun unlimitedInputDeadlineNeverExpiresAtMaxElapsed() {
        val opened = firstAdvance("\\![open,inputbox,unlimited,0]", elapsedMillis = 12L)
        val input = requireNotNull(opened.state.dialogue.input)

        val attempted = SakuraScriptPlayer.reduce(
            opened.state,
            PlayerCommand.InputExpired(input.key, Long.MAX_VALUE),
        )

        assertEquals(input, attempted.state.dialogue.input)
        assertTrue(attempted.effects.isEmpty())
    }

    // Mutation caught: submitting an input uses a later structurally equal input or leaves the action live.
    @Test
    fun submitAndDismissInputClaimExactStableKey() {
        val opened = firstAdvance("\\![open,inputbox,name,9000,initial,--supplement=s,--reference=tail]\\e")
        val input = opened.state.dialogue.input!!
        val stale = SakuraScriptPlayer.reduce(
            opened.state,
            PlayerCommand.SubmitInput(input.key.copy(actionId = input.key.actionId + 1), "value"),
        )
        assertEquals(opened.state, stale.state)

        val submitted = SakuraScriptPlayer.reduce(opened.state, PlayerCommand.SubmitInput(input.key, "value"))
        assertNull(submitted.state.dialogue.input)
        val submit = submitted.effects.filterIsInstance<PlayerEffect.RequestShiori>().single()
        assertEquals("OnUserInput", submit.intent.eventId())
        assertEquals(listOf("name", "value", "s", "tail"), submit.intent.references())

        val reopened = firstAdvance("\\![open,inputbox,name]\\e")
        val dismissed = SakuraScriptPlayer.reduce(
            reopened.state,
            PlayerCommand.DismissInput(reopened.state.dialogue.input!!.key),
        )
        assertEquals("OnUserInputCancel", dismissed.effects.filterIsInstance<PlayerEffect.RequestShiori>().single().intent.eventId())

        val direct = firstAdvance(
            "\\![open,inputbox,OnAnswer,9000,--supplement=s,--reference=tail]\\e",
        )
        val directInput = requireNotNull(direct.state.dialogue.input)
        val directRequest = SakuraScriptPlayer.reduce(
            direct.state,
            PlayerCommand.SubmitInput(directInput.key, "value"),
        ).effects.filterIsInstance<PlayerEffect.RequestShiori>().single()
        assertEquals("OnAnswer", directRequest.intent.eventId())
        assertEquals(listOf("value", "s", "tail"), directRequest.intent.references())

        val password = firstAdvance("\\![open,passwordinput,password]After\\e")
        assertEquals(true, requireNotNull(password.state.dialogue.input).pending.spec.presentation.obscured)
        assertEquals("", password.state.presentation.sakura.text)
    }

    // Mutation caught: normal/direct choices remain reusable or clear only the selected row.
    @Test
    fun choicesBecomeLabelsAndOneOrderedAction() {
        val trace = drive("\\hA\\q[One,id1]B\\q[Two,OnTwo,tail]\\e", stopOnAction = true)
        assertEquals("AOneBTwo", trace.authoredTextBeforeStop())
        assertEquals(listOf("One", "Two"), trace.state.dialogue.choices.map { it.action.label() })

        val selected = trace.state.dialogue.choices[1]
        val activated = SakuraScriptPlayer.reduce(trace.state, PlayerCommand.ActivateChoice(selected.key))
        assertTrue(activated.state.dialogue.choices.isEmpty())
        val request = activated.effects.single() as PlayerEffect.RequestShiori
        assertEquals("OnTwo", request.intent.eventId())
        assertEquals(listOf("tail"), request.intent.references())
        assertTrue(SakuraScriptPlayer.reduce(activated.state, PlayerCommand.ActivateChoice(selected.key)).effects.isEmpty())

        val normal = trace.state.dialogue.choices.first()
        val normalRequest = SakuraScriptPlayer.reduce(
            trace.state,
            PlayerCommand.ActivateChoice(normal.key),
        ).effects.single() as PlayerEffect.RequestShiori
        assertEquals("OnChoiceSelectEx", normalRequest.intent.eventId())
        assertEquals(listOf("One", "id1"), normalRequest.intent.references())
        assertEquals("OnChoiceSelect", requireNotNull(normalRequest.fallback).eventId())
        assertEquals(listOf("id1"), normalRequest.fallback.references())
    }

    // Mutation caught: remapping actions by speaker-grouped contents swaps alternating source actions.
    @Test
    fun alternatingSpeakerChoicesKeepSourceOrderAndSpeakerOwnership() {
        val shown = drive(
            "\\h\\q[A,a]\\u\\q[B,OnB]\\h\\q[C,script:\\hDone]\\e",
            stopOnAction = true,
        ).state
        assertEquals(listOf("A", "B", "C"), shown.dialogue.choices.map { it.action.label() })
        assertEquals(
            listOf("A", "C"),
            shown.dialogue.state.contents.filter { it.speaker == GhostSpeaker.SAKURA }
                .flatMap { it.segments }.filterIsInstance<DialogueSegment.Choice>()
                .map { it.action.label() },
        )
        assertEquals(
            listOf("B"),
            shown.dialogue.state.contents.filter { it.speaker == GhostSpeaker.KERO }
                .flatMap { it.segments }.filterIsInstance<DialogueSegment.Choice>()
                .map { it.action.label() },
        )
    }

    // Mutation caught: a local-script choice calls SHIORI or leaves sibling choices claimable.
    @Test
    fun localScriptChoiceClearsSiblingsAndEnqueuesWithoutShiori() {
        val shown = drive("\\q[A,a]\\q[Local,script:\\hDone\\e]\\e", stopOnAction = true).state
        val local = shown.dialogue.choices.single { it.action is DialogueAction.Script }
        val activated = SakuraScriptPlayer.reduce(shown, PlayerCommand.ActivateChoice(local.key))

        assertTrue(activated.state.dialogue.choices.isEmpty())
        assertTrue(activated.state.queue.any { it.script == "\\hDone\\e" })
        assertTrue(activated.effects.none { it is PlayerEffect.RequestShiori })
    }

    // Mutation caught: anchors are consumed like choices rather than remaining published and reusable.
    @Test
    fun anchorsRemainPublishedAndEachActivationRequests() {
        val shown = drive("\\_a[id,tail]Link\\_a\\e", stopOnAction = true).state
        val anchor = shown.dialogue.anchors.single()
        val first = SakuraScriptPlayer.reduce(shown, PlayerCommand.ActivateAnchor(anchor.key))
        val second = SakuraScriptPlayer.reduce(first.state, PlayerCommand.ActivateAnchor(anchor.key))

        assertEquals(shown.dialogue.anchors, first.state.dialogue.anchors)
        val normalRequest = first.effects.single() as PlayerEffect.RequestShiori
        assertEquals("OnAnchorSelectEx", normalRequest.intent.eventId())
        assertEquals(listOf("Link", "id", "tail"), normalRequest.intent.references())
        assertEquals("OnAnchorSelect", requireNotNull(normalRequest.fallback).eventId())
        assertEquals(listOf("id"), normalRequest.fallback.references())
        assertEquals(1, second.effects.size)

        val directShown = drive("\\_a[OnDirect,tail]Direct\\_a\\e", stopOnAction = true).state
        val direct = directShown.dialogue.anchors.single()
        val directRequest = SakuraScriptPlayer.reduce(
            directShown,
            PlayerCommand.ActivateAnchor(direct.key),
        ).effects.single() as PlayerEffect.RequestShiori
        assertEquals("OnDirect", directRequest.intent.eventId())
        assertEquals(listOf("tail"), directRequest.intent.references())
        assertNull(directRequest.fallback)
    }

    // Mutation caught: the opening anchor command skips its authored visible label.
    @Test
    fun anchorLabelRevealsBeforeReusableActionPublishes() {
        var transition = firstAdvance("\\_a[id]Link\\_a\\e")
        assertEquals("L", transition.state.presentation.sakura.text)
        assertTrue(transition.state.dialogue.anchors.isEmpty())
        while (transition.state.dialogue.anchors.isEmpty()) {
            transition = advance(transition.state, 0L)
        }
        assertEquals("Link", transition.state.presentation.sakura.text)
        assertEquals("Link", (transition.state.dialogue.anchors.single().action as AnchorAction.Normal).label)
    }

    // Mutation caught: returned collections retain mutable input lists or expose mutable queue/dialogue/effect lists.
    @Test
    fun queueDialogueAndEffectCollectionsRejectMutation() {
        val scripts = arrayListOf(
            "\\q[One,id,tail]\\![open,inputbox,name,--reference=ref,--option=password]\\e",
        )
        val enqueued = SakuraScriptPlayer.reduce(PlayerState.initial(4), PlayerCommand.Enqueue(scripts.single(), null))
        scripts[0] = "changed"
        val shown = drive(enqueued.state).state

        assertEquals(
            "\\q[One,id,tail]\\![open,inputbox,name,--reference=ref,--option=password]\\e",
            enqueued.state.queue.single().script,
        )
        assertUnmodifiable(enqueued.state.queue)
        assertUnmodifiable(shown.dialogue.choices)
        assertUnmodifiable(shown.dialogue.state.contents)
        assertUnmodifiable((shown.dialogue.choices.single().action as DialogueAction.Normal).extraReferences)
        assertUnmodifiable(requireNotNull(shown.dialogue.input).pending.spec.extraReferences)
        assertUnmodifiable(shown.dialogue.input.pending.spec.unknownOptions)
        assertUnmodifiable(enqueued.effects)
    }

    // Mutation caught: speaker, surface, or explicit cues are reordered relative to reveal steps.
    @Test
    fun speakerTextSurfaceAndAnimationHaveOrderedTransition() {
        val trace = drive("\\hHi\\s[120]\\i[3]\\uYo\\s[11]\\i[4]\\e")
        assertEquals(
            listOf(
                "text:sakura:H", "text:sakura:Hi", "surface:sakura:120", "animation:sakura:3",
                "text:kero:Y", "text:kero:Yo", "surface:kero:11", "animation:kero:4",
            ),
            trace.visibleEvents(),
        )
    }

    // Mutation caught: newline modifiers leak into text or clear fails to erase earlier text.
    @Test
    fun newlineModifierAndClearHaveOrderedTextStates() {
        val trace = drive("\\hA\\n[half]B\\cC\\e")
        assertEquals(listOf("A", "A\n", "A\nB", "C"), trace.distinctSakuraText())
    }

    // Mutation caught: quick mode still emits character-by-character transitions.
    @Test
    fun quickSessionEmitsOneWholeLineTransition() {
        val trace = drive("\\h\\_qHello, world.\\e")
        assertEquals(listOf("Hello, world."), trace.distinctSakuraText())

        val exited = firstAdvance("\\_qAB\\_q\\_w[7]C")
        assertEquals("AB", exited.state.presentation.sakura.text)
        assertFalse(requireNotNull(exited.state.current).quickSession)
        assertEquals(7L, exited.schedule().delayMillis)
    }

    // Mutation caught: equal surfaces generate extra transitions or equal animation commands collapse together.
    @Test
    fun distinctSurfaceTransitionsAndAnimationCuesAreOrdered() {
        val trace = drive("\\h\\s[120]\\s[120]\\i[3]\\i[3]\\e")
        assertEquals(
            listOf("surface:sakura:120", "animation:sakura:3", "animation:sakura:3"),
            trace.visibleEvents(),
        )
        assertEquals(1L, trace.state.presentation.sakura.surfaceEpoch)
    }

    // Mutation caught: hidden scope commands mutate visible speakers, actions, surfaces, balloons, or cues.
    @Test
    fun hiddenScopeConsumesCommandsWithoutChangingVisibleProjection() {
        val trace = drive(
            "\\hA\\p2hidden\\q[Hidden,h]\\s[99]\\i[7]\\b[0]\\p0B\\p1K\\e",
        )
        val authored = trace.states.lastOrNull {
            it.presentation.sakura.text == "AB" && it.presentation.kero.text == "K"
        }
        assertTrue(
            "visible texts=${trace.states.map { it.presentation.sakura.text to it.presentation.kero.text }}",
            authored != null,
        )

        assertEquals("0", requireNotNull(authored).presentation.sakura.surfaceId)
        assertEquals("10", authored.presentation.kero.surfaceId)
        assertTrue(trace.state.dialogue.choices.isEmpty())
        assertFalse(trace.effects.filterIsInstance<PlayerEffect.PresentationCue>().any { it.animationId == "7" })

        val exact = drive("\\p[1]K\\_b[-1]\\p[0]S\\_b[0]\\e")
        val projected = exact.states.first {
            it.presentation.sakura.text == "S" && it.presentation.kero.text == "K"
        }.presentation
        assertTrue(projected.sakura.balloonVisible)
        assertTrue(projected.kero.balloonVisible)
    }

    // Mutation caught: choice labels disappear from presentation or publish only after later text.
    @Test
    fun choicesPublishThenLabelsContinueAsText() {
        val script = "\\hA\\q[One,id1]\\_w[50]B\\q[Two,id2]\\e"
        var transition = firstAdvance(script)
        while (transition.state.dialogue.choices.isEmpty()) transition = advance(transition.state, 0L)
        assertEquals(listOf("One"), transition.state.dialogue.choices.map { it.action.label() })
        assertFalse(transition.state.presentation.sakura.text.contains("B"))

        val trace = drive(script, stopOnAction = true)
        assertEquals("AOneBTwo", trace.authoredTextBeforeStop())
        assertEquals(listOf("One", "Two"), trace.state.dialogue.choices.map { it.action.label() })
    }

    // Mutation caught: unsupported controls render their command bytes as visible dialogue.
    @Test
    fun unsupportedTagsAreConsumedNotRendered() {
        val trace = drive("\\hA\\4\\5\\6\\v\\_n\\_V\\_l[half]B\\e")
        assertEquals(listOf("A", "AB"), trace.distinctSakuraText())
    }

    // Mutation caught: escaped or terminal backslashes are rendered instead of recovered and consumed.
    @Test
    fun escapedAndTrailingBackslashesAreConsumedLikeRunnerRecovery() {
        assertEquals("AB", drive("\\hA\\\\B\\e").authoredTextBeforeStop())
        assertEquals("A", drive("\\hA\\").authoredTextBeforeStop())

        assertEquals(
            "AB",
            drive("\\hA\\p[malformed\\hB\\e").authoredTextBeforeStop(),
        )
    }

    // Mutation caught: a non-animation transition emits a one-shot cue.
    @Test
    fun animationCueAppearsOnlyWhenPlayerSchedulesIt() {
        val trace = drive("\\hA\\i[3]B\\e")
        assertEquals(
            listOf(PlayerEffect.PresentationCue(GhostSpeaker.SAKURA, RuntimeCueKind.ONE_SHOT, "3")),
            trace.effects.filterIsInstance<PlayerEffect.PresentationCue>()
                .filter { it.kind == RuntimeCueKind.ONE_SHOT },
        )
    }

    // Mutation caught: choice inventory order follows speaker grouping rather than source order.
    @Test
    fun inputAndChoiceActionsKeepSourceOrder() {
        val trace = drive("\\q[One,id]A\\![open,inputbox,name]", stopOnAction = true)
        val choiceId = trace.state.dialogue.choices.single().key.actionId
        val inputId = trace.state.dialogue.input!!.key.actionId
        assertTrue(choiceId < inputId)
    }

    // Mutation caught: input tags remain visible text or normalize away the exact ID.
    @Test
    fun inputBoxIsConsumedAndRetainsStableActionId() {
        val opened = firstAdvance("\\hA\\![open,inputbox,user-name]B\\e")
        var transition = opened
        while (transition.state.dialogue.input == null) transition = advance(transition.state, 0L)
        val input = requireNotNull(transition.state.dialogue.input)
        assertEquals("user-name", (input.pending.spec.dispatch as InputDispatch.Normal).id)
        assertFalse(transition.state.presentation.sakura.text.contains("user-name"))
    }

    // Mutation caught: plain text is rewritten merely because the dialogue projector is active.
    @Test
    fun scriptWithoutInteractionsRemainsUntouched() {
        val trace = drive("\\hplain text\\e")
        assertEquals("plain text", trace.authoredTextBeforeStop())
        assertTrue(trace.state.dialogue.choices.isEmpty())
        assertNull(trace.state.dialogue.input)
    }

    // Mutation caught: the first input command greedily consumes a later input command.
    @Test
    fun inputBoxesParseIndividually() {
        var transition = firstAdvance("\\![open,inputbox,first]X\\![open,inputbox,second]")
        val first = transition.state.dialogue.input!!
        transition = SakuraScriptPlayer.reduce(transition.state, PlayerCommand.SubmitInput(first.key, "one"))
        while (transition.state.dialogue.input == null) transition = advance(transition.state, 0L)
        assertEquals(
            "second",
            (requireNotNull(transition.state.dialogue.input).pending.spec.dispatch as InputDispatch.Normal).id,
        )
        assertEquals("X", transition.state.presentation.sakura.text)
    }

    // Mutation caught: adopting the next script resets surfaces together with transient text/balloons.
    @Test
    fun scriptResetKeepsSurfacesAndClearsTransientPresentation() {
        val trace = drive("\\u\\s[42]K\\b[7]\\e", autoRespond = true)
        val next = driveFrom(trace.state, "\\hN\\e")
        assertEquals("0", next.state.presentation.sakura.surfaceId)
        assertEquals("42", next.state.presentation.kero.surfaceId)
        assertFalse(next.state.presentation.kero.balloonVisible)
    }

    // Mutation caught: synchronized Kero output stays hidden or clearing Sakura clears Kero's balloon.
    @Test
    fun synchronizationAndKeroTextPreserveBalloonPolicy() {
        val trace = drive("\\h\\_sA\\c\\e")
        val authored = trace.states.first {
            it.presentation.sakura.text == "A" && it.presentation.kero.text == "A"
        }
        assertEquals("A", authored.presentation.sakura.text)
        assertTrue(authored.presentation.kero.balloonVisible)
        val cleared = trace.states.last { it.presentation.sakura.text.isEmpty() && it.presentation.kero.text == "A" }
        assertTrue(cleared.presentation.kero.balloonVisible)
    }

    // Mutation caught: talking cues emit on every frame or synchronized reveal cues only one speaker.
    @Test
    fun talkingCuesFollowTenFrameCadenceAndSynchronizeSpeakers() {
        val cadence = drive("\\habcdefghijk\\e")
        assertEquals(
            listOf(GhostSpeaker.SAKURA, GhostSpeaker.SAKURA),
            cadence.effects.filterIsInstance<PlayerEffect.PresentationCue>()
                .filter { it.kind == RuntimeCueKind.TALKING }
                .map { it.speaker },
        )

        val synchronized = drive("\\h\\_sA\\e")
        assertEquals(
            listOf(GhostSpeaker.SAKURA, GhostSpeaker.KERO),
            synchronized.effects.filterIsInstance<PlayerEffect.PresentationCue>()
                .filter { it.kind == RuntimeCueKind.TALKING }
                .map { it.speaker },
        )
    }

    // Mutation caught: reselecting Sakura always clears it, including when it is already current.
    @Test
    fun reselectingCurrentSpeakerRetainsText() {
        val trace = drive("\\hA\\hB\\uC\\hD\\e")
        assertTrue(trace.states.any { it.presentation.sakura.text == "AB" })
        assertTrue(trace.states.any { it.presentation.sakura.text == "D" && it.presentation.kero.text == "C" })
    }

    // Mutation caught: a one-shot animation cue is retained in durable presentation or emitted twice.
    @Test
    fun animationBecomesOneLeaseScopedCue() {
        val trace = drive("\\i[3]A\\e")
        assertEquals(1, trace.effects.filterIsInstance<PlayerEffect.PresentationCue>()
            .count { it.kind == RuntimeCueKind.ONE_SHOT && it.animationId == "3" })
        assertFalse(trace.state.presentation.sakura.text.contains("3"))
    }

    // Mutation caught: final stop fails to clear transient text while preserving final surfaces.
    @Test
    fun textSurfaceAnimationAndStopMatchOrderedTransitions() {
        assertEquals(
            listOf(
                "A:0:null::10:null",
                "A:120:null::10:null",
                "A:120:3::10:null",
                "A:120:null:B:10:null",
                "A:120:null:B:11:null",
                "A:120:null:B:11:4",
                "A:120:null:B:11:null",
                ":120:null::11:null",
            ),
            orderedFrames("\\hA\\s[120]\\i[3]\\uB\\s[11]\\i[4]\\e"),
        )
    }

    // Mutation caught: repeated selectors or newline modifiers clear/replace visible text.
    @Test
    fun repeatedSpeakerAndNewlineKeepVisibleText() {
        val trace = drive("\\hA\\hB\\n[half]C\\e")
        assertEquals(listOf("A", "AB", "AB\n", "AB\nC"), trace.distinctSakuraText())
    }

    // Mutation caught: explicit animation still emits a talking cue for the same presentation step.
    @Test
    fun explicitAnimationSuppressesTalkingCueAndKeepsBalloonText() {
        val trace = drive("\\hhello\\i[3]\\e")
        val explicitIndex = trace.effects.indexOfFirst {
            it == PlayerEffect.PresentationCue(GhostSpeaker.SAKURA, RuntimeCueKind.ONE_SHOT, "3")
        }
        assertTrue(explicitIndex >= 0)
        assertFalse(trace.effects.drop(explicitIndex).take(2).any {
            it == PlayerEffect.PresentationCue(GhostSpeaker.SAKURA, RuntimeCueKind.TALKING, null)
        })
        assertTrue(trace.states.any { it.presentation.sakura.text == "hello" && it.presentation.sakura.balloonVisible })
    }

    // Mutation caught: an empty authoritative surface ID reaches a runtime presentation snapshot.
    @Test
    fun missingSurfaceIdIsRejectedAtPlayerBoundary() {
        val failed = firstAdvance("\\s[]\\e")
        assertEquals(PlayerEffect.Failure(null, RuntimeNoticeCode.PLAYER_FAILED), failed.effects.single())
        assertEquals("0", failed.state.presentation.sakura.surfaceId)
    }

    private fun firstAdvance(
        script: String,
        parent: PlayerParent? = null,
        elapsedMillis: Long = 0L,
    ): PlayerTransition {
        val enqueued = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue(script, parent),
        )
        return advance(enqueued.state, elapsedMillis)
    }

    private fun advance(state: PlayerState, elapsedMillis: Long): PlayerTransition =
        SakuraScriptPlayer.reduce(state, PlayerCommand.Advance(state.playbackToken, elapsedMillis))

    private fun driveFrom(state: PlayerState, script: String): DriveTrace {
        val enqueued = SakuraScriptPlayer.reduce(state, PlayerCommand.Enqueue(script, null))
        return drive(enqueued.state)
    }

    private fun drive(
        script: String,
        parent: PlayerParent? = null,
        autoRespond: Boolean = true,
        stopOnAction: Boolean = false,
    ): DriveTrace {
        val enqueued = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue(script, parent),
        )
        return drive(enqueued.state, enqueued.effects, autoRespond, stopOnAction)
    }

    private fun drive(
        start: PlayerState,
        initialEffects: List<PlayerEffect> = emptyList(),
        autoRespond: Boolean = true,
        stopOnAction: Boolean = false,
    ): DriveTrace {
        var state = start
        var elapsed = 0L
        var requestId = 0L
        val states = mutableListOf(state)
        val effects = initialEffects.toMutableList()
        repeat(500) {
            if (stopOnAction && (state.dialogue.input != null || state.current == null && state.queue.isEmpty())) {
                return DriveTrace(state, states, effects)
            }
            val pending = state.authoredRequest
            val transition = if (pending != null && autoRespond) {
                SakuraScriptPlayer.reduce(
                    state,
                    PlayerCommand.NativeResponse(
                        RuntimeRequestToken(state.generation, ++requestId, null, pending),
                        PlayerResponse.Returned(response(204)),
                    ),
                )
            } else {
                if (pending != null || state.dialogue.input != null) return DriveTrace(state, states, effects)
                if (state.current == null && state.queue.isEmpty()) return DriveTrace(state, states, effects)
                val scheduled = effects.filterIsInstance<PlayerEffect.SchedulePlayback>().lastOrNull()
                    ?: PlayerEffect.SchedulePlayback(state.playbackToken, 0L)
                elapsed = saturatingAdd(elapsed, scheduled.delayMillis)
                SakuraScriptPlayer.reduce(
                    state,
                    PlayerCommand.Advance(state.playbackToken, elapsed),
                )
            }
            state = transition.state
            states += state
            effects += transition.effects
        }
        throw AssertionError("player did not reach a terminal")
    }

    private fun orderedFrames(script: String): List<String> {
        var state = SakuraScriptPlayer.reduce(
            PlayerState.initial(4),
            PlayerCommand.Enqueue(script, null),
        ).state
        var requestId = 0L
        val frames = mutableListOf<String>()
        repeat(100) {
            if (state.current == null && state.queue.isEmpty()) return frames
            val authoredRequest = state.authoredRequest
            val transition = if (authoredRequest != null) {
                SakuraScriptPlayer.reduce(
                    state,
                    PlayerCommand.NativeResponse(
                        RuntimeRequestToken(state.generation, ++requestId, null, authoredRequest),
                        PlayerResponse.Returned(response(204)),
                    ),
                )
            } else {
                SakuraScriptPlayer.reduce(
                    state,
                    PlayerCommand.Advance(state.playbackToken, 0L),
                ).also { advanced ->
                    val animation = advanced.effects.filterIsInstance<PlayerEffect.PresentationCue>()
                        .singleOrNull { it.kind == RuntimeCueKind.ONE_SHOT }
                    val presentation = advanced.state.presentation
                    frames += buildString {
                        append(presentation.sakura.text).append(':')
                        append(presentation.sakura.surfaceId).append(':')
                        append(animation?.takeIf { it.speaker == GhostSpeaker.SAKURA }?.animationId).append(':')
                        append(presentation.kero.text).append(':')
                        append(presentation.kero.surfaceId).append(':')
                        append(animation?.takeIf { it.speaker == GhostSpeaker.KERO }?.animationId)
                    }
                }
            }
            state = transition.state
        }
        throw AssertionError("player did not reach a terminal")
    }

    private fun PlayerTransition.schedule(): PlayerEffect.SchedulePlayback =
        effects.filterIsInstance<PlayerEffect.SchedulePlayback>().single()

    private data class DriveTrace(
        val state: PlayerState,
        val states: List<PlayerState>,
        val effects: List<PlayerEffect>,
    ) {
        fun distinctSakuraText(): List<String> = states.map { it.presentation.sakura.text }
            .filter(String::isNotEmpty)
            .fold(mutableListOf()) { result, value ->
                if (result.lastOrNull() != value) result += value
                result
            }

        fun authoredTextBeforeStop(): String = states.map { it.presentation.sakura.text }.maxBy(String::length)

        fun visibleEvents(): List<String> {
            val result = mutableListOf<String>()
            var previous = states.first().presentation
            var effectIndex = 0
            states.drop(1).forEach { player ->
                val next = player.presentation
                if (next.sakura.text != previous.sakura.text && next.sakura.text.isNotEmpty()) {
                    result += "text:sakura:${next.sakura.text.replace("\n", "\\n")}"
                }
                if (next.kero.text != previous.kero.text && next.kero.text.isNotEmpty()) {
                    result += "text:kero:${next.kero.text.replace("\n", "\\n")}"
                }
                if (next.sakura.surfaceId != previous.sakura.surfaceId) result += "surface:sakura:${next.sakura.surfaceId}"
                if (next.kero.surfaceId != previous.kero.surfaceId) result += "surface:kero:${next.kero.surfaceId}"
                val nextStateIndex = states.indexOf(player)
                val effectsBeforeNextState = effects.drop(effectIndex).takeWhile { effect ->
                    effect !is PlayerEffect.SchedulePlayback || effect.token <= player.playbackToken
                }
                effectsBeforeNextState.filterIsInstance<PlayerEffect.PresentationCue>()
                    .filter { it.kind == RuntimeCueKind.ONE_SHOT }
                    .forEach { cue -> result += "animation:${cue.speaker.name.lowercase()}:${cue.animationId}" }
                effectIndex += effectsBeforeNextState.size
                previous = next
            }
            // The state/effect correlation above intentionally ignores scheduling details; make
            // any one-shot cues not yet associated with a state visible in their source order.
            val recordedAnimations = result.count { it.startsWith("animation:") }
            effects.filterIsInstance<PlayerEffect.PresentationCue>()
                .filter { it.kind == RuntimeCueKind.ONE_SHOT }
                .drop(recordedAnimations)
                .forEach { cue -> result += "animation:${cue.speaker.name.lowercase()}:${cue.animationId}" }
            return result
        }
    }

    private fun response(status: Int, value: String? = null): ShioriResponse = ShioriResponse(
        "SHIORI/3.0 $status ${if (status == 200) "OK" else "No Content"}",
        Hashtable<String, String>().apply { if (value != null) put("Value", value) },
    )

    private fun ShioriRequestIntent.eventId(): String = protocolText.lineSequence()
        .first { it.startsWith("ID: ") }
        .removePrefix("ID: ")

    private fun ShioriRequestIntent.references(): List<String> = protocolText.lineSequence()
        .filter { it.startsWith("Reference") }
        .map { it.substringAfter(": ") }
        .toList()

    private fun DialogueAction.label(): String = when (this) {
        is DialogueAction.Normal -> label
        is DialogueAction.DirectEvent -> label
        is DialogueAction.Script -> label
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertUnmodifiable(values: List<*>) {
        assertThrows(UnsupportedOperationException::class.java) {
            (values as MutableList<Any?>).add(null)
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
