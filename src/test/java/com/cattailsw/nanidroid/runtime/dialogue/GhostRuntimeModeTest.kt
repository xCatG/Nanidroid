package com.cattailsw.nanidroid.runtime.dialogue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRuntimeModeTest {
    @Test
    fun idleModeCanTalk() {
        assertTrue(GhostRuntimeMode(false, false, false).canTalk)
    }

    @Test
    fun talkingPendingAndPassiveModesCannotTalk() {
        assertFalse(GhostRuntimeMode(true, false, false).canTalk)
        assertFalse(GhostRuntimeMode(false, true, false).canTalk)
        assertFalse(GhostRuntimeMode(false, false, true).canTalk)
    }

    @Test
    fun onlyPassiveModeBlocksUserActions() {
        assertTrue(GhostRuntimeMode(false, false, false).allowsUserAction)
        assertTrue(GhostRuntimeMode(true, true, false).allowsUserAction)
        assertFalse(GhostRuntimeMode(false, false, true).allowsUserAction)
    }
}
