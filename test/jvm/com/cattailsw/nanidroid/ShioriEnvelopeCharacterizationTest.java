package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cattailsw.nanidroid.shiori.JNIShiori;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.security.MessageDigest;

import org.junit.Test;

/** Characterizes the byte-decoding and response-parsing seam used by SHIORI adapters. */
public class ShioriEnvelopeCharacterizationTest {
    private final InertJniShiori decoder = new InertJniShiori();

    @Test
    public void requiredMigrationInvariant_declaredUtf8PreservesEnvelopeAndValue()
            throws Exception {
        String raw = "SHIORI/3.0 200 OK\r\n"
                + "Sender: SyntheticUtf8\r\n"
                + "Charset: UTF-8\r\n"
                + "Value: \\h猫:ready\\e\r\n\r\n";
        byte[] fixture = fixture(
                "3c1653c2d86c04fe71e0be4802b4f6ff92206b08f633c36beab160daf0053421",
                raw,
                "UTF-8");

        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals("SHIORI/3.0 200 OK", response.getHeader());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getProtocolVersion());
        assertEquals("SyntheticUtf8", response.getKey("Sender"));
        assertEquals("UTF-8", response.getKey("Charset"));
        assertEquals("\\h猫:ready\\e", response.getKey("Value"));
    }

    @Test
    public void requiredMigrationInvariant_declaredShiftJisPreservesNonAsciiValue()
            throws Exception {
        String raw = "SHIORI/3.0 200 OK\r\n"
                + "Sender: SyntheticShiftJis\r\n"
                + "Charset: Shift_JIS\r\n"
                + "Value: \\hさくら\\e\r\n\r\n";
        byte[] fixture = fixture(
                "0a5a71d641198f90f47eaf10a7a70227034bf687ebae440d5412471a02d867a8",
                raw,
                "Shift_JIS");

        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals(200, response.getStatusCode());
        assertEquals("Shift_JIS", response.getKey("Charset"));
        assertEquals("\\hさくら\\e", response.getKey("Value"));
    }

    @Test
    public void requiredMigrationInvariant_statusCodesAndNoValueArePreserved()
            throws Exception {
        assertStatusWithoutValue(
                "66a2fbd2c3c43c5208b4eeb1c5e381ca3894b398a0ba20512e82d8d4cd869af4",
                "SHIORI/3.0 204 No Content\r\nSender: Synthetic\r\n\r\n",
                204);
        assertStatusWithoutValue(
                "5b39a1cbf8d32b2ad69413d08601ee6260b4def86335d0219f574c301caf76f6",
                "SHIORI/3.0 400 Bad Request\r\nSender: Synthetic\r\n\r\n",
                400);
        assertStatusWithoutValue(
                "7e60e98dcccc0f6ade2f0e1046193036d2e3c7a12dd5e397dadaf352c0959933",
                "SHIORI/3.0 500 Internal Error\r\nSender: Synthetic\r\n\r\n",
                500);
    }

    @Test
    public void legacyObserved_duplicateHeadersUseLastValueAndKeysAreCaseSensitive()
            throws Exception {
        String raw = "SHIORI/3.0 200 OK\r\n"
                + "Sender: First\r\n"
                + "sender: lowercase\r\n"
                + "Sender: Last\r\n\r\n";
        byte[] fixture = fixture(
                "80f2e4a4237d43551646fedae0dde5ce2c401ad41540ad6450a887c036bde5a1",
                raw,
                "US-ASCII");

        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals("Last", response.getKey("Sender"));
        assertEquals("lowercase", response.getKey("sender"));
        assertEquals(2, response.getResponse().size());
    }

    @Test
    public void legacyObserved_malformedHeaderKeepsDefaultStatusAndNullProtocol()
            throws Exception {
        String raw = "not a SHIORI response\r\nValue: ignored-by-status\r\n\r\n";
        byte[] fixture = fixture(
                "99997cf5a309a81b7acb95da3516e7aaf3a0323cc061ff3934a56a0a5f19de59",
                raw,
                "US-ASCII");

        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals("not a SHIORI response", response.getHeader());
        assertEquals(500, response.getStatusCode());
        assertNull(response.getProtocolVersion());
        assertEquals("ignored-by-status", response.getKey("Value"));
    }

    @Test
    public void legacyObserved_headerWithoutSpaceDropsFirstValueCharacter()
            throws Exception {
        String raw = "SHIORI/3.0 200 OK\r\nSender:MySender\r\n\r\n";
        byte[] fixture = fixture(
                "29053bae20237169e87e33e40e148f13d1bde289896c2e4c1967237403c6ad13",
                raw,
                "US-ASCII");

        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals("ySender", response.getKey("Sender"));
    }

    @Test
    public void legacyObserved_charsetWithoutFollowingCrLfThrows() throws Exception {
        byte[] fixture = fixture(
                "dbfabe1afac086fbc15674bed87102f75103ce9867d435b722ccbdcdaaa7ef03",
                "SHIORI/3.0 200 OK\r\nCharset: UTF-8",
                "US-ASCII");

        try {
            decoder.decode(fixture);
        } catch (StringIndexOutOfBoundsException expected) {
            return;
        }
        throw new AssertionError("Legacy decoder unexpectedly accepted a truncated Charset line");
    }

    @Test
    public void legacyObserved_unsupportedCharsetFallsBackToPlatformDefaultForAscii()
            throws Exception {
        String raw = "SHIORI/3.0 200 OK\r\n"
                + "Charset: X-NANIDROID\r\n"
                + "Value: ASCII only\r\n\r\n";
        byte[] fixture = fixture(
                "e66239bc9c2537cd336b4791f02e5077b4ecd6f242cf395da369cd9da1b33708",
                raw,
                "US-ASCII");

        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals("X-NANIDROID", response.getKey("Charset"));
        assertEquals("ASCII only", response.getKey("Value"));
    }

    private void assertStatusWithoutValue(String hash, String raw, int expectedStatus)
            throws Exception {
        byte[] fixture = fixture(hash, raw, "US-ASCII");
        String decoded = decoder.decode(fixture);
        assertEquals(raw, decoded);
        ShioriResponse response = parse(decoded);

        assertEquals(raw.substring(0, raw.indexOf("\r\n")), response.getHeader());
        assertEquals(expectedStatus, response.getStatusCode());
        assertNotNull(response.getProtocolVersion());
        assertNull(response.getKey("Value"));
        assertFalse(response.getResponse().containsKey("Value"));
        assertTrue(response.getResponse().containsKey("Sender"));
    }

    private static ShioriResponse parse(String decoded) {
        return new ShioriResponse(new BufferedReader(new StringReader(decoded)));
    }

    private static byte[] fixture(String expectedHash, String raw, String charset)
            throws Exception {
        byte[] fixture = raw.getBytes(Charset.forName(charset));
        assertFixtureSha256(expectedHash, fixture);
        return fixture;
    }

    private static void assertFixtureSha256(String expected, byte[] fixture) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(fixture);
        StringBuilder actual = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            actual.append(String.format("%02x", value & 0xff));
        }
        assertEquals("Synthetic fixture bytes changed", expected, actual.toString());
    }

    private static final class InertJniShiori extends JNIShiori {
        String decode(byte[] fixture) {
            return modResponseWithCharSet(fixture);
        }

        @Override
        public String getModuleNameFromJNI() {
            throw new AssertionError("Native module lookup is outside this characterization");
        }

        @Override
        public byte[] requestFromJNI(String request) {
            throw new AssertionError("Native request execution is outside this characterization");
        }

        @Override
        public void terminateFromJNI() {
            throw new AssertionError("Native termination is outside this characterization");
        }

        @Override
        public void unloadShiori() {
            throw new AssertionError("Native unloading is outside this characterization");
        }
    }
}
