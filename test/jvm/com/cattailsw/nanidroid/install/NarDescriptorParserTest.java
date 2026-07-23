package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.Arrays;

import org.junit.Test;

public final class NarDescriptorParserTest {
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Test
    public void parsesRequiredGhostMetadataAndReturnsImmutableModel() {
        NarDescriptorResult result = parse(
                "type,ghost\n"
                        + "name,Example Ghost\n"
                        + "directory,example\n"
                        + "accept,Author\n");

        assertTrue(result.isSuccess());
        NarInstallDescriptor descriptor = result.getDescriptor();
        assertEquals("ghost", descriptor.getType());
        assertEquals("Example Ghost", descriptor.getName());
        assertEquals("example", descriptor.getDescriptorDirectory());
        assertEquals("example", descriptor.getTargetId());
        assertEquals("Author", descriptor.getAccept());
        assertFalse(descriptor.isRefreshEnabled());
        try {
            descriptor.getMetadata().put("mutated", "true");
            throw new AssertionError("metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void defaultsToShiftJisAndSupportsDeclaredUtf8AndBom() {
        String japanese = "\u5883\u754c\u30b4\u30fc\u30b9\u30c8";
        assertEquals(
                japanese,
                parseBytes(
                        descriptor("sjis", japanese).getBytes(SHIFT_JIS),
                        null).getDescriptor().getName());
        assertEquals(
                japanese,
                parseBytes(
                        ("charset,UTF-8\n" + descriptor("utf8", japanese))
                                .getBytes(UTF_8),
                        null).getDescriptor().getName());

        byte[] utf8 = ("charset,Shift_JIS\n" + descriptor("bom", japanese))
                .getBytes(UTF_8);
        byte[] bom = new byte[utf8.length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(utf8, 0, bom, 3, utf8.length);
        NarInstallDescriptor bomDescriptor =
                parseBytes(bom, null).getDescriptor();
        assertEquals(japanese, bomDescriptor.getName());
        assertEquals("UTF-8", bomDescriptor.getMetadata().get("charset"));
    }

    @Test
    public void charsetDeclarationIsRecognizedOnlyOnFirstLine() {
        assertError(
                NarInstallError.INVALID_METADATA,
                parseBytes(
                        ("type,ghost\ncharset,UTF-8\nname,G\ndirectory,g\n")
                                .getBytes(UTF_8),
                        null));
    }

    @Test
    public void rejectsUnsupportedAndMalformedDescriptorEncoding() {
        assertError(
                NarInstallError.UNSUPPORTED_DESCRIPTOR_CHARSET,
                parse("charset,X-NOT-A-CHARSET\n" + descriptor("g", "G")));

        byte[] utf8Prefix = "charset,UTF-8\n".getBytes(UTF_8);
        byte[] malformedUtf8 = Arrays.copyOf(
                utf8Prefix,
                utf8Prefix.length + 2);
        malformedUtf8[utf8Prefix.length] = (byte) 0xc3;
        malformedUtf8[utf8Prefix.length + 1] = (byte) 0x28;
        assertError(
                NarInstallError.INVALID_DESCRIPTOR_ENCODING,
                parseBytes(malformedUtf8, null));

        byte[] shiftJisPrefix = "type,ghost\nname,G\ndirectory,g\npadding,"
                .getBytes(SHIFT_JIS);
        byte[] malformedShiftJis = Arrays.copyOf(
                shiftJisPrefix,
                shiftJisPrefix.length + 1);
        malformedShiftJis[shiftJisPrefix.length] = (byte) 0x82;
        assertError(
                NarInstallError.INVALID_DESCRIPTOR_ENCODING,
                parseBytes(malformedShiftJis, null));
    }

    @Test
    public void acceptsLfCrlfBlankLinesAndCommasInValues() {
        assertTrue(parse(descriptor("lf", "LF")).isSuccess());
        NarDescriptorResult crlf = parse(
                "type,ghost\r\n\r\nname,Ghost, With, Commas\r\n"
                        + "directory,crlf\r\n");
        assertTrue(crlf.isSuccess());
        assertEquals("Ghost, With, Commas", crlf.getDescriptor().getName());
    }

    @Test
    public void strictlyRejectsMalformedLinesAndEveryDuplicateKey() {
        assertError(
                NarInstallError.INVALID_METADATA,
                parse("type,ghost\n# comment\nname,G\ndirectory,g\n"));
        assertError(
                NarInstallError.INVALID_METADATA,
                parse("type,ghost\nmalformed\nname,G\ndirectory,g\n"));
        String complete = "charset,Shift_JIS\n"
                + descriptor("g", "G")
                + "accept,A\nrefresh,0\n";
        String[] duplicateLines = {
            "Type,ghost", "Name,Again", "Directory,again",
            "Accept,Again", "Refresh,2", "Charset,UTF-8",
        };
        for (String duplicate : duplicateLines) {
            assertError(
                    NarInstallError.INVALID_METADATA,
                    parse(complete + duplicate + "\n"));
        }
    }

    @Test
    public void requiresGhostTypeNameAndDirectory() {
        assertError(NarInstallError.MISSING_TYPE, parse(""));
        assertError(
                NarInstallError.MISSING_TYPE,
                parse("name,G\ndirectory,g\n"));
        assertError(
                NarInstallError.INVALID_TYPE,
                parse("type, \nname,G\ndirectory,g\n"));
        assertError(
                NarInstallError.MISSING_METADATA,
                parse("type,ghost\ndirectory,g\n"));
        assertError(
                NarInstallError.MISSING_METADATA,
                parse("type,ghost\nname,G\n"));
    }

    @Test
    public void distinguishesOfficialUnsupportedTypesFromUnknownType() {
        String[] unsupported = {
            "shell",
            "supplement",
            "balloon",
            "plugin",
            "headline",
            "language",
            "calendar skin",
            "calendar plugin",
            "calendar",
            "package",
        };
        for (String type : unsupported) {
            assertError(
                    NarInstallError.UNSUPPORTED_TYPE,
                    parse("type," + type + "\nname,G\ndirectory,g\n"));
        }
        assertError(
                NarInstallError.INVALID_TYPE,
                parse("type,unknown-package\nname,G\ndirectory,g\n"));
    }

    @Test
    public void onlyExactRefreshOneIsUnsupported() {
        assertError(
                NarInstallError.UNSUPPORTED_REFRESH,
                parse(descriptor("g", "G") + "refresh,1\n"));
        for (String value : new String[] {"", "0", "2", "true", "01"}) {
            assertTrue(parse(
                    descriptor("g", "G") + "refresh," + value + "\n")
                    .isSuccess());
        }
    }

    @Test
    public void rejectsEveryRecognizedCompoundInstallDirective() {
        String[] prefixes = {
            "balloon", "balloon0",
            "headline", "headline12",
            "plugin", "plugin7",
            "calendar.skin", "calendar.skin3",
            "calendar.plugin", "calendar.plugin42",
        };
        String[] unsupportedDirectives = {
            "directory", "source.directory", "refreshundeletemask",
        };
        for (String prefix : prefixes) {
            for (String directive : unsupportedDirectives) {
                assertError(
                        NarInstallError.UNSUPPORTED_COMPOUND_INSTALL,
                        parse(descriptor("g", "G")
                                + prefix + "." + directive + ",value\n"));
            }
            assertError(
                    NarInstallError.UNSUPPORTED_COMPOUND_INSTALL,
                    parse(descriptor("g", "G")
                            + prefix + ".refresh,0\n"));
            assertError(
                    NarInstallError.UNSUPPORTED_REFRESH,
                    parse(descriptor("g", "G")
                            + prefix + ".refresh,1\n"));
        }
        assertError(
                NarInstallError.UNSUPPORTED_REFRESH,
                parse(descriptor("g", "G") + "Balloon0.Refresh,1\n"));
    }

    @Test
    public void preservesUnknownCustomDottedMetadata() {
        NarDescriptorResult result = parse(
                descriptor("g", "G")
                        + "custom.directory,allowed\n"
                        + "balloonish.refresh,1\n"
                        + "calendar.skin.extra.directory,allowed\n");

        assertTrue(result.isSuccess());
        assertEquals(
                "allowed",
                result.getDescriptor().getMetadata().get("custom.directory"));
        assertEquals(
                "1",
                result.getDescriptor().getMetadata().get("balloonish.refresh"));
    }

    @Test
    public void coreValidationAndExactRefreshPrecedeGenericCompoundRejection() {
        String compound = "balloon.directory,balloon\n";
        assertError(
                NarInstallError.MISSING_TYPE,
                parse("name,G\ndirectory,g\n" + compound));
        assertError(
                NarInstallError.INVALID_TYPE,
                parse("type, \nname,G\ndirectory,g\n" + compound));
        assertError(
                NarInstallError.UNSUPPORTED_TYPE,
                parse("type,shell\nname,G\ndirectory,g\n" + compound));
        assertError(
                NarInstallError.MISSING_METADATA,
                parse("type,ghost\ndirectory,g\n" + compound));
        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parse(descriptor("../unsafe", "G") + compound));
        assertError(
                NarInstallError.INVALID_METADATA,
                parse(descriptor("g", "G") + compound + "Name,Again\n"));

        assertError(
                NarInstallError.UNSUPPORTED_REFRESH,
                parse(descriptor("g", "G")
                        + "refresh,1\n"
                        + compound));
        assertError(
                NarInstallError.UNSUPPORTED_REFRESH,
                parse(descriptor("g", "G")
                        + compound
                        + "plugin0.refresh,1\n"));
        assertError(
                NarInstallError.UNSUPPORTED_COMPOUND_INSTALL,
                parse(descriptor("g", "G") + compound));
    }

    @Test
    public void forcedIdOverridesOnlyAfterDescriptorDirectoryIsValidated() {
        NarDescriptorResult forced = parseBytes(
                descriptor("descriptor-id", "G").getBytes(SHIFT_JIS),
                "forced-id");
        assertTrue(forced.isSuccess());
        assertEquals("descriptor-id",
                forced.getDescriptor().getDescriptorDirectory());
        assertEquals("forced-id", forced.getDescriptor().getTargetId());

        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parseBytes(descriptor("../unsafe", "G").getBytes(SHIFT_JIS), "safe"));
    }

    @Test
    public void rejectsUnsafeDescriptorAndForcedTargetVariants() {
        String[] unsafe = {
            ".", "..", "../escape", "nested/id", "nested\\id",
            "/absolute", "C:drive", "\\\\server\\share",
        };
        for (String value : unsafe) {
            assertError(
                    NarInstallError.INVALID_TARGET_ID,
                    parse(descriptor(value, "G")));
            assertError(
                    NarInstallError.INVALID_TARGET_ID,
                    parseBytes(
                            descriptor("safe", "G").getBytes(SHIFT_JIS),
                            value));
        }
        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parseBytes(
                        descriptor("safe", "G").getBytes(SHIFT_JIS),
                        "bad\u0001id"));
        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parseBytes(
                        descriptor("safe", "G").getBytes(SHIFT_JIS),
                        "bad\uD800id"));
        for (String forced : new String[] {" safe", "safe ", "\u2003safe"}) {
            assertError(
                    NarInstallError.INVALID_TARGET_ID,
                    parseBytes(
                            descriptor("safe", "G").getBytes(SHIFT_JIS),
                            forced));
        }
        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parseBytes(
                        descriptor("safe", "G").getBytes(SHIFT_JIS),
                        repeat('a', 256)));
    }

    @Test
    public void normalizesTargetAndEnforcesExactUtf8ByteBoundary() {
        String nfd = Normalizer.normalize("caf\u00e9", Normalizer.Form.NFD);
        String nfc = Normalizer.normalize(nfd, Normalizer.Form.NFC);
        NarDescriptorResult normalized = parseBytes(
                ("charset,UTF-8\n" + descriptor(nfd, "G")).getBytes(UTF_8),
                null);
        assertTrue(normalized.isSuccess());
        assertEquals(nfc, normalized.getDescriptor().getDescriptorDirectory());
        assertEquals(nfc, normalized.getDescriptor().getTargetId());

        assertTrue(parse(descriptor(repeat('a', 255), "G")).isSuccess());
        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parse(descriptor(repeat('a', 256), "G")));
        assertTrue(parse(descriptor(repeat('\u3042', 85), "G")).isSuccess());
        assertError(
                NarInstallError.INVALID_TARGET_ID,
                parse(descriptor(repeat('\u3042', 86), "G")));
    }

    @Test
    public void enforcesActualDescriptorByteLimitAndNullInput() {
        assertTrue(parse(paddedDescriptor(64 * 1024)).isSuccess());
        assertError(
                NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
                parse(paddedDescriptor(64 * 1024 + 1)));
        assertError(
                NarInstallError.INVALID_METADATA,
                new NarDescriptorParser().parse(null, null));
    }

    @Test
    public void snapshotDetachesParserInputBeforeParsing() {
        byte[] source = descriptor("stable", "Before").getBytes(SHIFT_JIS);
        byte[] expected = source.clone();
        byte[] snapshot = NarDescriptorParser.snapshot(source);

        assertNotSame(source, snapshot);
        Arrays.fill(source, (byte) 'x');
        assertArrayEquals(expected, snapshot);
    }

    private static NarDescriptorResult parse(String descriptor) {
        return parseBytes(descriptor.getBytes(SHIFT_JIS), null);
    }

    private static NarDescriptorResult parseBytes(
            byte[] descriptor,
            String forcedId) {
        return new NarDescriptorParser().parse(descriptor, forcedId);
    }

    private static void assertError(
            NarInstallError expected,
            NarDescriptorResult result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.getError());
        assertNull(result.getDescriptor());
    }

    private static String descriptor(String directory, String name) {
        return "type,ghost\nname," + name + "\ndirectory," + directory + "\n";
    }

    private static String paddedDescriptor(int byteCount) {
        String prefix = descriptor("safe", "G") + "padding,";
        return prefix + repeat('a', byteCount - prefix.length());
    }

    private static String repeat(char value, int count) {
        char[] content = new char[count];
        Arrays.fill(content, value);
        return new String(content);
    }
}
