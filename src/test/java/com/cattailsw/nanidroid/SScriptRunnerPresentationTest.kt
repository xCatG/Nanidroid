package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSpeakerOwnership
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.shiori.Shiori
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.util.ArrayDeque

/** Characterizes the UI-free runtime-to-renderer presentation trace.  */
class SScriptRunnerPresentationTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun emitsTextSurfaceAndOneShotAnimationFramesWithoutAndroidViews() {
        val frames: MutableList<String> = ArrayList<String>()
        val runner: com.cattailsw.nanidroid.SScriptRunner =
            com.cattailsw.nanidroid.SScriptRunner(null)
        runner.setNoWaitMode(true)
        // Production installs the Compose-backed adapter through this seam;
        // the runtime trace must remain independent of the chosen UI toolkit.
        runner.setPresentationRenderer(object :
            com.cattailsw.nanidroid.GhostPresentationRenderer {
            public override fun render(frame: com.cattailsw.nanidroid.GhostPresentationFrame) {
                frames.add(
                    frame.sakura.text + ":" + frame.sakura.surfaceId + ":" +
                            frame.sakura.animationId + ":" + frame.kero.text + ":" +
                            frame.kero.surfaceId + ":" + frame.kero.animationId
                )
            }
        })

        runner.addMsgToQueue(arrayOf<String>("\\hA\\s[120]\\i[3]\\uB\\s[11]\\i[4]\\e"))
        runner.run()

        Assert.assertEquals(
            mutableListOf<String>(
                "A:0:null::10:null",
                "A:120:null::10:null",
                "A:120:3::10:null",
                "A:120:null:B:10:null",
                "A:120:null:B:11:null",
                "A:120:null:B:11:4",
                "A:120:null:B:11:null",
                ":120:null::11:null"
            ),
            frames
        )
    }

    @Test
    fun attachingAReplacementRendererRepublishesTheCurrentFrameWithoutANewScriptEvent() {
        val firstFrames = mutableListOf<GhostPresentationFrame>()
        val runner = SScriptRunner(null)
        runner.setNoWaitMode(true)
        runner.setPresentationRenderer { firstFrames += it }
        runner.addMsgToQueue(arrayOf("\\hCurrent frame\\s[120]\\e"))
        runner.run()
        val current = firstFrames.last()
        val replacementFrames = mutableListOf<GhostPresentationFrame>()

        runner.setPresentationRenderer { replacementFrames += it }

        Assert.assertEquals(listOf(current), replacementFrames)
    }

    @Test
    fun firstAttachmentRetainsScriptsQueuedBeforeTheGhostIsReady() {
        val clock = FakeClock(10_000L)
        val runner = SScriptRunner(null, GhostSessionCoordinator(), clock)
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\hfirst queued talk\\e"))

        runner.setGhost(RecordingGhost(RecordingShiori(emptyList())))
        runner.run()

        Assert.assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text("first queued talk")),
                ),
            ),
            runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun quotedPassiveModeCommandsChangeTheRuntimeMode() {
        val runner = SScriptRunner(null)
        runner.setNoWaitMode(true)

        runner.addMsgToQueue(arrayOf("\\![\"enter\",passivemode]\\e"))
        runner.run()

        Assert.assertTrue(runner.runtimeModeSnapshot().passive)

        runner.addMsgToQueue(arrayOf("\\![\"leave\",passivemode]\\e"))
        runner.run()

        Assert.assertFalse(runner.runtimeModeSnapshot().passive)
    }

    @Test
    fun malformedAndEscapedPassiveModeLeavesDoNotDisablePassiveMode() {
        val runner = SScriptRunner(null)
        runner.setNoWaitMode(true)

        runner.addMsgToQueue(arrayOf("\\![\"enter\",passivemode]\\e"))
        runner.run()

        runner.addMsgToQueue(arrayOf("\\![leave,passivemode,unexpected]\\e"))
        runner.run()

        Assert.assertTrue(runner.runtimeModeSnapshot().passive)

        runner.addMsgToQueue(arrayOf("\\![leave\\,passivemode]\\e"))
        runner.run()

        Assert.assertTrue(runner.runtimeModeSnapshot().passive)
    }

    @Test
    fun normalChoiceUsesExactRawHeadersAndFallsBackOnlyAfterNoTalk() {
        val fixture = fixture(responses = listOf(noContent(), noContent()))

        fixture.runner.activateChoice(fixture.openChoice("Choice", "choice-id", "", "extra"))

        Assert.assertEquals(
            listOf(
                request("OnChoiceSelectEx", "Choice", "choice-id", "", "extra"),
                request("OnChoiceSelect", "choice-id"),
            ),
            fixture.shiori.requests,
        )
    }

    @Test
    fun nonEmptyControlOnlyChoiceResponseSuppressesLegacyFallback() {
        val fixture = fixture(responses = listOf(talk("\\e")))

        fixture.runner.activateChoice(fixture.openChoice("Choice", "choice-id"))

        Assert.assertEquals(listOf(request("OnChoiceSelectEx", "Choice", "choice-id")), fixture.shiori.requests)
    }

    @Test
    fun nonEmptyActualTalkChoiceResponseIsRetainedAndPlayedWithoutFallback() {
        val fixture = fixture(responses = listOf(talk("\\hplayed talk\\e")))

        fixture.runner.activateChoice(fixture.openChoice("Choice", "choice-id"))

        Assert.assertEquals(listOf(request("OnChoiceSelectEx", "Choice", "choice-id")), fixture.shiori.requests)
        Assert.assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text("played talk")),
                ),
            ),
            fixture.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun speakerReentryProjectsLatestContentForPreviouslyActiveGhost() {
        val fixture = fixture(responses = listOf(noContent()))

        fixture.runner.addMsgToQueue(arrayOf("\\hFirst\\uReply\\hSecond\\e"))
        fixture.runner.run()
        val ownership = DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
        val sakura = ownership.content(GhostSpeaker.SAKURA).segments
        val kero = ownership.content(GhostSpeaker.KERO).segments

        Assert.assertEquals(listOf(DialogueSegment.Text("Second")), sakura)
        Assert.assertEquals(listOf(DialogueSegment.Text("Reply")), kero)
    }

    @Test
    fun repeatedKeroSelectorClearsProjectedTextLikePlayback() {
        val fixture = fixture(responses = listOf(noContent()))

        fixture.runner.addMsgToQueue(arrayOf("\\uFirst\\uSecond\\e"))
        fixture.runner.run()

        val ownership = DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
        Assert.assertEquals(
            listOf(DialogueSegment.Text("Second")),
            ownership.content(GhostSpeaker.KERO).segments,
        )
        Assert.assertTrue(ownership.content(GhostSpeaker.SAKURA).segments.isEmpty())
    }

    @Test
    fun synchronizedTextProjectsToBothSpeakersLikePlayback() {
        val fixture = fixture(responses = listOf(noContent()))

        fixture.runner.addMsgToQueue(arrayOf("\\uOld\\h\\_sBoth\\e"))
        fixture.runner.run()

        val ownership = DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
        Assert.assertEquals(
            listOf(DialogueSegment.Text("Both")),
            ownership.content(GhostSpeaker.SAKURA).segments,
        )
        Assert.assertEquals(
            listOf(DialogueSegment.Text("OldBoth")),
            ownership.content(GhostSpeaker.KERO).segments,
        )
    }

    @Test
    fun keroStartedSynchronizedOutputProjectsTextAndNewlinesToBothSpeakersLikePlayback() {
        val fixture = fixture(responses = listOf(noContent()))

        fixture.runner.addMsgToQueue(arrayOf("\\u\\_sKero\\nBoth\\_s\\e"))
        fixture.runner.run()

        val expected = listOf(
            DialogueSegment.Text("Kero"),
            DialogueSegment.NewLine,
            DialogueSegment.Text("Both"),
        )
        val ownership = DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
        Assert.assertEquals(expected, ownership.content(GhostSpeaker.SAKURA).segments)
        Assert.assertEquals(expected, ownership.content(GhostSpeaker.KERO).segments)
    }

    @Test
    fun inputBeforeLaterSpeakerReentryRemainsCanonicalWhenPlaybackPauses() {
        val fixture = fixture(responses = emptyList())
        fixture.runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit
            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })

        fixture.runner.addMsgToQueue(arrayOf("\\h\\![open,inputbox,answer]\\uReply\\hSecond\\e"))
        fixture.runner.run()

        val pending = requireNotNull(fixture.runner.dialogueStateSnapshot().pendingInput)
        Assert.assertEquals(GhostSpeaker.SAKURA, pending.owner)
        Assert.assertEquals("answer", (pending.spec.dispatch as InputDispatch.Normal).id)
        Assert.assertSame(
            pending,
            DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
                .pendingInput(GhostSpeaker.SAKURA),
        )
    }

    @Test
    fun directAndScriptChoicesSendOnlyTheirAuthoredBehavior() {
        val direct = fixture(responses = listOf(noContent()))
        val directAction = direct.openChoices("\\q[Direct,OnDirect,\"\",tail]\\e").single()
        direct.runner.activateChoice(directAction)
        Assert.assertEquals(listOf(request("OnDirect", "", "tail")), direct.shiori.requests)

        val local = fixture(responses = emptyList())
        val revision = local.runner.dialogueStateSnapshot().revision
        val localAction = local.openChoices("\\q[Local,\"script:\\hlocal talk\\e\"]\\e").single()
        local.runner.activateChoice(localAction)
        Assert.assertTrue(local.runner.dialogueStateSnapshot().revision > revision)
        Assert.assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text("local talk")),
                ),
            ),
            local.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun normalAndDirectAnchorsUseTheirExactEventsWithoutSpeakerReferences() {
        val normal = fixture(responses = listOf(noContent(), noContent()))

        normal.runner.activateAnchor(normal.openAnchor("Anchor", "anchor-id", "", "tail"))

        Assert.assertEquals(
            listOf(
                request("OnAnchorSelectEx", "Anchor", "anchor-id", "", "tail"),
                request("OnAnchorSelect", "anchor-id"),
            ),
            normal.shiori.requests,
        )

        val direct = fixture(responses = listOf(talk("\\e")))
        direct.runner.activateAnchor(direct.openAnchor("Anchor", "OnAnchor", "", "tail"))

        Assert.assertEquals(listOf(request("OnAnchor", "", "tail")), direct.shiori.requests)
    }

    @Test
    fun nonEmptyActualTalkAnchorResponseIsRetainedAndSuppressesLegacyFallback() {
        val fixture = fixture(responses = listOf(talk("\\hanchor talk\\e")))

        fixture.runner.activateAnchor(fixture.openAnchor("Anchor", "anchor-id"))

        Assert.assertEquals(
            listOf(request("OnAnchorSelectEx", "Anchor", "anchor-id")),
            fixture.shiori.requests,
        )
        Assert.assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text("anchor talk")),
                ),
            ),
            fixture.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun normalAndDirectInputSubmissionUseExactReferencesAndFenceStaleGenerations() {
        val normal = fixture(responses = listOf(noContent()))
        val pending = normal.openPendingInput("answer", timeoutMillis = 1_000L)

        normal.runner.submitInput(pending.generation + 1, "ignored")
        normal.runner.submitInput(pending.generation, "value")

        Assert.assertEquals(
            listOf(request("OnUserInput", "answer", "value", "", "", "tail")),
            normal.shiori.requests,
        )
        Assert.assertEquals(null, normal.runner.dialogueStateSnapshot().pendingInput)

        val direct = fixture(responses = listOf(noContent()))
        val directPending = direct.openPendingInput(
            "OnReply",
            timeoutMillis = 1_000L,
            namedSupplement = "named supplement",
        )
        direct.runner.submitInput(directPending.generation, "value")

        Assert.assertEquals(listOf(request("OnReply", "value", "named supplement", "", "tail")), direct.shiori.requests)
    }

    @Test
    fun closeAndTimeoutTerminalizeBeforeDispatchAndTimeoutFallsBackOnlyWithoutTalk() {
        val close = fixture(responses = listOf(noContent()))
        close.shiori.beforeResponse = { Assert.assertEquals(null, close.runner.dialogueStateSnapshot().pendingInput) }
        val closePending = close.openPendingInput("answer", timeoutMillis = 1_000L)

        close.runner.dismissInput(closePending.generation)
        close.runner.dismissInput(closePending.generation)
        close.clock.value += 2_000L
        close.runner.processExpiredInput()

        Assert.assertEquals(
            listOf(request("OnUserInputCancel", "answer", "close", "", "", "tail")),
            close.shiori.requests,
        )

        val timeout = fixture(responses = listOf(noContent(), noContent()))
        timeout.shiori.beforeResponse = { Assert.assertEquals(null, timeout.runner.dialogueStateSnapshot().pendingInput) }
        val timeoutPending = timeout.openPendingInput("answer", timeoutMillis = 1_000L)
        timeout.clock.value = timeoutPending.deadlineElapsedMillis

        timeout.runner.processExpiredInput()
        timeout.runner.processExpiredInput()

        Assert.assertEquals(
            listOf(
                request("OnUserInputCancel", "answer", "timeout", "", "", "tail"),
                request("OnUserInput", "answer", "timeout", "", "", "tail"),
            ),
            timeout.shiori.requests,
        )
    }

    @Test
    fun hostReattachmentPreservesGenerationAndAbsoluteDeadlineUntilExactlyOneTimeout() {
        val fixture = fixture(responses = listOf(talk("\\e")))
        fixture.runner.addMsgToQueue(
            arrayOf(
                "\\q[Choice,choice-id,\"\",tail]" +
                    "\\![open,inputbox,answer,1000,\"initial text\",--reference=\"\",--reference=tail]\\e",
            ),
        )
        fixture.runner.run()
        val pending = requireNotNull(fixture.runner.dialogueStateSnapshot().pendingInput)
        val beforeReattach = fixture.runner.dialogueStateSnapshot()

        // Host recreation is a read/reattach operation: the runner remains the owner.
        val reattached = fixture.runner.dialogueStateSnapshot()
        Assert.assertEquals(beforeReattach.revision, reattached.revision)
        Assert.assertEquals(
            listOf(DialogueAction.Normal("Choice", "choice-id", listOf("", "tail"))),
            reattached.pendingChoices,
        )
        Assert.assertEquals(pending.generation, reattached.pendingInput!!.generation)
        Assert.assertEquals(pending.deadlineElapsedMillis, reattached.pendingInput.deadlineElapsedMillis)

        fixture.clock.value = pending.deadlineElapsedMillis - 1L
        fixture.runner.processExpiredInput()
        Assert.assertTrue(fixture.shiori.requests.isEmpty())
        fixture.clock.value += 1L
        fixture.runner.processExpiredInput()
        fixture.runner.processExpiredInput()

        Assert.assertEquals(
            listOf(request("OnUserInputCancel", "answer", "timeout", "", "", "tail")),
            fixture.shiori.requests,
        )
    }

    @Test
    fun omittedNonPositiveAndOverflowingInputDeadlinesAreUnlimited() {
        val fixture = fixture(responses = emptyList())

        Assert.assertEquals(Long.MAX_VALUE, fixture.openPendingInput("zero", timeoutMillis = 0L).deadlineElapsedMillis)
        Assert.assertEquals(Long.MAX_VALUE, fixture.openPendingInput("negative", timeoutMillis = -1L).deadlineElapsedMillis)
        Assert.assertEquals(Long.MAX_VALUE, fixture.openPendingInput("overflow", timeoutMillis = Long.MAX_VALUE).deadlineElapsedMillis)
        fixture.runner.addMsgToQueue(arrayOf("\\![open,inputbox,omitted]\\e"))
        fixture.runner.run()
        Assert.assertEquals(Long.MAX_VALUE, fixture.runner.dialogueStateSnapshot().pendingInput!!.deadlineElapsedMillis)
    }

    @Test
    fun unrelatedTalkAndChoiceTalkRetainAnExistingPendingInputExactly() {
        val fixture = fixture(responses = emptyList())
        val pending = fixture.openPendingInput("answer", timeoutMillis = 1_000L)

        fixture.runner.addMsgToQueue(arrayOf("\\hinterruption\\e"))
        fixture.runner.run()
        Assert.assertEquals(pending, fixture.runner.dialogueStateSnapshot().pendingInput)
        Assert.assertSame(
            pending,
            DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
                .pendingInput(GhostSpeaker.SAKURA),
        )

        fixture.runner.addMsgToQueue(arrayOf("\\q[Choice,choice-id]\\e"))
        fixture.runner.run()
        Assert.assertEquals(pending, fixture.runner.dialogueStateSnapshot().pendingInput)
        Assert.assertSame(
            pending,
            DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
                .pendingInput(GhostSpeaker.SAKURA),
        )
    }

    @Test
    fun choicesAreOneOfAndAnchorsRemainReusableWhenNoTalkArrives() {
        val choices = fixture(responses = listOf(noContent(), noContent()))
        val siblings = choices.openChoices("\\q[First,first]\\q[Second,second]\\e")

        choices.runner.activateChoice(siblings[0])
        choices.runner.activateChoice(siblings[1])
        Assert.assertEquals(
            listOf(
                request("OnChoiceSelectEx", "First", "first"),
                request("OnChoiceSelect", "first"),
            ),
            choices.shiori.requests,
        )

        val anchors = fixture(responses = listOf(noContent(), noContent(), noContent(), noContent()))
        val anchor = anchors.openAnchor("Anchor", "anchor-id")
        anchors.runner.activateAnchor(anchor)
        anchors.runner.activateAnchor(anchor)
        Assert.assertEquals(
            listOf(
                request("OnAnchorSelectEx", "Anchor", "anchor-id"),
                request("OnAnchorSelect", "anchor-id"),
                request("OnAnchorSelectEx", "Anchor", "anchor-id"),
                request("OnAnchorSelect", "anchor-id"),
            ),
            anchors.shiori.requests,
        )
    }

    @Test
    fun actionsAreIdentityFencedAndNewTalkAndGhostTransitionsClearOldState() {
        val fixture = fixture(responses = listOf(noContent()))
        val old = fixture.openChoice("Old", "old-id")
        val current = fixture.openChoice("Current", "current-id")

        fixture.runner.activateChoice(old)
        fixture.runner.activateChoice(DialogueAction.Normal("Current", "current-id", emptyList()))
        Assert.assertTrue(fixture.shiori.requests.isEmpty())

        fixture.runner.activateChoice(current)
        Assert.assertEquals(
            listOf(
                request("OnChoiceSelectEx", "Current", "current-id"),
                request("OnChoiceSelect", "current-id"),
            ),
            fixture.shiori.requests,
        )

        val anchor = fixture.openAnchor("Anchor", "anchor-id")
        fixture.runner.addMsgToQueue(arrayOf("\\hnew talk\\e"))
        fixture.runner.run()
        fixture.runner.activateAnchor(anchor)
        Assert.assertEquals(2, fixture.shiori.requests.size)

        val pending = fixture.openChoice("Pending", "pending-id")
        fixture.runner.setGhost(null)
        Assert.assertEquals(null, fixture.runner.dialogueStateSnapshot().pendingInput)
        Assert.assertTrue(fixture.runner.dialogueStateSnapshot().pendingChoices.isEmpty())
        fixture.runner.activateChoice(pending)
        Assert.assertEquals(2, fixture.shiori.requests.size)
    }

    @Test
    fun dialogBindingStaleInputCallbacksCannotConsumeRepeatedInputIds() {
        val fixture = fixture(responses = listOf(noContent(), noContent()))
        val binding = DialogueDialogBinding { fixture.runner }
        val first = fixture.openPendingInput("same-id", timeoutMillis = 1_000L)
        var editedValue: String? = null
        val firstDialog = binding.userInput("same-id", first.generation) { editedValue = it }

        firstDialog.onValueChanged("edited")
        Assert.assertEquals("edited", editedValue)

        fixture.runner.dismissInput(first.generation)
        val current = fixture.openPendingInput("same-id", timeoutMillis = 1_000L)
        val requestsBeforeStaleCallback = fixture.shiori.requests.toList()

        firstDialog.onSubmit("same-id", "stale")
        firstDialog.onCancel()

        Assert.assertEquals(requestsBeforeStaleCallback, fixture.shiori.requests)
        Assert.assertEquals(current, fixture.runner.dialogueStateSnapshot().pendingInput)
    }

    @Test
    fun dialogBindingChoiceCallbacksKeepExactRowIdentityForDuplicatesDirectAndScript() {
        val duplicate = fixture(responses = listOf(noContent()))
        val duplicateBinding = DialogueDialogBinding { duplicate.runner }
        val duplicateActions = duplicate.openChoices("\\q[First,same]\\q[Second,same]\\e")
        val duplicateDialog = duplicateBinding.userChoice(
            listOf("First", "Second"), listOf("same", "same"), duplicateActions,
        )

        duplicateDialog.onChoice(1)

        Assert.assertEquals(
            listOf(
                request("OnChoiceSelectEx", "Second", "same"),
                request("OnChoiceSelect", "same"),
            ),
            duplicate.shiori.requests,
        )

        val direct = fixture(responses = listOf(noContent()))
        val directActions = direct.openChoices("\\q[Direct,OnDirect,\"\",tail]\\e")
        DialogueDialogBinding { direct.runner }.userChoice(
            listOf("Direct"), listOf("OnDirect"), directActions,
        ).onChoice(0)
        Assert.assertEquals(listOf(request("OnDirect", "", "tail")), direct.shiori.requests)

        val script = fixture(responses = emptyList())
        val scriptActions = script.openChoices("\\q[Script,\"script:\\hlocal\\e\"]\\e")
        DialogueDialogBinding { script.runner }.userChoice(
            listOf("Script"), listOf("script"), scriptActions,
        ).onChoice(0)
        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("local")))),
            script.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun restoredInputDialogRebindsSubmissionAndCancelToTheSameRunnerGeneration() {
        val fixture = fixture(responses = listOf(noContent()))
        val pending = fixture.openPendingInput("answer", timeoutMillis = 1_000L)
        val binding = DialogueDialogBinding { fixture.runner }
        val presented = binding.userInput("answer", pending.generation)
        val restored = requireNotNull(DialogueDialogBinding { fixture.runner }.restoreUserInput(
            "answer",
            requireNotNull(presented.restoration),
            "typed",
        ))

        restored.onSubmit("answer", "typed")

        Assert.assertEquals(
            listOf(request("OnUserInput", "answer", "typed", "", "", "tail")),
            fixture.shiori.requests,
        )
        Assert.assertEquals(null, fixture.runner.dialogueStateSnapshot().pendingInput)

        val cancel = fixture(responses = listOf(noContent()))
        val cancelPending = cancel.openPendingInput("answer", timeoutMillis = 1_000L)
        val cancelPresented = DialogueDialogBinding { cancel.runner }
            .userInput("answer", cancelPending.generation)
        requireNotNull(DialogueDialogBinding { cancel.runner }.restoreUserInput(
            "answer",
            requireNotNull(cancelPresented.restoration),
        )).onCancel()

        Assert.assertEquals(
            listOf(request("OnUserInputCancel", "answer", "close", "", "", "tail")),
            cancel.shiori.requests,
        )
        Assert.assertEquals(null, cancel.runner.dialogueStateSnapshot().pendingInput)
    }

    @Test
    fun restoredChoiceDialogRebindsToTheSameRunnersExactPendingActions() {
        val fixture = fixture(responses = listOf(noContent()))
        val actions = fixture.openChoices("\\q[First,same]\\q[Second,same]\\e")
        val binding = DialogueDialogBinding { fixture.runner }
        val presented = binding.userChoice(
            listOf("First", "Second"),
            listOf("same", "same"),
            actions,
        )
        val restored = DialogueDialogBinding { fixture.runner }.restoreUserChoice(
            listOf("First", "Second"),
            listOf("same", "same"),
            requireNotNull(presented.restoration),
        )

        restored.onChoice(1)

        Assert.assertEquals(
            listOf(request("OnChoiceSelectEx", "Second", "same"), request("OnChoiceSelect", "same")),
            fixture.shiori.requests,
        )
        Assert.assertTrue(fixture.runner.dialogueStateSnapshot().pendingChoices.isEmpty())

        val direct = fixture(responses = listOf(noContent()))
        val directActions = direct.openChoices("\\q[Direct,OnDirect,\"\",tail]\\e")
        val directPresented = DialogueDialogBinding { direct.runner }.userChoice(
            listOf("Direct"),
            listOf("OnDirect"),
            directActions,
        )
        DialogueDialogBinding { direct.runner }.restoreUserChoice(
            listOf("Direct"),
            listOf("OnDirect"),
            requireNotNull(directPresented.restoration),
        ).onChoice(0)
        Assert.assertEquals(listOf(request("OnDirect", "", "tail")), direct.shiori.requests)

        val script = fixture(responses = emptyList())
        val scriptActions = script.openChoices("\\q[Script,\"script:\\hlocal\\e\"]\\e")
        val scriptPresented = DialogueDialogBinding { script.runner }.userChoice(
            listOf("Script"),
            listOf("script"),
            scriptActions,
        )
        DialogueDialogBinding { script.runner }.restoreUserChoice(
            listOf("Script"),
            listOf("script"),
            requireNotNull(scriptPresented.restoration),
        ).onChoice(0)
        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("local")))),
            script.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun restoredInputNeverBindsToAReplacementRunnerWhenGenerationAndIdRepeat() {
        val original = fixture(responses = emptyList())
        val originalPending = original.openPendingInput("same-id", timeoutMillis = 1_000L)
        val presented = DialogueDialogBinding { original.runner }
            .userInput("same-id", originalPending.generation)
        val restoration = requireNotNull(presented.restoration)

        val replacement = fixture(responses = emptyList())
        val replacementPending = replacement.openPendingInput("same-id", timeoutMillis = 1_000L)
        Assert.assertEquals(originalPending.generation, replacementPending.generation)
        val restoredAgainstReplacement = DialogueDialogBinding { replacement.runner }
            .restoreUserInput("same-id", restoration, "stale")

        var lateRunner: SScriptRunner? = null
        val restoredWithoutRunner = DialogueDialogBinding { lateRunner }
            .restoreUserInput("same-id", restoration, "stale")
        lateRunner = replacement.runner

        Assert.assertNull(restoredAgainstReplacement)
        Assert.assertNull(restoredWithoutRunner)

        Assert.assertEquals(replacementPending, replacement.runner.dialogueStateSnapshot().pendingInput)
        Assert.assertTrue(replacement.shiori.requests.isEmpty())
    }

    @Test
    fun restoredChoiceNeverBindsToAReplacementOrLaterRepeatedPrompt() {
        val original = fixture(responses = listOf(noContent()))
        val originalActions = original.openChoices("\\q[First,same]\\q[Second,same]\\e")
        val presented = DialogueDialogBinding { original.runner }.userChoice(
            listOf("First", "Second"),
            listOf("same", "same"),
            originalActions,
        )
        val restoration = requireNotNull(presented.restoration)

        val replacement = fixture(responses = emptyList())
        val replacementActions = replacement.openChoices("\\q[First,same]\\q[Second,same]\\e")
        val restoredAgainstReplacement = DialogueDialogBinding { replacement.runner }.restoreUserChoice(
            listOf("First", "Second"),
            listOf("same", "same"),
            restoration,
        )
        restoredAgainstReplacement.onChoice(1)
        Assert.assertEquals(replacementActions, replacement.runner.dialogueStateSnapshot().pendingChoices)
        Assert.assertTrue(replacement.shiori.requests.isEmpty())

        original.runner.activateChoice(originalActions[0])
        val restoredWithoutMatch = DialogueDialogBinding { original.runner }.restoreUserChoice(
            listOf("First", "Second"),
            listOf("same", "same"),
            restoration,
        )
        val laterActions = original.openChoices("\\q[First,same]\\q[Second,same]\\e")
        restoredWithoutMatch.onChoice(1)

        Assert.assertEquals(laterActions, original.runner.dialogueStateSnapshot().pendingChoices)
        Assert.assertEquals(
            listOf(request("OnChoiceSelectEx", "First", "same"), request("OnChoiceSelect", "same")),
            original.shiori.requests,
        )
    }

    @Test
    fun transitionDuringPrimaryResponsePreventsFallbackAndStaleTalkEnqueue() {
        val fixture = fixture(responses = listOf(noContent()))
        val action = fixture.openChoice("Choice", "choice-id")
        fixture.shiori.beforeResponse = { fixture.runner.setGhost(null) }

        fixture.runner.activateChoice(action)

        Assert.assertEquals(listOf(request("OnChoiceSelectEx", "Choice", "choice-id")), fixture.shiori.requests)
        Assert.assertTrue(fixture.runner.dialogueStateSnapshot().contents.isEmpty())
    }

    @Test
    fun transitionAfterPlayablePrimaryResponseDoesNotEnqueueItOnTheNewGeneration() {
        val fixture = fixture(responses = listOf(talk("\\hstale\\e")))
        val action = fixture.openChoice("Choice", "choice-id")
        fixture.shiori.beforeResponse = { fixture.runner.setGhost(null) }

        fixture.runner.activateChoice(action)

        Assert.assertEquals(listOf(request("OnChoiceSelectEx", "Choice", "choice-id")), fixture.shiori.requests)
        Assert.assertTrue(fixture.runner.dialogueStateSnapshot().contents.isEmpty())
    }

    @Test
    fun transitionAtTheClaimGateCannotSendAnOldGenerationRequest() {
        val fixture = fixture(responses = listOf(noContent()))
        val action = fixture.openChoice("Choice", "choice-id")
        fixture.runner.setDialogueClaimHookForTesting { fixture.runner.setGhost(null) }

        fixture.runner.activateChoice(action)

        Assert.assertTrue(fixture.shiori.requests.isEmpty())
        Assert.assertTrue(fixture.runner.dialogueStateSnapshot().pendingChoices.isEmpty())
        fixture.runner.setDialogueClaimHookForTesting(null)
    }

    @Test
    fun scopeTwoCommandsDoNotCreatePendingActionsBeforeScopeZeroResumes() {
        val fixture = fixture(responses = emptyList())

        fixture.runner.addMsgToQueue(
            arrayOf(
                "\\hvisible\\p2\\q[ignored,id]\\![open,inputbox,ignored,9000,ignored]" +
                    "\\p[2]\\q[also-ignored,id]\\![open,inputbox,still-ignored,9000,ignored]" +
                    "\\p0\\q[usable,id]\\e",
            ),
        )
        fixture.runner.run()

        val state = fixture.runner.dialogueStateSnapshot()
        Assert.assertEquals(listOf(DialogueAction.Normal("usable", "id", emptyList())), state.pendingChoices)
        Assert.assertEquals(null, state.pendingInput)
        Assert.assertFalse(state.contents.flatMap { it.segments }.any { it.toString().contains("ignored") })
    }

    @Test
    fun hiddenScopeInputDoesNotPausePublishOrBlockTrailingVisibleText() {
        val fixture = fixture(responses = emptyList())
        val shownInputIds = mutableListOf<String>()
        fixture.runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                shownInputIds += id
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })

        fixture.runner.addMsgToQueue(arrayOf("\\p2\\![open,inputbox,hidden]\\hAfter\\e"))
        fixture.runner.run()

        val state = fixture.runner.dialogueStateSnapshot()
        Assert.assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text("After")),
                ),
            ),
            state.contents,
        )
        Assert.assertEquals(null, state.pendingInput)
        Assert.assertTrue(shownInputIds.isEmpty())
    }

    @Test
    fun speakerChangeClearDoesNotDropPriorVisibleChoice() {
        val fixture = fixture(responses = emptyList())

        val choices = fixture.openChoices("\\h\\q[Pick,id]\\uReply\\hSecond\\e")

        Assert.assertEquals(listOf(DialogueAction.Normal("Pick", "id", emptyList())), choices)
        fixture.runner.activateChoice(choices.single())

        Assert.assertEquals(
            listOf(request("OnChoiceSelectEx", "Pick", "id"), request("OnChoiceSelect", "id")),
            fixture.shiori.requests,
        )
    }

    @Test
    fun legacyChoiceCallbackIncludesChoicesAfterUnderscoreCommandPayload() {
        val fixture = fixture(responses = emptyList())
        var callbackChoices: Pair<List<String>, List<String>>? = null
        fixture.runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                callbackChoices = textlabel.toList() to ids.toList()
            }
        })

        fixture.runner.addMsgToQueue(arrayOf("\\q[A,a]\\_l[half,\\p2]\\q[B,b]\\e"))
        fixture.runner.run()

        Assert.assertEquals(listOf("A", "B") to listOf("a", "b"), callbackChoices)
    }

    @Test
    fun legacyChoiceCallbackIncludesChoiceInsideBracketAfterPayloadlessUnderscoreQ() {
        val fixture = fixture(responses = emptyList())
        var callbackChoices: Pair<List<String>, List<String>>? = null
        fixture.runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                callbackChoices = textlabel.toList() to ids.toList()
            }
        })

        fixture.runner.addMsgToQueue(arrayOf("\\q[A,a]\\_q[label \\q[B,b]]\\e"))
        fixture.runner.run()

        Assert.assertEquals(listOf("A", "B") to listOf("a", "b"), callbackChoices)
    }

    @Test
    fun directScopeCommandsSwitchSpeakersLikeLegacyAliases() {
        val fixture = fixture(responses = emptyList())

        fixture.runner.addMsgToQueue(arrayOf("\\p1Reply\\p0Second\\e"))
        fixture.runner.run()

        val ownership = DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
        Assert.assertEquals(listOf(DialogueSegment.Text("Second")), ownership.content(GhostSpeaker.SAKURA).segments)
        Assert.assertEquals(listOf(DialogueSegment.Text("Reply")), ownership.content(GhostSpeaker.KERO).segments)
    }

    @Test
    fun hiddenScopeDoesNotChangeTheActiveVisibleSpeaker() {
        val fixture = fixture(responses = emptyList())

        fixture.runner.addMsgToQueue(arrayOf("\\hA\\p2hidden\\p0B\\e"))
        fixture.runner.run()

        val ownership = DialogueSpeakerOwnership.from(fixture.runner.dialogueStateSnapshot())
        Assert.assertEquals(listOf(DialogueSegment.Text("AB")), ownership.content(GhostSpeaker.SAKURA).segments)
        Assert.assertTrue(ownership.content(GhostSpeaker.KERO).segments.isEmpty())
    }

    @Test
    fun hiddenScopeConsumesSurfaceAnimationAndBalloonCommands() {
        val frames = mutableListOf<GhostPresentationFrame>()
        val runner = SScriptRunner(null)
        runner.setNoWaitMode(true)
        runner.setPresentationRenderer { frames += it }

        runner.addMsgToQueue(arrayOf("\\h\\s[0]\\i[3]\\b[-1]\\p2\\s[99]\\i[7]\\b[0]\\_b[0]\\p0\\e"))
        runner.run()

        Assert.assertFalse(frames.any { it.sakura.surfaceId == "99" })
        Assert.assertFalse(frames.any { it.sakura.animationId == "7" })
        Assert.assertFalse(frames.last().sakura.balloonVisible)
    }

    @Test
    fun speakerChangeClearRetiresAnInlineAnchorCapability() {
        val fixture = fixture(responses = emptyList())
        var captured: AnchorAction? = null
        fixture.runner.setDialogueStateObserver { state ->
            if (captured == null) {
                captured = state.contents.asSequence()
                    .flatMap { it.segments.asSequence() }
                    .mapNotNull { (it as? DialogueSegment.Anchor)?.action }
                    .firstOrNull()
            }
        }

        fixture.runner.addMsgToQueue(arrayOf("\\h\\_a[id]Old\\_a\\uReply\\hSecond\\e"))
        fixture.runner.run()
        fixture.runner.activateAnchor(captured ?: throw AssertionError("anchor was never revealed"))

        Assert.assertTrue(fixture.shiori.requests.isEmpty())
    }

    private fun fixture(responses: List<String>): Fixture {
        val clock = FakeClock(10_000L)
        val shiori = RecordingShiori(responses)
        val runner = SScriptRunner(null, GhostSessionCoordinator(), clock)
        runner.setNoWaitMode(true)
        runner.setGhost(RecordingGhost(shiori))
        return Fixture(runner, shiori, clock)
    }

    private fun Fixture.openPendingInput(
        id: String,
        timeoutMillis: Long,
        namedSupplement: String? = null,
    ): PendingInputState {
        val namedSupplementArgument = namedSupplement?.let { ",--supplement=\"$it\"" } ?: ""
        runner.addMsgToQueue(
            arrayOf(
                "\\![open,inputbox,$id,$timeoutMillis,\"initial text\"$namedSupplementArgument," +
                    "--reference=\"\",--reference=tail]\\e",
            ),
        )
        runner.run()
        return requireNotNull(runner.dialogueStateSnapshot().pendingInput)
    }

    private fun Fixture.openChoice(label: String, target: String, vararg references: String): DialogueAction =
        openChoices(buildString {
            append("\\q[").append(label).append(',').append(target)
            references.forEach { append(",\"").append(it).append("\"") }
            append("]\\e")
        }).single()

    private fun Fixture.openChoices(script: String): List<DialogueAction> {
        runner.addMsgToQueue(arrayOf(script))
        runner.run()
        return runner.dialogueStateSnapshot().pendingChoices
    }

    private fun Fixture.openAnchor(label: String, target: String, vararg references: String): AnchorAction {
        val referencesText = references.joinToString(separator = ",", prefix = if (references.isEmpty()) "" else ",") {
            "\"$it\""
        }
        runner.addMsgToQueue(arrayOf("\\_a[$target$referencesText]$label\\_a\\e"))
        runner.run()
        return runner.dialogueStateSnapshot().contents.asSequence()
            .flatMap { it.segments.asSequence() }
            .mapNotNull { (it as? DialogueSegment.Anchor)?.action }
            .single()
    }

    private fun request(event: String, vararg references: String): String = buildString {
        append("GET SHIORI/3.0\r\n")
        append("Sender: Nanidroid\r\n")
        append("ID: ").append(event).append("\r\n")
        append("SecurityLevel: local\r\n")
        references.forEachIndexed { index, value ->
            append("Reference").append(index).append(": ").append(value).append("\r\n")
        }
        append("\r\n")
    }

    private fun noContent(): String = "SHIORI/3.0 204 No Content\r\n\r\n"

    private fun talk(value: String): String = "SHIORI/3.0 200 OK\r\nValue: $value\r\n\r\n"

    private data class Fixture(
        val runner: SScriptRunner,
        val shiori: RecordingShiori,
        val clock: FakeClock,
    )

    private class FakeClock(var value: Long) : MonotonicClock {
        override fun nowMillis(): Long = value
    }

    private class RecordingShiori(responses: List<String>) : Shiori {
        private val cannedResponses = ArrayDeque(responses)
        val requests = mutableListOf<String>()
        var beforeResponse: (() -> Unit)? = null

        override fun getModuleName(): String = "recording"

        override fun request(request: String): String {
            requests += request
            beforeResponse?.invoke()
            return if (cannedResponses.isEmpty()) "SHIORI/3.0 204 No Content\r\n\r\n" else cannedResponses.removeFirst()
        }

        override fun terminate() = Unit

        override fun unloadShiori() = Unit
    }

    private class RecordingGhost(recordingShiori: RecordingShiori) : Ghost("recording") {
        init {
            shiori = recordingShiori
        }

        override fun loadGhostInfo() = Unit

        override fun getCreateCount(): Long = 1L

        override fun incrementCreateCount() = Unit

        override fun getGhostName(): String = "Recording"

        override fun getSakuraName(): String = "Sakura"

        override fun getKeroName(): String = "Kero"

        override fun unload() = Unit
    }
}
