package com.cattailsw.nanidroid.install

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.UnixStat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NarArchiveReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val reader = NarArchiveReader()
    private val descriptor = "type,ghost\r\ndirectory,example\r\n".toByteArray(charset("windows-31j"))

    private fun archive(vararg entries: Pair<String, ByteArray>, encoding: String = "UTF-8"): File {
        val file = Files.createTempFile("nar-reader", ".zip").toFile().apply { deleteOnExit() }
        ZipArchiveOutputStream(file).use { zip ->
            zip.setEncoding(encoding)
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return file
    }

    private fun underreportSecondEntrySize(zip: File) {
        val bytes = zip.readBytes()
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val second = bytes.indices.filter { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4).contentEquals(signature)
        }[1]
        byteArrayOf(1, 0, 0, 0).copyInto(bytes, second + 24)
        zip.writeBytes(bytes)
    }

    private fun archiveWithFakeEndRecord(zip64Locator: Boolean): File {
        val zip = archive("install.txt" to descriptor)
        val bytes = zip.readBytes()
        val eocd = bytes.size - 22
        val centralStart = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(eocd + 16)
        val local = bytes.copyOfRange(0, centralStart)
        val central = bytes.copyOfRange(centralStart, eocd)
        val zip64 = if (zip64Locator) ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06064b50).putLong(44).putShort(45.toShort()).putShort(45.toShort())
            putInt(0).putInt(0).putLong(1).putLong(1)
            putLong((central.size * 4).toLong()).putLong((local.size + 56).toLong())
        }.array() else byteArrayOf()
        val bigStart = local.size + zip64.size
        val lastStart = bigStart + central.size * 3
        val real = bytes.copyOfRange(eocd, bytes.size)
        ByteBuffer.wrap(real).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(12, central.size)
            putInt(16, lastStart)
            putShort(20, (if (zip64Locator) 45 else 25).toShort())
        }
        val locator = if (zip64Locator) ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x07064b50).putInt(0).putLong(local.size.toLong()).putInt(1)
        }.array() else byteArrayOf()
        val fakeOffset = lastStart + central.size + real.size + locator.size
        val fake = bytes.copyOfRange(eocd, bytes.size)
        ByteBuffer.wrap(fake).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(12, fakeOffset - bigStart)
            putInt(16, bigStart)
        }
        zip.writeBytes(local + zip64 + central + central + central + central + real + locator + fake + byteArrayOf(1, 2, 3))
        ZipFile.builder().setFile(zip).setCharset("windows-31j").setMaxNumberOfDisks(1).get().use {
            assertEquals(4, it.entries.asSequence().count())
        }
        return zip
    }

    @Test fun rootDescriptorExtractsJapanesePayload() {
        val zip = archive("install.txt" to descriptor, "ghost/立ち絵.png" to byteArrayOf(1, 2))
        val metadata = reader.inspect(zip)
        assertEquals(ImportPackage("example", ""), metadata)
        val target = Files.createTempDirectory("nar-target").toFile()
        reader.extract(zip, metadata, target) {}
        assertArrayEquals(byteArrayOf(1, 2), File(target, "ghost/立ち絵.png").readBytes())
    }

    @Test fun wrapperDescriptorSelectsOneDirectory() {
        val zip = archive("wrapper/" to byteArrayOf(), "wrapper/install.txt" to descriptor,
            "wrapper/ghost/master/descript.txt" to byteArrayOf(9))
        assertEquals(ImportPackage("example", "wrapper/"), reader.inspect(zip))
    }

    @Test fun selectsCaseInsensitiveDescriptorAndPrefersRoot() {
        val root = archive("INSTALL.TXT" to descriptor, "ghost/a" to byteArrayOf(1))
        assertEquals(ImportPackage("example", ""), reader.inspect(root))
        val wrapped = archive("wrapper/Install.Txt" to descriptor, "wrapper/ghost/a" to byteArrayOf(2))
        assertEquals(ImportPackage("example", "wrapper/"), reader.inspect(wrapped))
        val both = archive("InStAlL.TxT" to descriptor, "wrapper/install.txt" to descriptor,
            "wrapper/ghost/a" to byteArrayOf(3))
        assertEquals(ImportPackage("example", ""), reader.inspect(both))
    }

    @Test fun normalizesMixedSeparatorsAndUnixDirectoryEntries() {
        val zip = Files.createTempFile("nar-mixed", ".zip").toFile()
        ZipArchiveOutputStream(zip).use { output ->
            listOf("wrapper\\" to UnixStat.DIR_FLAG, "wrapper\\install.txt" to UnixStat.FILE_FLAG,
                "wrapper/ghost" to UnixStat.DIR_FLAG, "wrapper\\ghost/a" to UnixStat.FILE_FLAG)
                .forEach { (name, mode) ->
                    val entry = ZipArchiveEntry(name)
                    entry.unixMode = mode or 0x1FF
                    output.putArchiveEntry(entry)
                    if (name.endsWith("install.txt")) output.write(descriptor)
                    else if (name.endsWith("/a")) output.write(byteArrayOf(7))
                    output.closeArchiveEntry()
                }
        }
        val metadata = reader.inspect(zip)
        assertEquals(ImportPackage("example", "wrapper/"), metadata)
        val target = Files.createTempDirectory("nar-mixed-target").toFile()
        reader.extract(zip, metadata, target) {}
        assertArrayEquals(byteArrayOf(7), File(target, "ghost/a").readBytes())
    }

    @Test fun refusesMissingAmbiguousDeepAndOutsidePayload() {
        listOf(
            archive("a.txt" to byteArrayOf()),
            archive("a/install.txt" to descriptor, "b/install.txt" to descriptor),
            archive("a/b/install.txt" to descriptor),
            archive("a/install.txt" to descriptor, "outside.txt" to byteArrayOf()),
            archive("wrapper/install.txt" to descriptor, "__MACOSX/junk" to byteArrayOf()),
            archive("wrapper/install.txt" to descriptor, ".DS_Store" to byteArrayOf())
        ).forEachIndexed { index, file ->
            assertThrows("case $index", IllegalArgumentException::class.java) { reader.inspect(file) }
        }
    }

    @Test fun refusesMissingBadIdentityAndNonGhostType() {
        listOf("type,ghost\n", "type,shell\ndirectory,example\n",
            "type,ghost\ndirectory,../bad\n", "type,ghost\ndirectory,.hidden\n",
            "type,ghost\ndirectory,nested/id\n")
            .forEach { value ->
                val zip = archive("install.txt" to value.toByteArray())
                assertThrows(IllegalArgumentException::class.java) { reader.inspect(zip) }
            }
    }

    @Test fun readsCp932AndUtf8BomDescriptor() {
        val cp932 = archive("install.txt" to "type,ghost\ndirectory,幽霊\n".toByteArray(charset("windows-31j")))
        val utf8 = archive("install.txt" to "\uFEFFcharset,UTF-8\ntype,ghost\ndirectory,幽霊\n".toByteArray())
        val bomOnly = archive("install.txt" to "\uFEFFtype,ghost\ndirectory,幽霊\n".toByteArray())
        assertEquals("幽霊", reader.inspect(cp932).directoryId)
        assertEquals("幽霊", reader.inspect(utf8).directoryId)
        assertEquals("幽霊", reader.inspect(bomOnly).directoryId)
    }

    @Test fun preservesLegacyCp932EntryName() {
        val zip = archive("install.txt" to descriptor, "ghost/立ち絵.png" to byteArrayOf(7), encoding = "windows-31j")
        val target = Files.createTempDirectory("nar-cp932").toFile()
        reader.extract(zip, reader.inspect(zip), target) {}
        assertArrayEquals(byteArrayOf(7), File(target, "ghost/立ち絵.png").readBytes())
    }

    @Test fun refusesAliasesAndConflicts() {
        listOf(
            archive("install.txt" to descriptor, "a\\b" to byteArrayOf(), "a/b" to byteArrayOf()),
            archive("install.txt" to descriptor, "A" to byteArrayOf(), "a" to byteArrayOf()),
            archive("install.txt" to descriptor, "A/x" to byteArrayOf(), "a/y" to byteArrayOf()),
            archive("install.txt" to descriptor, "a" to byteArrayOf(), "a/b" to byteArrayOf()),
            archive("install.txt" to descriptor, "a/b" to byteArrayOf(), "a" to byteArrayOf())
        ).forEach { assertThrows(IllegalArgumentException::class.java) { reader.inspect(it) } }
    }

    @Test fun permitsExplicitParentDirectory() {
        val zip = archive("install.txt" to descriptor, "ghost/" to byteArrayOf(), "ghost/a" to byteArrayOf(1))
        assertEquals("example", reader.inspect(zip).directoryId)
    }

    @Test fun ignoresExactEmptyRootDirectoryWithoutCreatingAnOutputPath() {
        val realDosStyle = rootEntryArchive("\\", 0, byteArrayOf(), dosDirectory = true)
        assertEquals('\\'.code.toByte(), realDosStyle.readBytes()[30])
        val syntheticUnixStyle = rootEntryArchive("\\", UnixStat.DIR_FLAG, byteArrayOf())
        for (zip in listOf(realDosStyle, syntheticUnixStyle)) {
            val metadata = reader.inspect(zip)
            assertEquals(ImportPackage("example", ""), metadata)
            val target = Files.createTempDirectory("nar-root-entry").toFile()
            reader.extract(zip, metadata, target) {}
            assertEquals(setOf("install.txt", "ghost"), target.list()!!.toSet())
            assertArrayEquals(byteArrayOf(7), File(target, "ghost/a").readBytes())
            assertThrows(IllegalArgumentException::class.java) {
                NarArchiveReader(ImportLimits(entries = 2)).inspect(zip)
            }
        }
    }

    @Test fun rootEntryExceptionDoesNotAdmitUnsafeNamesOrMetadata() {
        listOf(
            Triple("\\", 0, byteArrayOf()),
            Triple("\\", UnixStat.FILE_FLAG, byteArrayOf(1)),
            Triple("\\", UnixStat.DIR_FLAG, byteArrayOf(1)),
            Triple("\\", UnixStat.LINK_FLAG, byteArrayOf()),
            Triple("/escape", UnixStat.FILE_FLAG, byteArrayOf(1)),
            Triple("C:/escape", UnixStat.FILE_FLAG, byteArrayOf(1)),
            Triple("../escape", UnixStat.FILE_FLAG, byteArrayOf(1)),
        ).forEachIndexed { index, (name, mode, payload) ->
            val zip = rootEntryArchive(name, mode, payload)
            assertThrows("case $index", IllegalArgumentException::class.java) { reader.inspect(zip) }
        }
    }

    private fun rootEntryArchive(name: String, mode: Int, payload: ByteArray,
                                 dosDirectory: Boolean = false): File {
        val file = Files.createTempFile("nar-root-entry", ".zip").toFile().apply { deleteOnExit() }
        ZipArchiveOutputStream(file).use { zip ->
            val root = ZipArchiveEntry(name).apply {
                if (dosDirectory) externalAttributes = 0x10L else unixMode = mode or 0x1FF
            }
            zip.putArchiveEntry(root)
            zip.write(payload)
            zip.closeArchiveEntry()
            listOf("install.txt" to descriptor, "ghost/a" to byteArrayOf(7)).forEach { (path, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(path))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        if (dosDirectory || mode == 0) {
            // Commons' writer stamps Unix even for a DOS-attribute entry; the corpus stamps DOS.
            val bytes = file.readBytes()
            val central = bytes.indices.first { offset ->
                offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4)
                    .contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            }
            bytes[central + 5] = 0
            bytes[30] = '\\'.code.toByte()
            bytes[central + 46] = '\\'.code.toByte()
            if (!dosDirectory) (38..41).forEach { bytes[central + it] = 0 }
            file.writeBytes(bytes)
        }
        return file
    }

    @Test fun enforcesActualFileAndTotalLimits() {
        val longPayload = archive("install.txt" to descriptor, "a" to ByteArray(descriptor.size + 1))
        underreportSecondEntrySize(longPayload)
        val fileError = assertThrows(IllegalArgumentException::class.java) {
            NarArchiveReader(ImportLimits(fileBytes = descriptor.size.toLong())).inspect(longPayload)
        }
        assertTrue(fileError.message!!.contains("File byte limit"))

        val totalPayload = archive("install.txt" to descriptor, "a" to byteArrayOf(1, 2, 3))
        underreportSecondEntrySize(totalPayload)
        val totalError = assertThrows(IllegalArgumentException::class.java) {
            NarArchiveReader(ImportLimits(expandedBytes = descriptor.size.toLong() + 2)).inspect(totalPayload)
        }
        assertTrue(totalError.message!!.contains("Expanded byte limit"))
    }

    @Test fun ghostDescriptorLimitAcceptsExactBytesAndRejectsOneMore() {
        val exact = "name,Exact\n".toByteArray()
        val limits = ImportLimits(ghostDescriptorBytes = exact.size)
        val bounded = NarArchiveReader(limits)
        val accepted = archive("install.txt" to descriptor, "ghost/master/descript.txt" to exact)
        val metadata = bounded.inspect(accepted)
        val target = Files.createTempDirectory("nar-descriptor-exact").toFile()
        bounded.extract(accepted, metadata, target) {}
        assertArrayEquals(exact, File(target, "ghost/master/descript.txt").readBytes())

        val rejected = archive("install.txt" to descriptor, "ghost/master/descript.txt" to (exact + byteArrayOf(1)))
        val error = assertThrows(IllegalArgumentException::class.java) { bounded.inspect(rejected) }
        assertEquals("Ghost descriptor byte limit exceeded", error.message)
    }

    @Test fun wrappedMixedCaseBackslashDescriptorIsBoundedDuringInspectAndExtract() {
        val limits = ImportLimits(ghostDescriptorBytes = 10)
        val bounded = NarArchiveReader(limits)
        val zip = archive("wrapper/install.txt" to descriptor,
            "wrapper\\GHOST\\MASTER\\DESCRIPT.TXT" to ByteArray(11) { 7 })
        val inspectError = assertThrows(IllegalArgumentException::class.java) { bounded.inspect(zip) }
        assertEquals("Ghost descriptor byte limit exceeded", inspectError.message)

        val extractTarget = Files.createTempDirectory("nar-descriptor-extract").toFile()
        val extractError = assertThrows(IllegalArgumentException::class.java) {
            bounded.extract(zip, ImportPackage("example", "wrapper/"), extractTarget) {}
        }
        assertEquals("Ghost descriptor byte limit exceeded", extractError.message)
    }

    @Test fun rootPackageDoesNotBoundDescriptorNamedExtraPayload() {
        val limits = ImportLimits(ghostDescriptorBytes = 10)
        val bounded = NarArchiveReader(limits)
        val extra = ByteArray(11) { 7 }
        val zip = archive("install.txt" to descriptor,
            "ghost/master/descript.txt" to "name,OK\n".toByteArray(),
            "extras/ghost/master/descript.txt" to extra)

        val metadata = bounded.inspect(zip)
        assertEquals(ImportPackage("example", ""), metadata)
        val target = Files.createTempDirectory("nar-descriptor-extra").toFile()
        bounded.extract(zip, metadata, target) {}
        assertArrayEquals(extra, File(target, "extras/ghost/master/descript.txt").readBytes())
    }

    @Test fun ghostDescriptorStreamRejectsUnderreportedSize() {
        val limits = ImportLimits(ghostDescriptorBytes = 10)
        val zip = archive("install.txt" to descriptor, "ghost/master/descript.txt" to ByteArray(11) { 7 })
        underreportSecondEntrySize(zip)
        val error = assertThrows(IllegalArgumentException::class.java) { NarArchiveReader(limits).inspect(zip) }
        assertEquals("Ghost descriptor byte limit exceeded", error.message)
    }

    @Test fun enforcesEntryAndArchiveLimits() {
        val zip = archive("install.txt" to descriptor, "a" to byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { NarArchiveReader(ImportLimits(entries = 1)).inspect(zip) }
        assertThrows(IllegalArgumentException::class.java) { NarArchiveReader(ImportLimits(archiveBytes = 1)).inspect(zip) }
    }

    @Test fun rejectsFakeEndRecordInCommentBeforeCommonsReadsExtraEntries() {
        val zip = archiveWithFakeEndRecord(zip64Locator = false)
        val error = assertThrows(IllegalArgumentException::class.java) {
            CentralDirectoryEntryLimit.check(zip, 2)
        }
        assertTrue(error.message!!.contains("Entry count limit") || error.message!!.contains("ZIP end record"))
    }

    @Test fun rejectsFakeEndRecordWithZip64LocatorBeforeCommonsReadsExtraEntries() {
        val zip = archiveWithFakeEndRecord(zip64Locator = true)
        val error = assertThrows(IllegalArgumentException::class.java) {
            CentralDirectoryEntryLimit.check(zip, 2)
        }
        assertTrue(error.message!!.contains("Entry count limit") || error.message!!.contains("ZIP end record"))
    }

    @Test fun countsActualCentralRecordsBeforeOpeningArchiveDespiteLiedEocdCount() {
        val zip = archive("install.txt" to descriptor, "a" to byteArrayOf(1))
        val bytes = zip.readBytes()
        val central = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val second = bytes.indices.filter { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4).contentEquals(central)
        }[1]
        val eocd = bytes.indices.last { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4)
                .contentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        }
        // Claim one entry; make the second entry's local offset impossible so eager ZipFile open fails.
        bytes[second + 42] = 0xFF.toByte()
        bytes[second + 43] = 0xFF.toByte()
        bytes[second + 44] = 0xFF.toByte()
        bytes[second + 45] = 0x7F
        bytes[eocd + 8] = 1
        bytes[eocd + 10] = 1
        zip.writeBytes(bytes)

        val error = assertThrows(IllegalArgumentException::class.java) {
            NarArchiveReader(ImportLimits(entries = 1)).inspect(zip)
        }
        assertTrue(error.message!!.contains("Entry count limit"))
    }

    @Test fun rejectsPrefixedZipBeforeCommonsCorrectsRelativeDirectoryOffset() {
        val zip = archive("install.txt" to descriptor, "a" to byteArrayOf(1))
        val bytes = zip.readBytes()
        val second = bytes.indices.filter { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4)
                .contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        }[1]
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(second + 42, Int.MAX_VALUE)
        zip.writeBytes(ByteArray(16) { 0x53 } + bytes)

        val error = assertThrows(IllegalArgumentException::class.java) {
            NarArchiveReader(ImportLimits(entries = 1)).inspect(zip)
        }
        assertTrue(error.message!!.contains("Invalid ZIP central directory record"))
    }

    @Test fun countsActualZip64CentralRecordsDespiteClaimedDirectorySize() {
        val zip = archive("install.txt" to descriptor, "a" to byteArrayOf(1))
        val bytes = zip.readBytes()
        val second = bytes.indices.filter { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4)
                .contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        }[1]
        val eocd = bytes.indices.last { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4)
                .contentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        }
        val original = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val directorySize = original.getInt(eocd + 12).toLong() and 0xFFFF_FFFFL
        val directoryOffset = original.getInt(eocd + 16).toLong() and 0xFFFF_FFFFL
        listOf(directorySize, 0L, 1L).forEach { claimedSize ->
            val zip64 = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
            zip64.putInt(0x06064b50).putLong(44).putShort(45.toShort()).putShort(45.toShort())
            zip64.putInt(0).putInt(0).putLong(1).putLong(1) // Deliberately false counts.
            zip64.putLong(claimedSize).putLong(directoryOffset)
            zip64.putInt(0x07064b50).putInt(0).putLong(eocd.toLong()).putInt(1)
            val result = bytes.copyOfRange(0, eocd) + zip64.array() + bytes.copyOfRange(eocd, bytes.size)
            val patched = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            patched.putInt(second + 42, Int.MAX_VALUE) // ZipFile cannot resolve this local header.
            patched.putShort(eocd + 76 + 8, 0xFFFF.toShort())
            patched.putShort(eocd + 76 + 10, 0xFFFF.toShort())
            patched.putInt(eocd + 76 + 12, -1)
            patched.putInt(eocd + 76 + 16, -1)
            zip.writeBytes(result)

            val error = assertThrows(IllegalArgumentException::class.java) {
                NarArchiveReader(ImportLimits(entries = 1)).inspect(zip)
            }
            assertTrue("claimed size $claimedSize: ${error.message}",
                error.message!!.contains("Entry count limit"))
        }
    }

    @Test fun rejectsCentralRecordWhoseVariableFieldsExceedDirectory() {
        val zip = archive("install.txt" to descriptor)
        val bytes = zip.readBytes()
        val central = bytes.indices.first { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4)
                .contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        }
        bytes[central + 28] = 0xFF.toByte()
        bytes[central + 29] = 0x7F
        zip.writeBytes(bytes)
        val error = assertThrows(IllegalArgumentException::class.java) { reader.inspect(zip) }
        assertTrue(error.message!!.contains("Truncated ZIP central directory record"))
    }

    @Test fun cancellationStopsExtraction() {
        val zip = archive("install.txt" to descriptor, "a" to ByteArray(100_000))
        val target = Files.createTempDirectory("nar-cancel").toFile()
        assertThrows(IllegalStateException::class.java) {
            reader.extract(zip, reader.inspect(zip), target) { throw IllegalStateException("cancel") }
        }
    }

    @Test fun extractionPreservesExactPayloadBytesAfterInspection() {
        // Offset-dependent bytes distinguish adjacent bytes and successive streaming chunks.
        val payload = ByteArray(100_000) { offset ->
            ((offset * 31) xor (offset ushr 8) xor (offset ushr 16)).toByte()
        }.apply {
            // Explicit zero and signed-byte boundaries straddle the 8 KiB buffer boundary.
            byteArrayOf(0, 127, -128, -1).copyInto(this, 8_190)
        }
        val zip = archive("install.txt" to descriptor, "large.bin" to payload)
        val metadata = reader.inspect(zip)
        val target = temporaryFolder.newFolder("nar-exact-payload")
        reader.extract(zip, metadata, target) {}
        assertArrayEquals(descriptor, File(target, "install.txt").readBytes())
        assertArrayEquals(payload, File(target, "large.bin").readBytes())
    }

    @Test fun extractionStillEnforcesExpandedByteLimit() {
        val zip = archive("install.txt" to descriptor, "large.bin" to ByteArray(100_000) { 7 })
        val metadata = reader.inspect(zip)
        val target = Files.createTempDirectory("nar-extract-limit").toFile()
        val error = assertThrows(IllegalArgumentException::class.java) {
            NarArchiveReader(ImportLimits(expandedBytes = descriptor.size.toLong() + 100))
                .extract(zip, metadata, target) {}
        }
        assertTrue(error.message!!.contains("Expanded byte limit"))
    }

    @Test fun cancellationStopsInspectionDuringPayloadRead() {
        val zip = archive("install.txt" to descriptor, "large.bin" to ByteArray(100_000))
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            reader.inspect(zip) {
                if (++checks == 5) throw IllegalStateException("cancel")
            }
        }
        assertEquals(5, checks)
    }

    @Test fun refusesUnixLinksAndSpecialFiles() {
        listOf(UnixStat.LINK_FLAG, 0x1000).forEach { mode ->
            val file = Files.createTempFile("nar-mode", ".zip").toFile()
            ZipArchiveOutputStream(file).use { zip ->
                zip.putArchiveEntry(ZipArchiveEntry("install.txt"))
                zip.write(descriptor)
                zip.closeArchiveEntry()
                val entry = ZipArchiveEntry("ghost/link")
                entry.unixMode = mode or 0x1FF
                zip.putArchiveEntry(entry)
                zip.write("target".toByteArray())
                zip.closeArchiveEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { reader.inspect(file) }
        }
    }

    @Test fun detectsCentralDirectoryCrcMismatch() {
        val zip = archive("install.txt" to descriptor, "a" to byteArrayOf(1, 2, 3))
        val bytes = zip.readBytes()
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val second = bytes.indices.filter { offset ->
            offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4).contentEquals(signature)
        }[1]
        bytes[second + 16] = (bytes[second + 16].toInt() xor 1).toByte()
        zip.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { reader.inspect(zip) }
    }

    @Test fun detectsUnknownAndMismatchedSizeMetadata() {
        listOf(byteArrayOf(1, 0, 0, 0), byteArrayOf(-1, -1, -1, -1)).forEach { size ->
            val zip = archive("install.txt" to descriptor, "a" to byteArrayOf(1, 2, 3))
            val bytes = zip.readBytes()
            val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
            val second = bytes.indices.filter { offset ->
                offset + 4 <= bytes.size && bytes.copyOfRange(offset, offset + 4).contentEquals(signature)
            }[1]
            size.copyInto(bytes, second + 24)
            zip.writeBytes(bytes)
            assertThrows(Exception::class.java) { reader.inspect(zip) }
        }
    }

    @Test fun rejectsTruncatedArchiveAndUnsupportedMethod() {
        val truncated = archive("install.txt" to descriptor)
        truncated.writeBytes(truncated.readBytes().dropLast(12).toByteArray())
        assertThrows(Exception::class.java) { reader.inspect(truncated) }

        val unsupported = archive("install.txt" to descriptor)
        val bytes = unsupported.readBytes()
        val central = bytes.indices.first { offset ->
            offset + 4 <= bytes.size && bytes[offset] == 0x50.toByte() &&
                bytes[offset + 1] == 0x4b.toByte() && bytes[offset + 2] == 0x01.toByte() && bytes[offset + 3] == 0x02.toByte()
        }
        bytes[central + 10] = 99
        unsupported.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { reader.inspect(unsupported) }
    }

    @Test fun rejectsOversizeInstallDescriptor() {
        val zip = archive("install.txt" to (descriptor + ByteArray(65_536)))
        assertThrows(IllegalArgumentException::class.java) { reader.inspect(zip) }
    }
}
