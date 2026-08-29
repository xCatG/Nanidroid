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
        val passive = RuntimeSnapshot.initial().copy(
            mode = GhostRuntimeMode(playingTalk = false, pendingUserAction = false, passive = true),
        )
        val active = passive.copy(mode = passive.mode.copy(passive = false))

        listOf(GuardedAction.EXIT, GuardedAction.SWITCH_GHOST, GuardedAction.IMPORT_INSTALL).forEach { action ->
            assertFalse(userActionAllowed(passive, action))
            assertTrue(userActionAllowed(active, action))
        }
    }
}
