package com.cattailsw.nanidroid.install

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Bounded structural preflight for a ZIP central directory. */
internal class NarZipCentralPreflight private constructor() {
    internal interface RandomAccessSource {
        @Throws(IOException::class) fun length(): Long
        @Throws(IOException::class) fun readFully(position: Long, target: ByteArray, offset: Int, length: Int)
    }

    internal class Result private constructor(private val entryCount: Int) {
        fun getEntryCount(): Int = entryCount
        fun isEntryCountOverLimit(): Boolean = entryCount > MAX_ENTRIES
        companion object { internal fun create(entryCount: Int) = Result(entryCount) }
    }

    private class FileRandomAccess(private val random: RandomAccessFile) : RandomAccessSource {
        override fun length(): Long = random.length()
        override fun readFully(position: Long, target: ByteArray, offset: Int, length: Int) { random.seek(position); random.readFully(target, offset, length) }
    }

    private class Zip64Directory(val recordOffset: Long, val entries: Long, val size: Long, val offset: Long)

    companion object {
        const val MAX_ENTRIES = 10000
        private const val OVER_LIMIT = MAX_ENTRIES + 1
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L

        @JvmStatic @Throws(IOException::class)
        fun inspect(file: File?): Result {
            if (file == null) throw IOException("null ZIP source")
            var random: RandomAccessFile? = null; var failure: IOException? = null; var result: Result? = null
            try { random = RandomAccessFile(file, "r"); result = inspect(FileRandomAccess(random)) } catch (error: IOException) { failure = error }
            finally { try { random?.close() } catch (close: IOException) { if (failure == null) failure = close } }
            if (failure != null) throw failure
            return result!!
        }

        @JvmStatic @Throws(IOException::class)
        fun inspect(source: RandomAccessSource?): Result {
            if (source == null) throw IOException("null ZIP source")
            val length = source.length()
            if (length < 22) throw IOException("missing EOCD")
            val tailLength = minOf(length, 65557L).toInt(); val tailOffset = length - tailLength; val tail = ByteArray(tailLength)
            source.readFully(tailOffset, tail, 0, tail.size)
            val eocdInTail = findEocd(tail)
            if (eocdInTail < 0) throw IOException("invalid EOCD tail")
            val eocdOffset = tailOffset + eocdInTail; val eocd = slice(tail, eocdInTail, 22)
            val disk = u16(eocd, 4); val centralDisk = u16(eocd, 6)
            var entriesOnDisk = u16(eocd, 8).toLong(); var entries = u16(eocd, 10).toLong()
            var centralSize = u32(eocd, 12); var centralOffset = u32(eocd, 16); var centralBoundary = eocdOffset
            val zip64 = entriesOnDisk == 0xffffL || entries == 0xffffL || centralSize == 0xffffffffL || centralOffset == 0xffffffffL
            if (zip64) {
                val directory = readZip64(source, eocdOffset, disk, centralDisk)
                if ((entriesOnDisk != 0xffffL && entriesOnDisk != directory.entries) || (entries != 0xffffL && entries != directory.entries) || (centralSize != 0xffffffffL && centralSize != directory.size) || (centralOffset != 0xffffffffL && centralOffset != directory.offset)) throw IOException("inconsistent ZIP64 EOCD")
                entriesOnDisk = directory.entries; entries = directory.entries; centralSize = directory.size; centralOffset = directory.offset; centralBoundary = directory.recordOffset
            } else if (disk != 0 || centralDisk != 0 || entriesOnDisk != entries) throw IOException("multi-disk archive")
            requireRange(centralOffset, centralSize, centralBoundary)
            if (centralSize != centralBoundary - centralOffset) throw IOException("central directory gap")
            if (entries > MAX_ENTRIES) return Result.create(OVER_LIMIT)
            return Result.create(walkCentral(source, centralOffset, centralSize, entries.toInt()))
        }

        private fun findEocd(tail: ByteArray): Int { for (index in tail.size - 22 downTo 0) if (u32(tail, index) == EOCD_SIGNATURE && index.toLong() + 22L + u16(tail, index + 20) == tail.size.toLong()) return index; return -1 }
        private fun readZip64(source: RandomAccessSource, eocdOffset: Long, disk: Int, centralDisk: Int): Zip64Directory {
            if (disk != 0 || centralDisk != 0 || eocdOffset < 20) throw IOException("invalid ZIP64 locator")
            val locatorOffset = eocdOffset - 20; val locator = ByteArray(20); source.readFully(locatorOffset, locator, 0, 20)
            if (u32(locator, 0) != ZIP64_LOCATOR_SIGNATURE || u32(locator, 4) != 0L || u32(locator, 16) != 1L) throw IOException("multi-disk ZIP64")
            val recordOffset = u64(locator, 8); requireRange(recordOffset, 56, locatorOffset); val record = ByteArray(56); source.readFully(recordOffset, record, 0, 56)
            if (u32(record, 0) != ZIP64_EOCD_SIGNATURE) throw IOException("invalid ZIP64 EOCD signature")
            val recordSize = u64(record, 4)
            if (recordSize < 44 || recordSize != locatorOffset - recordOffset - 12 || u32(record, 16) != 0L || u32(record, 20) != 0L) throw IOException("invalid ZIP64 EOCD")
            val entriesOnDisk = u64(record, 24); val entries = u64(record, 32)
            if (entriesOnDisk != entries) throw IOException("multi-disk ZIP64 entries")
            return Zip64Directory(recordOffset, entries, u64(record, 40), u64(record, 48))
        }
        private fun walkCentral(source: RandomAccessSource, offset: Long, size: Long, declaredEntries: Int): Int {
            val end = offset + size; var cursor = offset; var count = 0; val header = ByteArray(46)
            while (cursor < end) {
                if (count >= OVER_LIMIT || end - cursor < header.size) throw IOException("central entry limit or truncation")
                source.readFully(cursor, header, 0, header.size)
                if (u32(header, 0) != CENTRAL_SIGNATURE || u16(header, 34) != 0) throw IOException("invalid central record")
                val variable = u16(header, 28).toLong() + u16(header, 30) + u16(header, 32); val recordLength = 46L + variable
                if (recordLength > end - cursor) throw IOException("central variable fields")
                cursor += recordLength; count++
            }
            if (cursor != end || count != declaredEntries) throw IOException("central count mismatch")
            return count
        }
        private fun requireRange(offset: Long, size: Long, boundary: Long) { if (offset < 0 || size < 0 || boundary < 0 || offset > boundary || size > boundary - offset) throw IOException("ZIP bounds") }
        private fun slice(source: ByteArray, offset: Int, length: Int) = ByteArray(length).also { System.arraycopy(source, offset, it, 0, length) }
        private fun u16(source: ByteArray, offset: Int) = (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)
        private fun u32(source: ByteArray, offset: Int): Long = u16(source, offset).toLong() or (u16(source, offset + 2).toLong() shl 16)
        private fun u64(source: ByteArray, offset: Int): Long { val low = u32(source, offset); val high = u32(source, offset + 4); if ((high and 0x80000000L) != 0L) throw IOException("ZIP64 value overflow"); return low or (high shl 32) }
    }
}
