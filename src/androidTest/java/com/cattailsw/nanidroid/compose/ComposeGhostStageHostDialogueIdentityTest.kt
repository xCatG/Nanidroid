package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
            assertEquals(lease, activation.host)
        }
    }

    @Test
    fun productionHostSuppressesChoiceAndAnchorAfterForegroundOwnershipMoves() {
        val player = drive("\\h\\_a[id]Link\\_a\\q[Choose,id]\\e")
        val leaseA = RuntimeHostLease(RuntimeHostId(91L), 3L)
        val leaseB = RuntimeHostLease(RuntimeHostId(92L), 3L)
        val snapshot = mutableStateOf(
            RuntimeSnapshot.freeze(
                RuntimeSnapshot.initial().copy(
                    revision = 1L,
                    generation = player.generation,
                    phase = GhostRuntimePhase.Attached,
                    activeGhostId = "host-fence-ghost",
                    presentation = player.presentation,
                    dialogue = player.dialogue,
                    foregroundHost = leaseA,
                ),
            ),
        )
        val commands = mutableListOf<RuntimeCommand>()

        composeRule.setContent {
            ComposeGhostStageHost().Stage(
                snapshot = snapshot.value,
                hostLease = leaseA,
                submitCommand = commands::add,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            snapshot.value = snapshot.value.copy(
                revision = snapshot.value.revision + 1L,
                foregroundHost = leaseB,
            )
        }

        composeRule.onNodeWithTag("ghost-bubble-anchor-sakura-0").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("dialogue-action-0").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(emptyList<RuntimeCommand>(), commands) }
    }

    @Test
    fun productionHostUsesThePlayersConsumedBackslashProjection() {
        val lease = RuntimeHostLease(RuntimeHostId(101L), 7L)
        val snapshot = mutableStateOf(snapshot(driveUntilText("\\hA\\\\B\\e", "AB"), lease))

        composeRule.setContent {
            ComposeGhostStageHost().Stage(
                snapshot = snapshot.value,
                hostLease = lease,
                submitCommand = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        assertEquals("AB", bubbleText("ghost-bubble-sakura"))
        composeRule.runOnIdle {
            snapshot.value = snapshot(driveUntilText("\\hA\\", "A"), lease)
        }
        composeRule.waitForIdle()
        assertEquals("A", bubbleText("ghost-bubble-sakura"))
    }

    @Test
    fun productionHostRendersOneContinuousCodePointSafeTextNodeAndSettledAccessibility() {
        val body = "a".repeat(255) + "\uD83D\uDE00" + "\u0301z" + "b".repeat(300)
        val lease = RuntimeHostLease(RuntimeHostId(111L), 8L)
        val player = driveUntilText("\\h$body\\e", body)
        val snapshot = snapshot(player, lease)
        composeRule.setContent {
            ComposeGhostStageHost().Stage(
                snapshot = snapshot,
                hostLease = lease,
                submitCommand = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        assertEquals(body, bubbleText("ghost-bubble-sakura"))
        assertEquals(1, bubbleTextNodeCount("ghost-bubble-sakura"))

        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        val bubble = composeRule.onNodeWithTag("ghost-bubble-sakura", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(body), bubble.config[SemanticsProperties.ContentDescription])
        assertEquals(LiveRegionMode.Polite, bubble.config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun productionHostDispatchesExactEqualEscapedCloseAnchorsAndRendersRecoveredLabel() {
        val lease = RuntimeHostLease(RuntimeHostId(121L), 9L)
        val player = driveUntilAnchors(
            "\\h\\_a[id]A\\\\_aB\\_a\\_a[id]A_aB\\_a\\_a[third]A\\\\B\\_a\\e",
            expectedCount = 3,
        )
        val snapshot = snapshot(player, lease)
        val anchors = snapshot.dialogue.anchors
        val contentAnchors = snapshot.dialogue.state.contents
            .single { it.speaker == com.cattailsw.nanidroid.runtime.GhostSpeaker.SAKURA }
            .segments.filterIsInstance<com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment.Anchor>()
        val commands = mutableListOf<RuntimeCommand>()

        assertEquals(listOf("A_aB", "A_aB", "AB"), anchors.map { it.action.visibleLabel() })
        assertEquals(anchors.map { it.action }, contentAnchors.map { it.action })
        assertSame(anchors[0].action, contentAnchors[0].action)
        assertSame(anchors[1].action, contentAnchors[1].action)
        assertNotSame(anchors[0].action, anchors[1].action)

        composeRule.setContent {
            ComposeGhostStageHost().Stage(
                snapshot = snapshot,
                hostLease = lease,
                submitCommand = commands::add,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ghost-bubble-anchor-sakura-1", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            val activation = commands.filterIsInstance<RuntimeCommand.ActivateAnchor>().single()
            assertEquals(anchors[1].key, activation.key)
            assertEquals(lease, activation.host)
        }
        assertEquals("A_aBA_aBAB", bubbleText("ghost-bubble-sakura"))
    }

    private fun snapshot(player: PlayerState, lease: RuntimeHostLease): RuntimeSnapshot = RuntimeSnapshot.freeze(
        RuntimeSnapshot.initial().copy(
            revision = 1L,
            generation = player.generation,
            phase = GhostRuntimePhase.Attached,
            activeGhostId = "escape-ghost",
            presentation = player.presentation,
            dialogue = player.dialogue,
            foregroundHost = lease,
        ),
    )

    private fun drive(script: String): PlayerState {
        var transition = SakuraScriptPlayer.reduce(
            PlayerState.initial(generation = 8L),
            PlayerCommand.Enqueue(script, parent = null),
        )
        var state = transition.state
        repeat(5_000) {
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

    private fun driveUntilText(script: String, expected: String): PlayerState {
        var transition = SakuraScriptPlayer.reduce(
            PlayerState.initial(generation = 8L),
            PlayerCommand.Enqueue(script, parent = null),
        )
        var state = transition.state
        repeat(5_000) {
            if (state.presentation.sakura.text == expected) return state
            val scheduled = transition.effects.filterIsInstance<PlayerEffect.SchedulePlayback>().lastOrNull()
            transition = SakuraScriptPlayer.reduce(
                state,
                PlayerCommand.Advance(state.playbackToken, scheduled?.delayMillis ?: 0L),
            )
            state = transition.state
        }
        throw AssertionError("player did not reveal $expected")
    }

    private fun driveUntilAnchors(script: String, expectedCount: Int): PlayerState {
        var transition = SakuraScriptPlayer.reduce(
            PlayerState.initial(generation = 8L),
            PlayerCommand.Enqueue(script, parent = null),
        )
        var state = transition.state
        repeat(5_000) {
            if (state.dialogue.anchors.size == expectedCount) return state
            val scheduled = transition.effects.filterIsInstance<PlayerEffect.SchedulePlayback>().lastOrNull()
            transition = SakuraScriptPlayer.reduce(
                state,
                PlayerCommand.Advance(state.playbackToken, scheduled?.delayMillis ?: 0L),
            )
            state = transition.state
        }
        throw AssertionError("player did not reveal expected anchors")
    }

    private fun bubbleText(tag: String): String = buildString {
        fun appendText(node: SemanticsNode) {
            if (SemanticsProperties.Text in node.config) {
                node.config[SemanticsProperties.Text].forEach { append(it.text) }
            }
            node.children.forEach(::appendText)
        }
        appendText(composeRule.onNodeWithTag(tag).fetchSemanticsNode())
    }

    private fun bubbleTextNodeCount(tag: String): Int {
        fun count(node: SemanticsNode): Int =
            (if (SemanticsProperties.Text in node.config) 1 else 0) + node.children.sumOf(::count)
        return count(composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
    }

    private fun com.cattailsw.nanidroid.runtime.dialogue.AnchorAction.visibleLabel(): String = when (this) {
        is com.cattailsw.nanidroid.runtime.dialogue.AnchorAction.Normal -> label
        is com.cattailsw.nanidroid.runtime.dialogue.AnchorAction.DirectEvent -> label
    }
}
