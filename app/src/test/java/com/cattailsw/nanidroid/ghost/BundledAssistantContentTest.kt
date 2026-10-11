package com.cattailsw.nanidroid.ghost

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BundledAssistantContentTest {
    @Test fun localeFallbackAndRequiredKeys() {
        for (language in listOf("en", "ja", "fr", "zh", "", "../en")) {
            val expectedLocale = if (language == "en") "en" else "ja"
            val paths = mutableListOf<String>()
            val content = BundledAssistantContent.load({ path ->
                paths += path
                File("src/main/assets", path).inputStream()
            }, language)
            assertEquals(listOf("assistant/$expectedLocale/content.txt"), paths)
            assertEquals(if (language == "en") "Show touch bounds" else "タッチ範囲を表示",
                content.label("ShowTouchBounds"))
            assertEquals(if (language == "en") "Close" else "閉じる", content.label("Close"))
            assertEquals(if (language == "en") "Debug tools" else "デバッグツール", content.label("DebugToolsTitle"))
            assertNull(content.script("Unknown"))
        }
        val english = File("src/main/assets/assistant/en/content.txt").readText()
        val required = listOf("OnFirstBoot", "OnBoot", "OnNanidroidHelp", "OnNanidroidIntro",
            "OnNanidroidImportHelp", "OnNanidroidInteractionHelp", "OnNanidroidTools",
            "OnNanidroidToolsDebug", "OnNanidroidDismiss", "DebugToolsTitle", "ShowTouchBounds", "Close")
        for (key in required) {
            val missing = english.lineSequence().filterNot { it.startsWith("$key,") }.joinToString("\n")
            expectRejected(missing.toByteArray())
            val blank = english.lineSequence().map { if (it.startsWith("$key,")) "$key," else it }.joinToString("\n")
            expectRejected(blank.toByteArray())
        }
    }

    @Test fun oversizedContentRejected() {
        val english = File("src/main/assets/assistant/en/content.txt").readBytes()
        val limit = 64 * 1024
        val exactly = english + "\n;".toByteArray() + ByteArray(limit - english.size - 2) { 'x'.code.toByte() }
        assertNotNull(BundledAssistantContent.load({ ByteArrayInputStream(exactly) }, "en").script("OnFirstBoot"))
        expectRejected(exactly + byteArrayOf('x'.code.toByte()))
        // UTF-8 is bounded in bytes, including Japanese multibyte characters.
        expectRejected((english.toString(Charsets.UTF_8) + "\n;" + "日".repeat(22_000)).toByteArray())
        val stream = object : ByteArrayInputStream(ByteArray(limit + 100)) {
            var closed = false
            override fun close() { closed = true; super.close() }
            fun bytesRead() = pos
        }
        try {
            BundledAssistantContent.load({ stream }, "ja")
            fail("Expected oversized content rejection")
        } catch (_: IllegalArgumentException) {
            assertTrue(stream.closed)
            assertTrue(stream.bytesRead() <= limit + 1)
        }
    }

    @Test fun tableKeepsFirstCommaBomCommentsAndLastValue() {
        val english = File("src/main/assets/assistant/en/content.txt").readText()
        val input = "\uFEFF;comment\r\ninvalid\r\n,empty key\r\n" + english +
            "\nCustom,one,two\nCustom,last,comma\n"
        val content = BundledAssistantContent.load({ ByteArrayInputStream(input.toByteArray()) }, "en")
        assertEquals("last,comma", content.script("Custom"))
        assertNull(content.script(";comment"))
        assertNull(content.script("invalid"))
        try {
            content.label("Unknown")
            fail("Unknown labels must not silently display a key")
        } catch (_: NoSuchElementException) { }
    }

    private fun expectRejected(bytes: ByteArray) {
        try {
            BundledAssistantContent.load({ ByteArrayInputStream(bytes) }, "en")
            fail("Expected content rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
