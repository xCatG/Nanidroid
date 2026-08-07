package com.cattailsw.nanidroid.llmghost.archive

import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NarCorpusLoaderTest {
    private val loader = NarCorpusLoader()

    @Test
    fun decodesShiftJisAndMaterializesDescriptorIdentity() = withTemporaryDirectory { directory ->
        val shiftJis = Charset.forName("Shift_JIS")
        val archive = writeNar(
            directory,
            validEntries(
                charsetDeclaration = "Shift_JIS",
                charset = shiftJis,
                ghostName = "小さな幽霊",
                sakuraName = "ソフィ",
                keroName = "リエール",
                dictionaryText = "＊挨拶\n：今日は\n",
            ),
        )

        val result = assertIs<NarLoadResult.Success>(loader.load(archive))

        assertEquals("小さな幽霊", result.input.identity.ghostName)
        assertEquals("ソフィ", result.input.identity.sakuraName)
        assertEquals("リエール", result.input.identity.keroName)
        assertEquals("ghost/master/dic01.txt", result.input.files.single().path)
        assertTrue(result.input.files.single().text.contains("今日は"))
    }

    @Test
    fun acceptsUtf8AndWindows31jAliasesWithStrictDecoding() = withTemporaryDirectory { directory ->
        val utf8 = writeNar(
            directory,
            validEntries(charsetDeclaration = "utf8", dictionaryText = "＊talk\nhello\n"),
            fileName = "utf8.nar",
        )
        val windows31j = Charset.forName("windows-31j")
        val cp932 = writeNar(
            directory,
            validEntries(
                charsetDeclaration = "CP932",
                charset = windows31j,
                dictionaryText = "＊記号\n①です\n",
            ),
            fileName = "cp932.nar",
        )

        val utf8Result = assertIs<NarLoadResult.Success>(loader.load(utf8))
        val cp932Result = assertIs<NarLoadResult.Success>(loader.load(cp932))

        assertTrue(utf8Result.input.files.single().text.contains("hello"))
        assertTrue(cp932Result.input.files.single().text.contains("①"))
    }

    @Test
    fun decodesCp932ExtensionsWhenLegacyFilesDeclareShiftJis() = withTemporaryDirectory { directory ->
        val windows31j = Charset.forName("windows-31j")
        val archive = writeNar(
            directory,
            validEntries(
                charsetDeclaration = "Shift_JIS",
                charset = windows31j,
                dictionaryText = "＊記号\n①です\n",
            ),
        )

        val result = assertIs<NarLoadResult.Success>(loader.load(archive))

        assertTrue(result.input.files.single().text.contains("①"))
    }

    @Test
    fun bootstrapsCharsetDeclarationBeforeDecodingNonAsciiDescriptorFields() = withTemporaryDirectory { directory ->
        val shiftJis = Charset.forName("Shift_JIS")
        val entries = validEntries(charsetDeclaration = "Shift-JIS", charset = shiftJis).map { spec ->
            if (spec.name.equals("ghost/master/descript.txt", ignoreCase = true)) {
                EntrySpec(
                    spec.name,
                    "name,幽霊\nsakura.name,桜\nkero.name,蛙\ncharset,Shift-JIS\n".toByteArray(shiftJis),
                )
            } else {
                spec
            }
        }

        val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

        assertEquals("幽霊", result.input.identity.ghostName)
        assertEquals("桜", result.input.identity.sakuraName)
        assertEquals("蛙", result.input.identity.keroName)
    }

    @Test
    fun selectsRootDictionariesAndNestedCharacterOrEventTextOnly() = withTemporaryDirectory { directory ->
        val entries = validEntries().filterNot { it.name == "ghost/master/dic01.txt" } + listOf(
            textEntry("ghost/master/dic_root.txt", "charset,UTF-8\n＊root\nroot line\n"),
            textEntry("ghost/master/characters/dic_sakura.txt", "＊character\ncharacter line\n"),
            textEntry("ghost/master/events/dic_seasonal.sat", "＊event\nevent line\n"),
            textEntry("ghost/master/characters/descript.txt", "metadata,not dialogue"),
            textEntry("ghost/master/readme.txt", "not a dictionary"),
            textEntry("ghost/master/private/notes.txt", "private notes,not executable dialogue"),
            textEntry("other/dic_ignored.txt", "not a ghost dictionary"),
        )

        val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

        assertEquals(
            listOf(
                "ghost/master/characters/dic_sakura.txt",
                "ghost/master/dic_root.txt",
                "ghost/master/events/dic_seasonal.sat",
            ),
            result.input.files.map { it.path },
        )
    }

    @Test
    fun parsesSurfaceListsRangesAndExclusionsWithoutTreatingAppendAsADefinition() =
        withTemporaryDirectory { directory ->
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") {
                    textEntry(
                        spec.name,
                        "charset,UTF-8\n" +
                            "surface0,3 {\n}\n" +
                            "surface10-12,!11,!0 {\n}\n" +
                            "surface.append19 {\n}\n" +
                            "surface.alias {\n}\n",
                    )
                } else {
                    spec
                }
            }

            val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))
            val expected = setOf(0, 3, 10, 12)

            assertEquals(expected, result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
            assertEquals(expected, result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
            assertFalse(
                result.input.identity.shellSurfaces.getValue(GhostSpeakerId.SAKURA) ===
                    result.input.identity.shellSurfaces.getValue(GhostSpeakerId.KERO),
            )
        }

    @Test
    fun usesAllMasterSurfaceSourcesAndExcludedRangesButIgnoresAlternateShells() =
        withTemporaryDirectory { directory ->
            val entries = validEntries().filterNot { it.name == "shell/master/surfaces.txt" } + listOf(
                textEntry(
                    "shell/master/surfaces2.txt",
                    "charset,UTF-8\nsurface1-30,!20-25 {\n}\nsurface.append99 {\n}\n",
                ),
                EntrySpec("shell/master/surface42.png", byteArrayOf(1, 2, 3)),
                textEntry("shell/alternate/surfaces.txt", "charset,UTF-8\nsurface999 {\n}\n"),
                EntrySpec("shell/alternate/surface777.png", byteArrayOf(1, 2, 3)),
            )

            val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))
            val expected = (1..19).toSet() + (26..30).toSet() + 42

            assertEquals(expected, result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
            assertEquals(expected, result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
        }

    @Test
    fun acceptsMasterShellInventoryDefinedOnlyByPngFiles() = withTemporaryDirectory { directory ->
        val entries = validEntries().filterNot { it.name == "shell/master/surfaces.txt" } +
            EntrySpec("shell/master/surface7.png", byteArrayOf(1, 2, 3))

        val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

        assertEquals(setOf(7), result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
        assertEquals(setOf(7), result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
    }

    @Test
    fun parsesOnlyClosedSurfaceBlocksWithSpacedSelectorsOutsideCommentsAndDescript() =
        withTemporaryDirectory { directory ->
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") {
                    textEntry(
                        spec.name,
                        """
                        charset,UTF-8
                        // surface900
                        descript
                        {
                        surface901 {
                        }
                        surface0, 3 // spaced selector
                        {
                        }
                        surface999
                        surface998
                        {
                        """.trimIndent(),
                    )
                } else {
                    spec
                }
            }

            val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

            assertEquals(setOf(0, 3), result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
            assertEquals(setOf(0, 3), result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
        }

    @Test
    fun materializesCompactSurfaceBlockAtEofWithWhitespaceAndComment() =
        withTemporaryDirectory { directory ->
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") {
                    textEntry(spec.name, "charset,UTF-8\n  surface0, 3   { }   // compact\n")
                } else {
                    spec
                }
            }

            val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

            assertEquals(setOf(0, 3), result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
            assertEquals(setOf(0, 3), result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
        }

    @Test
    fun compactDescriptDoesNotSuppressFollowingSurfaceBlocks() =
        withTemporaryDirectory { directory ->
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") {
                    textEntry(
                        spec.name,
                        "charset,UTF-8\ndescript { } // compact metadata\nsurface4 {}\nsurface5\n{}\n",
                    )
                } else {
                    spec
                }
            }

            val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

            assertEquals(setOf(4, 5), result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
            assertEquals(setOf(4, 5), result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
        }

    @Test
    fun unrelatedClosingBraceAfterCompactBlocksDoesNotAffectLaterInventory() =
        withTemporaryDirectory { directory ->
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") {
                    textEntry(
                        spec.name,
                        "charset,UTF-8\nsurface1 {}\ndescript {}\n}\nsurface2 {}\n",
                    )
                } else {
                    spec
                }
            }

            val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

            assertEquals(setOf(1, 2), result.input.identity.shellSurfaces[GhostSpeakerId.SAKURA])
            assertEquals(setOf(1, 2), result.input.identity.shellSurfaces[GhostSpeakerId.KERO])
        }

    @Test
    fun chargesAppendSelectorCardinalityAgainstPerFileBudget() =
        withTemporaryDirectory { directory ->
            val highWork = buildString {
                appendLine("charset,UTF-8")
                repeat(10) { append("surface.append0-9999 {\n}\n") }
            }
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") textEntry(spec.name, highWork) else spec
            }

            assertFailure(loader.load(writeNar(directory, entries)), "invalid-shell-inventory")
        }

    @Test
    fun rejectsSelectorWorkAbovePerFileBudgetBeforeExpandingAllRanges() =
        withTemporaryDirectory { directory ->
            val highWork = buildString {
                appendLine("charset,UTF-8")
                repeat(10) { append("surface0-9999 {\n}\n") }
            }
            val entries = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") textEntry(spec.name, highWork) else spec
            }

            assertFailure(loader.load(writeNar(directory, entries)), "invalid-shell-inventory")
        }

    @Test
    fun rejectsSelectorWorkAboveAggregateBudgetAcrossSurfaceFiles() =
        withTemporaryDirectory { directory ->
            val perFileWork = buildString {
                appendLine("charset,UTF-8")
                repeat(9) { append("surface0-9999 {\n}\n") }
            }
            val entries = validEntries().filterNot { it.name == "shell/master/surfaces.txt" } +
                (1..3).map { index -> textEntry("shell/master/surfaces$index.txt", perFileWork) }

            assertFailure(loader.load(writeNar(directory, entries)), "invalid-shell-inventory")
        }

    @Test
    fun acceptsHighCountRepeatedCharsetDeclarations() = withTemporaryDirectory { directory ->
        val repetitiveDictionary = buildString {
            repeat(200_000) { appendLine("charset,UTF-8") }
            append("＊talk\nline\n")
        }
        val entries = validEntries(dictionaryText = repetitiveDictionary)

        val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

        assertTrue(result.input.files.single().text.endsWith("＊talk\nline\n"))
    }

    @Test
    fun hashesEveryFileEntryUsingNormalizedPaths() = withTemporaryDirectory { directory ->
        val entries = validEntries() + EntrySpec("assets/empty.bin", ByteArray(0))

        val result = assertIs<NarLoadResult.Success>(loader.load(writeNar(directory, entries)))

        assertEquals(entries.map { it.name.lowercase() }.toSet(), result.entryHashes.keys)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result.entryHashes["assets/empty.bin"],
        )
        assertTrue(result.entryHashes.values.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun rejectsMissingNonZipAndDirectoryInputsWithoutLeakingContent() = withTemporaryDirectory { directory ->
        val missing = loader.load(directory.resolve("missing.nar"))
        val secret = "private-authored-secret"
        val nonZipPath = directory.resolve("not-a.nar")
        Files.writeString(nonZipPath, secret)

        val nonZip = loader.load(nonZipPath)
        val directoryResult = loader.load(directory)

        assertFailure(missing, "archive-not-found")
        assertFailure(nonZip, "invalid-archive", secret)
        assertFailure(directoryResult, "archive-unreadable")
    }

    @Test
    fun rejectsTraversalAbsoluteDriveAndUncNamesAcrossSlashStyles() = withTemporaryDirectory { directory ->
        val unsafeNames = listOf(
            "../escape.txt",
            "ghost/../escape.txt",
            "ghost\\..\\escape.txt",
            "/absolute.txt",
            "\\absolute.txt",
            "C:/drive.txt",
            "C:drive-relative.txt",
            "d:\\drive.txt",
            "//server/share.txt",
            "\\\\server\\share.txt",
        )

        unsafeNames.forEachIndexed { index, unsafeName ->
            val archive = writeNar(
                directory,
                validEntries() + textEntry(unsafeName, "private-authored-secret"),
                fileName = "unsafe-$index.nar",
            )
            assertFailure(loader.load(archive), "unsafe-entry-name", "private-authored-secret")
        }
    }

    @Test
    fun rejectsDuplicateNormalizedOrCaseFoldedEntryNames() = withTemporaryDirectory { directory ->
        val duplicates = listOf(
            "ghost\\master\\DIC01.TXT",
            "ghost/./master/dic01.txt",
            "ghost//master/dic01.txt",
        )

        duplicates.forEachIndexed { index, duplicateName ->
            val archive = writeNar(
                directory,
                validEntries() + textEntry(duplicateName, "duplicate"),
                fileName = "duplicate-$index.nar",
            )
            assertFailure(loader.load(archive), "duplicate-entry")
        }
    }

    @Test
    fun rejectsMoreThanTenThousandEntries() = withTemporaryDirectory { directory ->
        val entries = (0..10_000).map { index -> EntrySpec("empty/$index/", ByteArray(0), directory = true) }

        assertFailure(loader.load(writeNar(directory, entries)), "entry-count-limit")
    }

    @Test
    fun rejectsEntryAboveEightMibUsingStreamedBytesEvenWhenMetadataClaimsOneByte() =
        withTemporaryDirectory { directory ->
            val oversized = ByteArray(8 * 1024 * 1024 + 1)
            val archive = writeNar(
                directory,
                validEntries() + EntrySpec("assets/oversized.bin", oversized),
            )
            patchCentralDirectoryUncompressedSize(archive, "assets/oversized.bin", 1)
            ZipFile(archive.toFile()).use { zip ->
                assertEquals(1, assertNotNull(zip.getEntry("assets/oversized.bin")).size)
            }

            assertFailure(loader.load(archive), "entry-size-limit")
        }

    @Test
    fun rejectsMoreThanSixtyFourMibTotalUsingConsumedBytes() = withTemporaryDirectory { directory ->
        val payload = ByteArray(7 * 1024 * 1024 + 512 * 1024)
        val entries = validEntries() + (0 until 9).map { index ->
            EntrySpec("assets/chunk-$index.bin", payload)
        }

        assertFailure(loader.load(writeNar(directory, entries)), "archive-size-limit")
    }

    @Test
    fun rejectsUnreadableCompressedEntryAndClosesArchiveOnFailure() = withTemporaryDirectory { directory ->
        val archive = writeNar(directory, validEntries())
        corruptFirstCompressedByte(archive, "ghost/master/dic01.txt")

        assertFailure(loader.load(archive), "entry-read-failed")
        Files.delete(archive)
        assertFalse(Files.exists(archive))
    }

    @Test
    fun rejectsUnsupportedInconsistentAndMissingCharsetDeclarations() = withTemporaryDirectory { directory ->
        val unsupported = validEntries().map { spec ->
            EntrySpec(spec.name, spec.bytes.replaceAscii("charset,UTF-8", "charset,ISO-8859-1"), spec.directory)
        }
        val inconsistent = validEntries().map { spec ->
            if (spec.name == "ghost/master/dic01.txt") {
                textEntry(spec.name, "charset,Shift_JIS\n＊talk\nline\n", Charset.forName("Shift_JIS"))
            } else {
                spec
            }
        }
        val missing = validEntries().map { spec ->
            EntrySpec(spec.name, spec.bytes.replaceAscii("charset,UTF-8\n", ""), spec.directory)
        }

        assertFailure(loader.load(writeNar(directory, unsupported, "unsupported.nar")), "unsupported-charset")
        assertFailure(loader.load(writeNar(directory, inconsistent, "inconsistent.nar")), "inconsistent-charset")
        assertFailure(loader.load(writeNar(directory, missing, "missing.nar")), "missing-charset")
    }

    @Test
    fun reportsMalformedTextInsteadOfReplacingInvalidBytes() = withTemporaryDirectory { directory ->
        val malformed = byteArrayOf(
            *"charset,UTF-8\n＊talk\n".toByteArray(StandardCharsets.UTF_8),
            0xc3.toByte(),
            0x28,
        )
        val entries = validEntries().map { spec ->
            if (spec.name == "ghost/master/dic01.txt") EntrySpec(spec.name, malformed) else spec
        }

        assertFailure(loader.load(writeNar(directory, entries)), "malformed-text")
    }

    @Test
    fun rejectsMissingDictionariesIdentityAndShellInventoryWithoutContentLeakage() =
        withTemporaryDirectory { directory ->
            val secret = "private-authored-secret"
            val noDictionary = validEntries().filterNot { it.name == "ghost/master/dic01.txt" }
            val noIdentity = validEntries().map { spec ->
                if (spec.name == "ghost/master/descript.txt") {
                    textEntry(
                        spec.name,
                        "charset,UTF-8\nname,Ghost\nsakura.name,Sakura\nnote,$secret\n",
                    )
                } else {
                    spec
                }
            }
            val noSurfaces = validEntries().map { spec ->
                if (spec.name == "shell/master/surfaces.txt") {
                    textEntry(spec.name, "charset,UTF-8\n// $secret\n")
                } else {
                    spec
                }
            }

            assertFailure(loader.load(writeNar(directory, noDictionary, "no-dictionary.nar")), "missing-dictionary")
            assertFailure(loader.load(writeNar(directory, noIdentity, "no-identity.nar")), "missing-identity", secret)
            assertFailure(loader.load(writeNar(directory, noSurfaces, "no-surfaces.nar")), "missing-shell-inventory", secret)
        }

    @Test
    fun closesArchiveAfterSuccessfulLoad() = withTemporaryDirectory { directory ->
        val archive = writeNar(directory, validEntries())

        assertIs<NarLoadResult.Success>(loader.load(archive))
        Files.delete(archive)

        assertFalse(Files.exists(archive))
    }

    private fun validEntries(
        charsetDeclaration: String = "UTF-8",
        charset: Charset = StandardCharsets.UTF_8,
        ghostName: String = "Fixture Ghost",
        sakuraName: String = "Sakura",
        keroName: String = "Kero",
        dictionaryText: String = "＊talk\nhello\n",
    ): List<EntrySpec> = listOf(
        textEntry("install.txt", "charset,$charsetDeclaration\ntype,ghost\n", charset),
        textEntry(
            "ghost/master/descript.txt",
            "charset,$charsetDeclaration\nname,$ghostName\nsakura.name,$sakuraName\nkero.name,$keroName\n",
            charset,
        ),
        textEntry(
            "ghost/master/dic01.txt",
            "charset,$charsetDeclaration\n$dictionaryText",
            charset,
        ),
        textEntry("shell/master/surfaces.txt", "charset,$charsetDeclaration\nsurface0 {\n}\nsurface10 {\n}\n", charset),
    )

    private fun textEntry(
        name: String,
        text: String,
        charset: Charset = StandardCharsets.UTF_8,
    ) = EntrySpec(name, text.toByteArray(charset))

    private fun writeNar(
        directory: Path,
        entries: List<EntrySpec>,
        fileName: String = "fixture.nar",
    ): Path {
        val path = directory.resolve(fileName)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { spec ->
                zip.putNextEntry(ZipEntry(spec.name))
                if (!spec.directory) zip.write(spec.bytes)
                zip.closeEntry()
            }
        }
        return path
    }

    private fun patchCentralDirectoryUncompressedSize(path: Path, name: String, size: Int) {
        val bytes = Files.readAllBytes(path)
        val expectedName = name.toByteArray(StandardCharsets.UTF_8)
        var offset = 0
        while (offset <= bytes.size - 46) {
            if (bytes.readIntLe(offset) == 0x02014b50) {
                val nameLength = bytes.readShortLe(offset + 28)
                val extraLength = bytes.readShortLe(offset + 30)
                val commentLength = bytes.readShortLe(offset + 32)
                val actualName = bytes.copyOfRange(offset + 46, offset + 46 + nameLength)
                if (actualName.contentEquals(expectedName)) {
                    bytes.writeIntLe(offset + 24, size)
                    Files.write(path, bytes)
                    return
                }
                offset += 46 + nameLength + extraLength + commentLength
            } else {
                offset++
            }
        }
        error("Central-directory entry not found")
    }

    private fun corruptFirstCompressedByte(path: Path, name: String) {
        val bytes = Files.readAllBytes(path)
        val expectedName = name.toByteArray(StandardCharsets.UTF_8)
        var offset = 0
        while (offset <= bytes.size - 30) {
            if (bytes.readIntLe(offset) == 0x04034b50) {
                val nameLength = bytes.readShortLe(offset + 26)
                val extraLength = bytes.readShortLe(offset + 28)
                val actualName = bytes.copyOfRange(offset + 30, offset + 30 + nameLength)
                if (actualName.contentEquals(expectedName)) {
                    val dataOffset = offset + 30 + nameLength + extraLength
                    bytes[dataOffset] = (bytes[dataOffset].toInt() xor 0xff).toByte()
                    Files.write(path, bytes)
                    return
                }
            }
            offset++
        }
        error("Local entry not found")
    }

    private fun assertFailure(result: NarLoadResult, code: String, forbidden: String? = null) {
        val failure = assertIs<NarLoadResult.Failure>(result)
        assertEquals(code, failure.code)
        assertTrue(failure.detail.isNotBlank())
        if (forbidden != null) assertFalse(failure.detail.contains(forbidden))
    }

    private fun <T> withTemporaryDirectory(block: (Path) -> T): T {
        val directory = createTempDirectory("nar-loader-test-")
        return try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun ByteArray.replaceAscii(old: String, replacement: String): ByteArray {
        val source = toString(StandardCharsets.ISO_8859_1)
        return source.replace(old, replacement).toByteArray(StandardCharsets.ISO_8859_1)
    }

    private fun ByteArray.readShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readIntLe(offset: Int): Int =
        readShortLe(offset) or (readShortLe(offset + 2) shl 16)

    private fun ByteArray.writeIntLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private data class EntrySpec(
        val name: String,
        val bytes: ByteArray,
        val directory: Boolean = false,
    )
}
