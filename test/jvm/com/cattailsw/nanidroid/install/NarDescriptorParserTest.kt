package com.cattailsw.nanidroid.install

import java.nio.charset.Charset
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin port of the descriptor parser characterization suite. */
class NarDescriptorParserTest {
    private val shiftJis = Charset.forName("Shift_JIS")
    private val utf8 = Charset.forName("UTF-8")

    @Test fun parsesRequiredGhostMetadataAndReturnsImmutableModel() {
        val result = parse("type,ghost\nname,Example Ghost\ndirectory,example\naccept,Author\n")
        assertTrue(result.isSuccess())
        val descriptor = result.getDescriptor()!!
        assertEquals("ghost", descriptor.getType()); assertEquals("Example Ghost", descriptor.getName())
        assertEquals("example", descriptor.getDescriptorDirectory()); assertEquals("example", descriptor.getTargetId())
        assertEquals("Author", descriptor.getAccept()); assertFalse(descriptor.isRefreshEnabled())
        try { (descriptor.getMetadata() as MutableMap<String, String>)["mutated"] = "true"; throw AssertionError("metadata must be immutable") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun supportsShiftJisDeclaredUtf8AndBom() {
        val japanese = "境界ゴースト"
        assertEquals(japanese, parseBytes(descriptor("sjis", japanese).toByteArray(shiftJis)).getDescriptor()!!.getName())
        assertEquals(japanese, parseBytes(("charset,UTF-8\n" + descriptor("utf8", japanese)).toByteArray(utf8)).getDescriptor()!!.getName())
        val data = ("charset,Shift_JIS\n" + descriptor("bom", japanese)).toByteArray(utf8)
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + data
        assertEquals(japanese, parseBytes(bom).getDescriptor()!!.getName())
        assertEquals("UTF-8", parseBytes(bom).getDescriptor()!!.getMetadata()["charset"])
    }

    @Test fun rejectsMalformedLinesDuplicateKeysAndEncodings() {
        assertError(NarInstallError.INVALID_METADATA, parse("type,ghost\nmalformed\nname,G\ndirectory,g\n"))
        assertError(NarInstallError.INVALID_METADATA, parse("type,ghost\ncharset,UTF-8\nname,G\ndirectory,g\n"))
        assertError(NarInstallError.INVALID_METADATA, parse(descriptor("g", "G") + "Name,Again\n"))
        assertError(NarInstallError.UNSUPPORTED_DESCRIPTOR_CHARSET, parse("charset,X-NOT-A-CHARSET\n" + descriptor("g", "G")))
        assertError(NarInstallError.INVALID_DESCRIPTOR_ENCODING, parseBytes("charset,UTF-8\n".toByteArray(utf8) + byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test fun acceptsLfCrlfBlankLinesAndCommasInValues() {
        assertTrue(parse(descriptor("lf", "LF")).isSuccess())
        val crlf = parse("type,ghost\r\n\r\nname,Ghost, With, Commas\r\ndirectory,crlf\r\n")
        assertTrue(crlf.isSuccess()); assertEquals("Ghost, With, Commas", crlf.getDescriptor()!!.getName())
    }

    @Test fun strictlyRejectsMalformedLinesAndEveryDuplicateKey() {
        assertError(NarInstallError.INVALID_METADATA, parse("type,ghost\n# comment\nname,G\ndirectory,g\n"))
        val complete = "charset,Shift_JIS\n" + descriptor("g", "G") + "accept,A\nrefresh,0\n"
        listOf("Type,ghost", "Name,Again", "Directory,again", "Accept,Again", "Refresh,2", "Charset,UTF-8").forEach {
            assertError(NarInstallError.INVALID_METADATA, parse(complete + it + "\n"))
        }
    }

    @Test fun distinguishesOfficialUnsupportedTypesFromUnknownType() {
        listOf("shell", "supplement", "balloon", "plugin", "headline", "language", "calendar skin", "calendar plugin", "calendar", "package").forEach {
            assertError(NarInstallError.UNSUPPORTED_TYPE, parse("type,$it\nname,G\ndirectory,g\n"))
        }
        assertError(NarInstallError.INVALID_TYPE, parse("type,unknown-package\nname,G\ndirectory,g\n"))
    }

    @Test fun preservesExactRefreshOneForFreshInstallPlanning() {
        assertTrue(parse(descriptor("g", "G") + "refresh,1\n").isSuccess())
        listOf("", "0", "2", "true", "01").forEach { assertTrue(parse(descriptor("g", "G") + "refresh,$it\n").isSuccess()) }
    }

    @Test fun preservesRecognizedCompoundInstallDirectivesForFreshInstallPlanning() {
        val prefixes = listOf("balloon", "balloon0", "headline", "headline12", "plugin", "plugin7", "calendar.skin", "calendar.skin3", "calendar.plugin", "calendar.plugin42")
        prefixes.forEach { prefix ->
            listOf("directory", "source.directory", "refreshundeletemask").forEach { directive -> assertTrue(parse(descriptor("g", "G") + "$prefix.$directive,value\n").isSuccess()) }
            assertTrue(parse(descriptor("g", "G") + "$prefix.refresh,0\n").isSuccess())
            assertTrue(parse(descriptor("g", "G") + "$prefix.refresh,1\n").isSuccess())
        }
    }

    @Test fun preservesUnknownCustomDottedMetadataAndValidationOrdering() {
        val result = parse(descriptor("g", "G") + "custom.directory,allowed\nballoonish.refresh,1\ncalendar.skin.extra.directory,allowed\n")
        assertTrue(result.isSuccess()); assertEquals("allowed", result.getDescriptor()!!.getMetadata()["custom.directory"])
        val compound = "balloon.directory,balloon\n"
        assertError(NarInstallError.MISSING_TYPE, parse("name,G\ndirectory,g\n$compound"))
        assertError(NarInstallError.INVALID_TYPE, parse("type, \nname,G\ndirectory,g\n$compound"))
        assertError(NarInstallError.UNSUPPORTED_TYPE, parse("type,shell\nname,G\ndirectory,g\n$compound"))
        assertError(NarInstallError.MISSING_METADATA, parse("type,ghost\ndirectory,g\n$compound"))
        assertError(NarInstallError.INVALID_TARGET_ID, parse(descriptor("../unsafe", "G") + compound))
        assertError(NarInstallError.INVALID_METADATA, parse(descriptor("g", "G") + compound + "Name,Again\n"))
        assertTrue(parse(descriptor("g", "G") + "refresh,1\n" + compound).isSuccess())
        assertTrue(parse(descriptor("g", "G") + compound).isSuccess())
    }

    @Test fun forcedIdOverridesOnlyAfterDescriptorDirectoryIsValidated() {
        val forced = parseBytes(descriptor("descriptor-id", "G").toByteArray(shiftJis), "forced-id")
        assertTrue(forced.isSuccess()); assertEquals("descriptor-id", forced.getDescriptor()!!.getDescriptorDirectory()); assertEquals("forced-id", forced.getDescriptor()!!.getTargetId())
        assertError(NarInstallError.INVALID_TARGET_ID, parseBytes(descriptor("../unsafe", "G").toByteArray(shiftJis), "safe"))
    }

    @Test fun rejectsUnsafeDescriptorAndForcedTargetVariants() {
        listOf("bad\u0001id", "bad\uD800id", " safe", "safe ", "\u2003safe", "a".repeat(256)).forEach {
            assertError(NarInstallError.INVALID_TARGET_ID, parseBytes(descriptor("safe", "G").toByteArray(shiftJis), it))
        }
        // Kotlin's default trim would accept this after stripping U+2003;
        // Java String.trim leaves it for normalizeTarget to reject.
        assertError(NarInstallError.INVALID_TARGET_ID, parseBytes("charset,UTF-8\ntype,ghost\nname,G\ndirectory,\u2003unsafe\n".toByteArray(utf8)))
    }

    @Test fun parseDoesNotMutateCallerDescriptorBytes() {
        val source = descriptor("stable", "Before").toByteArray(shiftJis); val expected = source.copyOf()
        assertTrue(parseBytes(source).isSuccess()); assertTrue(source.contentEquals(expected))
    }

    @Test fun validatesTypeAndMetadataWhilePreservingFreshInstallDirectives() {
        assertError(NarInstallError.MISSING_TYPE, parse("name,G\ndirectory,g\n"))
        assertError(NarInstallError.INVALID_TYPE, parse("type, \nname,G\ndirectory,g\n"))
        assertError(NarInstallError.UNSUPPORTED_TYPE, parse("type,shell\nname,G\ndirectory,g\n"))
        assertError(NarInstallError.MISSING_METADATA, parse("type,ghost\ndirectory,g\n"))
        assertTrue(parse(descriptor("g", "G") + "refresh,1\n").isSuccess())
        assertTrue(parse(descriptor("g", "G") + "balloon.directory,x\n").isSuccess())
        assertTrue(parse(descriptor("g", "G") + "Balloon0.Refresh,1\n").isSuccess())
        assertTrue(parse(descriptor("g", "G") + "custom.directory,allowed\n").isSuccess())
    }

    @Test fun validatesTargetSafetyNormalizationAndByteLimits() {
        listOf(".", "..", "../escape", "nested/id", "nested\\id", "/absolute", "C:drive", "\\\\server\\share").forEach { unsafe ->
            assertError(NarInstallError.INVALID_TARGET_ID, parse(descriptor(unsafe, "G")))
            assertError(NarInstallError.INVALID_TARGET_ID, parseBytes(descriptor("safe", "G").toByteArray(shiftJis), unsafe))
        }
        val nfd = Normalizer.normalize("café", Normalizer.Form.NFD)
        assertEquals("café", parseBytes(("charset,UTF-8\n" + descriptor(nfd, "G")).toByteArray(utf8)).getDescriptor()!!.getTargetId())
        assertTrue(parse(descriptor("a".repeat(255), "G")).isSuccess())
        assertError(NarInstallError.INVALID_TARGET_ID, parse(descriptor("a".repeat(256), "G")))
    }

    @Test fun enforcesDescriptorLimitAndNullInput() {
        assertTrue(parse(paddedDescriptor(64 * 1024)).isSuccess())
        assertError(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, parse(paddedDescriptor(64 * 1024 + 1)))
        assertError(NarInstallError.INVALID_METADATA, NarDescriptorParser().parse(null, null))
    }

    private fun parse(text: String) = parseBytes(text.toByteArray(shiftJis))
    private fun parseBytes(bytes: ByteArray, forcedId: String? = null) = NarDescriptorParser().parse(bytes, forcedId)
    private fun assertError(error: NarInstallError, result: NarDescriptorResult) { assertFalse(result.isSuccess()); assertEquals(error, result.getError()); assertNull(result.getDescriptor()) }
    private fun descriptor(directory: String, name: String) = "type,ghost\nname,$name\ndirectory,$directory\n"
    private fun paddedDescriptor(bytes: Int): String { val prefix = descriptor("safe", "G") + "padding,"; return prefix + "a".repeat(bytes - prefix.length) }
}
