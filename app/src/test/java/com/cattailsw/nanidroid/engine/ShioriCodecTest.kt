package com.cattailsw.nanidroid.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class ShioriCodecTest {
    @Test fun defaultMethodKeepsGetFraming() {
        val expected = "GET SHIORI/3.0\r\nSender: Nanidroid\r\nID: OnBoot\r\nSecurityLevel: local\r\n\r\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), ShioriCodec.encode(ShioriEvent("OnBoot"), Charsets.UTF_8, false))
    }

    @Test fun lifecycleNotificationsUseNotifyWithoutReferences() {
        for (id in listOf("OnInitialize", "OnDestroy")) {
            val expected = "NOTIFY SHIORI/3.0\r\nSender: Nanidroid\r\nID: $id\r\nSecurityLevel: local\r\n\r\n"
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8),
                ShioriCodec.encode(ShioriEvent(id, method = ShioriMethod.NOTIFY), Charsets.UTF_8, false))
        }
    }

    @Test fun notifyKeepsSatoriCharsetAndEncodesReferencesWhenGiven() {
        val expected = "NOTIFY SHIORI/3.0\r\nSender: Nanidroid\r\nCharset: Shift_JIS\r\nID: CustomNotice\r\nSecurityLevel: local\r\nReference0: 日本語\r\n\r\n"
        assertArrayEquals(expected.toByteArray(Charset.forName("Shift_JIS")), ShioriCodec.encode(
            ShioriEvent("CustomNotice", listOf("日本語"), ShioriMethod.NOTIFY), Charset.forName("Shift_JIS"), true))
    }

    @Test fun encodesOrderedReferencesAndSatoriHeader() {
        val event = ShioriEvent("OnBoot", listOf("一", "two"))
        val expected = "GET SHIORI/3.0\r\nSender: Nanidroid\r\nCharset: Shift_JIS\r\nID: OnBoot\r\nSecurityLevel: local\r\nReference0: 一\r\nReference1: two\r\n\r\n"
        assertArrayEquals(expected.toByteArray(Charset.forName("Shift_JIS")), ShioriCodec.encode(event, Charset.forName("Shift_JIS"), true))
        assertFalse(String(ShioriCodec.encode(event, Charsets.UTF_8, false), Charsets.UTF_8).contains("Charset:"))
    }

    @Test fun decodesStatusValueAndCharsetWithoutTrimmingScript() {
        val script = "\\0こんにちは  \\e"
        val response = "SHIORI/3.0 200 OK\r\nCharset: Shift_JIS\r\nValue: $script\r\n\r\n"
        assertEquals(script, ShioriCodec.decode(response.toByteArray(Charset.forName("Shift_JIS")), Charsets.UTF_8).value)
        assertEquals("plain", ShioriCodec.decode("SHIORI/3.0 200 OK\r\nValue:plain\r\n\r\n".toByteArray(), Charsets.UTF_8).value)
    }

    @Test fun only200WithValueReturnsScript() {
        assertNull(ShioriCodec.decode("SHIORI/3.0 204 No Content\r\nValue:bad\r\n\r\n".toByteArray(), Charsets.UTF_8).value)
        assertNull(ShioriCodec.decode("nonsense\r\nValue: bad\r\n\r\n".toByteArray(), Charsets.UTF_8).value)
        assertNull(ShioriCodec.decode("SHIORI/3.0 200 OK\r\n\r\n".toByteArray(), Charsets.UTF_8).value)
        assertEquals(0, ShioriCodec.decode("garbled".toByteArray(), Charsets.UTF_8).status)
    }

    @Test fun invalidDeclaredCharsetUsesFallback() {
        val response = "SHIORI/3.0 200 OK\r\nCharset: not-a-charset\r\nValue: 日本語\r\n\r\n"
        assertEquals("日本語", ShioriCodec.decode(response.toByteArray(Charsets.UTF_8), Charsets.UTF_8).value)
    }

    @Test fun utf8BomBeforeValidStatusStillReturnsScript() {
        val response = "\uFEFFSHIORI/3.0 200 OK\r\nValue: hello\r\n\r\n"
        assertEquals(200, ShioriCodec.decode(response.toByteArray(Charsets.UTF_8), Charsets.UTF_8).status)
        assertEquals("hello", ShioriCodec.decode(response.toByteArray(Charsets.UTF_8), Charsets.UTF_8).value)
    }

    @Test fun acceptsValidResponseVersionsOtherThanThreePointZero() {
        for (version in listOf("2.6", "3.1")) {
            val response = "SHIORI/$version 200 OK\r\nValue: version-$version\r\n\r\n"
            val reply = ShioriCodec.decode(response.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            assertEquals(200, reply.status)
            assertEquals("version-$version", reply.value)
        }
    }

    @Test fun malformedResponseVersionDoesNotYieldScript() {
        for (version in listOf("3", "3.x", "x.y")) {
            val response = "SHIORI/$version 200 OK\r\nValue: should-not-play\r\n\r\n"
            val reply = ShioriCodec.decode(response.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            assertEquals(0, reply.status)
            assertNull(reply.value)
        }
    }
}
