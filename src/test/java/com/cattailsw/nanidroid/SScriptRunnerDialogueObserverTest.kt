package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SScriptRunnerDialogueObserverTest {
    @get:Rule val androidStubs = HostAndroidStubRule()
    @get:Rule val runtimes = RuntimeFixtureRegistry()

    @Test
    fun observerPublishesNoTalkChoiceAndInputCompletionWithoutChangingStableTalkId() {
        val runner = runner()
        val observed = mutableListOf<DialogueRuntimeState>()
        runner.setDialogueStateObserver(observed::add)

        runner.addMsgToQueue(arrayOf("\\hVisible\\q[Same,choice-id,\"\",tail]\\e"))
        runner.run()
        val choiceState = runner.dialogueStateSnapshot()
        runner.activateChoice(choiceState.pendingChoices.single())

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
        val fixture = fixture(id = "old")
        val runner = fixture.runner
        val observed = mutableListOf<DialogueRuntimeState>()
        runner.setDialogueStateObserver(observed::add)
        runner.addMsgToQueue(arrayOf("\\hOld dialogue\\q[Old choice,old-choice]\\e"))
        runner.run()
        val before = observed.last()

        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/observer/replacement")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "replacement", targetRoot),
        ).value
        assertTrue(runner.doGhostChanging(operationId, "Replacement", "manual", targetRoot.path))
        val replacement = runBlocking {
            assertIs<RuntimeResult.Success<GhostHandle>>(
                fixture.runtime.startOrJoin("replacement", targetRoot),
            ).value
        }
        runBlocking {
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                fixture.runtime.attachHost(replacement.generation),
            )
        }

        val cleared = observed.last()
        assertTrue(cleared.revision > before.revision)
        assertTrue(cleared.contents.isEmpty())
        assertTrue(cleared.pendingChoices.isEmpty())
        assertEquals(null, cleared.pendingInput)
        assertEquals(cleared, runner.dialogueStateSnapshot())
    }

    @Test
    fun pendingInputRestoresOnlyAgainstSameDialogueIncarnationAndGeneration() {
        val firstFixture = fixture(id = "first")
        val runner = firstFixture.runner
        var attachedRunner = runner
        val binding = DialogueDialogBinding { attachedRunner }
        runner.addMsgToQueue(arrayOf("\\![open,passwordinput,answer,1000]\\e"))
        runner.run()
        val first = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        val firstRestoration = requireNotNull(binding.userInput(first).restoration)

        val outgoing = firstFixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/observer/restoration-replacement")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            firstFixture.runtime.beginSwitch(outgoing.generation, "replacement", targetRoot),
        ).value
        assertTrue(runner.doGhostChanging(operationId, "Replacement", "manual", targetRoot.path))
        val replacement = runBlocking {
            assertIs<RuntimeResult.Success<GhostHandle>>(
                firstFixture.runtime.startOrJoin("replacement", targetRoot),
            ).value
        }
        runBlocking {
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                firstFixture.runtime.attachHost(replacement.generation),
            )
        }

        // The same authored input ID is a new dialogue incarnation after switching ghosts.
        runner.addMsgToQueue(arrayOf("\\![open,inputbox,answer,1000]\\e"))
        runner.run()
        val replacementInput = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        val replacementRestoration = requireNotNull(binding.userInput(replacementInput).restoration)
        assertNotEquals(firstRestoration.owner, replacementRestoration.owner)
        assertNull(binding.restoreUserInput("answer", firstRestoration))
        assertEquals(
            "draft",
            requireNotNull(binding.restoreUserInput("answer", replacementRestoration, "draft")).value,
        )

        // A newly attached runner can reuse both an authored input ID and the first input
        // generation, so its distinct dialogue owner must reject the stale restoration too.
        val successor = fixture(id = "successor").runner
        attachedRunner = successor
        successor.addMsgToQueue(arrayOf("\\![open,inputbox,answer,1000]\\e"))
        successor.run()
        val successorInput = requireNotNull(successor.dialogueStateSnapshot().pendingInput)
        val successorRestoration = requireNotNull(binding.userInput(successorInput).restoration)
        assertEquals(first.generation, successorInput.generation)
        assertNotEquals(firstRestoration.owner, successorRestoration.owner)
        assertNull(binding.restoreUserInput("answer", firstRestoration))
        assertEquals(
            "current draft",
            requireNotNull(binding.restoreUserInput("answer", successorRestoration, "current draft")).value,
        )
    }

    @Test
    fun legacyChoiceCallbackSkipsHiddenScopeChoicesBeforeLaterChoiceIsRevealed() {
        val runner = runner()
        val callbacks = mutableListOf<Pair<List<String>, List<String>>>()
        val timeline = mutableListOf<String>()
        runner.setDialogueStateObserver { state ->
            if (state.pendingChoices.any { action -> action.choiceLabel() == "B" }) timeline += "B-revealed"
        }
        runner.setUICallback(recordingChoiceCallback(callbacks) { timeline += "callback" })

        runner.addMsgToQueue(arrayOf("\\h\\q[A,a]\\p2\\q[H,h]\\p0before\\w1\\q[B,b]\\e"))
        runner.run()

        assertEquals(listOf(listOf("A", "B") to listOf("a", "b")), callbacks)
        assertTrue(timeline.indexOf("callback") < timeline.indexOf("B-revealed"))
    }

    @Test
    fun legacyChoiceCallbackIgnoresScopeCommandsInsideBalancedArguments() {
        val runner = runner()
        val callbacks = mutableListOf<Pair<List<String>, List<String>>>()
        runner.setUICallback(recordingChoiceCallback(callbacks))

        runner.addMsgToQueue(arrayOf("\\q[A,a]\\![open,inputbox,name,9000,\"\\p2\"]\\q[B,b]"))
        runner.run()

        assertEquals(listOf(listOf("A", "B") to listOf("a", "b")), callbacks)
    }

    @Test
    fun legacyChoiceCallbackSkipsChoicesInsideAnchorLabels() {
        val runner = runner()
        val callbacks = mutableListOf<Pair<List<String>, List<String>>>()
        runner.setUICallback(recordingChoiceCallback(callbacks))

        runner.addMsgToQueue(arrayOf("\\q[A,a]\\_a[id]label \\q[Fake,fake]\\_a\\q[B,b]"))
        runner.run()

        assertEquals(listOf(listOf("A", "B") to listOf("a", "b")), callbacks)
    }

    private fun runner(): SScriptRunner = fixture().runner

    private fun fixture(id: String = "recording") = runtimes.create(
        id = id,
        runnerConfiguration = SScriptRunnerConfiguration(
            monotonicClock = MonotonicClock { 10_000L },
        ),
        preparedFactory = { operationId, ghostId, root ->
            preparedGhost(
                operationId,
                ghostId,
                root,
                name = "Recording",
                sakuraName = "Sakura",
                keroName = "Kero",
            )
        },
    ).also { it.runner.setNoWaitMode(true) }

    private fun recordingChoiceCallback(
        callbacks: MutableList<Pair<List<String>, List<String>>>,
        after: () -> Unit = {},
    ) = object : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) = Unit
        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
            callbacks += textlabel.toList() to ids.toList()
            after()
        }
    }

    private fun DialogueAction.choiceLabel(): String = when (this) {
        is DialogueAction.Normal -> label
        is DialogueAction.DirectEvent -> label
        is DialogueAction.Script -> label
    }
}
