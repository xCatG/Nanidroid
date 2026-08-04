package com.cattailsw.nanidroid.compose.debug

import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugPanelStateTest {
    @Test
    fun collisionOverlayRemainsObservableAfterPanelDismissal() {
        val state = DebugPanelState(
            visible = true,
            selectedSpeaker = SurfaceSpeaker.KERO,
            showCollisionOverlay = true,
        ).dismissDebugSurface()

        assertEquals(false, state.visible)
        assertEquals(true, state.showCollisionOverlay)
        assertEquals(
            SurfaceSpeaker.KERO,
            state.collisionOverlaySpeaker(
                loading = false,
                debugBuild = true,
            ),
        )
    }

    @Test
    fun collisionOverlayStaysHiddenOutsideInteractiveDebugState() {
        val enabled = DebugPanelState(showCollisionOverlay = true)

        assertNull(enabled.collisionOverlaySpeaker(loading = true, debugBuild = true))
        assertNull(enabled.collisionOverlaySpeaker(loading = false, debugBuild = false))
        assertNull(
            DebugPanelState(showCollisionOverlay = false).collisionOverlaySpeaker(
                loading = false,
                debugBuild = true,
            ),
        )
    }
}
