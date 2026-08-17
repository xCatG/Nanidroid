package com.cattailsw.nanidroid

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransientUiBundleInstrumentationTest {
    @Test
    fun primitiveToolbarSnapshotRoundTrips() {
        val expected = TransientUiSnapshot(toolbarVisible = false)
        val bundle = Bundle().apply { writeTransientUiSnapshot(expected) }

        val restored = requireNotNull(bundle.readTransientUiSnapshot())
        assertEquals(expected, restored)
        assertFalse(restored.toolbarVisible)
    }

    @Test
    fun absentSnapshotDoesNotTurnFreshLoadingIntoAHiddenToolbarRestore() {
        assertNull(Bundle().readTransientUiSnapshot())
    }
}
