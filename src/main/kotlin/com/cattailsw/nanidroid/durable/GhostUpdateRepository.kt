package com.cattailsw.nanidroid.durable

import android.net.Uri
import com.cattailsw.nanidroid.SScriptRunner
import com.cattailsw.nanidroid.install.NarRelativePathPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class GhostUpdateRequest(
    val operationId: OperationId,
    val ghostId: String,
    val ghostRoot: File,
    val baseUri: Uri,
    val attemptId: AttemptId? = null,
    val workManagerUuid: String? = null,
)

sealed interface GhostUpdateResult {
    data class Completed(val files: List<String>) : GhostUpdateResult
    data object NoChanges : GhostUpdateResult
    data object Cancelled : GhostUpdateResult
    data object Interrupted : GhostUpdateResult
    data class PublishPending(val files: List<String>) : GhostUpdateResult
    data class RollbackPending(val status: OperationStatus, val files: List<String>) : GhostUpdateResult
    data class Failed(val diagnostic: String) : GhostUpdateResult
}

internal enum class GhostUpdateStopReason {
    NONE,
    USER_CANCELLED,
    SYSTEM_INTERRUPTED,
}

fun interface GhostUpdateNetwork {
    fun open(baseUri: Uri, relativePath: String): InputStream?
}

interface GhostUpdateEvents {
    fun ready(files: List<String>) = Unit
    fun downloadBegin(file: String, index: Int, total: Int) = Unit
    fun digestCompareBegin(file: String, expected: String, actual: String) = Unit
    fun digestCompareComplete(file: String, expected: String, actual: String) = Unit
    fun digestCompareFailure(file: String, expected: String, actual: String) = Unit
    fun complete(files: List<String>) = Unit
    fun noChanges() = Unit
    fun failure(reason: String, files: List<String>) = Unit

    companion object {
        val NONE: GhostUpdateEvents = object : GhostUpdateEvents {}
    }
}

internal interface GhostUpdateFileOperations {
    fun rename(source: File, destination: File): Boolean = source.renameTo(destination)
    fun publishStaging(source: File, destination: File): Boolean = source.renameTo(destination)
    fun deleteTree(root: File): Boolean = root.deleteRecursively()

    companion object {
        val DEFAULT = object : GhostUpdateFileOperations {}
    }
}

internal fun interface GhostUpdateCommitGuard {
    fun commit(
        ghostId: String,
        ghostRoot: File,
        onFailure: (Throwable) -> GhostUpdateResult,
        action: () -> GhostUpdateResult,
    ): GhostUpdateResult

    fun commit(
        ghostId: String,
        ghostRoot: File,
        onFailure: (Throwable) -> GhostUpdateResult,
        shouldStop: () -> Boolean,
        onStopped: () -> GhostUpdateResult,
        action: () -> GhostUpdateResult,
    ): GhostUpdateResult = commit(ghostId, ghostRoot, onFailure, action)

    companion object {
        val NONE = GhostUpdateCommitGuard { _, _, onFailure, action ->
            try {
                action()
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}

internal fun interface GhostUpdateRecoveryGuard {
    fun recover(
        ghostId: String,
        ghostRoot: File,
        onFailure: (Throwable) -> RecoveryResult,
        action: () -> RecoveryResult,
    ): RecoveryResult

    fun recover(
        ghostId: String,
        ghostRoot: File,
        onFailure: (Throwable) -> RecoveryResult,
        shouldStop: () -> Boolean,
        onStopped: () -> RecoveryResult,
        action: () -> RecoveryResult,
    ): RecoveryResult = recover(ghostId, ghostRoot, onFailure, action)

    companion object {
        val NONE = GhostUpdateRecoveryGuard { _, _, onFailure, action ->
            try { action() } catch (error: Exception) { onFailure(error) }
        }
    }
}

class GhostUpdateRepository internal constructor(
    private val network: GhostUpdateNetwork,
    private val events: GhostUpdateEvents = GhostUpdateEvents.NONE,
    private val fileOperations: GhostUpdateFileOperations = GhostUpdateFileOperations.DEFAULT,
    private val journalIo: GhostUpdateJournalIo = GhostUpdateJournalIo.DEFAULT,
    private val onProgress: (phase: String, completed: Long) -> Unit = { _, _ -> },
    private val onCommitClassified: (GhostUpdateResult.Completed) -> Boolean = { true },
    private val onRollbackClassified: (OperationStatus) -> Boolean = { true },
    private val commitGuard: GhostUpdateCommitGuard = GhostUpdateCommitGuard.NONE,
    private val recoveryGuard: GhostUpdateRecoveryGuard = GhostUpdateRecoveryGuard.NONE,
) {
    fun run(
        request: GhostUpdateRequest,
        isCancelled: () -> Boolean,
    ): GhostUpdateResult = runInterruptible(request) {
        if (isCancelled()) GhostUpdateStopReason.USER_CANCELLED else GhostUpdateStopReason.NONE
    }

    internal fun runInterruptible(
        request: GhostUpdateRequest,
        stopReason: () -> GhostUpdateStopReason,
    ): GhostUpdateResult = withGhostLock(request.ghostRoot) {
        runLocked(request, stopReason)
    }

    private fun runLocked(
        request: GhostUpdateRequest,
        stopReason: () -> GhostUpdateStopReason,
    ): GhostUpdateResult {
        val isStopped = { stopReason() != GhostUpdateStopReason.NONE }
        val ghostRoot = request.ghostRoot.canonicalFile
        if (request.operationId.value.isBlank()) {
            return failed("invalid ghost update request", emptyList())
        }
        val transactionRoot = transactionRoot(ghostRoot, request.operationId)
        val candidateRoot = File(transactionRoot, CANDIDATE)
        val backupRoot = File(transactionRoot, BACKUP)
        val journalFile = File(transactionRoot, GhostUpdateJournalStore.FILE_NAME)
        var journalPersisted = false
        var manifestFiles = emptyList<String>()
        try {
            val replayFiles = replayJournalFiles(ghostRoot, request, journalIo)
            var replayStopped: GhostUpdateResult? = null
            val prior = if (journalFile.isFile) {
                recoveryGuard.recover(
                    request.ghostId,
                    ghostRoot,
                    onFailure = { RecoveryResult.Failed(it.message ?: "replay recovery gate failed") },
                    shouldStop = isStopped,
                    onStopped = {
                        replayStopped = stopResult(stopReason()) ?: GhostUpdateResult.Interrupted
                        RecoveryResult.Failed("ghost update stopped while awaiting replay recovery")
                    },
                ) {
                    recoverForReplay(ghostRoot, request, journalIo) { pending, status ->
                        when (status) {
                            OperationStatus.COMPLETED -> onCommitClassified(GhostUpdateResult.Completed(pending.files))
                            OperationStatus.FAILED,
                            OperationStatus.CANCELLED,
                            -> onRollbackClassified(status)
                            else -> false
                        }
                    }
                }
            } else RecoveryResult.NoJournal
            replayStopped?.let { return it }
            if (prior is RecoveryResult.Failed) return failed(prior.diagnostic, emptyList())
            if (prior is RecoveryResult.CompletedCommit && replayFiles != null) {
                events.complete(replayFiles)
                return GhostUpdateResult.Completed(replayFiles)
            }
            when (prior) {
                is RecoveryResult.PublishPending -> return completePending(
                    ghostRoot, request.operationId, prior.files,
                )
                is RecoveryResult.RollbackPending -> return GhostUpdateResult.RollbackPending(
                    prior.status, prior.files,
                )
                is RecoveryResult.CommitPending ->
                    return failed("replay recovery direction is unknown", prior.files)
                else -> Unit
            }
            if (!ghostRoot.isDirectory) return failed("invalid ghost update request", emptyList())
            stopResult(stopReason())?.let { return it }
            onProgress("Preparing candidate", 0)
            if (transactionRoot.exists() && !fileOperations.deleteTree(transactionRoot)) {
                return failed("cannot clear abandoned update staging", emptyList())
            }
            if (!createOwnedTransactionRoot(transactionRoot, request)) {
                return failed("cannot prepare update staging", emptyList())
            }
            var copiedBytes = 0L
            copyTree(ghostRoot, candidateRoot, isStopped) { count ->
                copiedBytes += count
                onProgress("Preparing candidate", copiedBytes)
            }
            stopBeforeJournal(transactionRoot, request, stopReason())?.let { return it }

            onProgress("Fetching update manifest", 0)
            val manifestSource = readManifest(request, stopReason)
            if (manifestSource == null) {
                stopBeforeJournal(transactionRoot, request, stopReason())?.let { return it }
                return failedBeforeJournal(transactionRoot, request, "update manifest not found")
            }
            stopBeforeJournal(transactionRoot, request, stopReason())?.let { return it }
            val manifest = parseManifest(manifestSource)
            var comparedBytes = 0L
            val changedManifest = manifest.filter { entry ->
                val local = resolveChild(candidateRoot, entry.path)
                !local.isFile || !fileDigest(local, stopReason) { count ->
                    comparedBytes += count
                    onProgress("Comparing installed files", comparedBytes)
                }.equals(entry.digest, ignoreCase = true)
            }
            manifestFiles = changedManifest.map { it.path }
            if (manifestFiles.isNotEmpty()) events.ready(manifestFiles)

            var downloadedBytes = 0L
            changedManifest.forEachIndexed { index, entry ->
                stopBeforeJournal(transactionRoot, request, stopReason())?.let { return it }
                events.downloadBegin(entry.path, index, changedManifest.lastIndex)
                onProgress("Downloading update", downloadedBytes)
                val target = resolveChild(candidateRoot, entry.path)
                val targetParent = target.parentFile ?: throw IOException("candidate target has no parent")
                if ((!targetParent.exists() && !targetParent.mkdirs()) || !targetParent.isDirectory) {
                    throw IOException("cannot prepare candidate parent")
                }
                val digest = MessageDigest.getInstance("MD5")
                val source = network.open(request.baseUri, entry.path)
                    ?: throw IOException("update file not found: ${entry.path}")
                source.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            throwIfStopped(stopReason)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloadedBytes += count
                            onProgress("Downloading update", downloadedBytes)
                        }
                        output.fd.sync()
                    }
                }
                throwIfStopped(stopReason)
                val actual = digest.digest().toHex()
                onProgress("Verifying update", index.toLong())
                events.digestCompareBegin(entry.path, entry.digest, actual)
                if (!actual.equals(entry.digest, ignoreCase = true)) {
                    events.digestCompareFailure(entry.path, entry.digest, actual)
                    return failedBeforeJournal(transactionRoot, request, "digest mismatch: ${entry.path}", manifestFiles)
                }
                events.digestCompareComplete(entry.path, entry.digest, actual)
            }

            val deletedCandidateEntries = applyCandidateDeletes(candidateRoot)
            if (changedManifest.isEmpty() && !deletedCandidateEntries.changed) {
                if (!cleanPreparedTransaction(transactionRoot, preparedJournal(transactionRoot, request))) {
                    return failed("cannot clean no-change update transaction", emptyList())
                }
                events.noChanges()
                return GhostUpdateResult.NoChanges
            }

            stopBeforeJournal(transactionRoot, request, stopReason())?.let { return it }
            val commitResult = commitGuard.commit(
                request.ghostId,
                ghostRoot,
                onFailure = { error ->
                    handleCommitFailure(
                        error,
                        ghostRoot,
                        request,
                        transactionRoot,
                        journalPersisted,
                        manifestFiles,
                        stopReason,
                    )
                },
                shouldStop = isStopped,
                onStopped = {
                    stopBeforeJournal(transactionRoot, request, stopReason()) ?: GhostUpdateResult.Interrupted
                },
            ) {
                onProgress("Committing update", 0)
                throwIfStopped(stopReason)
                resyncUnmanagedLivePaths(
                    ghostRoot,
                    candidateRoot,
                    manifest.map { it.path },
                    deletedCandidateEntries.entries,
                    stopReason,
                )
                throwIfStopped(stopReason)
                val journal = GhostUpdateJournal(
                    operationId = request.operationId,
                    ghostRoot = ghostRoot.canonicalPath,
                    candidateRoot = candidateRoot.canonicalPath,
                    backupRoot = backupRoot.canonicalPath,
                    phase = CommitPhase.PREPARED,
                    files = manifestFiles,
                    attemptId = request.attemptId,
                    workManagerUuid = request.workManagerUuid,
                )
                journalIo.write(journalFile, journal)
                journalPersisted = true

                if (!fileOperations.rename(ghostRoot, backupRoot)) throw IOException("cannot back up live ghost")
                journalIo.write(journalFile, journal.copy(phase = CommitPhase.BACKED_UP))
                if (!fileOperations.rename(candidateRoot, ghostRoot)) throw IOException("cannot publish candidate ghost")
                journalIo.write(journalFile, journal.copy(phase = CommitPhase.PUBLISHED))
                val completed = GhostUpdateResult.Completed(manifestFiles)
                if (!onCommitClassified(completed)) {
                    return@commit GhostUpdateResult.PublishPending(manifestFiles)
                }
                if (backupRoot.exists() && !fileOperations.deleteTree(backupRoot)) {
                    throw IOException("cannot remove ghost update backup")
                }
                onProgress("Cleaning up update", 0)
                journalIo.write(journalFile, journal.copy(phase = CommitPhase.CLEANED))
                if (!fileOperations.deleteTree(transactionRoot)) throw IOException("cannot clean update transaction")
                completed
            }
            when (commitResult) {
                is GhostUpdateResult.Completed -> events.complete(manifestFiles)
                is GhostUpdateResult.Failed -> events.failure(commitResult.diagnostic, manifestFiles)
                else -> Unit
            }
            return commitResult
        } catch (stopped: UpdateStoppedException) {
            if (!journalPersisted) return stopBeforeJournal(transactionRoot, request, stopped.reason)
                ?: GhostUpdateResult.Interrupted
            return when (val recovered = recoverAfterJournalFailure(
                ghostRoot,
                request,
                if (stopped.reason == GhostUpdateStopReason.USER_CANCELLED) {
                    OperationStatus.CANCELLED
                } else OperationStatus.FAILED,
                journalIo,
                classify = { pending, status ->
                    when (status) {
                        OperationStatus.COMPLETED -> onCommitClassified(GhostUpdateResult.Completed(pending.files))
                        OperationStatus.FAILED,
                        OperationStatus.CANCELLED,
                        -> onRollbackClassified(status)
                        else -> false
                    }
                },
            )) {
                RecoveryResult.CompletedCommit -> GhostUpdateResult.Completed(manifestFiles)
                is RecoveryResult.PublishPending -> completePending(
                    ghostRoot, request.operationId, recovered.files,
                )
                is RecoveryResult.RollbackPending -> GhostUpdateResult.RollbackPending(
                    recovered.status, recovered.files,
                )
                is RecoveryResult.CommitPending ->
                    failed("recovery direction is unknown", recovered.files)
                RecoveryResult.RolledBack, RecoveryResult.NoJournal -> stopResult(stopped.reason)
                    ?: GhostUpdateResult.Interrupted
                is RecoveryResult.Failed -> failed(recovered.diagnostic, manifestFiles)
            }
        } catch (e: Exception) {
            if (journalPersisted) {
                when (val recovered = recoverAfterJournalFailure(
                    ghostRoot, request, OperationStatus.FAILED, journalIo,
                    classify = { pending, status ->
                        when (status) {
                            OperationStatus.COMPLETED -> onCommitClassified(GhostUpdateResult.Completed(pending.files))
                            OperationStatus.FAILED,
                            OperationStatus.CANCELLED,
                            -> onRollbackClassified(status)
                            else -> false
                        }
                    },
                )) {
                    RecoveryResult.CompletedCommit -> {
                        events.complete(manifestFiles)
                        return GhostUpdateResult.Completed(manifestFiles)
                    }
                    is RecoveryResult.PublishPending -> return completePending(
                        ghostRoot, request.operationId, recovered.files,
                    )
                    is RecoveryResult.RollbackPending -> return GhostUpdateResult.RollbackPending(
                        recovered.status, recovered.files,
                    )
                    is RecoveryResult.CommitPending ->
                        return failed("recovery direction is unknown", recovered.files)
                    RecoveryResult.RolledBack -> Unit
                    RecoveryResult.NoJournal -> Unit
                    is RecoveryResult.Failed -> return failed(recovered.diagnostic, manifestFiles)
                }
            } else {
                cleanPreparedTransaction(transactionRoot, preparedJournal(transactionRoot, request))
                stopResult(stopReason())?.let { return it }
            }
            return failed(e.message ?: "ghost update failed", manifestFiles)
        }
    }

    private fun handleCommitFailure(
        error: Throwable,
        ghostRoot: File,
        request: GhostUpdateRequest,
        transactionRoot: File,
        journalPersisted: Boolean,
        manifestFiles: List<String>,
        stopReason: () -> GhostUpdateStopReason,
    ): GhostUpdateResult {
        val stopped = error as? UpdateStoppedException
        if (!journalPersisted) {
            val reason = stopped?.reason ?: stopReason()
            if (reason == GhostUpdateStopReason.USER_CANCELLED) {
                return cancelledBeforeJournal(transactionRoot, request)
            }
            cleanPreparedTransaction(transactionRoot, preparedJournal(transactionRoot, request))
            return stopResult(reason)
                ?: GhostUpdateResult.Failed(error.message ?: "ghost update failed")
        }

        val failureStatus = if (stopped?.reason == GhostUpdateStopReason.USER_CANCELLED) {
            OperationStatus.CANCELLED
        } else {
            OperationStatus.FAILED
        }
        return when (val recovered = recoverAfterJournalFailure(
            ghostRoot,
            request,
            failureStatus,
            journalIo,
            classify = { pending, status ->
                when (status) {
                    OperationStatus.COMPLETED ->
                        onCommitClassified(GhostUpdateResult.Completed(pending.files))
                    OperationStatus.FAILED,
                    OperationStatus.CANCELLED,
                    -> onRollbackClassified(status)
                    else -> false
                }
            },
        )) {
            RecoveryResult.CompletedCommit -> GhostUpdateResult.Completed(manifestFiles)
            is RecoveryResult.PublishPending -> completePendingWithoutEvents(
                ghostRoot,
                request.operationId,
                recovered.files,
            )
            is RecoveryResult.RollbackPending -> GhostUpdateResult.RollbackPending(
                recovered.status,
                recovered.files,
            )
            is RecoveryResult.CommitPending ->
                GhostUpdateResult.Failed("recovery direction is unknown")
            RecoveryResult.RolledBack, RecoveryResult.NoJournal ->
                stopped?.let { stopResult(it.reason) }
                    ?: GhostUpdateResult.Failed(error.message ?: "ghost update failed")
            is RecoveryResult.Failed -> GhostUpdateResult.Failed(recovered.diagnostic)
        }
    }

    private fun readManifest(
        request: GhostUpdateRequest,
        stopReason: () -> GhostUpdateStopReason,
    ): ManifestSource? {
        for (name in listOf(UPDATE_V2, UPDATE_V3)) {
            val source = network.open(request.baseUri, name) ?: continue
            source.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    throwIfStopped(stopReason)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > MAX_MANIFEST_BYTES) throw IOException("update manifest is too large")
                    output.write(buffer, 0, count)
                }
                return ManifestSource(name, output.toByteArray())
            }
        }
        return null
    }

    private fun completePending(
        ghostRoot: File,
        operationId: OperationId,
        files: List<String>,
    ): GhostUpdateResult {
        val result = completePendingWithoutEvents(ghostRoot, operationId, files)
        if (result is GhostUpdateResult.Completed) events.complete(files)
        return result
    }

    private fun completePendingWithoutEvents(
        ghostRoot: File,
        operationId: OperationId,
        files: List<String>,
    ): GhostUpdateResult {
        val completed = GhostUpdateResult.Completed(files)
        if (!onCommitClassified(completed)) return GhostUpdateResult.PublishPending(files)
        finishPublishedTransaction(ghostRoot, operationId, journalIo)
        return completed
    }

    private fun parseManifest(source: ManifestSource): List<ManifestEntry> {
        val charset = manifestCharset(source)
        val text = source.bytes.toString(charset)
        val rawEntries = mutableListOf<Pair<String, String>>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isEmpty()) return@forEach
            if (source.name == UPDATE_V3 && line.startsWith("charset,", ignoreCase = true)) {
                return@forEach
            }
            val record = if (source.name == UPDATE_V3) {
                if (!line.startsWith("file,")) return@forEach
                line.removePrefix("file,")
            } else {
                line
            }
            val fields = record.split('\u0001')
            if (fields.size < 2) throw IOException("malformed update manifest")
            rawEntries += fields[0] to fields[1]
        }
        val decodeEscapes = rawEntries.isNotEmpty() && rawEntries.all { containsPercentEscape(it.first) }
        val entries = mutableListOf<ManifestEntry>()
        val keys = hashSetOf<String>()
        rawEntries.forEach { (rawPath, digest) ->
            val path = normalizedPath(if (decodeEscapes) decodePercentPath(rawPath, charset) else rawPath)
            if (!digest.matches(MD5)) throw IOException("invalid update digest")
            val key = NarRelativePathPolicy.collisionKey(path)
            if (!keys.add(key)) throw IOException("duplicate update path")
            entries += ManifestEntry(path, digest.lowercase())
        }
        if (entries.isEmpty()) throw IOException("empty update manifest")
        return entries
    }

    private fun containsPercentEscape(path: String): Boolean =
        PERCENT_ESCAPE.containsMatchIn(path)

    private fun decodePercentPath(path: String, charset: Charset): String {
        val bytes = ByteArrayOutputStream(path.length)
        var index = 0
        while (index < path.length) {
            if (path[index] == '%' && index + 2 < path.length) {
                val value = path.substring(index + 1, index + 3).toIntOrNull(16)
                    ?: throw IOException("invalid percent-escaped update path")
                bytes.write(value)
                index += 3
            } else {
                val codePoint = path.codePointAt(index)
                bytes.write(String(Character.toChars(codePoint)).toByteArray(charset))
                index += Character.charCount(codePoint)
            }
        }
        return bytes.toByteArray().toString(charset)
    }

    private fun manifestCharset(source: ManifestSource): Charset {
        val ascii = source.bytes.toString(Charsets.ISO_8859_1)
        val declared = if (source.name == UPDATE_V3) {
            ascii.lineSequence()
                .map { it.removeSuffix("\r") }
                .takeWhile { !it.startsWith("file,") }
                .firstOrNull { it.startsWith("charset,", ignoreCase = true) }
                ?.substringAfter(',')
        } else {
            Regex("(?:^|\\u0001)charset=([^\\u0001\\r\\n]+)", RegexOption.IGNORE_CASE)
                .find(ascii)?.groupValues?.get(1)
        }
        return try {
            Charset.forName(declared ?: DEFAULT_UPDATE_CHARSET)
        } catch (_: Exception) {
            throw IOException("unsupported update manifest charset")
        }
    }

    private fun applyCandidateDeletes(candidateRoot: File): DeleteApplication {
        val deleteFile = File(candidateRoot, DELETE_FILE)
        if (!deleteFile.isFile) return DeleteApplication(false, emptyList())
        var changed = false
        val entries = mutableListOf<DeleteEntry>()
        val bytes = readDeleteManifestBytes(deleteFile)
        val ascii = bytes.toString(Charsets.ISO_8859_1)
        val declared = ascii.lineSequence().firstOrNull()?.removeSuffix("\r")
            ?.takeIf { it.startsWith("charset,", ignoreCase = true) }
            ?.substringAfter(',')
        val charset = try {
            Charset.forName(declared ?: DEFAULT_UPDATE_CHARSET)
        } catch (_: Exception) {
            throw IOException("unsupported delete manifest charset")
        }
        deleteManifestLines(bytes, charset).forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isEmpty() || line.startsWith("charset,", ignoreCase = true)) return@forEach
            if (line.startsWith('\\') || line.startsWith('/') || ':' in line) {
                throw IOException("invalid delete path")
            }
            val directory = line.endsWith('\\')
            val normalized = normalizedPath(line.removeSuffix("\\").replace('\\', '/'))
            entries += DeleteEntry(normalized, directory)
            val target = resolveChild(candidateRoot, normalized)
            if (target.exists()) {
                changed = true
                if (directory) {
                    if (!fileOperations.deleteTree(target)) throw IOException("cannot delete candidate directory")
                } else if (!target.isFile || !target.delete()) {
                    throw IOException("cannot delete candidate file")
                }
            }
        }
        return DeleteApplication(changed, entries)
    }

    private fun resyncUnmanagedLivePaths(
        liveRoot: File,
        candidateRoot: File,
        manifestPaths: List<String>,
        deleteEntries: List<DeleteEntry>,
        stopReason: () -> GhostUpdateStopReason,
    ) {
        val managed = ManagedPaths(manifestPaths, deleteEntries)
        val liveBefore = scanTree(liveRoot, stopReason)
        val candidateBefore = scanTree(candidateRoot, stopReason)

        liveBefore.values.forEach { entry ->
            throwIfStopped(stopReason)
            if (managed.hasManagedFileAncestor(entry.path)) {
                throw IOException("live path descends from a managed file")
            }
        }

        candidateBefore.values
            .filter { it.kind == TreeEntryKind.FILE && !managed.isManaged(it.path) }
            .filter { liveBefore[it.key]?.kind != TreeEntryKind.FILE }
            .forEach { entry ->
                throwIfStopped(stopReason)
                val file = resolveChild(candidateRoot, entry.path)
                if (!file.delete()) throw IOException("cannot remove stale candidate file")
            }

        candidateBefore.values
            .filter { it.kind == TreeEntryKind.DIRECTORY && !managed.isManaged(it.path) }
            .filter { liveBefore[it.key]?.kind != TreeEntryKind.DIRECTORY }
            .sortedByDescending { it.path.count { character -> character == '/' } }
            .forEach { entry ->
                throwIfStopped(stopReason)
                if (managed.isRequiredParent(entry.path)) return@forEach
                val directory = resolveChild(candidateRoot, entry.path)
                if (directory.exists() && !fileOperations.deleteTree(directory)) {
                    throw IOException("cannot remove stale candidate directory")
                }
            }

        liveBefore.values
            .filter { it.kind == TreeEntryKind.DIRECTORY && !managed.isManaged(it.path) }
            .sortedBy { it.path.count { character -> character == '/' } }
            .forEach { entry ->
                throwIfStopped(stopReason)
                val target = resolveChild(candidateRoot, entry.path)
                if (target.isFile) {
                    if (managed.isRequiredParent(entry.path) || !target.delete()) {
                        throw IOException("unsafe candidate directory type change")
                    }
                }
                if ((!target.exists() && !target.mkdir()) || !target.isDirectory) {
                    throw IOException("cannot mirror live directory")
                }
            }

        liveBefore.values
            .filter { it.kind == TreeEntryKind.FILE && !managed.isManaged(it.path) }
            .forEach { entry ->
                throwIfStopped(stopReason)
                val source = resolveChild(liveRoot, entry.path)
                val target = resolveChild(candidateRoot, entry.path)
                if (target.isDirectory) {
                    if (managed.isRequiredParent(entry.path) || !fileOperations.deleteTree(target)) {
                        throw IOException("unsafe candidate file type change")
                    }
                }
                val parent = target.parentFile ?: throw IOException("candidate mirror has no parent")
                if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
                    throw IOException("cannot prepare candidate mirror parent")
                }
                copyFile(source, target, stopReason)
            }

        val liveAfter = scanTree(liveRoot, stopReason)
        if (liveBefore != liveAfter) throw IOException("live ghost changed during final merge")
        val candidateAfter = scanTree(candidateRoot, stopReason)
        val liveUnmanaged = liveAfter.values.filterNot { managed.isManaged(it.path) }
        liveUnmanaged.forEach { live ->
            throwIfStopped(stopReason)
            val candidate = candidateAfter[live.key]
                ?: throw IOException("candidate mirror is missing live path")
            if (candidate.kind != live.kind) throw IOException("candidate mirror type mismatch")
            if (live.kind == TreeEntryKind.FILE && !filesEqual(
                    resolveChild(liveRoot, live.path),
                    resolveChild(candidateRoot, candidate.path),
                    stopReason,
                )
            ) throw IOException("candidate mirror content mismatch")
        }
        candidateAfter.values
            .filterNot { managed.isManaged(it.path) || managed.isRequiredParent(it.path) }
            .forEach { candidate ->
                throwIfStopped(stopReason)
                if (liveAfter[candidate.key]?.kind != candidate.kind) {
                    throw IOException("candidate retained stale unmanaged path")
                }
            }
    }

    private fun scanTree(
        root: File,
        stopReason: () -> GhostUpdateStopReason,
    ): Map<String, TreeEntry> {
        throwIfStopped(stopReason)
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) {
            throw IOException("unsafe ghost tree root")
        }
        val entries = linkedMapOf<String, TreeEntry>()
        fun visit(directory: File, prefix: String) {
            val children = directory.listFiles() ?: throw IOException("cannot list ghost tree")
            children.forEach { child ->
                throwIfStopped(stopReason)
                if (Files.isSymbolicLink(child.toPath())) throw IOException("ghost tree contains symlink")
                val rawPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                val path = normalizedPath(rawPath)
                val key = NarRelativePathPolicy.collisionKey(path)
                val kind = when {
                    child.isDirectory -> TreeEntryKind.DIRECTORY
                    child.isFile -> TreeEntryKind.FILE
                    else -> throw IOException("ghost tree contains special file")
                }
                val entry = TreeEntry(key, path, kind, child.length(), child.lastModified())
                if (entries.put(key, entry) != null) throw IOException("ghost tree contains colliding paths")
                if (kind == TreeEntryKind.DIRECTORY) visit(child, path)
            }
        }
        visit(root, "")
        return entries
    }

    private fun copyFile(
        source: File,
        destination: File,
        stopReason: () -> GhostUpdateStopReason,
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    throwIfStopped(stopReason)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    private fun filesEqual(
        left: File,
        right: File,
        stopReason: () -> GhostUpdateStopReason,
    ): Boolean {
        if (left.length() != right.length()) return false
        FileInputStream(left).use { leftInput ->
            FileInputStream(right).use { rightInput ->
                val leftBuffer = ByteArray(COPY_BUFFER)
                val rightBuffer = ByteArray(COPY_BUFFER)
                while (true) {
                    throwIfStopped(stopReason)
                    val leftCount = leftInput.read(leftBuffer)
                    val rightCount = rightInput.read(rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount < 0) return true
                    if (!leftBuffer.copyOf(leftCount).contentEquals(rightBuffer.copyOf(rightCount))) return false
                }
            }
        }
    }

    private fun fileDigest(
        file: File,
        stopReason: () -> GhostUpdateStopReason,
        onBytes: (Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                throwIfStopped(stopReason)
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    onBytes(count.toLong())
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun failed(reason: String, files: List<String>): GhostUpdateResult.Failed {
        events.failure(reason, files)
        return GhostUpdateResult.Failed(reason)
    }

    /**
     * Publishes an ownership journal before the transaction name becomes visible to recovery
     * discovery. That makes a journal-less transaction directory unambiguously stale residue.
     */
    private fun createOwnedTransactionRoot(
        transactionRoot: File,
        request: GhostUpdateRequest,
    ): Boolean {
        if (transactionRoot.parentFile == null) return false
        val staging = stagingRoot(transactionRoot)
        val journal = preparedJournal(transactionRoot, request)
        val markerParent = transactionRoot.parentFile!!
        var marker: File? = null
        var published = false
        var stagingCreated = false
        return try {
            marker = GhostUpdateJournalStore.createPrivateMarker(markerParent, journal)
            // A deterministic name alone is not proof of ownership: a valid ghost ID may occupy it.
            if (staging.exists() || !staging.mkdir()) return false
            stagingCreated = true
            journalIo.write(File(staging, GhostUpdateJournalStore.FILE_NAME), journal)
            published = fileOperations.publishStaging(staging, transactionRoot)
            if (published) marker.delete()
            published
        } catch (_: Exception) {
            false
        } finally {
            if (!published && stagingCreated) {
                val stagingRemoved = !staging.exists() || fileOperations.deleteTree(staging)
                if (stagingRemoved) marker?.delete()
            }
            if (!stagingCreated) marker?.delete()
        }
    }

    private fun preparedJournal(transactionRoot: File, request: GhostUpdateRequest) = GhostUpdateJournal(
        operationId = request.operationId,
        ghostRoot = request.ghostRoot.canonicalPath,
        candidateRoot = File(transactionRoot, CANDIDATE).canonicalPath,
        backupRoot = File(transactionRoot, BACKUP).canonicalPath,
        phase = CommitPhase.PREPARED,
        files = emptyList(),
        attemptId = request.attemptId,
        workManagerUuid = request.workManagerUuid,
    )

    /** Keeps recovery evidence until the candidate and transaction root are both gone. */
    private fun cleanPreparedTransaction(transactionRoot: File, journal: GhostUpdateJournal): Boolean {
        val candidate = File(transactionRoot, CANDIDATE)
        val journalFile = File(transactionRoot, GhostUpdateJournalStore.FILE_NAME)
        if (!fileOperations.deleteTree(candidate)) return false
        if (!journalFile.delete() && journalFile.exists()) return false
        if (transactionRoot.delete()) return true
        journalIo.write(journalFile, journal)
        return false
    }

    private fun failedBeforeJournal(
        transactionRoot: File,
        request: GhostUpdateRequest,
        reason: String,
        files: List<String> = emptyList(),
    ): GhostUpdateResult.Failed {
        cleanPreparedTransaction(transactionRoot, preparedJournal(transactionRoot, request))
        return failed(reason, files)
    }

    private fun cancelledBeforeJournal(
        transactionRoot: File,
        request: GhostUpdateRequest,
    ): GhostUpdateResult {
        return try {
            val candidateRoot = File(transactionRoot, CANDIDATE)
            val journalFile = File(transactionRoot, GhostUpdateJournalStore.FILE_NAME)
            val journal = GhostUpdateJournal(
                operationId = request.operationId,
                ghostRoot = request.ghostRoot.canonicalPath,
                candidateRoot = candidateRoot.canonicalPath,
                backupRoot = File(transactionRoot, BACKUP).canonicalPath,
                phase = CommitPhase.PREPARED,
                files = emptyList(),
                attemptId = request.attemptId,
                workManagerUuid = request.workManagerUuid,
            )
            journalIo.write(
                journalFile,
                journal,
            )
            if (fileOperations.deleteTree(candidateRoot) && journalFile.delete()) {
                if (!transactionRoot.delete()) journalIo.write(journalFile, journal)
            }
            GhostUpdateResult.Cancelled
        } catch (error: Exception) {
            failed("cannot retain cancelled update staging: ${error.message}", emptyList())
        }
    }

    private fun interruptedBeforeJournal(transactionRoot: File): GhostUpdateResult {
        fileOperations.deleteTree(transactionRoot)
        return GhostUpdateResult.Interrupted
    }

    private fun stopBeforeJournal(
        transactionRoot: File,
        request: GhostUpdateRequest,
        reason: GhostUpdateStopReason,
    ): GhostUpdateResult? = when (reason) {
        GhostUpdateStopReason.NONE -> null
        GhostUpdateStopReason.USER_CANCELLED -> cancelledBeforeJournal(transactionRoot, request)
        GhostUpdateStopReason.SYSTEM_INTERRUPTED -> interruptedBeforeJournal(transactionRoot)
    }

    private fun stopResult(reason: GhostUpdateStopReason): GhostUpdateResult? = when (reason) {
        GhostUpdateStopReason.NONE -> null
        GhostUpdateStopReason.USER_CANCELLED -> GhostUpdateResult.Cancelled
        GhostUpdateStopReason.SYSTEM_INTERRUPTED -> GhostUpdateResult.Interrupted
    }

    private fun throwIfStopped(stopReason: () -> GhostUpdateStopReason) {
        val reason = stopReason()
        if (reason != GhostUpdateStopReason.NONE) throw UpdateStoppedException(reason)
    }

    private data class ManifestEntry(val path: String, val digest: String)
    private data class ManifestSource(val name: String, val bytes: ByteArray)
    private data class DeleteEntry(val path: String, val directory: Boolean)
    private data class DeleteApplication(val changed: Boolean, val entries: List<DeleteEntry>)
    private enum class TreeEntryKind { FILE, DIRECTORY }
    private data class TreeEntry(
        val key: String,
        val path: String,
        val kind: TreeEntryKind,
        val size: Long,
        val lastModified: Long,
    )

    private class ManagedPaths(
        manifestPaths: List<String>,
        deleteEntries: List<DeleteEntry>,
    ) {
        private val manifestFiles = manifestPaths.associateBy(NarRelativePathPolicy::collisionKey)
        private val deleteFiles = deleteEntries.filterNot { it.directory }
            .associateBy { NarRelativePathPolicy.collisionKey(it.path) }
        private val deleteDirectories = deleteEntries.filter { it.directory }
            .map { NarRelativePathPolicy.collisionKey(it.path) }
        private val requiredPaths = (manifestPaths + deleteEntries.map { it.path })
            .map(NarRelativePathPolicy::collisionKey)

        fun isManaged(path: String): Boolean {
            val key = NarRelativePathPolicy.collisionKey(path)
            return key in manifestFiles || key in deleteFiles ||
                deleteDirectories.any { directory -> key == directory || key.startsWith("$directory/") }
        }

        fun isRequiredParent(path: String): Boolean {
            val key = NarRelativePathPolicy.collisionKey(path)
            return requiredPaths.any { managed -> managed.startsWith("$key/") }
        }

        fun hasManagedFileAncestor(path: String): Boolean {
            val key = NarRelativePathPolicy.collisionKey(path)
            return (manifestFiles.keys + deleteFiles.keys).any { managed -> key.startsWith("$managed/") }
        }
    }

    private class UpdateStoppedException(val reason: GhostUpdateStopReason) :
        IOException("ghost update stopped: $reason")

    companion object {
        private const val UPDATE_V2 = "updates2.dau"
        private const val UPDATE_V3 = "updates.txt"
        private const val CANDIDATE = "candidate"
        private const val BACKUP = "backup"
        private const val DELETE_FILE = "delete.txt"
        private const val DEFAULT_UPDATE_CHARSET = "Windows-31J"
        private const val COPY_BUFFER = 16 * 1024
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        internal const val MAX_DELETE_BYTES = 1024 * 1024
        internal const val MAX_DELETE_LINES = 10_000
        private val MD5 = Regex("[0-9a-fA-F]{32}")
        private val PERCENT_ESCAPE = Regex("%[0-9a-fA-F]{2}")
        private val processLocks = ConcurrentHashMap<String, Any>()

        internal fun readDeleteManifestBytes(file: File): ByteArray {
            FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > MAX_DELETE_BYTES) {
                        throw IOException("delete manifest is too large")
                    }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }

        internal fun deleteManifestLines(bytes: ByteArray, charset: Charset): List<String> {
            val lines = mutableListOf<String>()
            bytes.toString(charset).lineSequence().forEach { line ->
                if (lines.size == MAX_DELETE_LINES) throw IOException("delete manifest has too many lines")
                lines += line
            }
            return lines
        }

        fun recoverBeforeGhostLoad(ghostRoot: File): RecoveryResult =
            withGhostLock(ghostRoot) {
                val root = ghostRoot.canonicalFile
                SScriptRunner.withProductionGhostMutation(
                    root.name,
                    root,
                    onFailure = { RecoveryResult.Failed(it.message ?: "ghost recovery gate failed") },
                ) {
                    recoverLocked(root, GhostUpdateJournalIo.DEFAULT, RecoveryAuthorization.WAIT)
                }
            }

        internal fun recoverAllBeforeGhostLoad(
            ghostStorageRoot: File,
            targetGhostRoot: File? = null,
            authorize: (GhostUpdateJournal, GhostTreeTopology) -> RecoveryAuthorization = { _, _ -> RecoveryAuthorization.WAIT },
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean = { _, _ -> false },
        ): RecoveryResult {
            val canonicalStorage = ghostStorageRoot.canonicalFile
            sweepUnpublishedStaging(canonicalStorage)
            val target = targetGhostRoot?.canonicalFile
            val journals = if (target != null) {
                if (target.parentFile != canonicalStorage) {
                    return RecoveryResult.Failed("target ghost escapes ghost storage")
                }
                listOfNotNull(journalFileFor(transactionRoot(target, canonicalOperationIdFor(target))))
            } else {
                canonicalStorage.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith(TRANSACTION_PREFIX) }
                    .mapNotNull(::journalFileFor)
            }
            if (journals.isEmpty()) return RecoveryResult.NoJournal
            val journalRecords = try {
                journals.map { journalFile ->
                    val journal = GhostUpdateJournalIo.DEFAULT.read(journalFile)
                    val root = File(journal.ghostRoot).canonicalFile
                    if (root.parentFile != canonicalStorage) {
                        return RecoveryResult.Failed("ghost update journal escapes ghost storage")
                    }
                    if (target != null && (
                            root != target ||
                                journal.operationId != canonicalOperationIdFor(target) ||
                                journalFile.parentFile?.canonicalFile != transactionRoot(
                                    target,
                                    canonicalOperationIdFor(target),
                                )
                            )
                    ) {
                        return RecoveryResult.Failed("target ghost update journal has foreign identity")
                    }
                    root to journal
                }.distinctBy { it.first.path }
            } catch (e: Exception) {
                return RecoveryResult.Failed(e.message ?: "corrupt ghost update journal")
            }
            var completedCommit = false
            var rolledBack = false
            var commitPending = false
            var publishPending = false
            val pendingFiles = mutableListOf<String>()
            val publishPendingFiles = mutableListOf<String>()
            val rollbackPendingFiles = mutableMapOf<OperationStatus, MutableList<String>>()
            journalRecords.forEach { (root, journal) ->
                val recovery = withGhostLock(root) {
                    SScriptRunner.withProductionGhostMutation(
                        root.name,
                        root,
                        onFailure = { RecoveryResult.Failed(it.message ?: "ghost recovery gate failed") },
                    ) {
                        val transaction = transactionRoot(root, journal.operationId)
                        val topology = topologyOf(root, File(transaction, CANDIDATE), File(transaction, BACKUP))
                        recoverLocked(
                            root,
                            GhostUpdateJournalIo.DEFAULT,
                            authorize(journal, topology),
                            classify,
                        )
                    }
                }
                when (recovery) {
                    RecoveryResult.CompletedCommit -> completedCommit = true
                    is RecoveryResult.CommitPending -> {
                        commitPending = true
                        pendingFiles += recovery.files
                    }
                    is RecoveryResult.PublishPending -> {
                        publishPending = true
                        publishPendingFiles += recovery.files
                    }
                    is RecoveryResult.RollbackPending ->
                        rollbackPendingFiles.getOrPut(recovery.status, ::mutableListOf) += recovery.files
                    RecoveryResult.RolledBack -> rolledBack = true
                    RecoveryResult.NoJournal -> Unit
                    is RecoveryResult.Failed -> return recovery
                }
            }
            return when {
                rollbackPendingFiles.size > 1 -> RecoveryResult.Failed("conflicting rollback classifications")
                rollbackPendingFiles.isNotEmpty() -> rollbackPendingFiles.entries.single().let { (status, files) ->
                    RecoveryResult.RollbackPending(status, files)
                }
                publishPending -> RecoveryResult.PublishPending(publishPendingFiles)
                commitPending -> RecoveryResult.CommitPending(pendingFiles)
                completedCommit -> RecoveryResult.CompletedCommit
                rolledBack -> RecoveryResult.RolledBack
                else -> RecoveryResult.NoJournal
            }
        }

        internal fun <T> withRecoveredGhostRoot(
            ghostRoot: File,
            block: () -> T,
        ): Pair<RecoveryResult, T?> = withGhostLock(ghostRoot) {
            val root = ghostRoot.canonicalFile
            val recovery = SScriptRunner.withProductionGhostMutation(
                root.name,
                root,
                onFailure = { RecoveryResult.Failed(it.message ?: "ghost recovery gate failed") },
            ) { recoverLocked(root, GhostUpdateJournalIo.DEFAULT, RecoveryAuthorization.WAIT) }
            if (recovery is RecoveryResult.Failed ||
                recovery is RecoveryResult.PublishPending ||
                recovery is RecoveryResult.RollbackPending ||
                (recovery is RecoveryResult.CommitPending && !canBootPreparedOldLive(ghostRoot.canonicalFile))
            ) {
                recovery to null
            } else {
                recovery to block()
            }
        }

        internal fun blockedGhostRoots(ghostStorageRoot: File): Set<File> {
            val storage = ghostStorageRoot.canonicalFile
            val blocked = storage.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(TRANSACTION_PREFIX) }
                .mapNotNull { root ->
                    val expected = transactionRoot(root, canonicalOperationIdFor(root))
                    val journalFile = journalFileFor(expected) ?: return@mapNotNull null
                    val safePrepared = try {
                        val journal = GhostUpdateJournalStore.read(journalFile)
                        journal.operationId == canonicalOperationIdFor(root) &&
                            File(journal.ghostRoot).canonicalFile == root.canonicalFile &&
                            File(journal.candidateRoot).canonicalFile == File(expected, CANDIDATE).canonicalFile &&
                            File(journal.backupRoot).canonicalFile == File(expected, BACKUP).canonicalFile &&
                            validPersistedPaths(journal.files) &&
                            journal.phase == CommitPhase.PREPARED &&
                            topologyOf(root, File(expected, CANDIDATE), File(expected, BACKUP)) ==
                            GhostTreeTopology.LIVE_CANDIDATE
                    } catch (_: Exception) {
                        false
                    }
                    root.canonicalFile.takeUnless { safePrepared }
                }.toMutableSet()
            blocked += storage.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(TRANSACTION_PREFIX) }
                .mapNotNull { transaction ->
                    val journalFile = journalFileFor(transaction)
                        ?: return@mapNotNull if (transaction.listFiles().isNullOrEmpty()) {
                            transaction.delete()
                            null
                        } else null
                    val journal = try {
                        GhostUpdateJournalStore.read(journalFile)
                    } catch (_: Exception) {
                        if (transaction.listFiles().isNullOrEmpty()) transaction.delete()
                        return@mapNotNull null
                    }
                    val root = File(journal.ghostRoot).canonicalFile
                    if (root.parentFile != storage) return@mapNotNull null
                    if (transaction.canonicalFile != transactionRoot(root, journal.operationId)) {
                        return@mapNotNull null
                    }
                    val topology = topologyOf(
                        root,
                        File(transaction, CANDIDATE),
                        File(transaction, BACKUP),
                    )
                    root.takeUnless {
                        journal.phase == CommitPhase.PREPARED &&
                            topology == GhostTreeTopology.LIVE_CANDIDATE
                    }
                }
            return blocked
        }

        internal fun recoveryTargets(ghostStorageRoot: File): Set<File> {
            val storage = ghostStorageRoot.canonicalFile
            return storage.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(TRANSACTION_PREFIX) }
                .mapNotNull { transaction ->
                    val journalFile = journalFileFor(transaction)
                        ?: return@mapNotNull if (transaction.listFiles().isNullOrEmpty()) {
                            transaction.delete()
                            null
                        } else null
                    val journal = try {
                        GhostUpdateJournalStore.read(journalFile)
                    } catch (_: Exception) {
                        if (transaction.listFiles().isNullOrEmpty()) transaction.delete()
                        return@mapNotNull null
                    }
                    val root = File(journal.ghostRoot).canonicalFile
                    val expected = transactionRoot(root, canonicalOperationIdFor(root))
                    root.takeIf {
                        root.parentFile == storage &&
                            journal.operationId == canonicalOperationIdFor(root) &&
                            transaction.canonicalFile == expected &&
                            File(journal.candidateRoot).canonicalFile == File(expected, CANDIDATE).canonicalFile &&
                            File(journal.backupRoot).canonicalFile == File(expected, BACKUP).canonicalFile &&
                            validPersistedPaths(journal.files)
                    }
                }
                .toSet()
        }

        /**
         * An unpublished staging directory owns a prepared journal which names a different live
         * ghost and its eventual transaction root. Filename prefixes alone are not ownership:
         * ghost IDs may use this prefix too. Sweep only a verified staging journal while holding
         * that ghost's mutation lock, so creation cannot race discovery and deletion.
         */
        private fun sweepUnpublishedStaging(storage: File) {
            storage.listFiles().orEmpty()
                .filter { entry ->
                    entry.name.startsWith(STAGING_PREFIX) &&
                        entry.parentFile?.canonicalFile == storage
                }
                .forEach { staging -> sweepVerifiedUnpublishedStaging(storage, staging) }
            sweepPrivateMarkerWrites(storage)
            sweepPrivateOwnershipMarkers(storage)
        }

        /** Reclaims private marker write residue only after its writer's OS lock is released. */
        private fun sweepPrivateMarkerWrites(storage: File) {
            storage.listFiles().orEmpty()
                .filter { entry ->
                    entry.parentFile?.canonicalFile == storage &&
                        entry.name.startsWith(PRIVATE_WRITING_PREFIX)
                }
                .forEach(GhostUpdateJournalStore::deleteAbandonedPrivateMarkerWrite)
        }

        private fun sweepVerifiedUnpublishedStaging(storage: File, staging: File) {
            val journal = stagingJournalFor(storage, staging) ?: return
            withGhostLock(File(journal.ghostRoot)) {
                if (stagingJournalFor(storage, staging) != journal) return@withGhostLock
                if (Files.isSymbolicLink(staging.toPath())) staging.delete() else staging.deleteRecursively()
            }
        }

        private fun stagingJournalFor(storage: File, staging: File): GhostUpdateJournal? {
            if (Files.isSymbolicLink(staging.toPath()) || !staging.isDirectory) return null
            val journal = try {
                val completed = File(staging, GhostUpdateJournalStore.FILE_NAME)
                val journalFile = when {
                    completed.isFile -> completed
                    else -> File(staging, "${GhostUpdateJournalStore.FILE_NAME}.tmp").takeIf(File::isFile)
                        ?: return null
                }
                GhostUpdateJournalStore.read(journalFile)
            } catch (_: Exception) {
                return null
            }
            val root = File(journal.ghostRoot).canonicalFile
            val transaction = transactionRoot(root, journal.operationId)
            return journal.takeIf {
                root.parentFile == storage &&
                    root != staging.canonicalFile &&
                    staging.canonicalFile == stagingRoot(transaction) &&
                    journal.phase == CommitPhase.PREPARED &&
                    File(journal.candidateRoot).canonicalFile == File(transaction, CANDIDATE).canonicalFile &&
                    File(journal.backupRoot).canonicalFile == File(transaction, BACKUP).canonicalFile &&
                    hasPrivateOwnershipMarker(storage, journal)
            }
        }

        private fun hasPrivateOwnershipMarker(storage: File, journal: GhostUpdateJournal): Boolean =
            storage.listFiles().orEmpty().any { marker ->
                marker.isFile &&
                    !Files.isSymbolicLink(marker.toPath()) &&
                    marker.name.startsWith(PRIVATE_MARKER_PREFIX) &&
                    runCatching { GhostUpdateJournalStore.read(marker) }.getOrNull() == journal
            }

        /** Reclaims only authenticated private markers and their empty, exact staging roots. */
        private fun sweepPrivateOwnershipMarkers(storage: File) {
            storage.listFiles().orEmpty()
                .filter {
                    it.isFile &&
                        !Files.isSymbolicLink(it.toPath()) &&
                        it.name.startsWith(PRIVATE_MARKER_PREFIX)
                }
                .forEach { marker ->
                    val journal = try {
                        GhostUpdateJournalStore.read(marker)
                    } catch (_: Exception) {
                        // Prefix collisions are user-owned until a readable marker proves
                        // otherwise; never delete storage content based on a filename alone.
                        return@forEach
                    }
                    val root = File(journal.ghostRoot).canonicalFile
                    val transaction = transactionRoot(root, journal.operationId)
                    if (
                        root.parentFile != storage ||
                        journal.operationId != canonicalOperationIdFor(root) ||
                        journal.phase != CommitPhase.PREPARED ||
                        journal.files.isNotEmpty() ||
                        File(journal.candidateRoot).canonicalFile != File(transaction, CANDIDATE).canonicalFile ||
                        File(journal.backupRoot).canonicalFile != File(transaction, BACKUP).canonicalFile
                    ) return@forEach
                    withGhostLock(root) {
                        val staging = stagingRoot(transaction)
                        if (!staging.exists()) {
                            marker.delete()
                        } else if (
                            staging.isDirectory &&
                            !Files.isSymbolicLink(staging.toPath()) &&
                            (staging.listFiles().isNullOrEmpty() || removeIncompleteStagingJournal(staging))
                        ) {
                            if (staging.delete()) marker.delete()
                        }
                    }
                }
        }

        /** Removes only the private temporary left by an interrupted staging journal write. */
        private fun removeIncompleteStagingJournal(staging: File): Boolean {
            val entries = staging.listFiles() ?: return false
            if (entries.size != 1) return false
            val temporary = entries.single()
            return temporary.name == "${GhostUpdateJournalStore.FILE_NAME}.tmp" &&
                temporary.isFile &&
                !Files.isSymbolicLink(temporary.toPath()) &&
                temporary.delete()
        }

        private fun canBootPreparedOldLive(ghostRoot: File): Boolean {
            val parent = ghostRoot.parentFile ?: return false
            val matches = parent.listFiles().orEmpty().mapNotNull { transaction ->
                if (!transaction.isDirectory || !transaction.name.startsWith(TRANSACTION_PREFIX)) return@mapNotNull null
                val journal = try {
                    GhostUpdateJournalStore.read(File(transaction, GhostUpdateJournalStore.FILE_NAME))
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                if (File(journal.ghostRoot).canonicalFile != ghostRoot) return@mapNotNull null
                journal to topologyOf(
                    ghostRoot,
                    File(transaction, CANDIDATE),
                    File(transaction, BACKUP),
                )
            }
            return matches.size == 1 && matches.single().let { (journal, topology) ->
                journal.phase == CommitPhase.PREPARED && topology == GhostTreeTopology.LIVE_CANDIDATE
            }
        }

        private fun recoverLocked(
            ghostRoot: File,
            journalIo: GhostUpdateJournalIo,
            authorization: RecoveryAuthorization? = null,
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean = { _, _ -> false },
        ): RecoveryResult {
            val journalFile = journalFileFor(transactionRoot(ghostRoot, canonicalOperationIdFor(ghostRoot)))
                ?: return RecoveryResult.NoJournal
            val journal = try {
                journalIo.read(journalFile)
            } catch (e: Exception) {
                return RecoveryResult.Failed(e.message ?: "corrupt ghost update journal")
            }
            if (File(journal.ghostRoot).canonicalFile != ghostRoot) {
                return RecoveryResult.Failed("ghost update journal targets another ghost")
            }
            val transactionRoot = journalFile.parentFile?.canonicalFile
                ?: return RecoveryResult.Failed("ghost update journal has no transaction parent")
            val expectedTransaction = transactionRoot(ghostRoot, journal.operationId)
            val candidate = File(journal.candidateRoot).canonicalFile
            val backup = File(journal.backupRoot).canonicalFile
            if (
                transactionRoot != expectedTransaction ||
                candidate != File(transactionRoot, CANDIDATE).canonicalFile ||
                backup != File(transactionRoot, BACKUP).canonicalFile ||
                !validPersistedPaths(journal.files)
            ) {
                return RecoveryResult.Failed("invalid ghost update journal paths")
            }
            return try {
                if (authorization != null) {
                    return recoverAuthorized(
                        authorization,
                        ghostRoot,
                        transactionRoot,
                        journalFile,
                        journal,
                        candidate,
                        backup,
                        journalIo,
                        classify,
                    )
                }
                when (journal.phase) {
                    CommitPhase.PREPARED -> recoverPrepared(ghostRoot, transactionRoot, candidate, backup)
                    CommitPhase.BACKED_UP -> recoverBackedUp(ghostRoot, transactionRoot, journalFile, journal, candidate, backup, journalIo)
                    CommitPhase.PUBLISHED -> recoverPublished(ghostRoot, transactionRoot, journalFile, journal, candidate, backup, journalIo)
                    CommitPhase.CLEANED -> recoverCleaned(ghostRoot, transactionRoot, candidate, backup)
                    CommitPhase.ROLLBACK_CLASSIFIED -> rollbackTransaction(
                        ghostRoot, transactionRoot, candidate, backup,
                    )
                }
            } catch (e: Exception) {
                RecoveryResult.Failed(e.message ?: "ghost update recovery failed")
            }
        }

        private fun recoverAuthorized(
            authorization: RecoveryAuthorization,
            ghostRoot: File,
            transactionRoot: File,
            journalFile: File,
            journal: GhostUpdateJournal,
            candidate: File,
            backup: File,
            journalIo: GhostUpdateJournalIo,
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean,
        ): RecoveryResult = when (authorization) {
            RecoveryAuthorization.WAIT -> when (journal.phase) {
                CommitPhase.PREPARED -> if (
                    (ghostRoot.isDirectory && candidate.isDirectory && !backup.exists()) ||
                    (!ghostRoot.exists() && candidate.isDirectory && backup.isDirectory) ||
                    (ghostRoot.isDirectory && !candidate.exists() && !backup.exists())
                ) RecoveryResult.CommitPending(journal.files)
                else RecoveryResult.Failed("ambiguous PREPARED ghost update state")
                CommitPhase.BACKED_UP -> if (
                    (!ghostRoot.exists() && candidate.isDirectory && backup.isDirectory) ||
                    (ghostRoot.isDirectory && !candidate.exists() && backup.isDirectory) ||
                    (!ghostRoot.exists() && !candidate.exists() && backup.isDirectory)
                ) RecoveryResult.CommitPending(journal.files)
                else RecoveryResult.Failed("ambiguous BACKED_UP ghost update state")
                CommitPhase.PUBLISHED -> if (
                    (ghostRoot.isDirectory && !candidate.exists() && backup.isDirectory) ||
                    (!ghostRoot.exists() && candidate.isDirectory && backup.isDirectory)
                ) RecoveryResult.CommitPending(journal.files)
                else RecoveryResult.Failed("ambiguous PUBLISHED ghost update state")
                CommitPhase.CLEANED -> if (
                    ghostRoot.isDirectory && !candidate.exists() && !backup.exists()
                ) RecoveryResult.CommitPending(journal.files)
                else RecoveryResult.Failed("ambiguous CLEANED ghost update state")
                CommitPhase.ROLLBACK_CLASSIFIED -> if (
                    topologyOf(ghostRoot, candidate, backup) in setOf(
                        GhostTreeTopology.LIVE_CANDIDATE,
                        GhostTreeTopology.CANDIDATE_BACKUP,
                        GhostTreeTopology.LIVE_BACKUP,
                        GhostTreeTopology.LIVE_ONLY,
                    )
                ) RecoveryResult.CommitPending(journal.files)
                else RecoveryResult.Failed("ambiguous rollback ghost update state")
            }
            RecoveryAuthorization.ADOPT_PREPARED -> if (
                journal.phase == CommitPhase.PREPARED &&
                topologyOf(ghostRoot, candidate, backup) in setOf(
                    GhostTreeTopology.LIVE_CANDIDATE,
                    GhostTreeTopology.LIVE_ONLY,
                )
            ) {
                requireDelete(transactionRoot)
                RecoveryResult.RolledBack
            } else RecoveryResult.Failed("only untouched PREPARED update can be adopted")
            RecoveryAuthorization.ROLL_FORWARD -> when (journal.phase) {
                CommitPhase.PREPARED,
                CommitPhase.BACKED_UP,
                CommitPhase.PUBLISHED,
                -> rollForwardToPublished(
                    ghostRoot, journalFile, journal, candidate, backup, journalIo, classify,
                )
                CommitPhase.CLEANED -> if (classify(journal, OperationStatus.COMPLETED)) {
                    recoverCleaned(ghostRoot, transactionRoot, candidate, backup)
                } else RecoveryResult.PublishPending(journal.files)
                CommitPhase.ROLLBACK_CLASSIFIED -> RecoveryResult.Failed("rollback cannot roll forward")
            }
            RecoveryAuthorization.ROLL_BACK_FAILED -> classifyAndRollback(
                OperationStatus.FAILED, ghostRoot, transactionRoot, journalFile, journal,
                candidate, backup, journalIo, classify,
            )
            RecoveryAuthorization.ROLL_BACK_CANCELLED -> classifyAndRollback(
                OperationStatus.CANCELLED, ghostRoot, transactionRoot, journalFile, journal,
                candidate, backup, journalIo, classify,
            )
            RecoveryAuthorization.FAIL_CLOSED -> RecoveryResult.Failed("ghost update recovery is not authorized")
        }

        private fun rollForwardToPublished(
            ghostRoot: File,
            journalFile: File,
            journal: GhostUpdateJournal,
            candidate: File,
            backup: File,
            journalIo: GhostUpdateJournalIo,
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean,
        ): RecoveryResult {
            when (topologyOf(ghostRoot, candidate, backup)) {
                GhostTreeTopology.CANDIDATE_BACKUP -> {
                    if (!candidate.renameTo(ghostRoot)) throw IOException("cannot finish ghost publish")
                    journalIo.write(journalFile, journal.copy(phase = CommitPhase.PUBLISHED))
                }
                GhostTreeTopology.LIVE_BACKUP ->
                    journalIo.write(journalFile, journal.copy(phase = CommitPhase.PUBLISHED))
                GhostTreeTopology.LIVE_ONLY -> if (journal.phase != CommitPhase.PUBLISHED) {
                    return RecoveryResult.Failed("live-only tree is not published")
                }
                else -> return RecoveryResult.Failed("invalid roll-forward topology")
            }
            val published = journal.copy(phase = CommitPhase.PUBLISHED)
            if (!classify(published, OperationStatus.COMPLETED)) {
                return RecoveryResult.PublishPending(journal.files)
            }
            if (backup.exists()) requireDelete(backup)
            journalIo.write(journalFile, published.copy(phase = CommitPhase.CLEANED))
            requireDelete(journalFile.parentFile ?: throw IOException("journal has no transaction parent"))
            return RecoveryResult.CompletedCommit
        }

        private fun classifyAndRollback(
            status: OperationStatus,
            ghostRoot: File,
            transactionRoot: File,
            journalFile: File,
            journal: GhostUpdateJournal,
            candidate: File,
            backup: File,
            journalIo: GhostUpdateJournalIo,
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean,
        ): RecoveryResult {
            if (journal.phase == CommitPhase.CLEANED ||
                (journal.phase == CommitPhase.PUBLISHED &&
                    topologyOf(ghostRoot, candidate, backup) == GhostTreeTopology.LIVE_ONLY)
            ) return RecoveryResult.Failed("completed cleanup cannot roll back")
            if (!classify(journal, status)) return RecoveryResult.RollbackPending(status, journal.files)
            journalIo.write(journalFile, journal.copy(phase = CommitPhase.ROLLBACK_CLASSIFIED))
            return rollbackTransaction(ghostRoot, transactionRoot, candidate, backup)
        }

        private fun recoverForReplay(
            ghostRoot: File,
            request: GhostUpdateRequest,
            journalIo: GhostUpdateJournalIo,
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean,
        ): RecoveryResult {
            val transaction = transactionRoot(ghostRoot, request.operationId)
            val journalFile = File(transaction, GhostUpdateJournalStore.FILE_NAME)
            if (!journalFile.isFile) return RecoveryResult.NoJournal
            val journal = try {
                journalIo.read(journalFile)
            } catch (e: Exception) {
                return RecoveryResult.Failed(e.message ?: "corrupt ghost update journal")
            }
            if (
                journal.operationId != request.operationId ||
                journal.attemptId != request.attemptId ||
                journal.workManagerUuid != request.workManagerUuid
            ) return RecoveryResult.Failed("stale ghost update journal blocks replay")
            val topology = topologyOf(ghostRoot, File(transaction, CANDIDATE), File(transaction, BACKUP))
            val authorization = if (
                journal.phase == CommitPhase.PREPARED && topology in setOf(
                    GhostTreeTopology.LIVE_CANDIDATE,
                    GhostTreeTopology.LIVE_ONLY,
                )
            ) RecoveryAuthorization.ADOPT_PREPARED else RecoveryAuthorization.ROLL_FORWARD
            return recoverLocked(ghostRoot, journalIo, authorization, classify)
        }

        private fun replayJournalFiles(
            ghostRoot: File,
            request: GhostUpdateRequest,
            journalIo: GhostUpdateJournalIo,
        ): List<String>? = try {
            val journal = journalIo.read(
                File(transactionRoot(ghostRoot, request.operationId), GhostUpdateJournalStore.FILE_NAME),
            )
            journal.files.takeIf {
                journal.operationId == request.operationId &&
                    journal.attemptId == request.attemptId &&
                    journal.workManagerUuid == request.workManagerUuid
            }
        } catch (_: Exception) {
            null
        }

        private fun recoverAfterJournalFailure(
            ghostRoot: File,
            request: GhostUpdateRequest,
            failureStatus: OperationStatus,
            journalIo: GhostUpdateJournalIo,
            classify: (GhostUpdateJournal, OperationStatus) -> Boolean,
        ): RecoveryResult {
            val transaction = transactionRoot(ghostRoot, request.operationId)
            val journalFile = File(transaction, GhostUpdateJournalStore.FILE_NAME)
            val journal = try {
                journalIo.read(journalFile)
            } catch (e: Exception) {
                return RecoveryResult.Failed(e.message ?: "corrupt ghost update journal")
            }
            if (
                journal.operationId != request.operationId ||
                journal.attemptId != request.attemptId ||
                journal.workManagerUuid != request.workManagerUuid
            ) return RecoveryResult.Failed("stale ghost update journal blocks recovery")
            val topology = topologyOf(ghostRoot, File(transaction, CANDIDATE), File(transaction, BACKUP))
            val authorization = if (
                journal.phase == CommitPhase.PREPARED && topology == GhostTreeTopology.LIVE_CANDIDATE
            ) {
                if (failureStatus == OperationStatus.CANCELLED) {
                    RecoveryAuthorization.ROLL_BACK_CANCELLED
                } else RecoveryAuthorization.ROLL_BACK_FAILED
            } else RecoveryAuthorization.ROLL_FORWARD
            return recoverLocked(ghostRoot, journalIo, authorization, classify)
        }

        private fun rollbackTransaction(
            ghostRoot: File,
            transactionRoot: File,
            candidate: File,
            backup: File,
        ): RecoveryResult {
            if (backup.isDirectory) {
                if (ghostRoot.exists()) {
                    if (candidate.exists()) return RecoveryResult.Failed("rollback candidate collision")
                    if (!ghostRoot.renameTo(candidate)) throw IOException("cannot retain published ghost during rollback")
                }
                if (!backup.renameTo(ghostRoot)) throw IOException("cannot restore ghost backup")
            } else if (!ghostRoot.isDirectory) {
                return RecoveryResult.Failed("rollback has no complete live tree")
            }
            if (candidate.exists()) requireDelete(candidate)
            requireDelete(transactionRoot)
            return RecoveryResult.RolledBack
        }

        private fun topologyOf(ghostRoot: File, candidate: File, backup: File): GhostTreeTopology = when {
            ghostRoot.isDirectory && candidate.isDirectory && !backup.exists() -> GhostTreeTopology.LIVE_CANDIDATE
            !ghostRoot.exists() && candidate.isDirectory && backup.isDirectory -> GhostTreeTopology.CANDIDATE_BACKUP
            ghostRoot.isDirectory && !candidate.exists() && backup.isDirectory -> GhostTreeTopology.LIVE_BACKUP
            ghostRoot.isDirectory && !candidate.exists() && !backup.exists() -> GhostTreeTopology.LIVE_ONLY
            else -> GhostTreeTopology.INVALID
        }

        private fun recoverPrepared(
            ghostRoot: File,
            transactionRoot: File,
            candidate: File,
            backup: File,
        ): RecoveryResult = when {
            ghostRoot.isDirectory && candidate.isDirectory && !backup.exists() -> {
                requireDelete(transactionRoot)
                RecoveryResult.RolledBack
            }
            !ghostRoot.exists() && candidate.isDirectory && backup.isDirectory -> {
                if (!backup.renameTo(ghostRoot)) throw IOException("cannot restore ghost backup")
                requireDelete(transactionRoot)
                RecoveryResult.RolledBack
            }
            else -> RecoveryResult.Failed("ambiguous PREPARED ghost update state")
        }

        private fun recoverBackedUp(
            ghostRoot: File,
            transactionRoot: File,
            journalFile: File,
            journal: GhostUpdateJournal,
            candidate: File,
            backup: File,
            journalIo: GhostUpdateJournalIo,
        ): RecoveryResult = when {
            !ghostRoot.exists() && candidate.isDirectory && backup.isDirectory -> {
                if (!candidate.renameTo(ghostRoot)) throw IOException("cannot finish ghost publish")
                journalIo.write(journalFile, journal.copy(phase = CommitPhase.PUBLISHED))
                RecoveryResult.CommitPending(journal.files)
            }
            ghostRoot.isDirectory && !candidate.exists() && backup.isDirectory ->
                RecoveryResult.CommitPending(journal.files)
            !ghostRoot.exists() && !candidate.exists() && backup.isDirectory -> {
                if (!backup.renameTo(ghostRoot)) throw IOException("cannot restore ghost backup")
                requireDelete(transactionRoot)
                RecoveryResult.RolledBack
            }
            else -> RecoveryResult.Failed("ambiguous BACKED_UP ghost update state")
        }

        private fun recoverPublished(
            ghostRoot: File,
            transactionRoot: File,
            journalFile: File,
            journal: GhostUpdateJournal,
            candidate: File,
            backup: File,
            journalIo: GhostUpdateJournalIo,
        ): RecoveryResult = when {
            ghostRoot.isDirectory && !candidate.exists() ->
                RecoveryResult.CommitPending(journal.files)
            !ghostRoot.exists() && candidate.isDirectory && backup.isDirectory -> {
                if (!candidate.renameTo(ghostRoot)) throw IOException("cannot restore published ghost")
                RecoveryResult.CommitPending(journal.files)
            }
            else -> RecoveryResult.Failed("ambiguous PUBLISHED ghost update state")
        }

        private fun recoverCleaned(
            ghostRoot: File,
            transactionRoot: File,
            candidate: File,
            backup: File,
        ): RecoveryResult = if (ghostRoot.isDirectory && !candidate.exists() && !backup.exists()) {
            requireDelete(transactionRoot)
            RecoveryResult.CompletedCommit
        } else {
            RecoveryResult.Failed("ambiguous CLEANED ghost update state")
        }

        private fun finishPublishedTransaction(
            ghostRoot: File,
            operationId: OperationId,
            journalIo: GhostUpdateJournalIo,
        ) {
            val transactionRoot = transactionRoot(ghostRoot, operationId)
            val journalFile = File(transactionRoot, GhostUpdateJournalStore.FILE_NAME)
            val journal = journalIo.read(journalFile)
            val candidate = File(journal.candidateRoot).canonicalFile
            val backup = File(journal.backupRoot).canonicalFile
            val topology = topologyOf(ghostRoot, candidate, backup)
            val validPublished =
                journal.operationId == operationId &&
                    File(journal.ghostRoot).canonicalFile == ghostRoot.canonicalFile &&
                    journalFile.parentFile?.canonicalFile == transactionRoot &&
                    candidate == File(transactionRoot, CANDIDATE).canonicalFile &&
                    backup == File(transactionRoot, BACKUP).canonicalFile &&
                    validPersistedPaths(journal.files) &&
                    when (journal.phase) {
                        CommitPhase.PUBLISHED -> topology in setOf(
                            GhostTreeTopology.LIVE_BACKUP,
                            GhostTreeTopology.LIVE_ONLY,
                        )
                        CommitPhase.CLEANED -> topology == GhostTreeTopology.LIVE_ONLY
                        else -> false
                    }
            if (!validPublished) throw IOException("pending completion is not a published transaction")
            if (journal.phase == CommitPhase.PUBLISHED) {
                if (backup.exists()) requireDelete(backup)
                journalIo.write(journalFile, journal.copy(phase = CommitPhase.CLEANED))
            }
            requireDelete(transactionRoot)
        }

        private fun validPersistedPaths(paths: List<String>): Boolean {
            val keys = hashSetOf<String>()
            return paths.all { path ->
                val normalized = NarRelativePathPolicy.normalize(path)
                normalized.isSuccess() &&
                    normalized.normalized == path &&
                    keys.add(normalized.key!!)
            }
        }

        internal fun transactionRootFor(ghostRoot: File, operationId: OperationId): File {
            val digest = operationId.value.toByteArray(Charsets.UTF_8).sha256().take(32)
            return File(ghostRoot.canonicalFile.parentFile, "$TRANSACTION_PREFIX$digest").canonicalFile
        }

        internal fun canonicalOperationIdFor(ghostRoot: File): OperationId = OperationId(
            "ghost-update-${ghostRoot.canonicalPath.toByteArray(Charsets.UTF_8).sha256()}",
        )

        private fun transactionRoot(ghostRoot: File, operationId: OperationId) =
            transactionRootFor(ghostRoot, operationId)

        private fun stagingRoot(transactionRoot: File): File = File(
            transactionRoot.parentFile,
            "$STAGING_PREFIX${transactionRoot.name.removePrefix(TRANSACTION_PREFIX)}",
        ).canonicalFile

        /** A completed private write is valid recovery evidence even if publication was interrupted. */
        private fun journalFileFor(transactionRoot: File): File? = listOf(
            File(transactionRoot, GhostUpdateJournalStore.FILE_NAME),
        ).firstOrNull(File::isFile) ?: File(
            transactionRoot,
            "${GhostUpdateJournalStore.FILE_NAME}.tmp",
        ).takeIf { temporary ->
            temporary.isFile && runCatching { GhostUpdateJournalStore.read(temporary) }.isSuccess
        }

        private fun requireDelete(file: File) {
            if (file.exists() && !file.deleteRecursively()) throw IOException("cannot clean update transaction")
        }

        private fun <T> withGhostLock(ghostRoot: File, block: () -> T): T {
            val canonical = ghostRoot.canonicalFile
            val key = canonical.path
            val processLock = processLocks.computeIfAbsent(key) { Any() }
            return synchronized(processLock) {
                val parent = canonical.parentFile ?: throw IOException("ghost root has no parent")
                if (!parent.exists() && !parent.mkdirs()) throw IOException("cannot prepare ghost parent")
                val lockName = ".nanidroid-update-lock-${key.toByteArray().sha256().take(24)}"
                val lockFile = File(parent, lockName)
                RandomAccessFile(lockFile, "rw").use { random ->
                    random.channel.use { channel -> channel.lock().use { block() } }
                }
            }
        }

        private fun copyTree(
            source: File,
            destination: File,
            isCancelled: () -> Boolean,
            onBytes: (Long) -> Unit,
        ) {
            if (Files.isSymbolicLink(source.toPath())) throw IOException("symbolic links are not supported")
            if (source.isDirectory) {
                if (!destination.mkdir()) throw IOException("cannot create candidate directory")
                source.listFiles()?.sortedBy(File::getName)?.forEach { child ->
                    if (isCancelled()) return
                    copyTree(child, File(destination, child.name), isCancelled, onBytes)
                } ?: throw IOException("cannot list live ghost tree")
            } else if (source.isFile) {
                FileInputStream(source).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            if (isCancelled()) return
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                output.write(buffer, 0, count)
                                onBytes(count.toLong())
                            }
                        }
                        output.fd.sync()
                    }
                }
            } else {
                throw IOException("unsupported live ghost entry")
            }
        }

        private fun normalizedPath(raw: String): String {
            val normalized = NarRelativePathPolicy.normalize(raw)
            if (!normalized.isSuccess()) throw IOException("invalid update path")
            return normalized.normalized!!
        }

        private fun resolveChild(root: File, relativePath: String): File {
            val child = File(root, relativePath).canonicalFile
            if (!child.toPath().startsWith(root.canonicalFile.toPath())) {
                throw IOException("update path escapes candidate")
            }
            return child
        }

        private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(this)
            .toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private const val TRANSACTION_PREFIX = ".nanidroid-update-"
        private const val STAGING_PREFIX = ".nanidroid-staging-"
        private const val PRIVATE_MARKER_PREFIX = ".nanidroid-update-owner-"
        private const val PRIVATE_WRITING_PREFIX = ".nanidroid-update-writing-"
    }
}
