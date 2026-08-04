package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import org.junit.Assert.assertSame
import org.junit.Test

class ComposeGhostStageHostDialogueStateTest {
    @Test
    fun hostRejectsLateSnapshotsAcrossRevisionAndSessionIncarnationWhileAcceptingEqualValueReplacement() {
        val host = ComposeGhostStageHost(
            SurfaceInteractionPort { },
            SurfacePixelAssets { null },
        )
        val current = DialogueRuntimeState(revision = 5L, incarnation = 1L)
        host.updateDialogueState(current)

        host.updateDialogueState(DialogueRuntimeState(revision = 4L, incarnation = 1L))
        assertSame(current, host.dialogueState)

        val cleared = DialogueRuntimeState(revision = 1L, incarnation = 2L)
        host.updateDialogueState(cleared)
        host.updateDialogueState(DialogueRuntimeState(revision = 99L, incarnation = 1L))
        assertSame(cleared, host.dialogueState)

        val equalReplacement = DialogueRuntimeState(revision = 1L, incarnation = 2L)
        host.updateDialogueState(equalReplacement)
        assertSame(equalReplacement, host.dialogueState)
    }
}
