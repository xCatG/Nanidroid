package com.cattailsw.nanidroid

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextDocumentRestoreSnapshotInstrumentationTest {
    @Test
    fun installedGhostReadmeRoundTripsWithSwitchIdentity() {
        val document = NanidroidSimpleDialog.TextDocument(
            title = "Installed",
            text = "Installed ghost documentation",
            onOpenLink = {},
            sourceId = "installed-ghost",
            onSwitch = {},
        )

        assertEquals(
            TextDocumentRestoreSnapshot(
                kind = TextDocumentRestoreKind.INSTALLED_GHOST_README,
                title = "Installed",
                text = "Installed ghost documentation",
                sourceId = "installed-ghost",
            ),
            document.toTextDocumentRestoreSnapshot(),
        )
    }

    @Test
    fun currentGhostReadmeRoundTripsAsItsOwnDocumentWithIdentity() {
        val document = NanidroidSimpleDialog.TextDocument(
            title = "Readme",
            text = "Current ghost documentation",
            onOpenLink = {},
            sourceId = "current-ghost",
        )

        val snapshot = document.toTextDocumentRestoreSnapshot()
        val restored = Bundle().apply { writeTextDocumentRestoreSnapshot(snapshot) }
            .readTextDocumentRestoreSnapshot()

        assertEquals(
            TextDocumentRestoreSnapshot(
                kind = TextDocumentRestoreKind.CURRENT_GHOST_README,
                title = "Readme",
                text = "Current ghost documentation",
                sourceId = "current-ghost",
            ),
            restored,
        )
    }
}
