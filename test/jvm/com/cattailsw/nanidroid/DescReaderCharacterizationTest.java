package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
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

        Map<String, String> metadata = parse(fixture);

        assertEquals("猫", metadata.get("name"));
    }

    @Test
    public void requiredInvariant_lfAndCrLfHaveTheSameSemanticResult() throws Exception {
        byte[] lf = "name,Cat\nsakura.name,Sakura\n".getBytes(Charset.forName("US-ASCII"));
        byte[] crlf = "name,Cat\r\nsakura.name,Sakura\r\n"
                .getBytes(Charset.forName("US-ASCII"));

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

        Map<String, String> metadata = parse(fixture);

        assertEquals("猫", metadata.get("name"));
    }

    @Test
    public void legacyObserved_emptyDescriptorThrowsNullPointerException() throws Exception {
        try {
            parse(new byte[0]);
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError("Legacy parser unexpectedly accepted an empty descriptor");
    }

    @Test
    public void legacyObserved_incompleteShiftJisByteIsReplaced() throws Exception {
        byte[] fixture = bytes(0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x82);

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
}
