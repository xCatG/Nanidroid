package com.cattailsw.nanidroid.llmghost.archive

import com.cattailsw.nanidroid.llmghost.model.GhostCorpusInput
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostSourceFile
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

sealed interface NarLoadResult {
    data class Success(
        val input: GhostCorpusInput,
        val entryHashes: Map<String, String>,
    ) : NarLoadResult

    data class Failure(
        val code: String,
        val detail: String,
    ) : NarLoadResult
}

class NarCorpusLoader {
    fun load(path: Path): NarLoadResult {
        if (!Files.exists(path)) {
            return failure("archive-not-found", "The NAR path does not exist.")
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return failure("archive-unreadable", "The NAR path is not a readable file.")
        }

        return try {
            ZipFile(path.toFile()).use(::loadArchive)
        } catch (rejected: RejectedArchive) {
            failure(rejected.code, rejected.safeDetail)
        } catch (_: ZipException) {
            failure("invalid-archive", "The supplied file is not a readable ZIP/NAR archive.")
        } catch (_: IOException) {
            failure("archive-unreadable", "The NAR archive could not be read.")
        } catch (_: SecurityException) {
            failure("archive-unreadable", "The NAR archive could not be accessed.")
        }
    }

    private fun loadArchive(zip: ZipFile): NarLoadResult {
        val entries = linkedMapOf<String, ByteArray>()
        val hashes = linkedMapOf<String, String>()
        val normalizedNames = mutableSetOf<String>()
        val enumeration = zip.entries()
        var entryCount = 0
        var totalBytes = 0L

        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            entryCount++
            if (entryCount > MAX_ENTRY_COUNT) {
                reject("entry-count-limit", "The NAR contains too many entries.")
            }

            val normalizedName = normalizeEntryName(entry.name)
            if (!normalizedNames.add(normalizedName)) {
                reject("duplicate-entry", "The NAR contains duplicate normalized entry names.")
            }
            if (entry.isDirectory) continue

            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            val output = ByteArrayOutputStream()
            var entryBytes = 0L
            try {
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES) {
                            reject("entry-size-limit", "A NAR entry exceeds the uncompressed size limit.")
                        }
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            reject("archive-size-limit", "The NAR exceeds the total uncompressed size limit.")
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        crc.update(buffer, 0, read)
                    }
                }
            } catch (rejected: RejectedArchive) {
                throw rejected
            } catch (_: IOException) {
                reject("entry-read-failed", "A NAR entry could not be read safely.")
            }

            if (entry.crc >= 0 && crc.value != entry.crc) {
                reject("entry-read-failed", "A NAR entry failed its integrity check.")
            }
            entries[normalizedName] = output.toByteArray()
            hashes[normalizedName] = digest.digest().toHexString()
        }

        var declaredCharset: SupportedCharset? = null
        entries.forEach { (name, bytes) ->
            if (isPotentialTextEntry(name)) {
                declaredCharset = foldCharsetDeclarations(bytes, declaredCharset)
            }
        }
        if (declaredCharset == null) {
            reject("missing-charset", "No supported charset declaration was found.")
        }
        val charset = declaredCharset.charset

        val descriptorBytes = entries[GHOST_DESCRIPTOR]
            ?: reject("missing-identity", "The ghost descriptor is missing.")
        val descriptor = decodeText(descriptorBytes, charset)
        val identityFields = parseDescriptor(descriptor)
        val ghostName = identityFields["name"].orEmpty()
        val sakuraName = identityFields["sakura.name"].orEmpty()
        val keroName = identityFields["kero.name"].orEmpty()
        if (ghostName.isBlank() || sakuraName.isBlank() || keroName.isBlank()) {
            reject("missing-identity", "The ghost descriptor does not contain a complete identity.")
        }

        val dictionaryNames = entries.keys.filter(::isDictionaryEntry).sorted()
        if (dictionaryNames.isEmpty()) {
            reject("missing-dictionary", "The NAR does not contain any supported ghost dictionaries.")
        }
        val dictionaries = dictionaryNames.map { name ->
            GhostSourceFile(path = name, text = decodeText(entries.getValue(name), charset))
        }

        val surfaceInventory = entries.keys.mapNotNullTo(linkedSetOf()) { name ->
            MASTER_SURFACE_PNG.matchEntire(name)?.groupValues?.get(1)?.let(::parseSurfaceId)
        }
        val surfaceEntryNames = entries.keys.filter(::isSurfaceEntry).sorted()
        val selectorBudget = SurfaceSelectorBudget()
        surfaceEntryNames.forEach { name ->
            surfaceInventory += parseSurfaceIds(
                text = decodeText(entries.getValue(name), charset),
                aggregateBudget = selectorBudget,
            )
        }
        if (surfaceInventory.isEmpty()) {
            reject("missing-shell-inventory", "The shell surface inventory is empty.")
        }

        return NarLoadResult.Success(
            input = GhostCorpusInput(
                identity = GhostIdentity(
                    ghostName = ghostName,
                    sakuraName = sakuraName,
                    keroName = keroName,
                    shellSurfaces = mapOf(
                        GhostSpeakerId.SAKURA to surfaceInventory.toSet(),
                        GhostSpeakerId.KERO to surfaceInventory.toSet(),
                    ),
                ),
                files = dictionaries,
            ),
            entryHashes = hashes,
        )
    }

    private fun normalizeEntryName(rawName: String): String {
        if (rawName.isEmpty() || rawName.indexOf('\u0000') >= 0) {
            reject("unsafe-entry-name", "The NAR contains an unsafe entry name.")
        }
        val slashName = rawName.replace('\\', '/')
        if (slashName.startsWith('/') || DRIVE_PATH.matches(slashName)) {
            reject("unsafe-entry-name", "The NAR contains an absolute entry name.")
        }

        val normalizedParts = mutableListOf<String>()
        slashName.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> reject("unsafe-entry-name", "The NAR contains a traversing entry name.")
                else -> normalizedParts += part
            }
        }
        if (normalizedParts.isEmpty()) {
            reject("unsafe-entry-name", "The NAR contains an unsafe entry name.")
        }
        return normalizedParts.joinToString("/").lowercase(Locale.ROOT)
    }

    private fun foldCharsetDeclarations(
        bytes: ByteArray,
        initial: SupportedCharset?,
    ): SupportedCharset? {
        var declared = initial
        var lineStart = 0
        while (lineStart <= bytes.size) {
            var lineEnd = lineStart
            while (lineEnd < bytes.size && bytes[lineEnd] != '\n'.code.toByte()) lineEnd++
            val candidate = scanCharsetDeclaration(bytes, lineStart, lineEnd)
            if (candidate != null) {
                if (declared != null && declared != candidate) {
                    reject("inconsistent-charset", "Text entries declare inconsistent character encodings.")
                }
                declared = candidate
            }
            if (lineEnd == bytes.size) break
            lineStart = lineEnd + 1
        }
        return declared
    }

    private fun scanCharsetDeclaration(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): SupportedCharset? {
        var cursor = start
        if (
            start == 0 && end - start >= 3 &&
            bytes[start] == 0xef.toByte() &&
            bytes[start + 1] == 0xbb.toByte() &&
            bytes[start + 2] == 0xbf.toByte()
        ) {
            cursor += 3
        }
        while (cursor < end && bytes[cursor].isAsciiWhitespace()) cursor++
        if (!bytes.matchesAsciiIgnoreCase(cursor, end, "charset")) return null
        cursor += "charset".length
        while (cursor < end && bytes[cursor].isAsciiWhitespace()) cursor++
        if (cursor >= end || bytes[cursor] != ','.code.toByte()) return null
        cursor++
        while (cursor < end && bytes[cursor].isAsciiWhitespace()) cursor++
        val aliasStart = cursor
        while (cursor < end && bytes[cursor].isCharsetAliasCharacter()) cursor++
        if (cursor == aliasStart) return null
        val aliasEnd = cursor
        while (cursor < end && bytes[cursor].isAsciiWhitespace()) cursor++
        if (
            cursor < end &&
            !(cursor + 1 < end && bytes[cursor] == '/'.code.toByte() && bytes[cursor + 1] == '/'.code.toByte())
        ) {
            return null
        }

        return when {
            bytes.aliasEquals(aliasStart, aliasEnd, "utf8") -> SupportedCharset.UTF_8
            bytes.aliasEquals(aliasStart, aliasEnd, "shiftjis") ||
                bytes.aliasEquals(aliasStart, aliasEnd, "sjis") ||
                bytes.aliasEquals(aliasStart, aliasEnd, "mskanji") -> SupportedCharset.SHIFT_JIS
            bytes.aliasEquals(aliasStart, aliasEnd, "windows31j") ||
                bytes.aliasEquals(aliasStart, aliasEnd, "windows932") ||
                bytes.aliasEquals(aliasStart, aliasEnd, "cp932") ||
                bytes.aliasEquals(aliasStart, aliasEnd, "ms932") -> SupportedCharset.WINDOWS_31J
            else -> reject("unsupported-charset", "The NAR declares an unsupported character encoding.")
        }
    }

    private fun decodeText(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        reject("malformed-text", "A declared text entry contains malformed or unmappable bytes.")
    }

    private fun parseDescriptor(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            val comma = line.indexOf(',')
            if (comma > 0) {
                val key = line.substring(0, comma).trim().lowercase(Locale.ROOT)
                val value = line.substring(comma + 1).trim()
                if (key !in this) put(key, value)
            }
        }
    }

    private fun parseSurfaceIds(
        text: String,
        aggregateBudget: SurfaceSelectorBudget,
    ): Set<Int> {
        val inventory = linkedSetOf<Int>()
        var fileWork = 0L
        var state: SurfaceParseState = SurfaceParseState.TopLevel

        fun charge(amount: Long) {
            if (
                amount < 0 ||
                fileWork > MAX_SURFACE_FILE_WORK - amount ||
                aggregateBudget.work > MAX_SURFACE_TOTAL_WORK - amount
            ) {
                reject("invalid-shell-inventory", "The shell surface selector work limit was exceeded.")
            }
            fileWork += amount
            aggregateBudget.work += amount
        }

        fun materialize(selector: String, append: Boolean) {
            charge(1)
            val included = linkedSetOf<Int>()
            val exclusions = linkedSetOf<Int>()
            selector.splitToSequence(',').forEach tokenLoop@{ rawToken ->
                charge(1)
                var token = rawToken.trim().lowercase(Locale.ROOT)
                val exclusion = token.startsWith('!')
                if (exclusion) token = token.drop(1).trim()
                if (token.startsWith("surface")) token = token.removePrefix("surface")
                val match = SURFACE_RANGE.matchEntire(token) ?: return@tokenLoop
                val first = parseSurfaceId(match.groupValues[1])
                val last = match.groupValues[2].takeIf(String::isNotEmpty)?.let(::parseSurfaceId) ?: first
                if (last < first || last.toLong() - first > MAX_SURFACE_RANGE) {
                    reject("invalid-shell-inventory", "The shell contains an invalid surface range.")
                }
                val cardinality = last.toLong() - first + 1
                charge(cardinality)
                if (append) return@tokenLoop
                for (surface in first..last) {
                    if (exclusion) {
                        exclusions += surface
                        included -= surface
                    } else if (surface !in exclusions) {
                        included += surface
                    }
                }
            }
            inventory += included
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("//").trim()
            var reprocess = true
            while (reprocess) {
                reprocess = false
                state = when (val current = state) {
                    SurfaceParseState.TopLevel -> when {
                        line.isEmpty() -> SurfaceParseState.TopLevel
                        isDescriptDeclaration(line) -> {
                            if ('{' in line) SurfaceParseState.DescriptBlock else SurfaceParseState.ExpectDescriptOpen
                        }
                        isSurfaceDeclaration(line) -> {
                            val parsed = parseSurfaceDeclaration(line)
                            if ('{' in line) {
                                SurfaceParseState.SurfaceBlock(parsed.selector, parsed.append)
                            } else {
                                SurfaceParseState.ExpectSurfaceOpen(parsed.selector, parsed.append)
                            }
                        }
                        else -> SurfaceParseState.TopLevel
                    }
                    SurfaceParseState.ExpectDescriptOpen -> when {
                        line.isEmpty() -> current
                        line == "{" -> SurfaceParseState.DescriptBlock
                        else -> {
                            reprocess = true
                            SurfaceParseState.TopLevel
                        }
                    }
                    is SurfaceParseState.ExpectSurfaceOpen -> when {
                        line.isEmpty() -> current
                        line == "{" -> SurfaceParseState.SurfaceBlock(current.selector, current.append)
                        else -> {
                            reprocess = true
                            SurfaceParseState.TopLevel
                        }
                    }
                    SurfaceParseState.DescriptBlock -> when {
                        line.startsWith('}') -> SurfaceParseState.TopLevel
                        else -> current
                    }
                    is SurfaceParseState.SurfaceBlock -> when {
                        line.startsWith('}') -> {
                            materialize(current.selector, current.append)
                            SurfaceParseState.TopLevel
                        }
                        isSurfaceDeclaration(line) -> {
                            reprocess = true
                            SurfaceParseState.TopLevel
                        }
                        else -> current
                    }
                }
            }
        }
        return inventory
    }

    private fun isDescriptDeclaration(line: String): Boolean =
        line.substringBefore('{').trim().equals("descript", ignoreCase = true)

    private fun isSurfaceDeclaration(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return lower.startsWith("surface") && !lower.startsWith("surface.alias")
    }

    private fun parseSurfaceDeclaration(line: String): SurfaceDeclaration {
        val declaration = line.substringBefore('{').trim()
        val lower = declaration.lowercase(Locale.ROOT)
        val append = lower.startsWith("surface.append")
        val prefixLength = if (append) "surface.append".length else "surface".length
        return SurfaceDeclaration(
            selector = declaration.substring(prefixLength).trim(),
            append = append,
        )
    }

    private fun parseSurfaceId(value: String): Int {
        val surface = value.toIntOrNull()
            ?: reject("invalid-shell-inventory", "The shell contains an invalid surface identifier.")
        if (surface < 0 || surface > MAX_SURFACE_ID) {
            reject("invalid-shell-inventory", "The shell contains an out-of-range surface identifier.")
        }
        return surface
    }

    private fun isPotentialTextEntry(name: String): Boolean =
        name == "install.txt" ||
            name == GHOST_DESCRIPTOR ||
            isDictionaryEntry(name) ||
            isSurfaceEntry(name) ||
            name == "shell/master/descript.txt"

    private fun isDictionaryEntry(name: String): Boolean {
        if (!name.startsWith("ghost/master/")) return false
        val fileName = name.substringAfterLast('/')
        return fileName.startsWith("dic") &&
            (fileName.endsWith(".txt") || fileName.endsWith(".sat"))
    }

    private fun isSurfaceEntry(name: String): Boolean = MASTER_SURFACE_SOURCE.matches(name)

    private fun failure(code: String, detail: String) = NarLoadResult.Failure(code, detail)

    private fun reject(code: String, detail: String): Nothing = throw RejectedArchive(code, detail)

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun Byte.isAsciiWhitespace(): Boolean = (toInt() and 0xff) <= 0x20

    private fun Byte.isCharsetAliasCharacter(): Boolean {
        val value = toInt() and 0xff
        return value in 'A'.code..'Z'.code ||
            value in 'a'.code..'z'.code ||
            value in '0'.code..'9'.code ||
            value == '.'.code || value == '-'.code || value == '_'.code
    }

    private fun ByteArray.matchesAsciiIgnoreCase(start: Int, end: Int, expected: String): Boolean {
        if (end - start < expected.length) return false
        return expected.indices.all { index ->
            (this[start + index].toInt() and 0xff).toChar().lowercaseChar() == expected[index]
        }
    }

    private fun ByteArray.aliasEquals(start: Int, end: Int, expected: String): Boolean {
        var cursor = start
        var expectedIndex = 0
        while (cursor < end) {
            val value = (this[cursor].toInt() and 0xff).toChar().lowercaseChar()
            cursor++
            if (value == '-' || value == '_' || value == '.') continue
            if (expectedIndex >= expected.length || value != expected[expectedIndex]) return false
            expectedIndex++
        }
        return expectedIndex == expected.length
    }

    private enum class SupportedCharset(val charset: Charset) {
        UTF_8(StandardCharsets.UTF_8),
        SHIFT_JIS(Charset.forName("windows-31j")),
        WINDOWS_31J(Charset.forName("windows-31j")),
    }

    private data class SurfaceDeclaration(
        val selector: String,
        val append: Boolean,
    )

    private sealed interface SurfaceParseState {
        data object TopLevel : SurfaceParseState
        data object ExpectDescriptOpen : SurfaceParseState
        data object DescriptBlock : SurfaceParseState
        data class ExpectSurfaceOpen(val selector: String, val append: Boolean) : SurfaceParseState
        data class SurfaceBlock(val selector: String, val append: Boolean) : SurfaceParseState
    }

    private class SurfaceSelectorBudget(var work: Long = 0)

    private class RejectedArchive(
        val code: String,
        val safeDetail: String,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val COPY_BUFFER_SIZE = 16 * 1024
        const val MAX_SURFACE_ID = 1_000_000
        const val MAX_SURFACE_RANGE = 100_000L
        const val MAX_SURFACE_FILE_WORK = 100_000L
        const val MAX_SURFACE_TOTAL_WORK = 250_000L
        const val GHOST_DESCRIPTOR = "ghost/master/descript.txt"

        val DRIVE_PATH = Regex("^[A-Za-z]:.*")
        val SURFACE_RANGE = Regex("^([0-9]+)(?:-([0-9]+))?$")
        val MASTER_SURFACE_SOURCE = Regex("^shell/master/surfaces[^/]*\\.txt$")
        val MASTER_SURFACE_PNG = Regex("^shell/master/surface([0-9]+)\\.png$")
    }
}
