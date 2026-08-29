package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSpeakerOwnership
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.RuntimeDialogueSnapshot
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ComposeGhostStageHostActionBindingTest {
    @Test
    fun freezePreservesDuplicateEqualCrossSpeakerCapabilitiesAndExactKeys() {
        val sakuraChoice = DialogueAction.Normal("Same", "same", emptyList())
        val keroChoice = DialogueAction.Normal("Same", "same", emptyList())
        val sakuraAnchor = AnchorAction.Normal("Same", "same", emptyList())
        val keroAnchor = AnchorAction.Normal("Same", "same", emptyList())
        val inputSpec = InputBoxSpec(
            dispatch = InputDispatch.Normal("input"),
            timeoutMillis = null,
            initialText = "",
            behaviorOptions = emptySet(),
            supplement = "",
            extraReferences = emptyList(),
            unknownOptions = emptyList(),
        )
        val pendingInput = PendingInputState(1L, inputSpec, Long.MAX_VALUE, GhostSpeaker.KERO)
        val source = RuntimeSnapshot.initial().copy(
            generation = 1L,
            dialogue = RuntimeDialogueSnapshot(
                state = DialogueRuntimeState(
                    revision = 1L,
                    incarnation = 2L,
                    talkId = 3L,
                    contents = listOf(
                        DialogueContent(
                            GhostSpeaker.SAKURA,
                            listOf(DialogueSegment.Choice(sakuraChoice), DialogueSegment.Anchor(sakuraAnchor)),
                        ),
                        DialogueContent(
                            GhostSpeaker.KERO,
                            listOf(
                                DialogueSegment.Choice(keroChoice),
                                DialogueSegment.Anchor(keroAnchor),
                                DialogueSegment.InputBox(inputSpec),
                            ),
                        ),
                    ),
                    pendingChoices = listOf(sakuraChoice, keroChoice),
                    pendingInput = pendingInput,
                ),
                choices = listOf(
                    RuntimeChoiceAction(DialogueActionKey(1L, 2L, 3L), sakuraChoice),
                    RuntimeChoiceAction(DialogueActionKey(1L, 2L, 4L), keroChoice),
                ),
                anchors = listOf(
                    RuntimeAnchorAction(DialogueActionKey(1L, 2L, 5L), sakuraAnchor),
                    RuntimeAnchorAction(DialogueActionKey(1L, 2L, 6L), keroAnchor),
                ),
                input = RuntimeInputAction(DialogueActionKey(1L, 2L, 7L), pendingInput),
            ),
        )

        val frozen = RuntimeSnapshot.freeze(source)
        val ownership = DialogueSpeakerOwnership.from(frozen.dialogue.state)
        val frozenAnchors = frozen.dialogue.state.contents.flatMap { content ->
            content.segments.mapNotNull { (it as? DialogueSegment.Anchor)?.action }
        }
        val choiceBindings = identityActionBindings(
            frozen.dialogue.state.pendingChoices,
            frozen.dialogue.choices,
            RuntimeChoiceAction::action,
        )
        val anchorBindings = identityActionBindings(
            frozenAnchors,
            frozen.dialogue.anchors,
            RuntimeAnchorAction::action,
        )

        val frozenSakuraChoice = ownership.pendingChoices(GhostSpeaker.SAKURA).single()
        val frozenKeroChoice = ownership.pendingChoices(GhostSpeaker.KERO).single()
        assertNotSame(frozenSakuraChoice, frozenKeroChoice)
        assertEquals(3L, choiceBindings[frozenSakuraChoice]?.key?.actionId)
        assertEquals(4L, choiceBindings[frozenKeroChoice]?.key?.actionId)
        assertEquals(5L, anchorBindings[frozenAnchors[0]]?.key?.actionId)
        assertEquals(6L, anchorBindings[frozenAnchors[1]]?.key?.actionId)
        val frozenInputBox = frozen.dialogue.state.contents[1].segments
            .filterIsInstance<DialogueSegment.InputBox>()
            .single()
        assertSame(frozen.dialogue.state.pendingInput, frozen.dialogue.input?.pending)
        assertSame(frozen.dialogue.state.pendingInput?.spec, frozenInputBox.spec)
    }

    @Test
    fun equalCrossSpeakerActionsRetainTheirExactRuntimeKeys() {
        val sakuraChoice = DialogueAction.Normal("Same", "same", emptyList())
        val keroChoice = DialogueAction.Normal("Same", "same", emptyList())
        val sakuraAnchor = AnchorAction.Normal("Same", "same", emptyList())
        val keroAnchor = AnchorAction.Normal("Same", "same", emptyList())
        assertNotSame(sakuraChoice, keroChoice)
        assertNotSame(sakuraAnchor, keroAnchor)

        val choiceBindings = identityActionBindings(
            listOf(sakuraChoice, keroChoice),
            listOf(
                RuntimeChoiceAction(DialogueActionKey(1L, 2L, 3L), sakuraChoice.copy()),
                RuntimeChoiceAction(DialogueActionKey(1L, 2L, 4L), keroChoice.copy()),
            ),
            RuntimeChoiceAction::action,
        )
        val anchorBindings = identityActionBindings(
            listOf(sakuraAnchor, keroAnchor),
            listOf(
                RuntimeAnchorAction(DialogueActionKey(1L, 2L, 5L), sakuraAnchor.copy()),
                RuntimeAnchorAction(DialogueActionKey(1L, 2L, 6L), keroAnchor.copy()),
            ),
            RuntimeAnchorAction::action,
        )

        assertEquals(3L, choiceBindings[sakuraChoice]?.key?.actionId)
        assertEquals(4L, choiceBindings[keroChoice]?.key?.actionId)
        assertEquals(5L, anchorBindings[sakuraAnchor]?.key?.actionId)
        assertEquals(6L, anchorBindings[keroAnchor]?.key?.actionId)
    }
}
