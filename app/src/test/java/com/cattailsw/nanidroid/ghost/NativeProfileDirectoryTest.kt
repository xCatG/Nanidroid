package com.cattailsw.nanidroid.ghost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeProfileDirectoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun createsMissingProfileInsideMaster() {
        val master = temporaryFolder.newFolder("master")
        val profile = NativeProfileDirectory.prepare(master)

        assertEquals(File(master, "profile").canonicalFile, profile.canonicalFile)
        assertTrue(profile.isDirectory)
    }

    @Test fun repeatedPreparationPreservesSavedBytes() {
        val master = temporaryFolder.newFolder("master")
        val profile = File(master, "profile").apply { mkdir() }
        val save = File(profile, "dict-savedata.txt")
        val bytes = byteArrayOf(0, 1, -1, 42)
        save.writeBytes(bytes)

        assertEquals(profile.canonicalFile, NativeProfileDirectory.prepare(master).canonicalFile)
        assertEquals(profile.canonicalFile, NativeProfileDirectory.prepare(master).canonicalFile)
        assertArrayEquals(bytes, save.readBytes())
    }

    @Test fun rejectsRegularFileAtProfile() {
        val master = temporaryFolder.newFolder("master")
        File(master, "profile").writeText("keep")

        assertTrue(runCatching { NativeProfileDirectory.prepare(master) }.exceptionOrNull() is IllegalStateException)
        assertEquals("keep", File(master, "profile").readText())
    }

    @Test fun rejectsInjectedCreationFailure() {
        val master = temporaryFolder.newFolder("master")

        val error = runCatching { NativeProfileDirectory.prepare(master) { false } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(File(master, "profile").exists())
    }

    @Test fun acceptsDirectoryCreatedWhileMkdirsReturnsFalse() {
        val master = temporaryFolder.newFolder("master")

        val profile = NativeProfileDirectory.prepare(master) { directory ->
            assertTrue(directory.mkdir())
            false
        }
        assertTrue(profile.isDirectory)
    }

    @Test fun rejectsEscapingProfileSymlink() {
        val master = temporaryFolder.newFolder("master")
        val outside = temporaryFolder.newFolder("outside")
        try {
            Files.createSymbolicLink(File(master, "profile").toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }

        assertTrue(runCatching { NativeProfileDirectory.prepare(master) }.exceptionOrNull() is IllegalStateException)
    }
}
