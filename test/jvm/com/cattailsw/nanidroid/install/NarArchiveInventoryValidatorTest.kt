package com.cattailsw.nanidroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin port of central-directory inventory behavior characterization. */
class NarArchiveInventoryValidatorTest {
    @Test fun acceptsFlatAndWrappedLayoutsWithStableCentralMapping() {
        val flat = validate(file("install.txt", 20), file("ghost/master/surface0.png", 4))
        assertTrue(flat.isSuccess()); assertNull(flat.getInventory()!!.getWrapperDirectory()); assertEquals(0, flat.getInventory()!!.getDescriptorOrdinal())
        assertEquals(listOf("install.txt", "ghost/master/surface0.png"), flat.getInventory()!!.getEntries().map { it.getRelativePath() })
        val wrapped = validate(dir("wrapper"), file("wrapper/install.txt", 20), file("wrapper/ghost/master/surface0.png", 4))
        assertTrue(wrapped.isSuccess()); assertEquals("wrapper", wrapped.getInventory()!!.getWrapperDirectory())
        assertEquals(listOf(null, "install.txt", "ghost/master/surface0.png"), wrapped.getInventory()!!.getEntries().map { it.getRelativePath() })
    }

    @Test fun kotlinInventoryResultKeepsJavaDiagnosticFactorySemantics() {
        val failure = NarArchiveInventoryResult.failure(NarInstallError.INVALID_ENTRY_METADATA, "central getter")
        assertFalse(failure.isSuccess()); assertNull(failure.getInventory()); assertEquals(NarInstallError.INVALID_ENTRY_METADATA, failure.getError()); assertEquals("central getter", failure.getDetail())
    }

    @Test fun preservesExactCentralIdentityAndNfcOutput() {
        val payload = Record("ghost/master/file.bin", false, crcValue = 0x1234abcdL, methodValue = 8, sizeValue = 321, compressedValue = 123)
        val result = validate(file("install.txt", 1), payload); val entry = result.getInventory()!!.getEntries()[1]
        assertEquals(1, entry.getOrdinal()); assertEquals(payload.raw, entry.getRawName()); assertEquals(payload.raw, entry.getNormalizedArchivePath()); assertEquals(payload.raw, entry.getRelativePath()); assertEquals(0x1234abcdL, entry.getCrc()); assertEquals(321, entry.getDeclaredSize()); assertEquals(123, entry.getCompressedSize())
        val raw = java.text.Normalizer.normalize("ghost/café.txt", java.text.Normalizer.Form.NFD)
        val nfc = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
        val normalized = validate(file("install.txt", 1), file(raw!!, 1)).getInventory()!!.getEntries()[1]
        assertEquals(raw, normalized.getRawName()); assertEquals(nfc, normalized.getNormalizedArchivePath())
    }

    @Test fun rejectsCentralMetadataCollisionsAndUnsafePaths() {
        assertError(NarInstallError.INVALID_ENTRY_METADATA, NarArchiveInventoryValidator().validate(null))
        assertError(NarInstallError.INVALID_PATH, validate(file("../install.txt", 1)))
        assertError(NarInstallError.DUPLICATE_ENTRY, validate(file("install.txt", 1), file("install.txt", 1)))
        assertError(NarInstallError.FILE_DIRECTORY_COLLISION, validate(file("ghost", 1), file("ghost/install.txt", 1)))
        assertError(NarInstallError.NORMALIZED_COLLISION, validate(file("install.txt", 1), file("INSTALL.TXT", 1)))
        assertError(NarInstallError.MISSING_INSTALL_DESCRIPTOR, validate(file("payload", 1)))
        assertError(NarInstallError.INVALID_LAYOUT, validate(file("a/b/install.txt", 1)))
        assertError(NarInstallError.AMBIGUOUS_LAYOUT, validate(file("install.txt", 1), file("wrapper/install.txt", 1)))
        assertError(NarInstallError.MIXED_LAYOUT, validate(file("wrapper/install.txt", 1), file("outside", 1)))
    }

    @Test fun enforcesCountDepthLengthComponentAndRawNameBoundaries() {
        val entries = mutableListOf(file("install.txt", 1)); repeat(9_999) { entries += file("payload/$it", 1) }
        assertTrue(validate(entries).isSuccess()); entries += file("payload/overflow", 1); assertError(NarInstallError.ENTRY_COUNT_LIMIT, validate(entries))
        assertTrue(validateWithPath(repeatedPath(32, 1)).isSuccess()); assertError(NarInstallError.PATH_DEPTH_LIMIT, validateWithPath(repeatedPath(33, 1)))
        assertTrue(validateWithPath(asciiPath(1024)).isSuccess()); assertError(NarInstallError.PATH_LENGTH_LIMIT, validateWithPath(asciiPath(1025)))
        assertTrue(validateWithPath("a".repeat(255)).isSuccess()); assertError(NarInstallError.COMPONENT_LENGTH_LIMIT, validateWithPath("a".repeat(256)))
        val malformed = "a".repeat(4095) + '\uD800'; assertError(NarInstallError.INVALID_PATH, validateWithPath(malformed)); assertError(NarInstallError.RAW_NAME_LENGTH_LIMIT, validateWithPath(malformed + "a"))
    }

    @Test fun rejectsNfcAndCaseCollisionsInBothOrders() {
        assertError(NarInstallError.NORMALIZED_COLLISION, validate(file("install.txt", 1), file("Ghost/File", 1), file("ghost/file", 1)))
        val a = "J\u030C"; val b = "ǰ"
        listOf(a to b, b to a).forEach { (first, second) ->
            assertError(NarInstallError.NORMALIZED_COLLISION, validate(file("install.txt", 1), file(first, 1), file(second, 1)))
            assertError(NarInstallError.NORMALIZED_COLLISION, validate(file("install.txt", 1), file("$first/one", 1), file("$second/two", 1)))
        }
    }

    @Test fun enforcesDeclaredSizesCompressionAndDescriptorLimits() {
        assertError(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, validate(file("install.txt", 64L * 1024 + 1)))
        assertError(NarInstallError.DECLARED_ENTRY_SIZE_LIMIT, validate(file("install.txt", 1), file("payload", 128L * 1024 * 1024 + 1)))
        assertError(NarInstallError.DECLARED_RATIO_LIMIT, validate(Record("install.txt", false, sizeValue = 1001, compressedValue = 1)))
        assertError(NarInstallError.INVALID_ENTRY_METADATA, validate(Record("install.txt", false, crcValue = -2)))
    }

    @Test fun enforcesTotalAndExactCompressionBoundaries() {
        val descriptor = Record("install.txt", false, sizeValue = 0, compressedValue = -1); val total = mutableListOf(descriptor); repeat(4) { total += Record("part-$it", false, sizeValue = 128L * 1024 * 1024, compressedValue = -1) }
        assertTrue(validate(total).isSuccess()); total += Record("overflow", false, sizeValue = 1, compressedValue = -1); assertError(NarInstallError.DECLARED_TOTAL_SIZE_LIMIT, validate(total))
        assertTrue(validate(descriptor, Record("ratio", false, sizeValue = 1000, compressedValue = 1)).isSuccess())
        assertError(NarInstallError.DECLARED_RATIO_LIMIT, validate(descriptor, Record("ratio", false, sizeValue = 1001, compressedValue = 1)))
        assertError(NarInstallError.DECLARED_RATIO_LIMIT, validate(descriptor, Record("ratio", false, sizeValue = 1, compressedValue = 0)))
        assertTrue(validate(descriptor, Record("unknown", false, sizeValue = -1, compressedValue = -1)).isSuccess())
    }

    @Test fun mapsGetterFailuresAndRejectsInvalidCentralMetadata() {
        assertError(NarInstallError.INVALID_ENTRY_METADATA, NarArchiveInventoryValidator().validate(listOf(ThrowingEntry())))
        assertError(NarInstallError.INVALID_ENTRY_METADATA, NarArchiveInventoryValidator().validate(listOf(null)))
        assertError(NarInstallError.INVALID_PATH, validate(Record(null, false)))
        assertError(NarInstallError.INVALID_PATH, validate(Record("", false)))
        assertError(NarInstallError.INVALID_ENTRY_METADATA, validate(Record("file/", false)))
        assertError(NarInstallError.INVALID_ENTRY_METADATA, validate(Record("directory", true)))
        listOf(
            Record("install.txt", false, sizeValue = -2), Record("install.txt", false, compressedValue = -2), Record("install.txt", false, crcValue = -2), Record("install.txt", false, crcValue = 0x100000000L), Record("install.txt", false, methodValue = -2), Record("install.txt", false, methodValue = 7)
        ).forEach { assertError(NarInstallError.INVALID_ENTRY_METADATA, validate(it)) }
        assertTrue(validate(Record("install.txt", false, crcValue = -1, methodValue = -1, sizeValue = -1, compressedValue = -1)).isSuccess())
    }

    @Test fun deterministicFuzzMutatesValidPathsWithExactErrors() {
        val random = java.util.Random(0x4e415244396231L)
        repeat(512) { index ->
            val baseline = "root${random.nextInt(100000)}/leaf$index.txt"
            assertTrue(validateWithPath(baseline).isSuccess())
            val mutations = listOf(
                "../$baseline", "/$baseline", baseline.replace("/", "//"), baseline.replace("/", "\\"), baseline + '\u0001',
                baseline.substring(0, baseline.indexOf('/') + 1) + "a".repeat(256), repeatedPath(31, 1) + "/$baseline",
                baseline + "/" + asciiPath(1025 - baseline.toByteArray(Charsets.UTF_8).size - 1), baseline + "/" + "a".repeat(4097 - baseline.length - 1), baseline + '\uD800'
            )
            val expected = listOf(NarInstallError.INVALID_PATH, NarInstallError.INVALID_PATH, NarInstallError.INVALID_PATH, NarInstallError.INVALID_PATH, NarInstallError.INVALID_PATH, NarInstallError.COMPONENT_LENGTH_LIMIT, NarInstallError.PATH_DEPTH_LIMIT, NarInstallError.PATH_LENGTH_LIMIT, NarInstallError.RAW_NAME_LENGTH_LIMIT, NarInstallError.INVALID_PATH)
            assertError(expected[index % mutations.size], validateWithPath(mutations[index % mutations.size]))
        }
    }

    @Test fun snapshotsCentralEntryExactlyOnceAndReturnsImmutableResult() {
        val oneShot = OneShotEntry()
        val result = NarArchiveInventoryValidator().validate(listOf(oneShot))
        assertTrue(result.isSuccess()); assertEquals(1, result.getInventory()!!.getEntries().size)
        try { (result.getInventory()!!.getEntries() as MutableList).clear(); throw AssertionError("inventory must be immutable") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun entriesAreUnmodifiableFromJavaWithMultipleEntries() {
        val inventory = validate(file("install.txt", 1), file("payload", 1)).getInventory()!!
        assertEquals(2, inventory.getEntries().size)
        try { (inventory.getEntries() as MutableList<NarArchiveInventory.Entry>).clear(); throw AssertionError("entries must be Java-unmodifiable") } catch (_: UnsupportedOperationException) { }
    }

    private fun validate(vararg records: Record): NarArchiveInventoryResult = validate(records.toList())
    private fun validate(records: List<Record?>): NarArchiveInventoryResult { records.forEachIndexed { i, r -> if (r != null && r.ordinalValue < 0) r.ordinalValue = i }; return NarArchiveInventoryValidator().validate(records) }
    private fun validateWithPath(path: String) = validate(file("install.txt", 1), file(path, 1))
    private fun file(path: String, size: Long) = Record(path, false, sizeValue = size, compressedValue = if (size == 0L) 0 else size)
    private fun dir(path: String) = Record("$path/", true, sizeValue = 0, compressedValue = 0)
    private fun assertError(error: NarInstallError, result: NarArchiveInventoryResult) { assertFalse(result.isSuccess()); assertEquals(error, result.getError()); assertNull(result.getInventory()) }
    private fun repeatedPath(count: Int, componentLength: Int) = List(count) { "a".repeat(componentLength) }.joinToString("/")
    private fun asciiPath(bytes: Int): String { val letters = bytes - 4; val base = letters / 5; val remainder = letters % 5; return List(5) { "a".repeat(base + if (it < remainder) 1 else 0) }.joinToString("/") }
    private class Record(val raw: String?, val directory: Boolean, var crcValue: Long = 0, var methodValue: Int = if (directory) 0 else 8, var sizeValue: Long = 0, var compressedValue: Long = sizeValue) : NarArchiveInventoryValidator.CentralEntry {
        var ordinalValue = -1
        override fun getOrdinal() = ordinalValue; override fun getRawName() = raw; override fun isDirectory() = directory
        override fun getCrc() = crcValue; override fun getMethod() = methodValue; override fun getDeclaredSize() = sizeValue; override fun getCompressedSize() = compressedValue
    }
    private class ThrowingEntry : NarArchiveInventoryValidator.CentralEntry { override fun getOrdinal() = 0; override fun getRawName(): String = throw IllegalStateException(); override fun isDirectory() = false; override fun getCrc() = 0L; override fun getMethod() = 8; override fun getDeclaredSize() = 1L; override fun getCompressedSize() = 1L }
    private class OneShotEntry : NarArchiveInventoryValidator.CentralEntry {
        private var calls = 0; private fun <T> once(value: T): T { calls++; if (calls > 7) error("getter reread"); return value }
        override fun getOrdinal() = once(0); override fun getRawName() = once("install.txt"); override fun isDirectory() = once(false)
        override fun getCrc() = once(0L); override fun getMethod() = once(8); override fun getDeclaredSize() = once(1L); override fun getCompressedSize() = once(1L)
    }
}
