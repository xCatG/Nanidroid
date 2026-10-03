package com.cattailsw.nanidroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchivePathTest {
    @Test fun normalizesSeparatorsAndKeepsJapaneseNames() {
        assertEquals("ghost/立ち絵.png", ArchivePath.normalize("ghost\\立ち絵.png"))
    }

    @Test fun refusesPathsThatCouldEscapeOrAlias() {
        listOf(
            "/ghost/a", "C:/ghost/a", "C:\\ghost\\a", "\\\\host\\share\\a",
            "../a", "ghost/../a", "ghost/./a", "ghost//a", "ghost/\u0000a",
            "ghost/\u001fa", "ghost/\u0085a", "ghost/a:", "ghost/a/"
        ).forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { ArchivePath.normalize(path) }
        }
    }

    @Test fun enforcesPathLengthAndComponentCount() {
        assertThrows(IllegalArgumentException::class.java) { ArchivePath.normalize("a/".repeat(32) + "b") }
        assertThrows(IllegalArgumentException::class.java) { ArchivePath.normalize("a".repeat(1025)) }
    }
}
