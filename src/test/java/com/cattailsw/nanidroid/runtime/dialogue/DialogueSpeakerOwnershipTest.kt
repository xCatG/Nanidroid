package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DialogueSpeakerOwnershipTest {
    @Test
    fun exactPendingInstancesKeepAuthoredSpeakerOwnershipAndRejectEqualStaleCopies() {
        val sakuraAction = DialogueAction.Normal("Same", "same", listOf(""))
        val keroAction = DialogueAction.Normal("Same", "same", listOf(""))
        val equalStaleCopy = keroAction.copy()
        val inputSpec = InputBoxSpec(
            dispatch = InputDispatch.Normal("name"),
            timeoutMillis = null,
            initialText = "",
            behaviorOptions = emptySet(),
            supplement = "",
            extraReferences = listOf("", "tail"),
            unknownOptions = emptyList(),
        )
        val ownership = DialogueSpeakerOwnership.from(
            DialogueRuntimeState(
                talkId = 41L,
                contents = listOf(
                    DialogueContent(
                        GhostSpeaker.SAKURA,
                        listOf(DialogueSegment.Text("first"), DialogueSegment.Choice(sakuraAction)),
                    ),
                    DialogueContent(
                        GhostSpeaker.KERO,
                        listOf(DialogueSegment.Choice(keroAction), DialogueSegment.InputBox(inputSpec)),
                    ),
                    DialogueContent(
                        GhostSpeaker.SAKURA,
                        listOf(DialogueSegment.NewLine, DialogueSegment.Text("second")),
                    ),
                ),
                pendingChoices = listOf(keroAction, equalStaleCopy),
                pendingInput = PendingInputState(7L, inputSpec, Long.MAX_VALUE),
            ),
        )

        assertEquals(
            listOf(
                DialogueSegment.Text("first"),
                DialogueSegment.Choice(sakuraAction),
                DialogueSegment.NewLine,
                DialogueSegment.Text("second"),
            ),
            ownership.content(GhostSpeaker.SAKURA).segments,
        )
        val keroChoices = ownership.pendingChoices(GhostSpeaker.KERO)
        assertEquals(listOf(keroAction), keroChoices)
        assertSame(keroAction, keroChoices.single())
        assertEquals(emptyList<DialogueAction>(), ownership.pendingChoices(GhostSpeaker.SAKURA))
        assertEquals(GhostSpeaker.KERO, ownership.pendingInputOwner)
        assertSame(inputSpec, ownership.pendingInput(GhostSpeaker.KERO)?.spec)
    }
}
