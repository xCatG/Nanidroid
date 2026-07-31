package com.cattailsw.nanidroid.install


import org.junit.Assert
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class NarZipCentralPreflightTest {
    @Test
    @Throws(Exception::class)
    fun acceptsClassicRecordsAndLegalEocdComment() {
        val comment = ByteArray(30)
        put32(comment, 5, EOCD)
        val archive: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(3, 4, 5), intArrayOf(1, 2, 3)),
            2,
            0,
            comment
        )

        val result: NarZipCentralPreflight.Result =
            inspect(archive)

        Assert.assertEquals(2, result.getEntryCount())
        Assert.assertFalse(result.isEntryCountOverLimit())
    }

    @Test
    @Throws(Exception::class)
    fun acceptsZip64CentralDirectory() {
        val result: NarZipCentralPreflight.Result =
            inspect(
                Companion.zip64(
                    arrayOf<IntArray>(intArrayOf(2, 1, 3), intArrayOf(0, 4, 0)),
                    2
                )
            )

        Assert.assertEquals(2, result.getEntryCount())
        Assert.assertFalse(result.isEntryCountOverLimit())
    }

    @Test
    @Throws(Exception::class)
    fun acceptsExactLimitAndReturnsEarlySentinelAboveIt() {
        val exact: NarZipCentralPreflight.Result =
            inspect(
                classic(
                    zeroLengthRecords(10000),
                    10000,
                    0,
                    ByteArray(0)
                )
            )
        Assert.assertEquals(10000, exact.getEntryCount())
        Assert.assertFalse(exact.isEntryCountOverLimit())

        val result: NarZipCentralPreflight.Result =
            inspect(Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001))

        Assert.assertEquals(10001, result.getEntryCount())
        Assert.assertTrue(result.isEntryCountOverLimit())
    }

    @Test
    @Throws(Exception::class)
    fun rejectsMissingTruncatedAndTrailingEocd() {
        assertInvalid(ByteArray(21))

        val badComment: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 0, byteArrayOf(1)
        )
        put16(badComment, badComment.size - 3, 2)
        assertInvalid(badComment)

        val valid: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 0, ByteArray(0)
        )
        assertInvalid(valid.copyOf(valid.size + 1))
    }

    @Test
    @Throws(Exception::class)
    fun rejectsClassicCountDiskAndBoundsMismatches() {
        assertInvalid(
            Companion.classic(
                arrayOf<IntArray>(intArrayOf(0, 0, 0)), 2, 0, ByteArray(0)
            )
        )
        assertInvalid(
            Companion.classic(
                arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 1, ByteArray(0)
            )
        )

        val badOffset: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 0, ByteArray(0)
        )
        put32(badOffset, badOffset.size - 6, 0x7fffffffL)
        assertInvalid(badOffset)

        val gap: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 0, ByteArray(0)
        )
        put32(gap, gap.size - 6, 1)
        assertInvalid(gap)
    }

    @Test
    @Throws(Exception::class)
    fun rejectsMalformedVariableCentralRecords() {
        val truncated: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(1, 1, 1)), 1, 0, ByteArray(0)
        )
        put16(truncated, 28, 0xffff)
        assertInvalid(truncated)

        val overflowing: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(1, 1, 1)), 1, 0, ByteArray(0)
        )
        put16(overflowing, 28, 0xffff)
        put16(overflowing, 30, 0xffff)
        put16(overflowing, 32, 0xffff)
        assertInvalid(overflowing)

        val badSignature: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 0, ByteArray(0)
        )
        put32(badSignature, 0, 0)
        assertInvalid(badSignature)

        val splitDiskEntry: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(0, 0, 0)), 1, 0, ByteArray(0)
        )
        put16(splitDiskEntry, 34, 1)
        assertInvalid(splitDiskEntry)
    }

    @Test
    @Throws(Exception::class)
    fun rejectsZip64LocatorDiskAndOffsetErrors() {
        val locatorDisk: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put32(locatorDisk, 60, 1)
        assertInvalid(locatorDisk)

        val totalDisks: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put32(totalDisks, 72, 2)
        assertInvalid(totalDisks)

        val recordOffset: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put64(recordOffset, 64, 999)
        assertInvalid(recordOffset)
    }

    @Test
    @Throws(Exception::class)
    fun rejectsZip64RecordDiskCountAndBoundsErrors() {
        val recordDisk: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put32(recordDisk, 16, 1)
        assertInvalid(recordDisk)

        val splitEntries: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put64(splitEntries, 24, 10000)
        assertInvalid(splitEntries)

        val shortRecord: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put64(shortRecord, 4, 43)
        assertInvalid(shortRecord)

        val inconsistentClassicField: ByteArray =
            Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put16(inconsistentClassicField, 84, 7)
        assertInvalid(inconsistentClassicField)
    }

    @Test
    @Throws(Exception::class)
    fun rejectsUnsignedZip64Overflow() {
        val entryCount: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put64(entryCount, 32, Long.MIN_VALUE)
        assertInvalid(entryCount)

        val centralSize: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put64(centralSize, 40, Long.MIN_VALUE)
        assertInvalid(centralSize)

        val centralOffset: ByteArray = Companion.zip64(Array<IntArray>(0) { IntArray(0) }, 10001)
        put64(centralOffset, 48, Long.MIN_VALUE)
        assertInvalid(centralOffset)
    }

    @Test
    @Throws(Exception::class)
    fun inspectsARealFileWithApi9Io() {
        val archive: ByteArray = Companion.classic(
            arrayOf<IntArray>(intArrayOf(4, 2, 1)), 1, 0, ByteArray(0)
        )
        val file = File.createTempFile("nanidroid-preflight", ".nar")
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(file)
            output.write(archive)
        } finally {
            if (output != null) {
                output.close()
            }
        }
        try {
            Assert.assertEquals(
                1,
                NarZipCentralPreflight.inspect(file)
                    .getEntryCount()
            )
        } finally {
            Assert.assertTrue(file.delete())
        }
    }

    private class MemoryRandomAccess
        (private val content: ByteArray) : NarZipCentralPreflight.RandomAccessSource {
        public override fun length(): Long {
            return content.size.toLong()
        }

        @Throws(IOException::class)
        public override fun readFully(
            position: Long,
            target: ByteArray,
            offset: Int,
            length: Int
        ) {
            if (position < 0 || position > content.size || length > content.size - position) {
                throw IOException("test source bounds")
            }
            System.arraycopy(
                content, position.toInt(), target, offset, length
            )
        }
    }

    companion object {
        private const val EOCD = 0x06054b50L
        private const val ZIP64_EOCD = 0x06064b50L
        private const val ZIP64_LOCATOR = 0x07064b50L
        private const val CENTRAL = 0x02014b50L

        @Throws(IOException::class)
        private fun inspect(
            archive: ByteArray
        ): NarZipCentralPreflight.Result {
            return NarZipCentralPreflight.inspect(
                MemoryRandomAccess(archive)
            )
        }

        @Throws(Exception::class)
        private fun assertInvalid(archive: ByteArray) {
            try {
                inspect(archive)
                throw AssertionError("invalid central directory accepted")
            } catch (expected: IOException) {
                // Expected.
            }
        }

        private fun classic(
            lengths: Array<IntArray>,
            declaredRecords: Int,
            disk: Int,
            comment: ByteArray
        ): ByteArray {
            val centralSize: Int = centralSize(lengths)
            val archive = ByteArray(centralSize + 22 + comment.size)
            writeCentral(archive, lengths)
            val eocd = centralSize
            put32(archive, eocd, EOCD)
            put16(archive, eocd + 4, disk)
            put16(archive, eocd + 6, disk)
            put16(archive, eocd + 8, declaredRecords)
            put16(archive, eocd + 10, declaredRecords)
            put32(archive, eocd + 12, centralSize.toLong())
            put32(archive, eocd + 16, 0)
            put16(archive, eocd + 20, comment.size)
            System.arraycopy(
                comment, 0, archive, eocd + 22, comment.size
            )
            return archive
        }

        private fun zip64(
            lengths: Array<IntArray>, declaredRecords: Long
        ): ByteArray {
            val centralSize: Int = centralSize(lengths)
            val record = centralSize
            val locator = record + 56
            val eocd = locator + 20
            val archive = ByteArray(eocd + 22)
            writeCentral(archive, lengths)
            put32(archive, record, ZIP64_EOCD)
            put64(archive, record + 4, 44)
            put64(archive, record + 24, declaredRecords)
            put64(archive, record + 32, declaredRecords)
            put64(archive, record + 40, centralSize.toLong())
            put64(archive, record + 48, 0)
            put32(archive, locator, ZIP64_LOCATOR)
            put32(archive, locator + 4, 0)
            put64(archive, locator + 8, record.toLong())
            put32(archive, locator + 16, 1)
            put32(archive, eocd, EOCD)
            put16(archive, eocd + 8, 0xffff)
            put16(archive, eocd + 10, 0xffff)
            put32(archive, eocd + 12, 0xffffffffL)
            put32(archive, eocd + 16, 0xffffffffL)
            return archive
        }

        private fun centralSize(lengths: Array<IntArray>): Int {
            var total = 0
            for (record in lengths) {
                total += 46 + record[0] + record[1] + record[2]
            }
            return total
        }

        private fun zeroLengthRecords(count: Int): Array<IntArray> {
            return Array<IntArray>(count) { IntArray(3) }
        }

        private fun writeCentral(
            archive: ByteArray, lengths: Array<IntArray>
        ) {
            var cursor = 0
            for (record in lengths) {
                put32(archive, cursor, CENTRAL)
                put16(archive, cursor + 28, record[0])
                put16(archive, cursor + 30, record[1])
                put16(archive, cursor + 32, record[2])
                cursor += 46 + record[0] + record[1] + record[2]
            }
        }

        private fun put16(
            target: ByteArray, offset: Int, value: Int
        ) {
            target[offset] = value.toByte()
            target[offset + 1] = (value ushr 8).toByte()
        }

        private fun put32(
            target: ByteArray, offset: Int, value: Long
        ) {
            for (index in 0..3) {
                target[offset + index] = (value ushr (8 * index)).toByte()
            }
        }

        private fun put64(
            target: ByteArray, offset: Int, value: Long
        ) {
            for (index in 0..7) {
                target[offset + index] = (value ushr (8 * index)).toByte()
            }
        }
    }
}
