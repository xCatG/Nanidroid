package com.cattailsw.nanidroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostSwitchRequestTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun constructingAStaleGhostDoesNotConsumeItsFirstBootCount() {
        GhostActivationCounter.increments = 0

        val ghost = CountingGhost()

        assertTrue(GhostActivationCounter.increments == 0)
        ghost.recordActivation()
        assertTrue(GhostActivationCounter.increments == 1)
    }

    @Test
    fun completedRequestIsDiscardedWhenANewerGhostIsPending() {
        assertFalse(ownsGhostSwitchRequest("ghost-a", "ghost-b"))
    }

    @Test
    fun completedRequestIsAppliedWhenItStillOwnsThePendingGhost() {
        assertTrue(ownsGhostSwitchRequest("ghost-a", "ghost-a"))
    }

    private class CountingGhost : Ghost("counting-ghost") {
        override fun loadGhostInfo() = Unit

        override fun incrementCreateCount() {
            GhostActivationCounter.increments++
        }
    }

    private object GhostActivationCounter {
        var increments = 0
    }
}
