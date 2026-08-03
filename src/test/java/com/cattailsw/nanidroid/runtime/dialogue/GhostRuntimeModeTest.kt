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
    fun passiveModePermitsRecoveryOnlyForKeepWaitingAndStopOperation() {
        for (action in GuardedAction.entries) {
            assertTrue(GhostActionGuard(GhostRuntimeMode(false, false, false)).allows(action, ActionOrigin.USER))
            assertFalse(GhostActionGuard(GhostRuntimeMode(false, false, true)).allows(action, ActionOrigin.USER))
            assertTrue(GhostActionGuard(GhostRuntimeMode(false, false, true)).allows(action, ActionOrigin.SAKURA_SCRIPT))
            if (action !in setOf(GuardedAction.KEEP_WAITING, GuardedAction.STOP_OPERATION)) {
                assertFalse(GhostActionGuard(GhostRuntimeMode(false, false, true)).allows(action, ActionOrigin.RECOVERY))
            }
        }
        assertTrue(GhostActionGuard(GhostRuntimeMode(false, false, true)).allows(GuardedAction.KEEP_WAITING, ActionOrigin.RECOVERY))
        assertTrue(GhostActionGuard(GhostRuntimeMode(false, false, true)).allows(GuardedAction.STOP_OPERATION, ActionOrigin.RECOVERY))
    }
}
