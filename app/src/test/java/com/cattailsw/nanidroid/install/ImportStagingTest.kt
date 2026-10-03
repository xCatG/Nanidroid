package com.cattailsw.nanidroid.install

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportStagingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun recoveryDeletesOnlyOwnedChildrenAndKeepsPublishedGhost() = runBlocking {
        val files = temp.newFolder("files")
        val abandoned = files.resolve("import-staging/attempt-old").apply { mkdirs() }
        abandoned.resolve("source.nar").writeText("temporary")
        val foreign = files.resolve("import-staging/foreign").apply { mkdirs() }
        foreign.resolve("keep.txt").writeText("keep")
        val ghost = files.resolve("ghost/visitor").apply { mkdirs() }
        ghost.resolve("save.txt").writeText("saved")
        GhostImporter(files).recoverAbandonedAttempts()
        assertFalse(abandoned.exists())
        assertEquals("keep", foreign.resolve("keep.txt").readText())
        assertEquals("saved", ghost.resolve("save.txt").readText())
    }

    @Test fun linkedRootAndLinkedAttemptAreRejectedWithoutTraversal() = runBlocking {
        val files = temp.newFolder("files")
        val outside = temp.newFolder("outside").apply { resolve("keep").writeText("safe") }
        try { Files.createSymbolicLink(files.resolve("import-staging").toPath(), outside.toPath()) }
        catch (e: java.nio.file.FileSystemException) { assumeNoException(e) }
        assertThrows(Exception::class.java) { runBlocking { GhostImporter(files).recoverAbandonedAttempts() } }
        assertEquals("safe", outside.resolve("keep").readText())
    }

    @Test fun linkedChildInsideAttemptBlocksRecoveryWithoutDeletingOutside() = runBlocking {
        val files = temp.newFolder("files")
        val outside = temp.newFolder("outside").apply { resolve("keep").writeText("safe") }
        val attempt = files.resolve("import-staging/attempt-old").apply { mkdirs() }
        try { Files.createSymbolicLink(attempt.resolve("linked").toPath(), outside.toPath()) }
        catch (e: java.nio.file.FileSystemException) { assumeNoException(e) }
        assertThrows(Exception::class.java) { runBlocking { GhostImporter(files).recoverAbandonedAttempts() } }
        assertEquals("safe", outside.resolve("keep").readText())
    }

    @Test fun recoveryFailureBlocksNewStaging() = runBlocking {
        val files = temp.newFolder("files")
        files.resolve("import-staging/attempt-old").mkdirs()
        val importer = GhostImporter(files, beforeCleanup = { throw IOException("cannot clean") })
        assertThrows(IOException::class.java) { runBlocking { importer.recoverAbandonedAttempts() } }
        assertTrue(importer.importArchive({ error("provider must stay closed") }) {} is ImportOutcome.Failed)
    }
}
