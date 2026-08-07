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

        val textEntryNames = entries.keys.filter(::isPotentialTextEntry)
        val declaredCharsets = textEntryNames
            .flatMap { name -> scanCharsetDeclarations(entries.getValue(name)) }
            .map(::supportedCharset)
        if (declaredCharsets.isEmpty()) {
            reject("missing-charset", "No supported charset declaration was found.")
        }
        val canonicalCharsets = declaredCharsets.map { it.canonicalName }.toSet()
        if (canonicalCharsets.size != 1) {
            reject("inconsistent-charset", "Text entries declare inconsistent character encodings.")
        }
        val charset = declaredCharsets.first().charset

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
        surfaceEntryNames.forEach { name ->
            surfaceInventory += parseSurfaceIds(decodeText(entries.getValue(name), charset))
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

    private fun scanCharsetDeclarations(bytes: ByteArray): List<String> {
        val declarations = mutableListOf<String>()
        var lineStart = 0
        while (lineStart <= bytes.size) {
            var lineEnd = lineStart
            while (lineEnd < bytes.size && bytes[lineEnd] != '\n'.code.toByte()) lineEnd++
            var contentStart = lineStart
            if (
                lineStart == 0 && lineEnd - lineStart >= 3 &&
                bytes[lineStart] == 0xef.toByte() &&
                bytes[lineStart + 1] == 0xbb.toByte() &&
                bytes[lineStart + 2] == 0xbf.toByte()
            ) {
                contentStart += 3
            }
            val lineBytes = bytes.copyOfRange(contentStart, lineEnd)
            if (lineBytes.all { (it.toInt() and 0xff) <= 0x7f }) {
                val line = String(lineBytes, StandardCharsets.US_ASCII).trimEnd('\r')
                CHARSET_DECLARATION.matchEntire(line)?.groupValues?.get(1)?.let(declarations::add)
            }
            if (lineEnd == bytes.size) break
            lineStart = lineEnd + 1
        }
        return declarations
    }

    private fun supportedCharset(declaration: String): SupportedCharset {
        val alias = declaration.lowercase(Locale.ROOT).filterNot { it == '-' || it == '_' || it == '.' }
        return when (alias) {
            "utf8" -> SupportedCharset("utf-8", StandardCharsets.UTF_8)
            "shiftjis", "sjis", "mskanji" ->
                SupportedCharset("shift-jis", Charset.forName("windows-31j"))
            "windows31j", "windows932", "cp932", "ms932" ->
                SupportedCharset("windows-31j", Charset.forName("windows-31j"))
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

    private fun parseSurfaceIds(text: String): Set<Int> {
        val inventory = linkedSetOf<Int>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("//")
            val declaration = SURFACE_DECLARATION.matchEntire(line) ?: return@forEach
            if (declaration.groupValues[1].isNotEmpty()) return@forEach
            val selector = declaration.groupValues[2]
            val included = linkedSetOf<Int>()
            val exclusions = linkedSetOf<Int>()
            selector.split(',').forEach tokenLoop@{ rawToken ->
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
        return inventory
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

    private data class SupportedCharset(
        val canonicalName: String,
        val charset: Charset,
    )

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
        const val GHOST_DESCRIPTOR = "ghost/master/descript.txt"

        val DRIVE_PATH = Regex("^[A-Za-z]:.*")
        val CHARSET_DECLARATION = Regex(
            "^\\s*charset\\s*,\\s*([A-Za-z0-9._-]+)\\s*(?://.*)?$",
            RegexOption.IGNORE_CASE,
        )
        val SURFACE_DECLARATION = Regex(
            "^\\s*surface(\\.append)?\\s*([^\\s{]+)\\s*(?:\\{.*)?$",
            RegexOption.IGNORE_CASE,
        )
        val SURFACE_RANGE = Regex("^([0-9]+)(?:-([0-9]+))?$")
        val MASTER_SURFACE_SOURCE = Regex("^shell/master/surfaces[^/]*\\.txt$")
        val MASTER_SURFACE_PNG = Regex("^shell/master/surface([0-9]+)\\.png$")
    }
}
