package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ComposeGhostStageHostActionBindingTest {
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
