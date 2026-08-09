package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.debug.DebugPanelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TransientUiStateTest {
    @Test
    fun restoredDebugStateIsSanitizedByBuildAndNeverRestoresSampleWork() {
        val debug = restoredTransientUiSnapshot(
            toolbarVisible = false,
            debugVisible = true,
            debugSpeakerName = SurfaceSpeaker.KERO.name,
            collisionOverlayVisible = true,
            isDebuggable = true,
        )
        val release = restoredTransientUiSnapshot(
            toolbarVisible = false,
            debugVisible = true,
            debugSpeakerName = SurfaceSpeaker.KERO.name,
            collisionOverlayVisible = true,
            isDebuggable = false,
        )

        assertFalse(debug.toolbarVisible)
        assertEquals(
            DebugPanelState(
                visible = true,
                selectedSpeaker = SurfaceSpeaker.KERO,
                showCollisionOverlay = true,
                sampleFeedbackToken = 0L,
            ),
            debug.debugPanelState,
        )
        assertEquals(DebugPanelState(), release.debugPanelState)
        assertFalse(release.toolbarVisible)
    }

    @Test
    fun malformedRestoredSpeakerFallsBackToPhysicalSakuraRole() {
        val restored = restoredTransientUiSnapshot(
            toolbarVisible = true,
            debugVisible = true,
            debugSpeakerName = "corrupt",
            collisionOverlayVisible = false,
            isDebuggable = true,
        )

        assertEquals(SurfaceSpeaker.SAKURA, restored.debugPanelState.selectedSpeaker)
    }

    @Test
    fun pendingRestorationWinsAcrossASecondSaveWhileLoading() {
        val pending = TransientUiSnapshot(
            toolbarVisible = false,
            debugPanelState = DebugPanelState(
                visible = true,
                selectedSpeaker = SurfaceSpeaker.KERO,
                showCollisionOverlay = true,
            ),
        )

        val saved = transientUiSnapshotToSave(
            pending = pending,
            initialized = true,
            toolbarVisible = true,
            debugPanelState = DebugPanelState(),
        )

        assertSame(pending, saved)
    }

    @Test
    fun freshLoadingStateDoesNotSaveItsUninitializedHiddenToolbar() {
        assertNull(
            transientUiSnapshotToSave(
                pending = null,
                initialized = false,
                toolbarVisible = false,
                debugPanelState = DebugPanelState(),
            ),
        )
    }
}
