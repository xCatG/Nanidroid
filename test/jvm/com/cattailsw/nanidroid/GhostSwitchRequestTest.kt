package com.cattailsw.nanidroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostSwitchRequestTest {
    @Test
    fun completedRequestIsDiscardedWhenANewerGhostIsPending() {
        assertFalse(ownsGhostSwitchRequest("ghost-a", "ghost-b"))
    }

    @Test
    fun completedRequestIsAppliedWhenItStillOwnsThePendingGhost() {
        assertTrue(ownsGhostSwitchRequest("ghost-a", "ghost-a"))
    }
}
