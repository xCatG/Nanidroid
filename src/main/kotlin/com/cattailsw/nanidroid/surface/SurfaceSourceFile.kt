package com.cattailsw.nanidroid.surface

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class SurfaceParseDiagnostic(
    val file: String,
    val line: Int,
    val source: String,
    val reason: SurfaceDiagnosticReason,
)

enum class SurfaceDiagnosticReason { DECODE, SELECTOR, MISSING_BRACE, ENTRY, UNSUPPORTED }

data class SourceLine(val file: String, val number: Int, val text: String)

data class SurfaceSourceFile(
    val name: String,
    val charset: Charset,
    val lines: List<String>,
)

data class SurfaceSourceInput(val name: String, val bytes: ByteArray)

data class SurfaceSourceDecodeResult(
    val files: List<SurfaceSourceFile>,
    val diagnostics: List<SurfaceParseDiagnostic>,
)

object SurfaceSourceDecoder {
    private val utf8 = StandardCharsets.UTF_8
    private val windows31j = Charset.forName("Windows-31J")
    private val charsetDeclaration = Regex("^\\s*charset\\s*,\\s*([^\\s,;]+)", RegexOption.IGNORE_CASE)
    private val filenameComparator =
        compareBy<SurfaceSourceInput> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }

    fun decode(inputs: List<SurfaceSourceInput>): SurfaceSourceDecodeResult {
        val diagnostics = mutableListOf<SurfaceParseDiagnostic>()
        val sorted = inputs.sortedWith(filenameComparator)
        sorted.zipWithNext().forEach { (left, right) ->
            if (left.name.lowercase(Locale.ROOT) == right.name.lowercase(Locale.ROOT) &&
                left.name != right.name
            ) {
                diagnostics.addBounded(diagnostic(
                    right.name,
                    right.name,
                    "case-colliding surface source filename",
                ))
            }
        }
        val files = sorted.mapNotNull { input -> decodeOne(input, diagnostics) }
        return SurfaceSourceDecodeResult(files, diagnostics)
    }

    private fun decodeOne(
        input: SurfaceSourceInput,
        diagnostics: MutableList<SurfaceParseDiagnostic>,
    ): SurfaceSourceFile? {
        val hasUtf8Bom = input.bytes.startsWith(UTF8_BOM)
        val bytes = if (hasUtf8Bom) input.bytes.copyOfRange(UTF8_BOM.size, input.bytes.size) else input.bytes
        val declarationText = firstPhysicalLine(bytes)
        val declaredName = charsetDeclaration.find(declarationText)?.groupValues?.get(1)
        val declared = declaredName?.let(::supportedCharset)

        if (declaredName != null && declared == null) {
            diagnostics.addBounded(diagnostic(input.name, declarationText, "unsupported charset declaration"))
            return null
        }
        if (hasUtf8Bom && declared != null && declared != utf8) {
            diagnostics.addBounded(diagnostic(input.name, declarationText, "BOM and charset declaration conflict"))
            return null
        }

        val candidates = when {
            hasUtf8Bom -> listOf(utf8)
            declared != null -> listOf(declared)
            else -> listOf(utf8, windows31j)
        }
        for (charset in candidates) {
            val decoded = strictDecode(bytes, charset) ?: continue
            return SurfaceSourceFile(
                input.name,
                charset,
                decoded.split('\n').map { it.removeSuffix("\r") },
            )
        }

        diagnostics.addBounded(diagnostic(input.name, declarationText, "source is not valid in its allowed charset"))
        return null
    }

    private fun supportedCharset(name: String): Charset? = when (name.lowercase(Locale.ROOT)) {
        "utf-8", "utf8" -> utf8
        "shift_jis", "shift-jis", "sjis", "windows-31j", "cp932" -> windows31j
        else -> null
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun firstPhysicalLine(bytes: ByteArray): String {
        val end = bytes.indexOfFirst { it == '\n'.code.toByte() }.let { if (it == -1) bytes.size else it }
        return bytes.copyOfRange(0, end)
            .toString(StandardCharsets.US_ASCII)
            .removeSuffix("\r")
    }

    private fun diagnostic(file: String, source: String, @Suppress("UNUSED_PARAMETER") detail: String) =
        SurfaceParseDiagnostic(file, 1, source, SurfaceDiagnosticReason.DECODE)

    private fun MutableList<SurfaceParseDiagnostic>.addBounded(value: SurfaceParseDiagnostic) {
        if (size < MAX_DIAGNOSTICS) add(value)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val MAX_DIAGNOSTICS = 256
}
