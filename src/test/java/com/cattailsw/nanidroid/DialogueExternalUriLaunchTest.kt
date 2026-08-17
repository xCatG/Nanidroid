package com.cattailsw.nanidroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueExternalUriLaunchTest {
    @Test
    fun documentLinksAllowHttpHttpsAndMailtoSchemesCaseInsensitively() {
        listOf(
            "http://example.test/readme",
            "HTTP://example.test/readme",
            "https://example.test/readme",
            "HTTPS://example.test/readme",
            "mailto:ghost@example.test",
            "MAILTO:ghost@example.test",
            "https://例え.テスト/readme",
        ).forEach { value ->
            var launched: String? = null
            assertTrue(value, tryLaunchDocumentExternalUrl(value) { launched = it })
            assertTrue(value, launched == value)
        }
    }

    @Test
    fun documentLinksRejectUnsafeHostlessMalformedAndBlankMailtoValues() {
        listOf(
            "file:///readme.txt",
            "content://provider/readme",
            "javascript:alert(1)",
            "https:/missing-host",
            "http://",
            "mailto:   ",
            "not a uri",
        ).forEach { value ->
            assertFalse(value, tryLaunchDocumentExternalUrl(value) { error("must not launch") })
        }
    }

    @Test
    fun documentLinksContainRuntimeAndSecurityLaunchFailures() {
        assertFalse(tryLaunchDocumentExternalUrl("https://example.test") {
            throw SecurityException("blocked")
        })
        assertFalse(tryLaunchDocumentExternalUrl("mailto:ghost@example.test") {
            throw IllegalStateException("no resolver")
        })
    }

    @Test
    fun resolverOrSecurityFailureDoesNotEscapeExplicitActivation() {
        assertFalse(tryLaunchDialogueExternalUri { throw SecurityException("blocked") })
        assertFalse(tryLaunchDialogueExternalUri { throw IllegalStateException("no resolver") })
        assertTrue(tryLaunchDialogueExternalUri {})
    }
}
