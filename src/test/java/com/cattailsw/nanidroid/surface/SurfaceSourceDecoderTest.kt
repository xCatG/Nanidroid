package com.cattailsw.nanidroid.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

class SurfaceSourceDecoderTest {
    @Test
    fun each_file_honors_its_own_strict_charset_and_shift_jis_uses_cp932_extensions() {
        val cp932 = Charset.forName("Windows-31J")
        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput(
                    "surfaces2.txt",
                    "charset,Shift_JIS\nsurface2\n{\ncollision0,0,0,1,1,①\n}\n"
                        .toByteArray(cp932),
                ),
                SurfaceSourceInput(
                    "surfaces1.txt",
                    "charset,UTF-8\nsurface1\n{\ncollision0,0,0,1,1,雪\n}\n"
                        .toByteArray(StandardCharsets.UTF_8),
                ),
                SurfaceSourceInput(
                    "surfaces3.txt",
                    "charset,SJIS\nsurface3\n{\ncollision0,0,0,1,1,①\n}\n"
                        .toByteArray(cp932),
                ),
            ),
        )

        assertEquals(listOf("surfaces1.txt", "surfaces2.txt", "surfaces3.txt"), result.files.map { it.name })
        assertEquals(listOf("UTF-8", "windows-31j", "windows-31j"), result.files.map { it.charset.name() })
        assertTrue(result.files[1].lines.single { it.contains("collision") }.endsWith("①"))
        assertTrue(result.files[2].lines.single { it.contains("collision") }.endsWith("①"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun utf8_bom_is_stripped_and_matching_declaration_remains_authoritative() {
        val body = "charset,UTF-8\nsurface4\n{\n}\n".toByteArray(StandardCharsets.UTF_8)
        val result = SurfaceSourceDecoder.decode(
            listOf(SurfaceSourceInput("surfaces.txt", UTF8_BOM + body)),
        )

        assertEquals("charset,UTF-8", result.files.single().lines.first())
        assertEquals(StandardCharsets.UTF_8, result.files.single().charset)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun bom_declaration_conflict_is_diagnosed_once_and_file_is_omitted() {
        val body = "charset,Shift_JIS\nsurface0\n{\n}\n".toByteArray(StandardCharsets.UTF_8)
        val result = SurfaceSourceDecoder.decode(
            listOf(SurfaceSourceInput("surfaces.txt", UTF8_BOM + body)),
        )

        assertTrue(result.files.isEmpty())
        assertEquals(1, result.diagnostics.size)
        assertEquals(SurfaceDiagnosticReason.DECODE, result.diagnostics.single().reason)
    }

    @Test
    fun declared_charset_is_strict_and_unsupported_declaration_never_falls_back() {
        val invalidUtf8 = "charset,UTF-8\n".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0x81.toByte())
        val unsupported = "charset,UTF-32\nsurface0\n{\n}\n".toByteArray(StandardCharsets.US_ASCII)
        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput("surfaces1.txt", invalidUtf8),
                SurfaceSourceInput("surfaces2.txt", unsupported),
            ),
        )

        assertTrue(result.files.isEmpty())
        assertEquals(2, result.diagnostics.size)
        assertTrue(result.diagnostics.all { it.reason == SurfaceDiagnosticReason.DECODE })
    }

    @Test
    fun absent_declaration_tries_strict_utf8_then_windows_31j_and_skips_undecodable_files() {
        val cp932 = Charset.forName("Windows-31J")
        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput("surfaces1.txt", "surface1\n{\n// 雪\n}\n".toByteArray(StandardCharsets.UTF_8)),
                SurfaceSourceInput("surfaces2.txt", "surface2\n{\n// ①\n}\n".toByteArray(cp932)),
                SurfaceSourceInput("surfaces3.txt", byteArrayOf(0x81.toByte())),
                SurfaceSourceInput("surfaces4.txt", "surface4\n{\n}\n".toByteArray(StandardCharsets.UTF_8)),
            ),
        )

        assertEquals(listOf("surfaces1.txt", "surfaces2.txt", "surfaces4.txt"), result.files.map { it.name })
        assertEquals(StandardCharsets.UTF_8, result.files[0].charset)
        assertEquals("windows-31j", result.files[1].charset.name())
        assertEquals(listOf("surfaces3.txt"), result.diagnostics.map { it.file })
    }

    @Test
    fun charset_on_physical_line_two_is_content_not_an_authoritative_declaration() {
        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput(
                    "surfaces.txt",
                    "// first physical line\ncharset,Shift_JIS\n// 雪\n".toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )

        assertEquals(StandardCharsets.UTF_8, result.files.single().charset)
        assertEquals("charset,Shift_JIS", result.files.single().lines[1])
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun filename_order_and_case_collision_diagnostics_are_locale_independent() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val bytes = "surface0\n{\n}\n".toByteArray(StandardCharsets.UTF_8)
            val result = SurfaceSourceDecoder.decode(
                listOf(
                    SurfaceSourceInput("surfacesı.txt", bytes),
                    SurfaceSourceInput("surfacesI.txt", bytes),
                    SurfaceSourceInput("surfacesA.txt", bytes),
                    SurfaceSourceInput("Surfacesa.txt", bytes),
                ),
            )

            assertEquals(
                listOf("Surfacesa.txt", "surfacesA.txt", "surfacesI.txt", "surfacesı.txt"),
                result.files.map { it.name },
            )
            assertEquals(1, result.diagnostics.count { it.reason == SurfaceDiagnosticReason.DECODE })
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun decoder_diagnostics_are_bounded_without_hiding_a_later_valid_file() {
        val invalid = byteArrayOf(0x81.toByte())
        val inputs = (0 until 300).map { SurfaceSourceInput("surfaces$it.txt", invalid) } +
            SurfaceSourceInput(
                "surfaces-valid.txt",
                "surface9\n{\n}\n".toByteArray(StandardCharsets.UTF_8),
            )

        val result = SurfaceSourceDecoder.decode(inputs)

        assertEquals(256, result.diagnostics.size)
        assertTrue(result.files.any { it.name == "surfaces-valid.txt" })
    }

    @Test
    fun oversized_physical_line_is_omitted_without_hiding_a_later_file() {
        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput(
                    "surfaces1.txt",
                    ("//" + "x".repeat(65_536)).toByteArray(StandardCharsets.UTF_8),
                ),
                SurfaceSourceInput(
                    "surfaces2.txt",
                    "surface2\n{\n}\n".toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )

        assertEquals(listOf("surfaces2.txt"), result.files.map { it.name })
        assertEquals(listOf("surfaces1.txt"), result.diagnostics.map { it.file })
    }

    @Test
    fun excessive_physical_line_count_is_omitted_without_hiding_a_later_file() {
        val excessiveLines = buildString {
            repeat(20_001) { append("//\n") }
        }.toByteArray(StandardCharsets.UTF_8)
        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput("surfaces1.txt", excessiveLines),
                SurfaceSourceInput(
                    "surfaces2.txt",
                    "surface2\n{\n}\n".toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )

        assertEquals(listOf("surfaces2.txt"), result.files.map { it.name })
        assertEquals(listOf("surfaces1.txt"), result.diagnostics.map { it.file })
    }

    @Test
    fun exact_physical_line_and_line_count_limits_are_accepted() {
        val exactLine = ("//" + "x".repeat(65_534)).toByteArray(StandardCharsets.UTF_8)
        val exactCount = buildString {
            repeat(19_999) { append("//\n") }
            append("//")
        }.toByteArray(StandardCharsets.UTF_8)

        val result = SurfaceSourceDecoder.decode(
            listOf(
                SurfaceSourceInput("surfaces1.txt", exactLine),
                SurfaceSourceInput("surfaces2.txt", exactCount),
            ),
        )

        assertEquals(listOf("surfaces1.txt", "surfaces2.txt"), result.files.map { it.name })
        assertTrue(result.diagnostics.isEmpty())
    }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
