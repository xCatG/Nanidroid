package com.cattailsw.nanidroid

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveIntentAdapterTest {
    @Test
    fun `granted content archives accept both supported MIME types`() {
        listOf("application/zip", "application/x-nar").forEach { mimeType ->
            assertTrue(
                ArchiveIntentAdapter.accepts(
                    Intent.ACTION_VIEW,
                    "content",
                    mimeType,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        }
    }

    @Test
    fun `content scheme and MIME matching are case insensitive`() {
        assertTrue(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_VIEW,
                "CONTENT",
                "APPLICATION/X-NAR",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    @Test
    fun `wrong action is rejected`() {
        assertFalse(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_SEND,
                "content",
                "application/x-nar",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    @Test
    fun `non-content schemes are rejected`() {
        listOf("file", "http", "https").forEach { scheme ->
            assertFalse(
                ArchiveIntentAdapter.accepts(
                    Intent.ACTION_VIEW,
                    scheme,
                    "application/x-nar",
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        }
    }

    @Test
    fun `unsupported MIME type is rejected`() {
        assertFalse(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_VIEW,
                "content",
                "application/octet-stream",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    @Test
    fun `missing read grant is rejected`() {
        assertFalse(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_VIEW,
                "content",
                "application/x-nar",
                0,
            ),
        )
    }
}
