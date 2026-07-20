package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Characterizes descriptor bytes as a semantic metadata map.
 *
 * <p>Tests prefixed {@code requiredInvariant_} describe behavior that future parser
 * implementations must preserve. Tests prefixed {@code legacyObserved_} record current behavior
 * for migration review without declaring it desirable.
 */
public class DescReaderCharacterizationTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int fixtureIndex;

    @Test
    public void requiredInvariant_defaultShiftJisBytesProduceMetadata() throws Exception {
        byte[] fixture = bytes(
                0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x94, 0x4c, 0x0d, 0x0a,
                0x73, 0x61, 0x6b, 0x75, 0x72, 0x61, 0x2e, 0x6e, 0x61, 0x6d, 0x65, 0x2c,
                0x82, 0xb3, 0x82, 0xad, 0x82, 0xe7, 0x0d, 0x0a);
        assertFixtureSha256(
                "249a6a72e3228a9193d5ec787f51d136c48701e94ad519ddb4f0c56225898cca",
                fixture);

        Map<String, String> metadata = parse(fixture);

        assertEquals("猫", metadata.get("name"));
        assertEquals("さくら", metadata.get("sakura.name"));
    }

    @Test
    public void requiredInvariant_utf8BomAndDeclaredCharsetProduceMetadata() throws Exception {
        byte[] fixture = bytes(
                0xef, 0xbb, 0xbf,
                0x63, 0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x2c,
                0x55, 0x54, 0x46, 0x2d, 0x38, 0x0d, 0x0a,
                0x6e, 0x61, 0x6d, 0x65, 0x2c, 0xe7, 0x8c, 0xab, 0x0d, 0x0a,
                0x73, 0x61, 0x6b, 0x75, 0x72, 0x61, 0x2e, 0x6e, 0x61, 0x6d, 0x65, 0x2c,
                0xe3, 0x81, 0x95, 0xe3, 0x81, 0x8f, 0xe3, 0x82, 0x89, 0x0d, 0x0a);
        assertFixtureSha256(
                "87dcf73f2e913730769a2f2d730180c02da98afc26a29c5301058b9cc18e8af5",
                fixture);

        Map<String, String> metadata = parse(fixture);

        assertEquals("猫", metadata.get("name"));
        assertEquals("さくら", metadata.get("sakura.name"));
    }

    @Test
    public void requiredInvariant_declaredUtf8WithoutBomProducesMetadata() throws Exception {
        byte[] fixture = bytes(
                0x63, 0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x2c,
                0x55, 0x54, 0x46, 0x2d, 0x38, 0x0a,
                0x6e, 0x61, 0x6d, 0x65, 0x2c, 0xe7, 0x8c, 0xab, 0x0a);
        assertFixtureSha256(
                "4e25947b0d9cd59c8a4bbc9c4432420a93fa13ae56da703336e9f6925635d01f",
                fixture);

        Map<String, String> metadata = parse(fixture);

        assertEquals("猫", metadata.get("name"));
    }

    @Test
    public void requiredInvariant_lfAndCrLfHaveTheSameSemanticResult() throws Exception {
        byte[] lf = "name,Cat\nsakura.name,Sakura\n".getBytes(Charset.forName("US-ASCII"));
        byte[] crlf = "name,Cat\r\nsakura.name,Sakura\r\n"
                .getBytes(Charset.forName("US-ASCII"));
        assertFixtureSha256(
                "285a790e7fafa75f9a24b04a57f0bd3766202b6270eeb622626e60b0484aa9bd",
                lf);
        assertFixtureSha256(
                "efbc8332340260a373759e27b4a473d62f957e0faef3c3120e9b4f3841aea9f2",
                crlf);

        assertEquals(parse(lf), parse(crlf));
    }

    @Test
    public void legacyObserved_duplicateLabelUsesLastValueAndExtraCommaLineIsIgnored()
            throws Exception {
        byte[] fixture = (
                "name,First\r\n"
                        + "line-without-comma\r\n"
                        + "description,hello,world\r\n"
                        + "name,Second\r\n")
                .getBytes(Charset.forName("US-ASCII"));
        assertFixtureSha256(
                "2651cb94336e2ed7fa3111cf433f5094f44328f4932fc9ec6be633e9f1b72f43",
                fixture);

        Map<String, String> metadata = parse(fixture);

        assertEquals("Second", metadata.get("name"));
        assertFalse(metadata.containsKey("description"));
    }

    @Test
    public void legacyObserved_unsupportedCharsetFallsBackToShiftJis() throws Exception {
        byte[] fixture = bytes(
                0x63, 0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x2c,
                0x58, 0x2d, 0x4e, 0x41, 0x4e, 0x49, 0x44, 0x52, 0x4f, 0x49, 0x44, 0x0a,
                0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x94, 0x4c, 0x0a);
        assertFixtureSha256(
                "fbd12fc0a0c394a6fc359b3a1633676e0b7816988e58b89ece38f0111be28e54",
                fixture);

        Map<String, String> metadata = parse(fixture);

        assertEquals("猫", metadata.get("name"));
    }

    @Test
    public void legacyObserved_emptyDescriptorThrowsNullPointerException() throws Exception {
        byte[] fixture = new byte[0];
        assertFixtureSha256(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                fixture);
        try {
            parse(fixture);
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError("Legacy parser unexpectedly accepted an empty descriptor");
    }

    @Test
    public void legacyObserved_incompleteShiftJisByteIsReplaced() throws Exception {
        byte[] fixture = bytes(0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x82);
        assertFixtureSha256(
                "f8f7c99ac56d05f7d666f8b71dc7fdb03c5331e7f37993ce61cd7918ffa45a12",
                fixture);

        Map<String, String> metadata = parse(fixture);

        assertEquals("\ufffd", metadata.get("name"));
    }

    private Map<String, String> parse(byte[] fixture) throws Exception {
        File descriptor = temporaryFolder.newFile("descript-" + fixtureIndex++ + ".txt");
        FileOutputStream output = new FileOutputStream(descriptor);
        try {
            output.write(fixture);
        } finally {
            output.close();
        }
        return new DescReader(descriptor.getAbsolutePath()).parse();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static void assertFixtureSha256(String expected, byte[] fixture) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(fixture);
        StringBuilder actual = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            actual.append(String.format("%02x", value & 0xff));
        }
        assertEquals("Synthetic fixture bytes changed", expected, actual.toString());
    }
}
