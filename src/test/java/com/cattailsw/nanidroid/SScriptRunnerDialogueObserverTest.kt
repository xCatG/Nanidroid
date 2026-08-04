package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SScriptRunnerDialogueObserverTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun observerPublishesNoTalkChoiceAndInputCompletionWithoutChangingStableTalkId() {
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            MonotonicClock { 10_000L },
        )
        runner.setNoWaitMode(true)
        runner.setGhost(RecordingGhost(NoTalkShiori()))
        val observed = mutableListOf<DialogueRuntimeState>()
        runner.setDialogueStateObserver(observed::add)

        runner.addMsgToQueue(arrayOf("\\hVisible\\q[Same,choice-id,\"\",tail]\\e"))
        runner.run()
        val choiceState = runner.dialogueStateSnapshot()
        val choice = choiceState.pendingChoices.single()

        runner.activateChoice(choice)

        val choiceCompleted = runner.dialogueStateSnapshot()
        assertEquals(choiceState.talkId, choiceCompleted.talkId)
        assertTrue(choiceCompleted.revision > choiceState.revision)
        assertTrue(choiceCompleted.pendingChoices.isEmpty())
        assertEquals(choiceCompleted, observed.last())

        runner.addMsgToQueue(arrayOf("\\hInput\\![open,inputbox,name,9000,initial]\\e"))
        runner.run()
        val inputState = runner.dialogueStateSnapshot()
        val pending = assertNotNull(inputState.pendingInput).let { inputState.pendingInput!! }
        assertTrue(inputState.talkId > choiceCompleted.talkId)

        runner.submitInput(pending.generation, "value")

        val inputCompleted = runner.dialogueStateSnapshot()
        assertEquals(inputState.talkId, inputCompleted.talkId)
        assertTrue(inputCompleted.revision > inputState.revision)
        assertEquals(null, inputCompleted.pendingInput)
        assertEquals(inputCompleted, observed.last())
        assertTrue(observed.zipWithNext().all { (before, after) -> after.revision > before.revision })
    }

    @Test
    fun switchingGhostPublishesEmptyDialogueWithANewerRevision() {
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            MonotonicClock { 10_000L },
        )
        runner.setNoWaitMode(true)
        val oldGhost = RecordingGhost(NoTalkShiori(), "old")
        runner.setGhost(oldGhost)
        val observed = mutableListOf<DialogueRuntimeState>()
        runner.setDialogueStateObserver(observed::add)
        runner.addMsgToQueue(arrayOf("\\hOld dialogue\\q[Old choice,old-choice]\\e"))
        runner.run()
        val before = observed.last()

        assertTrue(runner.unloadGhostForSwitchForTesting(oldGhost))
        assertTrue(
            runner.attachReservedGhost(
                runner.reserveGhostForAttachmentForTesting(
                    RecordingGhost(NoTalkShiori(), "replacement"),
                ),
            ),
        )

        val cleared = observed.last()
        assertTrue(cleared.revision > before.revision)
        assertTrue(cleared.contents.isEmpty())
        assertTrue(cleared.pendingChoices.isEmpty())
        assertEquals(null, cleared.pendingInput)
        assertEquals(cleared, runner.dialogueStateSnapshot())
    }

    @Test
    fun invalidatingActiveSessionPublishesEmptyDialogueBeforeMutationActionRuns() {
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            MonotonicClock { 10_000L },
        )
        runner.setNoWaitMode(true)
        val ghost = RecordingGhost(NoTalkShiori(), "session")
        runner.setGhost(ghost)
        val observed = mutableListOf<DialogueRuntimeState>()
        runner.setDialogueStateObserver(observed::add)
        runner.addMsgToQueue(arrayOf("\\hOld dialogue\\q[Old choice,old-choice]\\e"))
        runner.run()
        val before = observed.last()

        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) {
            val cleared = observed.last()
            assertTrue(cleared.revision > before.revision)
            assertTrue(cleared.contents.isEmpty())
            assertTrue(cleared.pendingChoices.isEmpty())
            assertEquals(null, cleared.pendingInput)
        }

        assertFalse(observed.last().contents.isNotEmpty())
        assertEquals(observed.last(), runner.dialogueStateSnapshot())
    }

    private class NoTalkShiori : Shiori {
        override fun getModuleName(): String = "no-talk"

        override fun request(request: String): String = "SHIORI/3.0 204 No Content\r\n\r\n"

        override fun terminate() = Unit

        override fun unloadShiori() = Unit
    }

    private class RecordingGhost(recordingShiori: Shiori, path: String = "recording") : Ghost(path) {
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
