package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import com.cattailsw.nanidroid.runtime.dialogue.GuardedAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanidroidActionGuardIntegrationTest {
    // Mutation caught: Activity user entry points ignore the snapshot's passive mode.
    @Test
    fun passiveSnapshotRejectsBackListSwitchAndImportActionsThenActiveSnapshotPermitsThem() {
        val attachedPassive = RuntimeSnapshot.initial().copy(
            phase = GhostRuntimePhase.Attached,
            mode = GhostRuntimeMode(playingTalk = false, pendingUserAction = false, passive = true),
        )
        val poisonedPassive = attachedPassive.copy(phase = GhostRuntimePhase.Poisoned)
        val active = attachedPassive.copy(mode = attachedPassive.mode.copy(passive = false))

        listOf(GuardedAction.EXIT, GuardedAction.SWITCH_GHOST, GuardedAction.IMPORT_INSTALL).forEach { action ->
            assertFalse(userActionAllowed(attachedPassive, action))
            assertTrue(userActionAllowed(active, action))
        }
        assertTrue(userActionAllowed(poisonedPassive, GuardedAction.EXIT))
        assertFalse(userActionAllowed(poisonedPassive, GuardedAction.SWITCH_GHOST))
        assertFalse(userActionAllowed(poisonedPassive, GuardedAction.IMPORT_INSTALL))
    }
}
