package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The application-owned lock spans recovery, the final collision check and publication. */
class GhostImporter(
    private val filesDir: File,
    private val reader: NarArchiveReader = NarArchiveReader(),
    private val limits: ImportLimits = ImportLimits(),
    internal val beforeMove: () -> Unit = {},
    internal val afterMove: () -> Unit = {},
    internal val beforeCleanup: () -> Unit = {},
    internal val writeSourceChunk: (OutputStream, ByteArray, Int) -> Unit = { output, bytes, count ->
        output.write(bytes, 0, count)
    },
) {
    private val mutex = Mutex()
    private val staging = ImportStaging(filesDir, beforeCleanup)
    private val ghostRoot = File(filesDir, "ghost")

    suspend fun recoverAbandonedAttempts() = mutex.withLock {
        withContext(Dispatchers.IO) { staging.recover() }
    }

    suspend fun importArchive(
        openSource: () -> InputStream,
        onValidated: suspend (ImportPackage) -> Unit,
    ): ImportOutcome = mutex.withLock {
        var attempt: File? = null
        var committedId: String? = null
        var outcome: ImportOutcome
        try {
            withContext(Dispatchers.IO) { staging.recover() }
            currentCoroutineContext().ensureActive()
            attempt = withContext(Dispatchers.IO) { staging.begin() }
            val source = File(attempt, "source.nar")
            val job = currentCoroutineContext()[Job]
            withContext(Dispatchers.IO) { copySource(openSource, source, job) { job?.ensureActive() } }
            currentCoroutineContext().ensureActive()
            val metadata = withContext(Dispatchers.IO) { reader.inspect(source) { job?.ensureActive() } }
            validateId(metadata.directoryId)
            if (metadata.directoryId.equals("nanidroid", ignoreCase = true) ||
                withContext(Dispatchers.IO) { collision(metadata.directoryId) }) {
                outcome = ImportOutcome.Refused(metadata.directoryId)
            } else {
                onValidated(metadata)
                currentCoroutineContext().ensureActive()
                val tree = File(attempt, "tree")
                val candidate = File(tree, metadata.directoryId)
                withContext(Dispatchers.IO) {
                    require(tree.mkdir() && candidate.mkdir()) { "Cannot create import staging tree" }
                    reader.extract(source, metadata, candidate) { job?.ensureActive() }
                    InstalledGhostRepository(tree, limits.ghostDescriptorBytes).validate(metadata.directoryId)
                }
                currentCoroutineContext().ensureActive()
                outcome = withContext(Dispatchers.IO) {
                    prepareGhostRoot()
                    if (collision(metadata.directoryId)) ImportOutcome.Refused(metadata.directoryId)
                    else {
                        beforeMove()
                        // Check again after injected faults or a concurrent non-import writer.
                        if (collision(metadata.directoryId)) ImportOutcome.Refused(metadata.directoryId)
                        else try {
                            job?.ensureActive()
                            Files.move(candidate.toPath(), ghostRoot.resolve(metadata.directoryId).toPath())
                            committedId = metadata.directoryId
                            ImportOutcome.Installed(metadata.directoryId)
                        } catch (_: FileAlreadyExistsException) {
                            ImportOutcome.Refused(metadata.directoryId)
                        }
                    }
                }
                if (committedId != null) runCatching { afterMove() }
            }
        } catch (_: CancellationException) {
            outcome = committedId?.let(ImportOutcome::Installed) ?: ImportOutcome.Cancelled
        } catch (e: Exception) {
            outcome = committedId?.let(ImportOutcome::Installed)
                ?: ImportOutcome.Failed(friendlyFailure(e))
        } finally {
            // The IO return dispatch must also be noncancellable after publication.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    attempt?.let { runCatching { staging.cleanup(it) } }
                }
            }
        }
        outcome
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun copySource(openSource: () -> InputStream, target: File, job: Job?, checkCancelled: () -> Unit) {
        val input = openSource()
        val sourceClosed = AtomicBoolean()
        fun closeOnce() {
            if (sourceClosed.compareAndSet(false, true)) input.close()
        }
        // A provider pipe can block inside read after the last cancellation check.
        // Its close can block too, so never run it on the caller of Job.cancel().
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) CoroutineScope(Dispatchers.IO).launch { runCatching { closeOnce() } }
        }
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var count = 0L
                while (true) {
                    checkCancelled()
                    val n = try { input.read(buffer) } catch (e: Exception) {
                        checkCancelled()
                        throw e
                    }
                    if (n < 0) break
                    require(count <= limits.archiveBytes - n) { "Archive byte limit exceeded" }
                    count += n
                    writeSourceChunk(output, buffer, n)
                }
            }
        } finally {
            cancellation?.dispose()
            closeOnce()
        }
    }

    private fun validateId(id: String) {
        require(id.isNotEmpty() && id == id.trim() && !id.startsWith('.') && !id.endsWith('.') &&
            id.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            "Invalid ghost directory"
        }
    }

    private fun prepareGhostRoot() {
        staging.validatePrivateRoot()
        val path = ghostRoot.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "Ghost root is invalid"
        }
    }

    private fun collision(id: String): Boolean {
        prepareGhostRoot()
        Files.newDirectoryStream(ghostRoot.toPath()).use { children ->
            return children.any { it.fileName.toString().lowercase(Locale.ROOT) == id.lowercase(Locale.ROOT) }
        }
    }

    // openSource and validation callbacks may supply their own exception text.
    private fun friendlyFailure(e: Exception): String = when (e.message) {
        "Archive byte limit exceeded",
        "Entry count limit exceeded",
        "File byte limit exceeded",
        "Ghost descriptor byte limit exceeded",
        "Expanded byte limit exceeded",
        "Invalid ghost directory" -> if (e is IllegalArgumentException) e.message!! else "Unable to import ghost"
        else -> "Unable to import ghost"
    }
}
