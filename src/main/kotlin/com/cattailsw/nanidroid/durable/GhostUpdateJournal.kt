package com.cattailsw.nanidroid.durable

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID

data class GhostUpdateJournal(
    val operationId: OperationId,
    val ghostRoot: String,
    val candidateRoot: String,
    val backupRoot: String,
    val phase: CommitPhase,
    val files: List<String>,
    val attemptId: AttemptId? = null,
    val workManagerUuid: String? = null,
)

enum class CommitPhase { PREPARED, BACKED_UP, PUBLISHED, CLEANED, ROLLBACK_CLASSIFIED }

sealed interface RecoveryResult {
    data object NoJournal : RecoveryResult
    data object RolledBack : RecoveryResult
    data object CompletedCommit : RecoveryResult
    data class CommitPending(val files: List<String>) : RecoveryResult
    data class PublishPending(val files: List<String>) : RecoveryResult
    data class RollbackPending(val status: OperationStatus, val files: List<String>) : RecoveryResult
    data class Failed(val diagnostic: String) : RecoveryResult
}

internal enum class RecoveryAuthorization {
    WAIT,
    ADOPT_PREPARED,
    ROLL_FORWARD,
    ROLL_BACK_FAILED,
    ROLL_BACK_CANCELLED,
    FAIL_CLOSED,
}

internal enum class GhostTreeTopology {
    LIVE_CANDIDATE,
    CANDIDATE_BACKUP,
    LIVE_BACKUP,
    LIVE_ONLY,
    INVALID,
}

internal object GhostUpdateJournalStore {
    const val FILE_NAME = "journal.v1"
    private const val MAGIC = 0x4e475531
    internal const val MAX_FILES = 100_000
    private const val MAX_TEXT_BYTES = 16 * 1024
    private const val PRIVATE_WRITING_PREFIX = ".nanidroid-update-writing-"
    private val privateMarkerWriteLock = Any()
    private val activePrivateMarkerWrites = mutableSetOf<String>()

    fun write(file: File, journal: GhostUpdateJournal) {
        if (journal.files.size > MAX_FILES) throw IOException("too many update journal files")
        val parent = file.parentFile ?: throw IOException("journal has no parent")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("cannot prepare update journal directory")
        }
        val temporary = File(parent, "$FILE_NAME.tmp")
        writeContents(temporary, journal)
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /**
     * Creates one unique ownership record outside live ghost contents. Unlike a fixed marker
     * name, this never replaces a user-owned file on external storage. A crash while writing it
     * leaves only an unreadable sibling temporary, which cannot be mistaken for ownership.
     */
    fun createPrivateMarker(parent: File, journal: GhostUpdateJournal): File =
        createPrivateMarker(parent, journal, {})

    internal fun createPrivateMarker(
        parent: File,
        journal: GhostUpdateJournal,
        duringWrite: (File) -> Unit,
        beforePublication: (File) -> Unit = {},
    ): File {
        if (journal.files.size > MAX_FILES) throw IOException("too many update journal files")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("cannot prepare update journal directory")
        }
        val writing = synchronized(privateMarkerWriteLock) {
            File.createTempFile(PRIVATE_WRITING_PREFIX, ".tmp", parent).also {
                activePrivateMarkerWrites += it.canonicalPath
            }
        }
        var marker: File? = null
        try {
            writeContents(writing, journal, duringWrite)
            beforePublication(writing)
            repeat(8) {
                val candidate = File(parent, ".nanidroid-update-owner-${UUID.randomUUID()}.tmp")
                try {
                    java.nio.file.Files.move(
                        writing.toPath(),
                        candidate.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    marker = candidate
                    return candidate
                } catch (_: AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(writing.toPath(), candidate.toPath())
                    marker = candidate
                    return candidate
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // A user-owned file must never be replaced; choose another private name.
                }
            }
            throw IOException("cannot publish private ownership marker")
        } finally {
            synchronized(privateMarkerWriteLock) {
                activePrivateMarkerWrites -= writing.canonicalPath
            }
            if (marker == null) writing.delete()
        }
    }

    /** A filename and unlocked state cannot authenticate a private writer, so preserve residue. */
    fun deleteAbandonedPrivateMarkerWrite(file: File): Boolean {
        if (
            !file.isFile ||
            java.nio.file.Files.isSymbolicLink(file.toPath()) ||
            !file.name.startsWith(PRIVATE_WRITING_PREFIX) ||
            !file.name.endsWith(".tmp")
        ) return false
        synchronized(privateMarkerWriteLock) {
            if (file.canonicalPath in activePrivateMarkerWrites) return false
        }
        return false
    }

    private fun writeContents(
        file: File,
        journal: GhostUpdateJournal,
        duringWrite: (File) -> Unit = {},
    ) {
        FileOutputStream(file).use { raw ->
            raw.channel.lock().use {
                val output = DataOutputStream(BufferedOutputStream(raw))
                output.writeInt(MAGIC)
                output.flush()
                duringWrite(file)
                output.writeBounded(journal.operationId.value)
                output.writeBounded(journal.ghostRoot)
                output.writeBounded(journal.candidateRoot)
                output.writeBounded(journal.backupRoot)
                output.writeInt(journal.phase.ordinal)
                output.writeInt(journal.files.size)
                journal.files.forEach { output.writeBounded(it) }
                output.writeLong(journal.attemptId?.value ?: -1L)
                output.writeBounded(journal.workManagerUuid.orEmpty())
                output.flush()
                raw.fd.sync()
            }
        }
    }

    fun read(file: File): GhostUpdateJournal {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) throw IOException("invalid update journal version")
            val operationId = OperationId(input.readBounded())
            val ghostRoot = input.readBounded()
            val candidateRoot = input.readBounded()
            val backupRoot = input.readBounded()
            val phaseOrdinal = input.readInt()
            val phase = CommitPhase.entries.getOrNull(phaseOrdinal)
                ?: throw IOException("invalid update journal phase")
            val count = input.readInt()
            if (count !in 0..MAX_FILES) throw IOException("invalid update journal file count")
            val files = List(count) { input.readBounded() }
            val attemptValue = input.readLong()
            val workManagerUuid = input.readBounded().ifEmpty { null }
            if (input.read() != -1) throw IOException("trailing update journal data")
            return GhostUpdateJournal(
                operationId,
                ghostRoot,
                candidateRoot,
                backupRoot,
                phase,
                files,
                attemptValue.takeIf { it >= 0 }?.let(::AttemptId),
                workManagerUuid,
            )
        }
    }

    private fun DataOutputStream.writeBounded(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) throw IOException("update journal value is too long")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBounded(): String {
        val size = readInt()
        if (size !in 0..MAX_TEXT_BYTES) throw IOException("invalid update journal value length")
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
}

internal interface GhostUpdateJournalIo {
    fun write(file: File, journal: GhostUpdateJournal)
    fun read(file: File): GhostUpdateJournal

    companion object {
        val DEFAULT = object : GhostUpdateJournalIo {
            override fun write(file: File, journal: GhostUpdateJournal) =
                GhostUpdateJournalStore.write(file, journal)

            override fun read(file: File): GhostUpdateJournal =
                GhostUpdateJournalStore.read(file)
        }
    }
}
