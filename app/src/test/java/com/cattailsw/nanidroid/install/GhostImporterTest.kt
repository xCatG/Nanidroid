package com.cattailsw.nanidroid.install

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GhostImporterTest {
    @get:Rule val temp = TemporaryFolder()
    private fun files() = temp.newFolder()
    private fun archive(id: String = "visitor", includeShell: Boolean = true): ByteArray {
        val file = temp.newFile()
        ZipArchiveOutputStream(file).use { zip ->
            val entries = mutableMapOf(
                "install.txt" to "type,ghost\ndirectory,$id\n".toByteArray(),
                "ghost/master/descript.txt" to "name,Visitor\nshiori,Nanidroid\n".toByteArray(),
            )
            if (includeShell) entries["shell/master/placeholder.txt"] = byteArrayOf(1)
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return file.readBytes()
    }
    private fun installed(root: File, id: String): File = root.resolve("ghost/$id").apply {
        resolve("ghost/master").mkdirs()
        resolve("ghost/master/descript.txt").writeText("name,Existing\n")
        resolve("shell/master").mkdirs()
        resolve("ghost/master/savedata/profile.txt").apply { parentFile!!.mkdirs(); writeText("persistent") }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun publishesValidatedTreeAndCopiesProviderOnlyOnce() = runBlocking {
        val root = files(); val bytes = archive(); var opens = 0; var closes = 0; var validated = false
        val result = GhostImporter(root).importArchive({ opens++; object : ByteArrayInputStream(bytes) {
            override fun close() { closes++; super.close() }
        } }) {
            assertEquals("visitor", it.directoryId)
            validated = true
            assertFalse(root.resolve("ghost/visitor").exists())
        }
        assertEquals(ImportOutcome.Installed("visitor"), result)
        assertEquals(1, opens)
        assertEquals(1, closes)
        assertTrue(validated)
        assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
    }

    @Test fun duplicateAndCaseDuplicatePreserveSavedata() = runBlocking {
        val root = files(); val existing = installed(root, "Visitor")
        val save = existing.resolve("ghost/master/savedata/profile.txt"); val before = hash(save)
        for (id in listOf("Visitor", "visitor")) {
            var began = false
            assertEquals(ImportOutcome.Refused(id), GhostImporter(root).importArchive(
                { ByteArrayInputStream(archive(id)) }, { began = true }))
            assertFalse(began)
            assertEquals(before, hash(save))
        }
    }

    @Test fun invalidExistingTargetStillRefuses() = runBlocking {
        val root = files()
        root.resolve("ghost").mkdirs()
        val existing = root.resolve("ghost/visitor").apply { writeText("do not replace") }
        assertEquals(ImportOutcome.Refused("visitor"), GhostImporter(root).importArchive(
            { ByteArrayInputStream(archive()) }, { fail("Begin for existing file") }))
        assertEquals("do not replace", existing.readText())
    }

    @Test fun reservedAndInvalidIdsNeverPublish() = runBlocking {
        val root = files()
        for (id in listOf("nanidroid", "NANIDROID")) {
            assertEquals(ImportOutcome.Refused(id), GhostImporter(root).importArchive(
                { ByteArrayInputStream(archive(id)) }, { fail("Begin for reserved ID") }))
        }
        for (id in listOf(".nanidroid-staging", ".other", "trailing.", " leading", "bad:id")) {
            assertTrue(GhostImporter(root).importArchive({ ByteArrayInputStream(archive(id)) }) {} is ImportOutcome.Failed)
        }
    }

    @Test fun extractionErrorAndCancellationPreserveExistingTree() = runBlocking {
        val root = files(); val save = installed(root, "Visitor").resolve("ghost/master/savedata/profile.txt")
        val before = hash(save)
        assertTrue(GhostImporter(root).importArchive({ ByteArrayInputStream(archive("new", false)) }) {} is ImportOutcome.Failed)
        assertEquals(ImportOutcome.Cancelled, GhostImporter(root).importArchive(
            { ByteArrayInputStream(archive("new")) }, { throw CancellationException("stop") }))
        assertEquals(before, hash(save))
        assertFalse(root.resolve("ghost/new").exists())
    }

    @Test fun lateExactAndCaseCollisionRefuseAfterValidation() = runBlocking {
        for (collision in listOf("visitor", "VISITOR")) {
            val root = files()
            val result = GhostImporter(root).importArchive({ ByteArrayInputStream(archive()) }) {
                root.resolve("ghost/$collision").mkdirs()
            }
            assertEquals(ImportOutcome.Refused("visitor"), result)
            assertFalse(root.resolve("ghost/visitor/ghost").exists())
        }
    }

    @Test fun failuresAroundMoveRespectCommitPoint() = runBlocking {
        val before = files()
        assertTrue(GhostImporter(before, beforeMove = { throw IOException("disk full") }).importArchive(
            { ByteArrayInputStream(archive()) }) {} is ImportOutcome.Failed)
        assertFalse(before.resolve("ghost/visitor").exists())
        val cancelled = files()
        assertEquals(ImportOutcome.Cancelled, GhostImporter(cancelled,
            beforeMove = { throw CancellationException("stop before commit") }).importArchive(
            { ByteArrayInputStream(archive()) }) {})
        assertFalse(cancelled.resolve("ghost/visitor").exists())
        val after = files()
        assertEquals(ImportOutcome.Installed("visitor"), GhostImporter(after,
            afterMove = { throw CancellationException("late") }).importArchive(
            { ByteArrayInputStream(archive()) }) {})
        assertTrue(after.resolve("ghost/visitor").isDirectory)
    }

    @Test fun cleanupFailureAfterPublicationKeepsInstalledBytes() = runBlocking {
        val root = files()
        val importer = GhostImporter(root, beforeCleanup = { throw IOException("cleanup failed") })
        assertEquals(ImportOutcome.Installed("visitor"), importer.importArchive(
            { ByteArrayInputStream(archive()) }) {})
        assertEquals("name,Visitor\nshiori,Nanidroid\n",
            root.resolve("ghost/visitor/ghost/master/descript.txt").readText())
        assertTrue(importer.importArchive({ error("provider must stay closed") }) {} is ImportOutcome.Failed)
    }

    @Test fun unreadableNullAndOversizeSourcesFail() = runBlocking {
        val root = files()
        assertTrue(GhostImporter(root).importArchive({ throw IOException("provider") }) {} is ImportOutcome.Failed)
        assertTrue(GhostImporter(root).importArchive({ null!! }) {} is ImportOutcome.Failed)
        assertTrue(GhostImporter(root).importArchive({ object : ByteArrayInputStream(archive()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("storage full")
        } }) {} is ImportOutcome.Failed)
        assertTrue(GhostImporter(root, limits = ImportLimits(archiveBytes = 2)).importArchive(
            { ByteArrayInputStream(archive()) }) {} is ImportOutcome.Failed)
        assertFalse(root.resolve("ghost/visitor").exists())
    }

    @Test fun providerArgumentErrorDoesNotExposeItsMessage() = runBlocking {
        val result = GhostImporter(files()).importArchive(
            { throw IllegalArgumentException("content://provider/private/document/secret") }) {}
        assertEquals(ImportOutcome.Failed("Unable to import ghost"), result)
    }

    @Test fun archiveByteLimitKeepsItsUserFacingReason() = runBlocking {
        val result = GhostImporter(files(), limits = ImportLimits(archiveBytes = 2)).importArchive(
            { ByteArrayInputStream(archive()) }) {}
        assertEquals(ImportOutcome.Failed("Archive byte limit exceeded"), result)
    }

    @Test fun oversizedGhostDescriptorFailsWithoutPublicationAndShowsSafeReason() = runBlocking {
        val root = files()
        val limits = ImportLimits(ghostDescriptorBytes = 10)
        val result = GhostImporter(root, reader = NarArchiveReader(limits), limits = limits).importArchive(
            { ByteArrayInputStream(archive()) }) {}

        assertEquals(ImportOutcome.Failed("Ghost descriptor byte limit exceeded"), result)
        assertFalse(root.resolve("ghost/visitor").exists())
    }

    @Test fun sourceWriteFailureClosesProviderAndKeepsInstalledSaves() = runBlocking {
        val root = files()
        val save = installed(root, "existing").resolve("ghost/master/savedata/profile.txt")
        val before = hash(save)
        var closed = false
        val result = GhostImporter(root, writeSourceChunk = { _, _, _ -> throw IOException("disk full") })
            .importArchive({ object : ByteArrayInputStream(archive()) {
                override fun close() { closed = true; super.close() }
            } }) {}
        assertTrue(result is ImportOutcome.Failed)
        assertTrue(closed)
        assertEquals(before, hash(save))
        assertFalse(root.resolve("ghost/visitor").exists())
    }

    @Test fun cancellationDuringBlockedSourceReadClosesSourceAndCleansStaging() = runBlocking {
        val root = files()
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val returned = CompletableDeferred<ImportOutcome>()
        val importJob = launch(Dispatchers.Default) {
            returned.complete(GhostImporter(root).importArchive({ object : InputStream() {
                override fun read(): Int = error("Only chunk reads are expected")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    enteredRead.countDown()
                    releaseRead.await()
                    throw IOException("source closed")
                }
                override fun close() {
                    closeCount.incrementAndGet()
                    releaseRead.countDown()
                }
            } }) {})
        }
        try {
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS))
            importJob.cancel()
            assertEquals(ImportOutcome.Cancelled, withTimeout(2_000) { returned.await() })
            assertEquals(1, closeCount.get())
            assertFalse(root.resolve("ghost/visitor").exists())
            assertFalse(root.resolve("import-staging").listFiles()?.any() == true)
        } finally {
            releaseRead.countDown()
            withTimeout(5_000) { importJob.join() }
        }
    }

    @Test fun blockingSourceCloseDoesNotBlockCancellingThread() = runBlocking {
        val root = files()
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val returned = CompletableDeferred<ImportOutcome>()
        val importJob = launch(Dispatchers.IO) {
            returned.complete(GhostImporter(root).importArchive({ object : InputStream() {
                override fun read(): Int = error("Only chunk reads are expected")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    enteredRead.countDown()
                    releaseRead.await()
                    throw IOException("source closed")
                }
                override fun close() {
                    enteredClose.countDown()
                    releaseClose.await()
                    releaseRead.countDown()
                }
            } }) {})
        }
        var cancellingThread: Thread? = null
        try {
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS))
            cancellingThread = Thread {
                importJob.cancel()
                cancelReturned.countDown()
            }.apply { start() }
            assertTrue("Cancellation never began closing the source", enteredClose.await(5, TimeUnit.SECONDS))
            assertTrue("Job.cancel blocked on provider close", cancelReturned.await(1, TimeUnit.SECONDS))
        } finally {
            releaseClose.countDown()
            releaseRead.countDown()
            cancellingThread?.join(5_000)
            withTimeout(5_000) { importJob.join() }
        }
        assertEquals(ImportOutcome.Cancelled, returned.await())
    }

    @Test fun cancellationRacingNormalReadCompletionClosesSourceOnce() = runBlocking {
        repeat(20) {
            val root = files()
            val enteredRead = CountDownLatch(1)
            val releaseBoth = CountDownLatch(1)
            val closeCount = AtomicInteger()
            val returned = CompletableDeferred<ImportOutcome>()
            val importJob = launch(Dispatchers.IO) {
                returned.complete(GhostImporter(root).importArchive({ object : InputStream() {
                    override fun read(): Int = error("Only chunk reads are expected")
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                        enteredRead.countDown()
                        releaseBoth.await()
                        return -1
                    }
                    override fun close() { closeCount.incrementAndGet() }
                } }) {})
            }
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS))
            val cancellingThread = Thread {
                releaseBoth.await()
                importJob.cancel()
            }.apply { start() }
            releaseBoth.countDown()
            withTimeout(5_000) { returned.await() }
            cancellingThread.join(5_000)
            assertFalse(cancellingThread.isAlive)
            assertEquals("round $it", 1, closeCount.get())
        }
    }

    @Test fun cancellationWhileOpeningSourceStillClosesIt() = runBlocking {
        val root = files()
        val closeCount = AtomicInteger()
        val returned = CompletableDeferred<ImportOutcome>()
        lateinit var importJob: kotlinx.coroutines.Job
        importJob = launch(start = CoroutineStart.LAZY) {
            returned.complete(GhostImporter(root).importArchive({
                importJob.cancel()
                object : ByteArrayInputStream(archive()) {
                    override fun close() {
                        closeCount.incrementAndGet()
                        super.close()
                    }
                }
            }) {})
        }
        importJob.start()
        assertEquals(ImportOutcome.Cancelled, withTimeout(5_000) { returned.await() })
        assertTrue(closeCount.get() >= 1)
        assertFalse(root.resolve("ghost/visitor").exists())
    }

    @Test fun linkedAppRootCannotRedirectImport() = runBlocking {
        val real = temp.newFolder("real-app")
        val alias = temp.root.resolve("linked-app")
        try { Files.createSymbolicLink(alias.toPath(), real.toPath()) }
        catch (e: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) assumeNoException(e)
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", alias.absolutePath, real.absolutePath)
                .redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            if (process.waitFor() != 0) assumeNoException(e)
        }
        val files = alias.resolve("files").apply { mkdirs() }
        var opened = false
        val result = GhostImporter(files).importArchive({ opened = true; ByteArrayInputStream(archive()) }) {}
        assertTrue(result is ImportOutcome.Failed)
        assertFalse(opened)
        assertFalse(real.resolve("files/import-staging").exists())
    }

    @Test fun actualJobCancellationAfterMoveStillReturnsInstalled() = runBlocking {
        val root = files()
        val returned = CompletableDeferred<Result<ImportOutcome>>()
        lateinit var importJob: kotlinx.coroutines.Job
        val importer = GhostImporter(root, afterMove = { importJob.cancel() })
        importJob = launch(start = CoroutineStart.LAZY) {
            try {
                returned.complete(Result.success(importer.importArchive(
                    { ByteArrayInputStream(archive()) }) {}))
            } catch (e: Exception) {
                returned.complete(Result.failure(e))
            }
        }
        importJob.start()
        assertEquals(ImportOutcome.Installed("visitor"), withTimeout(10_000) { returned.await().getOrThrow() })
        assertTrue(root.resolve("ghost/visitor/ghost/master/descript.txt").isFile)
    }
}
