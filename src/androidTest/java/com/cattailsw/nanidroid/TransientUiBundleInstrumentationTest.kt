package com.cattailsw.nanidroid

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.debug.DebugPanelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransientUiBundleInstrumentationTest {
    @Test
    fun primitiveSnapshotRoundTripsAndReleaseDropsDebugState() {
        val expected = TransientUiSnapshot(
            toolbarVisible = false,
            debugPanelState = DebugPanelState(
                visible = true,
                selectedSpeaker = SurfaceSpeaker.KERO,
                showCollisionOverlay = true,
                sampleFeedbackToken = 0L,
            ),
        )
        val bundle = Bundle().apply { writeTransientUiSnapshot(expected) }

        assertEquals(expected, bundle.readTransientUiSnapshot(isDebuggable = true))
        val release = requireNotNull(bundle.readTransientUiSnapshot(isDebuggable = false))
        assertFalse(release.toolbarVisible)
        assertEquals(DebugPanelState(), release.debugPanelState)
    }

    @Test
    fun absentSnapshotDoesNotTurnFreshLoadingIntoAHiddenToolbarRestore() {
        assertNull(Bundle().readTransientUiSnapshot(isDebuggable = true))
    }
}
