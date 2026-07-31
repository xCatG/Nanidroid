package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * Characterizes descriptor bytes as a semantic metadata map.
 *
 *
 * Tests prefixed `requiredMigrationInvariant_` describe behavior that a mechanical
 * parser replacement must preserve pending the long-term supported-format decision. Tests
 * prefixed `legacyObserved_` record current behavior without declaring it desirable.
 */
class DescReaderCharacterizationTest {
    @Rule
    @JvmField
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Rule
    @JvmField
    val androidStubs: HostAndroidStubRule = HostAndroidStubRule()

    private var fixtureIndex = 0

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_defaultShiftJisBytesProduceMetadata() {
        val fixture: ByteArray = bytes(
            0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x94, 0x4c, 0x0d, 0x0a,
            0x73, 0x61, 0x6b, 0x75, 0x72, 0x61, 0x2e, 0x6e, 0x61, 0x6d, 0x65, 0x2c,
            0x82, 0xb3, 0x82, 0xad, 0x82, 0xe7, 0x0d, 0x0a
        )
        assertFixtureSha256(
            "249a6a72e3228a9193d5ec787f51d136c48701e94ad519ddb4f0c56225898cca",
            fixture
        )

        val metadata = parse(fixture)

        Assert.assertEquals("猫", metadata.get("name"))
        Assert.assertEquals("さくら", metadata.get("sakura.name"))
    }

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_utf8BomAndDeclaredCharsetProduceMetadata() {
        val fixture: ByteArray = bytes(
            0xef, 0xbb, 0xbf,
            0x63, 0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x2c,
            0x55, 0x54, 0x46, 0x2d, 0x38, 0x0d, 0x0a,
            0x6e, 0x61, 0x6d, 0x65, 0x2c, 0xe7, 0x8c, 0xab, 0x0d, 0x0a,
            0x73, 0x61, 0x6b, 0x75, 0x72, 0x61, 0x2e, 0x6e, 0x61, 0x6d, 0x65, 0x2c,
            0xe3, 0x81, 0x95, 0xe3, 0x81, 0x8f, 0xe3, 0x82, 0x89, 0x0d, 0x0a
        )
        assertFixtureSha256(
            "87dcf73f2e913730769a2f2d730180c02da98afc26a29c5301058b9cc18e8af5",
            fixture
        )

        val metadata = parse(fixture)

        Assert.assertEquals("猫", metadata.get("name"))
        Assert.assertEquals("さくら", metadata.get("sakura.name"))
    }

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_declaredUtf8WithoutBomProducesMetadata() {
        val fixture: ByteArray = bytes(
            0x63, 0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x2c,
            0x55, 0x54, 0x46, 0x2d, 0x38, 0x0a,
            0x6e, 0x61, 0x6d, 0x65, 0x2c, 0xe7, 0x8c, 0xab, 0x0a
        )
        assertFixtureSha256(
            "4e25947b0d9cd59c8a4bbc9c4432420a93fa13ae56da703336e9f6925635d01f",
            fixture
        )

        val metadata = parse(fixture)

        Assert.assertEquals("猫", metadata.get("name"))
    }

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_lfAndCrLfHaveTheSameSemanticResult() {
        val lf = "name,Cat\nsakura.name,Sakura\n".toByteArray(Charset.forName("US-ASCII"))
        val crlf = "name,Cat\r\nsakura.name,Sakura\r\n"
            .toByteArray(Charset.forName("US-ASCII"))
        assertFixtureSha256(
            "285a790e7fafa75f9a24b04a57f0bd3766202b6270eeb622626e60b0484aa9bd",
            lf
        )
        assertFixtureSha256(
            "efbc8332340260a373759e27b4a473d62f957e0faef3c3120e9b4f3841aea9f2",
            crlf
        )

        val lfMetadata = parse(lf)
        val crlfMetadata = parse(crlf)

        Assert.assertEquals("Cat", lfMetadata.get("name"))
        Assert.assertEquals("Sakura", lfMetadata.get("sakura.name"))
        Assert.assertEquals(2, lfMetadata.size.toLong())
        Assert.assertEquals(lfMetadata, crlfMetadata)
    }

    @Test
    @Throws(Exception::class)
    fun legacyObserved_duplicateLabelUsesLastValueAndExtraCommaLineIsIgnored() {
        val fixture = (("name,First\r\n"
                + "line-without-comma\r\n"
                + "description,hello,world\r\n"
                + "name,Second\r\n"))
            .toByteArray(Charset.forName("US-ASCII"))
        assertFixtureSha256(
            "2651cb94336e2ed7fa3111cf433f5094f44328f4932fc9ec6be633e9f1b72f43",
            fixture
        )

        val metadata = parse(fixture)

        Assert.assertEquals("Second", metadata.get("name"))
        Assert.assertFalse(metadata.containsKey("description"))
    }

    @Test
    @Throws(Exception::class)
    fun legacyObserved_unsupportedCharsetFallsBackToShiftJis() {
        val fixture: ByteArray = bytes(
            0x63, 0x68, 0x61, 0x72, 0x73, 0x65, 0x74, 0x2c,
            0x58, 0x2d, 0x4e, 0x41, 0x4e, 0x49, 0x44, 0x52, 0x4f, 0x49, 0x44, 0x0a,
            0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x94, 0x4c, 0x0a
        )
        assertFixtureSha256(
            "fbd12fc0a0c394a6fc359b3a1633676e0b7816988e58b89ece38f0111be28e54",
            fixture
        )

        val metadata = parse(fixture)

        Assert.assertEquals("猫", metadata.get("name"))
    }

    @Test
    @Throws(Exception::class)
    fun legacyObserved_emptyDescriptorThrowsNullPointerException() {
        val fixture = ByteArray(0)
        assertFixtureSha256(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            fixture
        )
        try {
            parse(fixture)
        } catch (expected: NullPointerException) {
            return
        }
        throw AssertionError("Legacy parser unexpectedly accepted an empty descriptor")
    }

    @Test
    @Throws(Exception::class)
    fun legacyObserved_incompleteShiftJisByteIsReplaced() {
        val fixture: ByteArray = bytes(0x6e, 0x61, 0x6d, 0x65, 0x2c, 0x82)
        assertFixtureSha256(
            "f8f7c99ac56d05f7d666f8b71dc7fdb03c5331e7f37993ce61cd7918ffa45a12",
            fixture
        )

        val metadata = parse(fixture)

        Assert.assertEquals("\ufffd", metadata.get("name"))
    }

    @Throws(Exception::class)
    private fun parse(fixture: ByteArray?): MutableMap<String, String> {
        val descriptor = temporaryFolder.newFile("descript-" + fixtureIndex++ + ".txt")
        val output = FileOutputStream(descriptor)
        try {
            output.write(fixture)
        } finally {
            output.close()
        }
        return DescReader(descriptor.getAbsolutePath()).parse()
    }

    companion object {
        private fun bytes(vararg values: Int): ByteArray {
            val result = ByteArray(values.size)
            for (index in values.indices) {
                result[index] = values[index].toByte()
            }
            return result
        }

        @Throws(Exception::class)
        private fun assertFixtureSha256(expected: String?, fixture: ByteArray) {
            val digest = MessageDigest.getInstance("SHA-256").digest(fixture)
            val actual = StringBuilder(digest.size * 2)
            for (value in digest) {
                actual.append(String.format("%02x", value.toInt() and 0xff))
            }
            Assert.assertEquals("Synthetic fixture bytes changed", expected, actual.toString())
        }
    }
}
