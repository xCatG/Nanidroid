package com.cattailsw.nanidroid.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.PatternHolders
import java.util.regex.Matcher

class ShioriResponseTest {

    @Test
    fun testGetResVersionPtrn() {
        var resHeader = "SHIORI/3.0 200 OK"
        var m: Matcher = PatternHolders.shiori_res_header_ptrn.matcher(resHeader)
        assertTrue(m.matches())
        assertEquals("SHIORI", m.group(1))
        assertEquals("3", m.group(2))
        assertEquals("0", m.group(3))
        assertEquals("200", m.group(4))
        assertEquals("OK", m.group(5))

        resHeader = "SHIORI/2.0 500 INTERNAL ERROR"
        m = PatternHolders.shiori_res_header_ptrn.matcher(resHeader)
        assertTrue(m.matches())
        assertEquals("SHIORI", m.group(1))
        assertEquals("2", m.group(2))
        assertEquals("0", m.group(3))
        assertEquals("500", m.group(4))
        assertEquals("INTERNAL ERROR", m.group(5))

        resHeader = "SHIORI/2.0 500"
        m = PatternHolders.shiori_res_header_ptrn.matcher(resHeader)
        assertTrue(m.matches())
        assertEquals("SHIORI", m.group(1))
        assertEquals("2", m.group(2))
        assertEquals("0", m.group(3))
        assertEquals("500", m.group(4))
        assertEquals("", m.group(5))
    }

    @Test
    fun testHeaderParsing() {
        var resHeader = "SHIORI/3.0 200 OK"
        var r = ShioriResponse(resHeader)
        var p = r.protocolVersion
        assertNotNull(p)
        assertEquals("SHIORI", p?.protocol)
        assertEquals(3, p?.major)
        assertEquals(0, p?.minor)
        assertEquals(200, r.statusCode)

        resHeader = "SHIORI/2.0 500 INTERNAL ERROR"
        r = ShioriResponse(resHeader)
        p = r.protocolVersion
        assertNotNull(p)
        assertEquals("SHIORI", p?.protocol)
        assertEquals(2, p?.major)
        assertEquals(0, p?.minor)
        assertEquals(500, r.statusCode)

        resHeader = "SHIORI/1.1 400 BAD REQUEST"
        r = ShioriResponse(resHeader)
        p = r.protocolVersion
        assertNotNull(p)
        assertEquals("SHIORI", p?.protocol)
        assertEquals(1, p?.major)
        assertEquals(1, p?.minor)
        assertEquals(400, r.statusCode)

        resHeader = "SHIORI/1.1 400"
        r = ShioriResponse(resHeader)
        p = r.protocolVersion
        assertNotNull(p)
        assertEquals("SHIORI", p?.protocol)
        assertEquals(1, p?.major)
        assertEquals(1, p?.minor)
        assertEquals(400, r.statusCode)
    }

    @Test
    fun testHeaderParsingNoSpace() {
        val rawResponse = "SHIORI/3.0 200 OK\r\nSender:MySender\r\nCharset:UTF-8\r\nValue:Hello World\r\n\r\n"
        val reader = java.io.BufferedReader(java.io.StringReader(rawResponse))
        val r = ShioriResponse(reader)

        assertEquals("MySender", r.getKey("Sender"))
        assertEquals("UTF-8", r.getKey("Charset"))
        assertEquals("Hello World", r.getKey("Value"))
    }
}
