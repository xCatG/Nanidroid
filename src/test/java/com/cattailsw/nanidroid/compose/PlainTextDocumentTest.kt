package com.cattailsw.nanidroid.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class PlainTextDocumentTest {
    @Test
    fun readsUtf8BomDocumentAsUtf8WithoutBom() {
        temporaryDocument(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "こんにちは".toByteArray()) { file ->
            assertEquals("こんにちは", PlainTextDocument.read(file))
        }
    }

    @Test
    fun readsShiftJisDocument() {
        temporaryDocument("ゴーストの説明".toByteArray(Charset.forName("Shift_JIS"))) { file ->
            assertEquals("ゴーストの説明", PlainTextDocument.read(file))
        }
    }

    @Test
    fun linkPatternMatchesOnlyExplicitWebAndMailtoLinks() {
        val links = PlainTextDocument.linkPattern.findAll(
            "http://example.test https://example.test mailto:ghost@example.test",
        ).map { it.value }.toList()

        assertEquals(
            listOf("http://example.test", "https://example.test", "mailto:ghost@example.test"),
            links,
        )
    }

    @Test
    fun linkPatternRejectsUnsafeSchemesAndBareText() {
        listOf(
            "file:///readme.txt",
            "content://provider/readme",
            "javascript:alert(1)",
            "plain text",
        ).forEach { value ->
            assertFalse(value, PlainTextDocument.linkPattern.containsMatchIn(value))
        }
    }

    private fun temporaryDocument(bytes: ByteArray, check: (File) -> Unit) {
        val file = File.createTempFile("plain-text-document", ".txt")
        try {
            file.writeBytes(bytes)
            check(file)
        } finally {
            file.delete()
        }
    }
}
