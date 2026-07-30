package com.cattailsw.nanidroid.install

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** Pure parser for a defensively snapshotted NAR install descriptor. */
class NarDescriptorParser {
    fun parse(descriptor: ByteArray?, forcedTargetId: String?): NarDescriptorResult {
        if (descriptor == null) return NarDescriptorResult.failure(NarInstallError.INVALID_METADATA, "null descriptor")
        if (descriptor.size > MAX_DESCRIPTOR_BYTES) return NarDescriptorResult.failure(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, "descriptor exceeds 64 KiB")
        return try {
            NarDescriptorResult.success(parseSnapshot(descriptor.copyOf(), forcedTargetId))
        } catch (rejected: Rejected) {
            NarDescriptorResult.failure(rejected.error, rejected.message!!)
        }
    }

    @Throws(Rejected::class)
    private fun parseSnapshot(bytes: ByteArray, forcedTargetId: String?): NarInstallDescriptor {
        val encoding = selectEncoding(bytes)
        val metadata = parseLines(decode(bytes, encoding.offset, encoding.charset))
        metadata["charset"] = encoding.charset.name()
        val type = metadata["type"] ?: reject(NarInstallError.MISSING_TYPE, "type is required")
        if (type.isEmpty()) reject(NarInstallError.INVALID_TYPE, "blank type")
        val normalizedType = collisionKey(type)
        if (normalizedType != "ghost") reject(if (normalizedType in UNSUPPORTED_TYPES) NarInstallError.UNSUPPORTED_TYPE else NarInstallError.INVALID_TYPE, normalizedType)
        if (metadata["name"].isNullOrEmpty() || metadata["directory"].isNullOrEmpty()) reject(NarInstallError.MISSING_METADATA, "name and directory are required")
        val descriptorDirectory = normalizeTarget(metadata["directory"]) ?: reject(NarInstallError.INVALID_TARGET_ID, "unsafe directory")
        val targetId = if (forcedTargetId == null) descriptorDirectory else normalizeTarget(forcedTargetId) ?: reject(NarInstallError.INVALID_TARGET_ID, "unsafe forced id")
        // Fresh installs extract a complete archive into a new target. Preserve
        // refresh and companion metadata for runtime compatibility; update code
        // continues to apply its own refresh restrictions.
        metadata["type"] = "ghost"
        metadata["directory"] = descriptorDirectory
        return NarInstallDescriptor("ghost", metadata["name"]!!, descriptorDirectory, targetId, metadata["accept"], metadata)
    }

    @Throws(Rejected::class)
    private fun selectEncoding(bytes: ByteArray): Encoding {
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xff) == 0xef && (bytes[1].toInt() and 0xff) == 0xbb && (bytes[2].toInt() and 0xff) == 0xbf) return Encoding(UTF_8, 3)
        var end = 0
        while (end < bytes.size && bytes[end] != '\n'.code.toByte() && bytes[end] != '\r'.code.toByte()) end++
        val firstLine = String(bytes, 0, end, ASCII)
        val comma = firstLine.indexOf(',')
        if (comma > 0 && collisionKey(javaTrim(firstLine.substring(0, comma))) == "charset") {
            val name = javaTrim(firstLine.substring(comma + 1))
            return try { Encoding(Charset.forName(name), 0) } catch (_: RuntimeException) { reject(NarInstallError.UNSUPPORTED_DESCRIPTOR_CHARSET, name) }
        }
        return Encoding(SHIFT_JIS, 0)
    }

    @Throws(Rejected::class)
    private fun decode(bytes: ByteArray, offset: Int, charset: Charset): String = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    } catch (_: CharacterCodingException) { reject(NarInstallError.INVALID_DESCRIPTOR_ENCODING, charset.name()) }

    @Throws(Rejected::class)
    private fun parseLines(text: String): LinkedHashMap<String, String> {
        val metadata = linkedMapOf<String, String>()
        // Java used a negative split limit, which retains trailing empty
        // fields. Kotlin requires a non-negative limit; Int.MAX_VALUE is the
        // equivalent unbounded, trailing-field-preserving limit.
        text.split(Regex("\\r?\\n"), Int.MAX_VALUE).forEach { line ->
            if (javaTrim(line).isEmpty()) return@forEach
            val comma = line.indexOf(',')
            if (comma <= 0) reject(NarInstallError.INVALID_METADATA, "malformed line")
            val key = collisionKey(javaTrim(line.substring(0, comma)))
            val value = Normalizer.normalize(javaTrim(line.substring(comma + 1)), Normalizer.Form.NFC)
            if (key.isEmpty() || containsControl(key) || containsControl(value) || (key == "charset" && metadata.isNotEmpty()) || metadata.containsKey(key)) reject(NarInstallError.INVALID_METADATA, "invalid or duplicate metadata")
            metadata[key] = value
        }
        return LinkedHashMap(metadata)
    }


    private fun normalizeTarget(value: String?): String? {
        if (value == null || value.isEmpty() || value.length > MAX_TARGET_BYTES || !validUnicode(value) || hasBoundaryWhitespace(value) || '/' in value || '\\' in value || ':' in value || containsControl(value) || value == "." || value == "..") return null
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        return if (normalized.toByteArray(UTF_8).size <= MAX_TARGET_BYTES) normalized else null
    }
    /** Exact Java String.trim() behavior: only UTF-16 code units <= U+0020. */
    private fun javaTrim(value: String): String = value.trim { it <= '\u0020' }
    private fun hasBoundaryWhitespace(value: String): Boolean { val first = value.codePointAt(0); val last = value.codePointBefore(value.length); return Character.isWhitespace(first) || Character.isSpaceChar(first) || Character.isWhitespace(last) || Character.isSpaceChar(last) }
    private fun collisionKey(value: String): String = Normalizer.normalize(Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.US), Normalizer.Form.NFC)
    private fun containsControl(value: String): Boolean = value.any { Character.isISOControl(it) }
    private fun validUnicode(value: String): Boolean { var index = 0; while (index < value.length) { val current = value[index]; if (Character.isHighSurrogate(current)) { if (++index >= value.length || !Character.isLowSurrogate(value[index])) return false } else if (Character.isLowSurrogate(current)) return false; index++ }; return true }
    @Throws(Rejected::class) private fun reject(error: NarInstallError, detail: String): Nothing = throw Rejected(error, detail)
    private class Encoding(val charset: Charset, val offset: Int)
    private class Rejected(val error: NarInstallError, detail: String) : Exception(detail)
    private companion object {
        val ASCII: Charset = Charset.forName("US-ASCII"); val SHIFT_JIS: Charset = Charset.forName("Shift_JIS"); val UTF_8: Charset = Charset.forName("UTF-8")
        const val MAX_DESCRIPTOR_BYTES = 64 * 1024; const val MAX_TARGET_BYTES = 255
        val UNSUPPORTED_TYPES = setOf("shell", "supplement", "balloon", "plugin", "headline", "language", "calendar skin", "calendar plugin", "calendar", "package")
    }
}
