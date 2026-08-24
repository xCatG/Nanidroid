package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.MonotonicClock
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import com.cattailsw.nanidroid.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Regression coverage for the runtime-owned dialogue projection used by the stage. */
class SScriptRunnerDialogueTimingTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun scheduledPlaybackDoesNotExposeUnreachedTextActionsOrInput() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)

        runner.addMsgToQueue(
            arrayOf("\\hBefore\\_w[100]After\\q[Choose,choice]\\![open,inputbox,name]\\e"),
        )
        runner.run()

        val queued = runner.dialogueStateSnapshot()
        assertTrue(queued.contents.isEmpty())
        assertTrue(queued.pendingChoices.isEmpty())
        assertNull(queued.pendingInput)

        scheduler.runNext()
        val firstStep = runner.dialogueStateSnapshot()
        assertEquals("B", firstStep.contents.single().segments.text())
        assertTrue(firstStep.pendingChoices.isEmpty())
        assertNull(firstStep.pendingInput)
    }

    @Test
    fun playbackProjectsTextAndClearInStepOrderWithoutLeakingPostWaitContent() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.addMsgToQueue(arrayOf("\\hOne\\_w[100]Two\\cThree\\e"))
        runner.run()

        scheduler.runNext()
        assertEquals("O", runner.dialogueStateSnapshot().contents.single().segments.text())
        scheduler.runNext()
        assertEquals("On", runner.dialogueStateSnapshot().contents.single().segments.text())
        scheduler.runNext()
        assertEquals("One", runner.dialogueStateSnapshot().contents.single().segments.text())
        scheduler.runNext()
        assertEquals("One", runner.dialogueStateSnapshot().contents.single().segments.text())
        scheduler.runNext()
        assertEquals("OneT", runner.dialogueStateSnapshot().contents.single().segments.text())

        repeat(3) { scheduler.runNext() }
        assertEquals(
            "T",
            com.cattailsw.nanidroid.runtime.dialogue.DialogueSpeakerOwnership
                .from(runner.dialogueStateSnapshot())
                .content(GhostSpeaker.SAKURA)
                .segments
                .text(),
        )
    }

    @Test
    fun completeStructuredTokensBecomeInteractiveOnlyWhenTheirPlaybackStepReachesThem() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.addMsgToQueue(
            arrayOf(
                "\\hA\\_a[anchor]Link\\_a\\j[https://example.test]" +
                    "\\q[Choose,choice]\\_w[50]\\![open,inputbox,name]\\e",
            ),
        )
        runner.run()

        repeat(5) { scheduler.runNext() }
        val beforeAnchorClose = runner.dialogueStateSnapshot()
        assertFalse(beforeAnchorClose.contents.single().segments.any { it is DialogueSegment.Anchor })
        assertTrue(beforeAnchorClose.pendingChoices.isEmpty())
        assertNull(beforeAnchorClose.pendingInput)

        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.single().segments.any { it is DialogueSegment.Anchor }
        }
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.single().segments.any { it is DialogueSegment.ExternalUrl }
        }
        assertTrue(runner.dialogueStateSnapshot().contents.single().segments.any { it is DialogueSegment.ExternalUrl })
        scheduler.runUntil { runner.dialogueStateSnapshot().pendingChoices.isNotEmpty() }
        val choice = runner.dialogueStateSnapshot().pendingChoices.single()
        assertEquals("Choose", (choice as DialogueAction.Normal).label)
        assertNull(runner.dialogueStateSnapshot().pendingInput)
        scheduler.runUntil { runner.dialogueStateSnapshot().pendingInput != null }
        assertEquals("name", runner.dialogueStateSnapshot().pendingInput!!.spec.dispatch.let {
            (it as com.cattailsw.nanidroid.runtime.dialogue.InputDispatch.Normal).id
        })
    }

    @Test
    fun noWaitModeRetainsFinalProjectionAndStableTalkIdentity() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\hA\\_w[100]B\\q[Choose,choice]\\![open,inputbox,name]\\e"))
        runner.run()

        val state = runner.dialogueStateSnapshot()
        assertEquals("AB", state.contents.single().segments.text())
        assertEquals(listOf(DialogueAction.Normal("Choose", "choice", emptyList())), state.pendingChoices)
        assertEquals("name", (state.pendingInput!!.spec.dispatch as com.cattailsw.nanidroid.runtime.dialogue.InputDispatch.Normal).id)
        val talkId = state.talkId
        runner.activateChoice(state.pendingChoices.single())
        assertEquals(talkId, runner.dialogueStateSnapshot().talkId)
    }

    @Test
    fun selectingOneChoiceRetiresItsEntirePresentedSiblingGeneration() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.setGhost(RecordingGhost())
        runner.addMsgToQueue(arrayOf("\\q[A,a]\\q[B,b]\\_w[5000]X\\e"))
        runner.run()
        scheduler.runUntil { runner.dialogueStateSnapshot().pendingChoices.size == 2 }
        val presented = runner.dialogueStateSnapshot().pendingChoices

        runner.activateChoice(presented.first())
        scheduler.runUntil { runner.dialogueStateSnapshot().contents.single().segments.text() == "X" }

        assertTrue(runner.dialogueStateSnapshot().pendingChoices.isEmpty())
    }

    @Test
    fun pendingChoicesKeepAlternatingSpeakerSourceOrderAndIdentityClaims() {
        val scheduler = RecordingScheduler()
        val ghost = RecordingGhost()
        val runner = runner(scheduler)
        runner.setGhost(ghost)
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\h\\q[A,a]\\u\\q[B,OnB]\\h\\q[C,script:\\hDone]\\e"))
        runner.run()

        val presented = runner.dialogueStateSnapshot().pendingChoices
        assertEquals(listOf("A", "B", "C"), presented.map(::choiceLabel))

        runner.activateChoice(presented[1])

        assertTrue(runner.dialogueStateSnapshot().pendingChoices.isEmpty())
        assertEquals(1, ghost.requestCount)

        runner.activateChoice(presented[1])

        assertEquals(1, ghost.requestCount)
    }

    @Test
    fun authoredInputsPublishInOrderWithDistinctGenerationsAsPlaybackReachesEachOne() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.setGhost(RecordingGhost())
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit
            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.addMsgToQueue(
            arrayOf("\\0\\![open,inputbox,first]After\\![open,inputbox,second]\\e"),
        )
        runner.run()

        scheduler.runUntil { runner.dialogueStateSnapshot().pendingInput != null }
        val first = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        assertEquals("first", first.normalInputId())
        assertEquals("", runner.dialogueStateSnapshot().contents.single().segments.text())

        runner.resumeEvt()
        runner.submitInput(first.generation, "one")
        scheduler.runUntil {
            runner.dialogueStateSnapshot().pendingInput?.generation?.let { it != first.generation } == true
        }
        val second = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        assertEquals("second", second.normalInputId())
        assertTrue(second.generation > first.generation)
        assertEquals("After", runner.dialogueStateSnapshot().contents.single().segments.text())
    }

    @Test
    fun clearRetiresPreviouslyVisibleChoiceAndRejectsPreviouslyVisibleAnchor() {
        val scheduler = RecordingScheduler()
        val ghost = RecordingGhost()
        val runner = runner(scheduler)
        runner.setGhost(ghost)
        runner.addMsgToQueue(
            arrayOf("\\q[A,a]\\_a[anchor]Link\\_a\\_w[5000]\\cAfter\\e"),
        )
        runner.run()
        scheduler.runUntil {
            runner.dialogueStateSnapshot().pendingChoices.isNotEmpty() &&
                runner.dialogueStateSnapshot().contents.single().segments.any {
                    it is DialogueSegment.Anchor
                }
        }
        val staleChoice = runner.dialogueStateSnapshot().pendingChoices.single()
        val staleAnchor = runner.dialogueStateSnapshot().contents.single().segments
            .filterIsInstance<DialogueSegment.Anchor>()
            .single()
            .action

        scheduler.runUntil {
            com.cattailsw.nanidroid.runtime.dialogue.DialogueSpeakerOwnership
                .from(runner.dialogueStateSnapshot())
                .content(GhostSpeaker.SAKURA)
                .segments
                .text() == "After"
        }
        assertTrue(runner.dialogueStateSnapshot().pendingChoices.isEmpty())

        runner.activateChoice(staleChoice)
        runner.activateAnchor(staleAnchor)

        assertEquals(0, ghost.requestCount)
    }

    @Test
    fun playbackShioriResponseIsQueuedWithoutChangingTheCurrentSourceCursorTuple() {
        val scheduler = RecordingScheduler()
        val ghost = RecordingGhost(surfaceResponse = "\\hIncoming response\\e")
        val frames = mutableListOf<GhostPresentationFrame>()
        val runner = runner(scheduler)
        runner.setGhost(ghost)
        runner.setPresentationRenderer(GhostPresentationRenderer(frames::add))
        runner.addMsgToQueue(arrayOf("\\s[1]\\q[OLD,old]\\_w[5000]Original\\e"))
        runner.run()

        scheduler.runNext()
        assertTrue(runner.dialogueStateSnapshot().pendingChoices.isEmpty())
        scheduler.runNext()

        assertEquals("OLD", frames.last().sakura.text)
        assertEquals("OLD", (runner.dialogueStateSnapshot().pendingChoices.single() as DialogueAction.Normal).label)
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "Incoming response"
        }
        assertEquals(2L, runner.dialogueStateSnapshot().talkId)
        assertEquals(1, ghost.surfaceRequestCount)
    }

    @Test
    fun inputOnlyDialogueIsHiddenUntilReachedThenCanSubmitAndResumePlayback() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.setGhost(RecordingGhost())
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit
            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.addMsgToQueue(arrayOf("\\0\\![open,inputbox,name]\\hAfter\\e"))
        runner.run()

        assertNull(runner.dialogueStateSnapshot().pendingInput)
        scheduler.runNext()
        val pending = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        assertEquals(GhostSpeaker.SAKURA, runner.dialogueStateSnapshot().contents.single().speaker)
        assertTrue(runner.dialogueStateSnapshot().contents.single().segments.any { it is DialogueSegment.InputBox })

        runner.resumeEvt()
        runner.submitInput(pending.generation, "value")
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.single().segments.text() == "After"
        }
        assertNull(runner.dialogueStateSnapshot().pendingInput)
    }

    @Test
    fun passwordInputPublishesObscuredPendingInputAndPausesBeforeTrailingText() {
        val scheduler = RecordingScheduler()
        val runner = runner(scheduler)
        runner.setGhost(RecordingGhost())
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit
            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.addMsgToQueue(arrayOf("\\0\\![open,passwordinput,password]After\\e"))
        runner.run()

        scheduler.runNext()
        val pending = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        assertEquals(InputPresentation(obscured = true), pending.spec.presentation)
        assertEquals("", runner.dialogueStateSnapshot().contents.single().segments.text())

        runner.resumeEvt()
        runner.submitInput(pending.generation, "value")
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.single().segments.text() == "After"
        }
        assertNull(runner.dialogueStateSnapshot().pendingInput)
    }

    private fun runner(scheduler: RecordingScheduler): SScriptRunner = SScriptRunner(
        ctx = null,
        sessionCoordinator = GhostSessionCoordinator(),
        monotonicClock = MonotonicClock { 10_000L },
        playbackSchedulerFactory = { scheduler },
    )

    private fun List<DialogueSegment>.text(): String = buildString {
        this@text.forEach { segment -> if (segment is DialogueSegment.Text) append(segment.value) }
    }

    private fun choiceLabel(action: DialogueAction): String = when (action) {
        is DialogueAction.Normal -> action.label
        is DialogueAction.DirectEvent -> action.label
        is DialogueAction.Script -> action.label
    }

    private fun com.cattailsw.nanidroid.runtime.dialogue.PendingInputState.normalInputId(): String =
        (spec.dispatch as com.cattailsw.nanidroid.runtime.dialogue.InputDispatch.Normal).id

    private class RecordingScheduler : SScriptPlaybackScheduler {
        private val pending = ArrayDeque<() -> Unit>()

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            pending.addLast(action)
        }

        override fun cancelPending() {
            pending.clear()
        }

        fun runNext() = requireNotNull(pending.removeFirstOrNull()).invoke()

        fun runUntil(predicate: () -> Boolean) {
            repeat(100) {
                if (predicate()) return
                runNext()
            }
            throw AssertionError("playback condition was not reached")
        }
    }

    private class RecordingGhost(
        private val surfaceResponse: String? = null,
    ) : Ghost("recording") {
        var requestCount: Int = 0
            private set
        var surfaceRequestCount: Int = 0
            private set

        init {
            shiori = object : Shiori {
                override fun getModuleName(): String = "recording"
                override fun request(request: String): String {
                    requestCount++
                    if (request.contains("ID: OnSurfaceChange")) {
                        surfaceRequestCount++
                        surfaceResponse?.let {
                            return "SHIORI/3.0 200 OK\r\nValue: $it\r\n\r\n"
                        }
                    }
                    return "SHIORI/3.0 204 No Content\r\n\r\n"
                }
                override fun terminate() = Unit
                override fun unloadShiori() = Unit
            }
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
