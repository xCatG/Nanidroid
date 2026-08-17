package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TransientUiStateTest {
    @Test
    fun restoredStateContainsOnlyToolbarVisibility() {
        val restored = restoredTransientUiSnapshot(toolbarVisible = false)

        assertEquals(TransientUiSnapshot(toolbarVisible = false), restored)
        assertFalse(restored.toolbarVisible)
    }

    @Test
    fun pendingRestorationWinsAcrossASecondSaveWhileLoading() {
        val pending = TransientUiSnapshot(toolbarVisible = false)

        val saved = transientUiSnapshotToSave(
            pending = pending,
            initialized = true,
            toolbarVisible = true,
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
            ),
        )
    }
}
