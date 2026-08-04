package com.cattailsw.nanidroid.compose.debug

import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.stage.StageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAvailabilityPolicyTest {
    @Test
    fun panelStateDefaultsToClosedSakuraPanelWithoutOverlay() {
        val state = DebugPanelState()
        assertFalse(state.visible)
        assertEquals(SurfaceSpeaker.SAKURA, state.selectedSpeaker)
        assertFalse(state.showCollisionOverlay)
    }

    @Test
    fun compactLandscapeUsesFullStageModalAtEveryWidth() {
        assertEquals(
            DebugPresentation.FULL_STAGE_MODAL,
            resolveDebugPresentation(600.dp, StageMode.COMPACT_LANDSCAPE),
        )
        assertEquals(
            DebugPresentation.FULL_STAGE_MODAL,
            resolveDebugPresentation(1_200.dp, StageMode.COMPACT_LANDSCAPE),
        )
    }

    @Test
    fun standardStageUsesBottomSheetBelowExpandedWidth() {
        assertEquals(
            DebugPresentation.BOTTOM_SHEET,
            resolveDebugPresentation(839.99.dp, StageMode.STANDARD),
        )
    }

    @Test
    fun standardStageUsesSidePanelAtExpandedWidth() {
        assertEquals(DebugPresentation.SIDE_PANEL, resolveDebugPresentation(840.dp, StageMode.STANDARD))
        assertEquals(DebugPresentation.SIDE_PANEL, resolveDebugPresentation(900.dp, StageMode.STANDARD))
    }

    @Test
    fun releasePolicyHasNoDebugIconOrSemantics() {
        val policy = DebugAvailabilityPolicy(isDebuggable = false)
        assertFalse(policy.showDebugIcon)
        assertFalse(policy.exposeDebugSemantics)
    }

    @Test
    fun debugPolicyEnablesIconAndSemantics() {
        val policy = DebugAvailabilityPolicy(isDebuggable = true)
        assertTrue(policy.showDebugIcon)
        assertTrue(policy.exposeDebugSemantics)
    }
}
