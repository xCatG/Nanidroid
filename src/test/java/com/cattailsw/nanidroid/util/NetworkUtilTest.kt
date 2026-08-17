package com.cattailsw.nanidroid.util

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkUtilTest {
    @Test
    fun `HTTPS URL with a nonblank host is accepted`() {
        val accepted = NetworkUtil.requireHttps("https://example.test/ghost.nar")

        assertEquals("https", accepted.protocol)
        assertEquals("example.test", accepted.host)
    }

    @Test
    fun `cleartext and hostless URLs are rejected`() {
        listOf(
            "http://example.test/ghost.nar",
            "https:///ghost.nar",
            "file:///ghost.nar",
        ).forEach { value ->
            assertThrows(IOException::class.java) {
                NetworkUtil.requireHttps(value)
            }
        }
    }
}
