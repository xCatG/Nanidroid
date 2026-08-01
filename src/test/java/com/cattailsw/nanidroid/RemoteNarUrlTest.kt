package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNarUrlTest {
    @Test fun acceptsHttpsArchivePaths() {
        assertTrue(RemoteNarUrl.isApproved("https://example.test/ghost.nar"))
        assertTrue(RemoteNarUrl.isApproved("https://example.test/GHOST.ZIP"))
    }

    @Test fun normalizesApprovedHttpsSchemesForDownloadManager() {
        assertEquals("https", RemoteNarUrl.normalizedSchemeForDownload("HTTPS"))
    }

    @Test fun rejectsNonHttpsHostlessAndNonArchiveUrls() {
        assertFalse(RemoteNarUrl.isApproved("http://example.test/ghost.nar"))
        assertFalse(RemoteNarUrl.isApproved("https:///ghost.nar"))
        assertFalse(RemoteNarUrl.isApproved("https://example.test/download?file=ghost.nar"))
        assertFalse(RemoteNarUrl.isApproved("https://example.test/ghost.txt"))
        assertFalse(RemoteNarUrl.isApproved(" https://example.test/ghost.nar"))
    }
}
