package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.GhostPresentationFrame
import com.cattailsw.nanidroid.GhostPresentationRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinGhostPresentationRuntimeTest {
    @Test
    fun renderProjectsBothSpeakersIntoState() {
        val frame = GhostPresentationFrame(
            GhostPresentationFrame.Speaker("Sakura", "4", null, "0"),
            GhostPresentationFrame.Speaker("", "12", "7", "-1"),
            true,
        )

        val state = GhostPresentationRuntimeReducer.reduce(
            GhostPresentationRuntimeState.Initial,
            frame,
        )

        assertEquals("Sakura", state.presentation.sakura.text)
        assertEquals("4", state.presentation.sakura.surfaceId)
        assertEquals(null, state.presentation.sakura.animationId)
        assertTrue(state.presentation.sakura.balloonVisible)
        assertEquals("", state.presentation.kero.text)
        assertEquals("12", state.presentation.kero.surfaceId)
        assertEquals("7", state.presentation.kero.animationId)
        assertFalse(state.presentation.kero.balloonVisible)
        assertTrue(state.talkingAnimationEnabled)
        assertEquals(1L, state.revision)
    }

    @Test
    fun equalFramesPublishEveryRenderAndAdvanceRevision() {
        val frame = GhostPresentationFrame(
            GhostPresentationFrame.Speaker("hello", "0", null, "-1"),
            GhostPresentationFrame.Speaker("", "10", null, "-1"),
            true,
        )
        val states = mutableListOf<GhostPresentationRuntimeState>()
        val renderer = KotlinGhostPresentationRuntime(states::add)

        renderer.render(frame)
        renderer.render(frame)

        assertEquals(listOf(1L, 2L), states.map { it.revision })
        assertSame(states.last(), renderer.state)
    }

    @Test
    fun callbackObservesCommittedStateAndRendererAbiRemainsIntact() {
        val states = mutableListOf<GhostPresentationRuntimeState>()
        lateinit var runtime: KotlinGhostPresentationRuntime
        runtime = KotlinGhostPresentationRuntime { state ->
            assertSame(state, runtime.state)
            states += state
        }
        val renderer: GhostPresentationRenderer = runtime

        renderer.render(
            GhostPresentationFrame(
                GhostPresentationFrame.Speaker("", "0", null, "-1"),
                GhostPresentationFrame.Speaker("K", "10", null, "0"),
                false,
            ),
        )

        assertEquals(1, states.size)
        assertSame(states.single(), runtime.state)
        assertEquals("K", runtime.state.presentation.kero.text)
    }

    @Test
    fun invalidFrameLeavesStateAndCallbackUntouchedForEitherSpeaker() {
        val states = mutableListOf<GhostPresentationRuntimeState>()
        val runtime = KotlinGhostPresentationRuntime(states::add)
        val initial = runtime.state

        listOf(
            GhostPresentationFrame(
                GhostPresentationFrame.Speaker("", null, null, "-1"),
                GhostPresentationFrame.Speaker("", "10", null, "-1"),
                false,
            ),
            GhostPresentationFrame(
                GhostPresentationFrame.Speaker("", "0", null, "-1"),
                GhostPresentationFrame.Speaker("", null, null, "-1"),
                false,
            ),
        ).forEach { frame ->
            assertThrows(IllegalArgumentException::class.java) { runtime.render(frame) }
        }

        assertSame(initial, runtime.state)
        assertTrue(states.isEmpty())
    }

    @Test
    fun callbackFailureKeepsCommittedState() {
        val runtime = KotlinGhostPresentationRuntime { throw CallbackFailure() }

        assertThrows(CallbackFailure::class.java) { runtime.render(frame("committed")) }

        assertEquals("committed", runtime.state.presentation.sakura.text)
        assertEquals(1L, runtime.state.revision)
    }

    @Test
    fun reentrantRenderAdvancesFromAlreadyCommittedState() {
        var callbackCount = 0
        lateinit var runtime: KotlinGhostPresentationRuntime
        runtime = KotlinGhostPresentationRuntime {
            callbackCount++
            if (callbackCount == 1) runtime.render(frame("nested"))
        }

        runtime.render(frame("outer"))

        assertEquals(2, callbackCount)
        assertEquals("nested", runtime.state.presentation.sakura.text)
        assertEquals(2L, runtime.state.revision)
    }

    private fun frame(text: String) = GhostPresentationFrame(
        GhostPresentationFrame.Speaker(text, "0", null, "-1"),
        GhostPresentationFrame.Speaker("", "10", null, "-1"),
        false,
    )

    private class CallbackFailure : RuntimeException()
}
