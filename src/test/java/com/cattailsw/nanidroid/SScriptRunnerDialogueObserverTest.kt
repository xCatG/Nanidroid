package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
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
    fun legacyChoiceCallbackSkipsHiddenScopeChoicesBeforeLaterChoiceIsRevealed() {
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            MonotonicClock { 10_000L },
        )
        runner.setNoWaitMode(true)
        val callbacks = mutableListOf<Pair<List<String>, List<String>>>()
        val timeline = mutableListOf<String>()
        runner.setDialogueStateObserver { state ->
            if (state.pendingChoices.any { action -> action.choiceLabel() == "B" }) {
                timeline += "B-revealed"
            }
        }
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                callbacks += textlabel.toList() to ids.toList()
                timeline += "callback"
            }
        })

        runner.addMsgToQueue(arrayOf("\\h\\q[A,a]\\p2\\q[H,h]\\p0before\\w1\\q[B,b]\\e"))
        runner.run()

        assertEquals(listOf(listOf("A", "B") to listOf("a", "b")), callbacks)
        assertTrue(timeline.indexOf("callback") < timeline.indexOf("B-revealed"))
    }

    @Test
    fun legacyChoiceCallbackIgnoresScopeCommandsInsideBalancedArguments() {
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            MonotonicClock { 10_000L },
        )
        runner.setNoWaitMode(true)
        val callbacks = mutableListOf<Pair<List<String>, List<String>>>()
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                callbacks += textlabel.toList() to ids.toList()
            }
        })

        runner.addMsgToQueue(arrayOf("\\q[A,a]\\![open,inputbox,name,9000,\"\\p2\"]\\q[B,b]"))
        runner.run()

        assertEquals(listOf(listOf("A", "B") to listOf("a", "b")), callbacks)
    }

    @Test
    fun legacyChoiceCallbackSkipsChoicesInsideAnchorLabels() {
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            MonotonicClock { 10_000L },
        )
        runner.setNoWaitMode(true)
        val callbacks = mutableListOf<Pair<List<String>, List<String>>>()
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                callbacks += textlabel.toList() to ids.toList()
            }
        })

        runner.addMsgToQueue(arrayOf("\\q[A,a]\\_a[id]label \\q[Fake,fake]\\_a\\q[B,b]"))
        runner.run()

        assertEquals(listOf(listOf("A", "B") to listOf("a", "b")), callbacks)
    }

    private fun DialogueAction.choiceLabel(): String = when (this) {
        is DialogueAction.Normal -> label
        is DialogueAction.DirectEvent -> label
        is DialogueAction.Script -> label
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
