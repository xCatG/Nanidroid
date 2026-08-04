package com.cattailsw.nanidroid.compose.stage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueActionSurfacePolicyTest {
    @Test
    fun touchPhoneUsesFullWidthSurfaceWhileExpandedTouchWindowUsesCappedSurface() {
        assertTrue(useCompactDialogueActionSurface(widthPx = 1_080, density = 3f, touch = true))
        assertFalse(useCompactDialogueActionSurface(widthPx = 2_400, density = 2f, touch = true))
    }
}
