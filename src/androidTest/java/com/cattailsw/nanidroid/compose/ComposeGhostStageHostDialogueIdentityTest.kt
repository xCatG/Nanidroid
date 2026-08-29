package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cattailsw.nanidroid.GhostRuntimePhase
import com.cattailsw.nanidroid.runtime.PlayerCommand
import com.cattailsw.nanidroid.runtime.PlayerEffect
import com.cattailsw.nanidroid.runtime.PlayerState
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.SakuraScriptPlayer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposeGhostStageHostDialogueIdentityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Mutation caught: the real player/runtime graph loses Kero's equal choice before the production host.
    @Test
    fun productionHostExposesAndDispatchesExactAlternatingSpeakerChoiceKey() {
        val player = drive("\\h\\q[Same,same]\\u\\q[Same,same]\\e")
        val lease = RuntimeHostLease(RuntimeHostId(81L), 5L)
        val snapshot = RuntimeSnapshot.freeze(
            RuntimeSnapshot.initial().copy(
                revision = 1L,
                generation = player.generation,
                phase = GhostRuntimePhase.Attached,
                activeGhostId = "identity-ghost",
                presentation = player.presentation,
                dialogue = player.dialogue,
                foregroundHost = lease,
            ),
        )
        val expectedKey = snapshot.dialogue.choices[1].key
        val commands = mutableListOf<RuntimeCommand>()

        composeRule.setContent {
            ComposeGhostStageHost().Stage(
                snapshot = snapshot,
                hostLease = lease,
                submitCommand = commands::add,
                modifier = Modifier.fillMaxSize(),
            )
        }

        composeRule.onNodeWithTag("ghost-bubble-choose-kero").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("dialogue-action-0").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            val activation = commands.filterIsInstance<RuntimeCommand.ActivateChoice>().single()
            assertEquals(expectedKey, activation.key)
        }
    }

    private fun drive(script: String): PlayerState {
        var transition = SakuraScriptPlayer.reduce(
            PlayerState.initial(generation = 8L),
            PlayerCommand.Enqueue(script, parent = null),
        )
        var state = transition.state
        repeat(500) {
            if (state.current == null && state.queue.isEmpty()) return state
            val scheduled = transition.effects.filterIsInstance<PlayerEffect.SchedulePlayback>().lastOrNull()
            transition = SakuraScriptPlayer.reduce(
                state,
                PlayerCommand.Advance(state.playbackToken, scheduled?.delayMillis ?: 0L),
            )
            state = transition.state
        }
        throw AssertionError("player did not complete")
    }
}
