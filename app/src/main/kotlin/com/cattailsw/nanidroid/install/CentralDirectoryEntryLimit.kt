package com.cattailsw.nanidroid.install

import java.io.File
import java.io.RandomAccessFile

/** Counts central-directory headers before Commons Compress constructs its entry list. */
internal object CentralDirectoryEntryLimit {
    fun check(archive: File, maximum: Int) {
        RandomAccessFile(archive, "r").use { file ->
            val length = file.length()
            val tailStart = (length - 65_557).coerceAtLeast(0)
            val tail = ByteArray((length - tailStart).toInt())
            file.seek(tailStart)
            file.readFully(tail)
            val endRecords = (tail.size - 22 downTo 0).filter { u32(tail, it) == 0x06054b50L }
            require(endRecords.size == 1) { "Missing or ambiguous ZIP end record" }
            val eocd = endRecords.single()
            require(eocd + 22 + u16(tail, eocd + 20) == tail.size) { "Invalid ZIP end record" }
            val eocdOffset = tailStart + eocd
            var directorySize = u32(tail, eocd + 12)
            var directoryOffset = u32(tail, eocd + 16)
            var physicalEnd = eocdOffset
            val zip64Fields = directorySize == 0xFFFF_FFFFL || directoryOffset == 0xFFFF_FFFFL ||
                u16(tail, eocd + 10) == 0xFFFF
            val hasZip64Locator = eocdOffset >= 20 && read32(file, eocdOffset - 20) == 0x07064b50L
            require(!hasZip64Locator || zip64Fields) { "Unhandled ZIP64 locator" }
            if (zip64Fields) {
                val locator = eocdOffset - 20
                require(locator >= 0 && read32(file, locator) == 0x07064b50L) {
                    "Missing ZIP64 locator"
                }
                val zip64Offset = read64(file, locator + 8)
                require(zip64Offset >= 0 && zip64Offset <= locator - 56 &&
                    read32(file, zip64Offset) == 0x06064b50L && read64(file, zip64Offset + 4) >= 44) {
                    "Invalid ZIP64 end record"
                }
                directorySize = read64(file, zip64Offset + 40)
                directoryOffset = read64(file, zip64Offset + 48)
                physicalEnd = zip64Offset
                require(directorySize >= 0 && directoryOffset >= 0 &&
                    directoryOffset <= zip64Offset && directorySize <= zip64Offset - directoryOffset) {
                    "Invalid ZIP64 directory bounds"
                }
            } else {
                require(directoryOffset <= eocdOffset && directorySize <= eocdOffset - directoryOffset) {
                    "Invalid ZIP directory bounds"
                }
                require(eocdOffset - directorySize - directoryOffset == 0L) {
                    "Invalid ZIP central directory record"
                }
            }
            var position = directoryOffset
            var count = 0
            // Commons Compress reads consecutive headers from the directory offset,
            // even when the end record understates the directory size.
            while (physicalEnd - position >= 4 && read32(file, position) == 0x02014b50L) {
                require(physicalEnd - position >= 46) { "Truncated ZIP central directory record" }
                require(count < maximum) { "Entry count limit exceeded" }
                val recordSize = 46L + read16(file, position + 28) +
                    read16(file, position + 30) + read16(file, position + 32)
                require(recordSize <= physicalEnd - position) { "Truncated ZIP central directory record" }
                position += recordSize
                count++
            }
            require(position == physicalEnd) { "Invalid ZIP central directory record" }
        }
    }

    private fun read16(file: RandomAccessFile, offset: Long): Int {
        file.seek(offset)
        return file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
    }

    private fun read32(file: RandomAccessFile, offset: Long): Long {
        file.seek(offset)
        return (0..3).fold(0L) { value, shift -> value or (file.readUnsignedByte().toLong() shl (shift * 8)) }
    }

    private fun read64(file: RandomAccessFile, offset: Long): Long {
        file.seek(offset)
        return (0..7).fold(0L) { value, shift -> value or (file.readUnsignedByte().toLong() shl (shift * 8)) }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, shift -> value or ((bytes[offset + shift].toLong() and 0xFF) shl (shift * 8)) }
}
