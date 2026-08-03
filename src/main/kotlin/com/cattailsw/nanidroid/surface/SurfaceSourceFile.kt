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
        val session = Session()
        inputs.sortedWith(filenameComparator).forEach(session::offer)
        return session.result()
    }

    internal fun newSession(): Session = Session()

    internal class Session internal constructor() {
        private val files = mutableListOf<SurfaceSourceFile>()
        private val diagnostics = mutableListOf<SurfaceParseDiagnostic>()
        private var attemptedFiles = 0
        private var attemptedBytes = 0L
        private var acceptedBytes = 0L
        private var acceptedLines = 0L
        private var previousName: String? = null

        internal fun begin(name: String): Boolean {
            observeName(name)
            if (attemptedFiles >= MAX_ATTEMPTED_FILES || attemptedBytes >= MAX_ATTEMPTED_BYTES) {
                diagnostics.addBounded(diagnostic(name, name, "source attempt budget exhausted"))
                return false
            }
            attemptedFiles++
            return true
        }

        internal fun maxReadBytes(): Int =
            minOf(MAX_SOURCE_BYTES + 1L, MAX_ATTEMPTED_BYTES - attemptedBytes + 1L).toInt()

        internal fun decodeStarted(input: SurfaceSourceInput): SurfaceSourceFile? {
            val byteCount = input.bytes.size.toLong()
            if (attemptedBytes + byteCount > MAX_ATTEMPTED_BYTES) {
                attemptedBytes = MAX_ATTEMPTED_BYTES
                diagnostics.addBounded(diagnostic(input.name, input.name, "attempted source bytes exhausted"))
                return null
            }
            attemptedBytes += byteCount
            if (input.bytes.size > MAX_SOURCE_BYTES) {
                diagnostics.addBounded(diagnostic(input.name, input.name, "surface source exceeds byte limit"))
                return null
            }
            if (files.size >= MAX_ACCEPTED_FILES || acceptedBytes + byteCount > MAX_ACCEPTED_BYTES) {
                diagnostics.addBounded(diagnostic(input.name, input.name, "accepted source budget exhausted"))
                return null
            }

            val decoded = decodeOne(input, diagnostics) ?: return null
            val shape = scanDecoded(decoded.text)
            if (shape == null || acceptedLines + shape.lineCount > MAX_ACCEPTED_LINES) {
                diagnostics.addBounded(diagnostic(input.name, input.name, "decoded line budget exhausted"))
                return null
            }
            val file = SurfaceSourceFile(
                input.name,
                decoded.charset,
                decoded.text.split('\n').map { it.removeSuffix("\r") },
            )
            acceptedBytes += byteCount
            acceptedLines += shape.lineCount
            files += file
            return file
        }

        fun offer(input: SurfaceSourceInput): SurfaceSourceFile? =
            if (begin(input.name)) decodeStarted(input) else null

        internal fun rejectStarted(name: String, bytesRead: Int = 0) {
            attemptedBytes = minOf(MAX_ATTEMPTED_BYTES, attemptedBytes + bytesRead)
            diagnostics.addBounded(diagnostic(name, name, "surface source read failed"))
        }

        internal fun rejectOversizedUnopened(name: String) {
            diagnostics.addBounded(diagnostic(name, name, "surface source exceeds byte limit"))
        }

        internal fun result(): SurfaceSourceDecodeResult =
            SurfaceSourceDecodeResult(files.toList(), diagnostics.toList())

        private fun observeName(name: String) {
            val previous = previousName
            if (previous != null &&
                previous.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) &&
                previous != name
            ) {
                diagnostics.addBounded(diagnostic(name, name, "case-colliding surface source filename"))
            }
            previousName = name
        }
    }

    private data class DecodedSource(val charset: Charset, val text: String)
    private data class DecodedShape(val lineCount: Long)

    private fun decodeOne(
        input: SurfaceSourceInput,
        diagnostics: MutableList<SurfaceParseDiagnostic>,
    ): DecodedSource? {
        if (!rawLinesWithinLimits(input.bytes)) {
            diagnostics.addBounded(diagnostic(input.name, input.name, "physical line limit exceeded"))
            return null
        }
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
            declared == windows31j -> listOf(windows31j, utf8)
            declared != null -> listOf(declared)
            else -> listOf(utf8, windows31j)
        }
        for (charset in candidates) {
            val decoded = strictDecode(bytes, charset) ?: continue
            if (declared == windows31j && charset == utf8) {
                diagnostics.addBounded(
                    diagnostic(input.name, declarationText, "legacy declaration contains strict UTF-8"),
                )
            }
            return DecodedSource(charset, decoded)
        }

        diagnostics.addBounded(diagnostic(input.name, declarationText, "source is not valid in its allowed charset"))
        return null
    }

    private fun rawLinesWithinLimits(bytes: ByteArray): Boolean {
        var lineCount = 1
        var lineBytes = 0
        bytes.forEach { value ->
            if (value == '\n'.code.toByte()) {
                lineCount++
                lineBytes = 0
            } else {
                lineBytes++
                if (lineBytes > MAX_LINE_LENGTH) return false
            }
            if (lineCount > MAX_LINES_PER_FILE) return false
        }
        return true
    }

    private fun scanDecoded(text: String): DecodedShape? {
        var lineCount = 1L
        var lineLength = 0
        text.forEach { value ->
            if (value == '\n') {
                lineCount++
                lineLength = 0
            } else if (value != '\r') {
                lineLength++
                if (lineLength > MAX_LINE_LENGTH) return null
            }
            if (lineCount > MAX_LINES_PER_FILE) return null
        }
        return DecodedShape(lineCount)
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
    internal const val MAX_SOURCE_BYTES = 1_048_576
    private const val MAX_ATTEMPTED_FILES = 512
    private const val MAX_ACCEPTED_FILES = 256
    private const val MAX_ATTEMPTED_BYTES = 8L * 1_048_576L
    private const val MAX_ACCEPTED_BYTES = 4L * 1_048_576L
    private const val MAX_LINE_LENGTH = 65_536
    private const val MAX_LINES_PER_FILE = 20_000
    private const val MAX_ACCEPTED_LINES = 100_000L
}
