package com.cattailsw.nanidroid.durable

import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.util.NetworkUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

private fun workManagerBinding(label: String) = ExternalJobBinding.WorkManager(
    UUID.nameUUIDFromBytes(label.toByteArray()).toString(),
)

class GhostUpdateRepositoryTest {
    @Test
    fun `retryable manifest transport failure preserves the update attempt`() {
        val fixture = fixture("retryable-manifest")
        fixture.network.retryablePaths += "updates2.dau"

        assertEquals(
            GhostUpdateResult.Interrupted,
            fixture.repository().run(fixture.request()) { false },
        )
    }

    @Test
    fun `verified update publishes one complete tree and preserves untouched files`() {
        val fixture = fixture("success")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLive("shell/master.txt", "keep")
        fixture.network.manifest(
            "ghost/master.txt" to bytes("new"),
            "ghost/extra.txt" to bytes("added"),
        )
        val events = RecordingEvents()

        val result = fixture.repository(events = events).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt", "ghost/extra.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertBytes("keep", File(fixture.ghostRoot, "shell/master.txt"))
        assertBytes("added", File(fixture.ghostRoot, "ghost/extra.txt"))
        assertFalse(fixture.transactionRoot().exists())
        assertEquals(
            listOf(
                "ready:ghost/master.txt,ghost/extra.txt",
                "download:ghost/master.txt:0:1",
                "digest-begin:ghost/master.txt",
                "digest-complete:ghost/master.txt",
                "download:ghost/extra.txt:1:1",
                "digest-begin:ghost/extra.txt",
                "digest-complete:ghost/extra.txt",
                "complete:ghost/master.txt,ghost/extra.txt",
            ),
            events.values,
        )
    }

    @Test
    fun `final merge preserves a non-manifest save changed after candidate snapshot`() {
        val fixture = fixture("live-save-changed")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLive("ghost/save.dat", "before-snapshot")
        fixture.network.manifest("ghost/master.txt" to bytes("server"))
        var changed = false

        val result = fixture.repository(onProgress = { phase, _ ->
            if (phase == "Committing update" && !changed) {
                changed = true
                fixture.writeLive("ghost/save.dat", "after-snapshot")
            }
        }).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("server", File(fixture.ghostRoot, "ghost/master.txt"))
        assertBytes("after-snapshot", File(fixture.ghostRoot, "ghost/save.dat"))
    }

    @Test
    fun `final merge preserves a non-manifest log created after candidate snapshot`() {
        val fixture = fixture("live-log-created")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("server"))
        var created = false

        val result = fixture.repository(onProgress = { phase, _ ->
            if (phase == "Committing update" && !created) {
                created = true
                fixture.writeLive("ghost/runtime/new.log", "late-log")
            }
        }).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("late-log", File(fixture.ghostRoot, "ghost/runtime/new.log"))
    }

    @Test
    fun `final merge preserves deletion of a non-manifest save after candidate snapshot`() {
        val fixture = fixture("live-save-deleted")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLive("ghost/stale-save.dat", "remove-me")
        fixture.network.manifest("ghost/master.txt" to bytes("server"))
        var deleted = false

        val result = fixture.repository(onProgress = { phase, _ ->
            if (phase == "Committing update" && !deleted) {
                deleted = true
                check(File(fixture.ghostRoot, "ghost/stale-save.dat").delete())
            }
        }).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertFalse(File(fixture.ghostRoot, "ghost/stale-save.dat").exists())
    }

    @Test
    fun `final merge keeps manifest and delete paths server authoritative`() {
        val fixture = fixture("managed-live-writes")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLive("ghost/obsolete.txt", "obsolete")
        val delete = "ghost\\obsolete.txt\r\n".toByteArray(Charset.forName("Windows-31J"))
        fixture.network.manifest(
            "delete.txt" to delete,
            "ghost/master.txt" to bytes("server"),
        )
        var wroteManagedPaths = false

        val result = fixture.repository(onProgress = { phase, _ ->
            if (phase == "Committing update" && !wroteManagedPaths) {
                wroteManagedPaths = true
                fixture.writeLive("ghost/master.txt", "late-local-write")
                fixture.writeLive("ghost/obsolete.txt", "late-recreated")
            }
        }).run(fixture.request()) { false }

        assertTrue(result is GhostUpdateResult.Completed)
        assertBytes("server", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(File(fixture.ghostRoot, "ghost/obsolete.txt").exists())
    }

    @Test
    fun `digest mismatch leaves the live tree untouched and removes candidate`() {
        val fixture = fixture("digest")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifestWithDigest("ghost/master.txt", "00000000000000000000000000000000", bytes("bad"))

        val result = fixture.repository().run(fixture.request()) { false }

        assertTrue(result is GhostUpdateResult.Failed)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `manifest rejects traversal absolute duplicate and case-colliding paths`() {
        val manifests = listOf(
            "../outside.txt\u0001${md5(bytes("bad"))}",
            "/absolute.txt\u0001${md5(bytes("bad"))}",
            "ghost/master.txt\u0001${md5(bytes("one"))}\nghost/master.txt\u0001${md5(bytes("two"))}",
            "Ghost/Master.txt\u0001${md5(bytes("one"))}\nghost/master.txt\u0001${md5(bytes("two"))}",
        )
        manifests.forEachIndexed { index, manifest ->
            val fixture = fixture("invalid-$index")
            fixture.writeLive("ghost/master.txt", "old")
            fixture.network.rawManifest(manifest)

            val result = fixture.repository().run(fixture.request()) { false }

            assertTrue("$manifest returned $result", result is GhostUpdateResult.Failed)
            assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
            assertFalse(fixture.transactionRoot().exists())
        }
    }

    @Test
    fun `cancellation during candidate download preserves the live tree`() {
        val fixture = fixture("cancel")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to ByteArray(32 * 1024) { 7 })
        var cancelled = false
        fixture.network.onCandidateRead = { cancelled = true }

        val result = fixture.repository().run(fixture.request()) { cancelled }

        assertEquals(GhostUpdateResult.Cancelled, result)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `cancellation during manifest read is cancelled rather than missing-manifest failure`() {
        val fixture = fixture("manifest-cancel")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var cancelled = false
        fixture.network.onManifestRead = { cancelled = true }

        val result = fixture.repository().run(fixture.request()) { cancelled }

        assertEquals(GhostUpdateResult.Cancelled, result)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `digest mismatch cleanup retains prepared journal when candidate deletion fails`() {
        val fixture = fixture("digest-cleanup-journal")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifestWithDigest("ghost/master.txt", "00000000000000000000000000000000", bytes("bad"))
        val fileOperations = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean =
                root.canonicalFile != File(fixture.transactionRoot(), "candidate").canonicalFile && root.deleteRecursively()
        }

        assertTrue(fixture.repository(fileOperations = fileOperations).run(fixture.request()) { false } is GhostUpdateResult.Failed)
        assertEquals(CommitPhase.PREPARED, GhostUpdateJournalStore.read(
            File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
        ).phase)
        assertTrue(File(fixture.transactionRoot(), "candidate").isDirectory)
    }

    @Test
    fun `process death during user cancellation cleanup preserves exact journal for recovery`() {
        val fixture = fixture("cancel-cleanup-ownership")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val request = fixture.request().copy(attemptId = AttemptId(3), workManagerUuid = "work-3")
        var cancelled = false
        fixture.network.onManifestRead = { cancelled = true }
        val failingDelete = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (root.canonicalFile != File(fixture.transactionRoot(), "candidate").canonicalFile) {
                    return root.deleteRecursively()
                }
                root.deleteRecursively()
                throw SimulatedProcessDeath()
            }
        }

        try {
            fixture.repository(fileOperations = failingDelete).run(request) { cancelled }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // A process death is not catchable by repository recovery.
        }

        val journal = GhostUpdateJournalStore.read(
            File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
        )
        assertEquals(CommitPhase.PREPARED, journal.phase)
        assertEquals(request.operationId, journal.operationId)
        assertEquals(request.attemptId, journal.attemptId)
        assertEquals(request.workManagerUuid, journal.workManagerUuid)
        assertFalse(File(fixture.transactionRoot(), "candidate").exists())

        val recovery = GhostUpdateRepository.recoverAllBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            authorize = { recovered, topology ->
                when (GhostUpdateWorker.recoveryTransition(
                    recovered.phase,
                    topology,
                    OperationStatus.CANCELLED,
                    exactIdentity = true,
                    GhostUpdateWorker.Companion.RecoveryWorkState.CANCELLED,
                )) {
                    GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_CANCELLED ->
                        RecoveryAuthorization.ROLL_BACK_CANCELLED
                    else -> RecoveryAuthorization.FAIL_CLOSED
                }
            },
            classify = { recovered, status ->
                recovered.operationId == request.operationId &&
                    recovered.attemptId == request.attemptId &&
                    recovered.workManagerUuid == request.workManagerUuid &&
                    status == OperationStatus.CANCELLED
            },
        )

        assertEquals(RecoveryResult.RolledBack, recovery)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `journal-less empty transaction root is swept before recovery scheduling`() {
        val fixture = fixture("empty-cancel-cleanup")
        assertTrue(fixture.transactionRoot().mkdir())

        assertTrue(GhostUpdateRepository.recoveryTargets(fixture.parent).isEmpty())
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `recovery discovery accepts a completed temporary journal after restoration interruption`() {
        val fixture = fixture("temporary-restoration-journal")
        fixture.writeLive("ghost/master.txt", "old")
        val transaction = fixture.transactionRoot()
        val journal = GhostUpdateJournal(
            fixture.operationId,
            fixture.ghostRoot.canonicalPath,
            File(transaction, "candidate").canonicalPath,
            File(transaction, "backup").canonicalPath,
            CommitPhase.PREPARED,
            emptyList(),
        )
        GhostUpdateJournalStore.write(File(transaction, "${GhostUpdateJournalStore.FILE_NAME}.tmp"), journal)
        write(File(transaction, "residue"), bytes("keeps root nonempty"))

        assertEquals(setOf(fixture.ghostRoot.canonicalFile), GhostUpdateRepository.recoveryTargets(fixture.parent))
        assertTrue(File(transaction, "${GhostUpdateJournalStore.FILE_NAME}.tmp").isFile)
    }

    @Test
    fun `incomplete temporary journal does not block the live ghost`() {
        val fixture = fixture("truncated-restoration-journal")
        fixture.writeLive("ghost/master.txt", "old")
        val temporary = File(fixture.transactionRoot(), "${GhostUpdateJournalStore.FILE_NAME}.tmp")
        write(temporary, bytes("truncated"))

        assertTrue(GhostUpdateRepository.recoveryTargets(fixture.parent).isEmpty())
        assertFalse(fixture.ghostRoot.canonicalFile in GhostUpdateRepository.blockedGhostRoots(fixture.parent))
        assertTrue(temporary.isFile)
    }

    @Test
    fun `recovery discovery preserves an active empty transaction root while stale residue is swept`() {
        val fixture = fixture("active-empty-transaction")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val stale = File(fixture.parent, ".nanidroid-update-stale").apply { mkdir() }
        var stagingPublished = false
        val fileOperations = object : GhostUpdateFileOperations {
            override fun publishStaging(source: File, destination: File): Boolean {
                val renamed = source.renameTo(destination)
                if (destination.canonicalFile == fixture.transactionRoot().canonicalFile &&
                    source.name.startsWith(".nanidroid-staging-")
                ) {
                    stagingPublished = true
                    assertTrue(renamed)
                    assertEquals(
                        setOf(fixture.ghostRoot.canonicalFile),
                        GhostUpdateRepository.recoveryTargets(fixture.parent),
                    )
                    assertTrue(fixture.transactionRoot().exists())
                    assertFalse(stale.exists())
                }
                return renamed
            }
        }

        assertEquals(
            GhostUpdateResult.Completed(listOf("ghost/master.txt")),
            fixture.repository(fileOperations = fileOperations).run(fixture.request()) { false },
        )
        assertTrue(stagingPublished)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `startup recovery sweeps unpublished staging left by process death`() {
        val fixture = fixture("unpublished-staging-recovery")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var staging: File? = null
        val fileOperations = object : GhostUpdateFileOperations {
            override fun publishStaging(source: File, destination: File): Boolean {
                staging = source
                return false
            }

            override fun deleteTree(root: File): Boolean {
                if (root == staging) throw SimulatedProcessDeath()
                return root.deleteRecursively()
            }
        }

        try {
            fixture.repository(fileOperations = fileOperations).run(fixture.request()) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The staging directory is intentionally left behind as process-death residue.
        }

        val abandoned = requireNotNull(staging)
        assertTrue(abandoned.exists())
        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))
        assertFalse(abandoned.exists())
    }

    @Test
    fun `startup recovery sweeps tmp-only staging journal left before ownership finalizes`() {
        val fixture = fixture("tmp-only-staging-recovery")
        fixture.writeLive("ghost/master.txt", "old")
        val transaction = fixture.transactionRoot()
        val staging = File(
            fixture.parent,
            ".nanidroid-staging-${transaction.name.removePrefix(".nanidroid-update-")}",
        ).apply { check(mkdir()) }
        val journal = GhostUpdateJournal(
            operationId = fixture.operationId,
            ghostRoot = fixture.ghostRoot.canonicalPath,
            candidateRoot = File(transaction, "candidate").canonicalPath,
            backupRoot = File(transaction, "backup").canonicalPath,
            phase = CommitPhase.PREPARED,
            files = emptyList(),
        )
        val completedJournal = File(staging, GhostUpdateJournalStore.FILE_NAME)
        GhostUpdateJournalStore.write(completedJournal, journal)
        GhostUpdateJournalStore.createPrivateMarker(fixture.parent, journal)
        val temporaryJournal = File(staging, "${GhostUpdateJournalStore.FILE_NAME}.tmp")
        check(completedJournal.renameTo(temporaryJournal))

        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))

        assertFalse(staging.exists())
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(transaction.exists())
    }

    @Test
    fun `startup recovery sweeps owned staging left before journal creation begins`() {
        val fixture = fixture("pre-journal-staging-recovery")
        fixture.writeLive("ghost/master.txt", "old")
        assertPreJournalStagingRecovery(fixture)
    }

    @Test
    fun `startup recovery reclaims owned staging with an interrupted private journal write`() {
        val fixture = fixture("interrupted-staging-journal-recovery")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val staging = File(
            fixture.parent,
            ".nanidroid-staging-${fixture.transactionRoot().name.removePrefix(".nanidroid-update-")}",
        )
        val journalIo = object : GhostUpdateJournalIo {
            override fun write(file: File, journal: GhostUpdateJournal) {
                if (file.parentFile?.canonicalFile == staging.canonicalFile) {
                    write(File(staging, "${GhostUpdateJournalStore.FILE_NAME}.tmp"), bytes("incomplete"))
                    throw SimulatedProcessDeath()
                }
                GhostUpdateJournalStore.write(file, journal)
            }

            override fun read(file: File) = GhostUpdateJournalStore.read(file)
        }
        val fileOperations = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (root.canonicalFile == staging.canonicalFile) throw SimulatedProcessDeath()
                return root.deleteRecursively()
            }
        }

        try {
            fixture.repository(journalIo = journalIo, fileOperations = fileOperations).run(fixture.request()) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The staging journal write has created only its private temporary file.
        }

        assertTrue(staging.exists())
        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))

        assertFalse(staging.exists())
        assertTrue(fixture.parent.listFiles().orEmpty().none { it.name.startsWith(".nanidroid-update-owner-") })
        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), fixture.repository().run(fixture.request()) { false })
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `startup recovery preserves an incomplete private ownership marker`() {
        val fixture = fixture("incomplete-private-owner-marker")
        val marker = File.createTempFile(".nanidroid-update-owner-", ".tmp", fixture.parent)
        write(marker, bytes("truncated"))

        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))

        assertTrue(marker.exists())
    }

    @Test
    fun `startup recovery never sweeps an installed ghost whose id uses the staging prefix`() {
        val storage = temporaryDirectory("staging-prefix-ghost")
        val ghost = File(storage, ".nanidroid-staging-valid-ghost").apply { mkdirs() }
        write(File(ghost, "ghost/master.txt"), bytes("live"))

        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(storage))

        assertTrue(ghost.exists())
        assertBytes("live", File(ghost, "ghost/master.txt"))
    }

    @Test
    fun `startup recovery never sweeps a prefix named ghost with an unauthenticated staging journal`() {
        val storage = temporaryDirectory("unauthenticated-staging-journal")
        val owner = File(storage, "owner").apply { mkdirs() }
        val operation = GhostUpdateRepository.canonicalOperationIdFor(owner)
        val transaction = GhostUpdateRepository.transactionRootFor(owner, operation)
        val ghost = File(
            storage,
            ".nanidroid-staging-${transaction.name.removePrefix(".nanidroid-update-")}",
        ).apply { mkdirs() }
        write(File(ghost, "ghost/master.txt"), bytes("live"))
        GhostUpdateJournalStore.write(
            File(ghost, GhostUpdateJournalStore.FILE_NAME),
            GhostUpdateJournal(
                operationId = operation,
                ghostRoot = owner.canonicalPath,
                candidateRoot = File(transaction, "candidate").canonicalPath,
                backupRoot = File(transaction, "backup").canonicalPath,
                phase = CommitPhase.PREPARED,
                files = emptyList(),
            ),
        )

        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(storage))

        assertTrue(ghost.exists())
        assertBytes("live", File(ghost, "ghost/master.txt"))
    }

    @Test
    fun `update preserves another installed ghost at its deterministic staging path`() {
        val fixture = fixture("staging-occupant")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val occupant = File(
            fixture.parent,
            ".nanidroid-staging-${fixture.transactionRoot().name.removePrefix(".nanidroid-update-")}",
        ).apply { mkdirs() }
        write(File(occupant, "ghost/master.txt"), bytes("other-live"))

        assertTrue(fixture.repository().run(fixture.request()) { false } is GhostUpdateResult.Failed)

        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(occupant.isDirectory)
        assertBytes("other-live", File(occupant, "ghost/master.txt"))
    }

    @Test
    fun `update ignores a live file at the former preparing marker path`() {
        val fixture = fixture("preparing-marker-occupant")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val marker = File(
            fixture.ghostRoot,
            ".nanidroid-update-preparing-${fixture.transactionRoot().name.removePrefix(".nanidroid-update-")}",
        )
        write(marker, bytes("live-marker"))

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), fixture.repository().run(fixture.request()) { false })

        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertBytes("live-marker", marker)
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `update leaves a live journal temporary file untouched`() {
        val fixture = fixture("preparing-marker-journal-temporary")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("old"))
        val temporaryJournal = File(fixture.ghostRoot, "journal.v1.tmp")
        write(temporaryJournal, bytes("live-temporary"))

        assertEquals(GhostUpdateResult.NoChanges, fixture.repository().run(fixture.request()) { false })

        assertBytes("live-temporary", temporaryJournal)
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `startup recovery preserves a live ghost at another root's expected staging path`() {
        val fixture = fixture("staging-occupant-recovery")
        fixture.writeLive("ghost/master.txt", "old")
        val occupant = File(
            fixture.parent,
            ".nanidroid-staging-${fixture.transactionRoot().name.removePrefix(".nanidroid-update-")}",
        ).apply { mkdirs() }
        write(File(occupant, "ghost/master.txt"), bytes("other-live"))

        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))

        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(occupant.isDirectory)
        assertBytes("other-live", File(occupant, "ghost/master.txt"))
    }

    @Test
    fun `startup recovery reclaims pre-journal staging for a prefix-named ghost`() {
        val fixture = fixture("prefix-owner-pre-journal", ".nanidroid-staging-live-ghost")
        fixture.writeLive("ghost/master.txt", "old")
        assertPreJournalStagingRecovery(fixture)
    }

    private fun assertPreJournalStagingRecovery(fixture: Fixture) {
        val staging = File(
            fixture.parent,
            ".nanidroid-staging-${fixture.transactionRoot().name.removePrefix(".nanidroid-update-")}",
        )
        val journalIo = object : GhostUpdateJournalIo {
            override fun write(file: File, journal: GhostUpdateJournal) {
                if (file.parentFile?.canonicalFile == staging.canonicalFile) {
                    throw IOException("process died before the first staging journal write")
                }
                GhostUpdateJournalStore.write(file, journal)
            }

            override fun read(file: File) = GhostUpdateJournalStore.read(file)
        }
        val fileOperations = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (root.canonicalFile == staging.canonicalFile) throw SimulatedProcessDeath()
                return root.deleteRecursively()
            }
        }

        try {
            fixture.repository(journalIo = journalIo, fileOperations = fileOperations).run(fixture.request()) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The owner dies after staging creation but before its readable journal exists.
        }

        assertTrue(staging.exists())
        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))

        assertFalse(staging.exists())
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
        assertTrue(fixture.parent.listFiles().orEmpty().none { it.name.startsWith(".nanidroid-update-owner-") })
    }

    @Test
    fun `exact replay adopts ownership journal published before candidate construction`() {
        val fixture = fixture("published-ownership-replay")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var published = false
        val fileOperations = object : GhostUpdateFileOperations {
            override fun publishStaging(source: File, destination: File): Boolean {
                assertTrue(source.renameTo(destination))
                published = true
                throw SimulatedProcessDeath()
            }
        }
        val request = fixture.request().copy(attemptId = AttemptId(8), workManagerUuid = "work-8")

        try {
            fixture.repository(fileOperations = fileOperations).run(request) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The ownership journal is durable but candidate construction has not begun.
        }

        assertTrue(published)
        assertTrue(File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME).isFile)
        assertFalse(File(fixture.transactionRoot(), "candidate").exists())

        assertEquals(
            GhostUpdateResult.Completed(listOf("ghost/master.txt")),
            fixture.repository().run(request) { false },
        )
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `startup recovery waits for an active unpublished staging owner before sweeping`() {
        val fixture = fixture("active-unpublished-staging")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val recoveryStarted = CountDownLatch(1)
        val recoveryFinished = CountDownLatch(1)
        val recovery = AtomicReference<RecoveryResult>()
        val fileOperations = object : GhostUpdateFileOperations {
            override fun publishStaging(source: File, destination: File): Boolean {
                Thread {
                    recoveryStarted.countDown()
                    recovery.set(GhostUpdateRepository.recoverAllBeforeGhostLoad(fixture.parent))
                    recoveryFinished.countDown()
                }.start()
                assertTrue(recoveryStarted.await(5, TimeUnit.SECONDS))
                assertFalse(recoveryFinished.await(250, TimeUnit.MILLISECONDS))
                assertTrue(source.exists())
                return source.renameTo(destination)
            }
        }

        assertEquals(
            GhostUpdateResult.Completed(listOf("ghost/master.txt")),
            fixture.repository(fileOperations = fileOperations).run(fixture.request()) { false },
        )
        assertTrue(recoveryFinished.await(5, TimeUnit.SECONDS))
        assertEquals(RecoveryResult.NoJournal, recovery.get())
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `system interruption during manifest download or digest is retryable and keeps live tree`() {
        listOf("prefetch", "manifest", "download", "digest").forEach { phase ->
            val fixture = fixture("system-interruption-$phase")
            val original = ByteArray(32 * 1024) { 3 }
            fixture.writeLiveBytes("ghost/master.txt", original)
            fixture.network.manifest(
                "ghost/master.txt" to if (phase == "digest") original else ByteArray(32 * 1024) { 7 },
            )
            var stop = GhostUpdateStopReason.NONE
            when (phase) {
                "prefetch" -> fixture.network.beforeOpen = { path ->
                    if (path == "updates2.dau" || path == "updates.txt") {
                        stop = GhostUpdateStopReason.SYSTEM_INTERRUPTED
                        false
                    } else true
                }
                "manifest" -> fixture.network.onManifestRead = {
                    stop = GhostUpdateStopReason.SYSTEM_INTERRUPTED
                    throw IOException("stream closed by system stop")
                }
                "download" -> fixture.network.onCandidateRead = {
                    stop = GhostUpdateStopReason.SYSTEM_INTERRUPTED
                    throw IOException("stream closed by system stop")
                }
                "digest" -> Unit
            }

            val result = fixture.repository(onProgress = { progressPhase, completed ->
                if (phase == "digest" && progressPhase == "Comparing installed files" && completed > 0) {
                    stop = GhostUpdateStopReason.SYSTEM_INTERRUPTED
                }
            }).runInterruptible(fixture.request()) { stop }

            assertEquals(phase, GhostUpdateResult.Interrupted, result)
            assertArrayEquals(original, File(fixture.ghostRoot, "ghost/master.txt").readBytes())
            assertFalse(phase, fixture.transactionRoot().exists())
            assertTrue(phase, fixture.network.openedStreams.all { it.closed })
        }
    }

    @Test
    fun `transport read failure during manifest or file download is retryable`() {
        listOf("manifest", "download").forEach { phase ->
            val fixture = fixture("transport-read-$phase")
            fixture.writeLive("ghost/master.txt", "old")
            fixture.network.manifest("ghost/master.txt" to bytes("new"))
            if (phase == "manifest") {
                fixture.network.onManifestRead = { throw IOException("connection reset") }
            } else {
                fixture.network.onCandidateRead = { throw IOException("connection reset") }
            }

            val result = fixture.repository().run(fixture.request()) { false }

            assertEquals(phase, GhostUpdateResult.Interrupted, result)
            assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
            assertFalse(phase, fixture.transactionRoot().exists())
            assertTrue(phase, fixture.network.openedStreams.all { it.closed })
        }
    }

    @Test
    fun `transport close failure during manifest or file download is retryable`() {
        listOf("manifest", "download").forEach { phase ->
            val fixture = fixture("transport-close-$phase")
            fixture.writeLive("ghost/master.txt", "old")
            fixture.network.manifest("ghost/master.txt" to bytes("new"))
            fixture.network.closeFailures += if (phase == "manifest") "updates2.dau" else "ghost/master.txt"

            assertEquals(phase, GhostUpdateResult.Interrupted, fixture.repository().run(fixture.request()) { false })
        }
    }

    @Test
    fun `HTTP timeout throttling and server errors are retryable`() {
        listOf(408, 429, 500, 503).forEach { status ->
            assertTrue(status.toString(), isRetryableGhostUpdateStatus(status))
        }
        listOf(400, 401, 403, 404).forEach { status ->
            assertFalse(status.toString(), isRetryableGhostUpdateStatus(status))
        }
    }

    @Test
    fun `explicit user cancellation during manifest download or digest is terminal cancellation`() {
        listOf("prefetch", "manifest", "download", "digest").forEach { phase ->
            val fixture = fixture("user-cancellation-$phase")
            val original = ByteArray(32 * 1024) { 4 }
            fixture.writeLiveBytes("ghost/master.txt", original)
            fixture.network.manifest(
                "ghost/master.txt" to if (phase == "digest") original else ByteArray(32 * 1024) { 8 },
            )
            var stop = GhostUpdateStopReason.NONE
            when (phase) {
                "prefetch" -> fixture.network.beforeOpen = { path ->
                    if (path == "updates2.dau" || path == "updates.txt") {
                        stop = GhostUpdateStopReason.USER_CANCELLED
                        false
                    } else true
                }
                "manifest" -> fixture.network.onManifestRead = {
                    stop = GhostUpdateStopReason.USER_CANCELLED
                    throw IOException("stream closed by user stop")
                }
                "download" -> fixture.network.onCandidateRead = {
                    stop = GhostUpdateStopReason.USER_CANCELLED
                    throw IOException("stream closed by user stop")
                }
                "digest" -> Unit
            }

            val result = fixture.repository(onProgress = { progressPhase, completed ->
                if (phase == "digest" && progressPhase == "Comparing installed files" && completed > 0) {
                    stop = GhostUpdateStopReason.USER_CANCELLED
                }
            }).runInterruptible(fixture.request()) { stop }

            assertEquals(phase, GhostUpdateResult.Cancelled, result)
            assertArrayEquals(original, File(fixture.ghostRoot, "ghost/master.txt").readBytes())
            assertFalse(phase, fixture.transactionRoot().exists())
            assertTrue(phase, fixture.network.openedStreams.all { it.closed })
        }
    }

    @Test
    fun `prepared old-live journal remains bootable and retains candidate`() {
        val fixture = fixture("prepared")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.PREPARED, listOf("ghost/master.txt"))

        val result = GhostUpdateRepository.recoverBeforeGhostLoad(fixture.ghostRoot)

        assertEquals(RecoveryResult.CommitPending(listOf("ghost/master.txt")), result)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `backed-up journal completes a verified candidate commit`() {
        val fixture = fixture("backed-up")
        assertTrue(fixture.ghostRoot.delete())
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.BACKED_UP, listOf("ghost/master.txt"))

        val result = GhostUpdateRepository.recoverBeforeGhostLoad(fixture.ghostRoot)

        assertEquals(RecoveryResult.CommitPending(listOf("ghost/master.txt")), result)
        assertFalse(fixture.ghostRoot.exists())
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `published journal keeps the new tree and completes cleanup`() {
        val fixture = fixture("published")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        fixture.writeJournal(CommitPhase.PUBLISHED, listOf("ghost/master.txt"))

        val result = GhostUpdateRepository.recoverBeforeGhostLoad(fixture.ghostRoot)

        assertEquals(RecoveryResult.CommitPending(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `updates2 v2 reads Shift_JIS charset extension and optional fields`() {
        val fixture = fixture("v2-shift-jis")
        fixture.writeLive("keep.txt", "keep")
        val japanesePath = "ghost/master/日本語.txt"
        val emptyDigest = md5(byteArrayOf())
        val manifest = (
            "$japanesePath\u0001$emptyDigest\u0001charset=Shift_JIS\u0001\r\n" +
                "shell/master/surface0.png\u0001$emptyDigest\u0001\r\n"
            ).toByteArray(Charset.forName("Windows-31J"))
        fixture.network.rawManifestBytes("updates2.dau", manifest)
        fixture.network.file(japanesePath, byteArrayOf())
        fixture.network.file("shell/master/surface0.png", byteArrayOf())

        val result = fixture.repository().run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf(japanesePath, "shell/master/surface0.png")), result)
        assertTrue(File(fixture.ghostRoot, japanesePath).isFile)
        assertBytes("keep", File(fixture.ghostRoot, "keep.txt"))
    }

    @Test
    fun `updates2 decodes consistently percent escaped Shift_JIS paths`() {
        val fixture = fixture("v2-percent-shift-jis")
        val japanesePath = "ghost/master/日本語.txt"
        val encodedPath = "ghost%2Fmaster%2F%93%FA%96%7B%8C%EA.txt"
        val emptyDigest = md5(byteArrayOf())
        val manifest = "$encodedPath\u0001$emptyDigest\u0001charset=Shift_JIS\u0001\r\n"
            .toByteArray(Charsets.US_ASCII)
        fixture.network.rawManifestBytes("updates2.dau", manifest)
        fixture.network.file(japanesePath, byteArrayOf())

        val result = fixture.repository().run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf(japanesePath)), result)
        assertTrue(File(fixture.ghostRoot, japanesePath).isFile)
        assertTrue(fixture.network.openedPaths.contains(japanesePath))
    }

    @Test
    fun `updates v3 reads prefixed Shift_JIS file records from fallback manifest`() {
        val fixture = fixture("v3-shift-jis")
        val japanesePath = "ghost/master/日本語.txt"
        val emptyDigest = md5(byteArrayOf())
        val manifest = (
            "charset,Shift_JIS\r\n" +
                "file,$japanesePath\u0001$emptyDigest\u0001\r\n"
            ).toByteArray(Charset.forName("Windows-31J"))
        fixture.network.rawManifestBytes("updates.txt", manifest)
        fixture.network.file(japanesePath, byteArrayOf())

        val result = fixture.repository().run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf(japanesePath)), result)
        assertTrue(File(fixture.ghostRoot, japanesePath).isFile)
    }

    @Test
    fun `candidate delete file removes only validated file and directory entries`() {
        val fixture = fixture("delete")
        fixture.writeLive("ghost/master/obsolete.txt", "obsolete")
        fixture.writeLive("shell/old/a.txt", "old")
        fixture.writeLive("shell/keep.txt", "keep")
        val delete = "charset,Shift_JIS\r\nghost\\master\\obsolete.txt\r\nshell\\old\\\r\n"
            .toByteArray(Charset.forName("Windows-31J"))
        fixture.network.manifest("delete.txt" to delete, "ghost/master.txt" to bytes("new"))

        val result = fixture.repository().run(fixture.request()) { false }

        assertTrue(result is GhostUpdateResult.Completed)
        assertFalse(File(fixture.ghostRoot, "ghost/master/obsolete.txt").exists())
        assertFalse(File(fixture.ghostRoot, "shell/old").exists())
        assertBytes("keep", File(fixture.ghostRoot, "shell/keep.txt"))
        assertTrue(File(fixture.ghostRoot, "delete.txt").isFile)
    }

    @Test
    fun `oversized delete manifest leaves the live ghost and transaction cleanup intact`() {
        val fixture = fixture("oversized-delete-manifest")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLive("ghost/obsolete.txt", "keep")
        fixture.network.manifest(
            "delete.txt" to ByteArray(GhostUpdateRepository.MAX_DELETE_BYTES + 1),
            "ghost/master.txt" to bytes("new"),
        )

        val result = fixture.repository().run(fixture.request()) { false }

        assertTrue(result is GhostUpdateResult.Failed)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertBytes("keep", File(fixture.ghostRoot, "ghost/obsolete.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `cancellation requested after journal persistence cannot interrupt bounded commit`() {
        val fixture = fixture("post-journal-cancel")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var cancelled = false
        val fileOperations = object : GhostUpdateFileOperations {
            override fun rename(source: File, destination: File): Boolean =
                source.renameTo(destination).also {
                    if (source.canonicalFile == fixture.ghostRoot.canonicalFile) cancelled = true
                }
        }

        val result = fixture.repository(fileOperations = fileOperations)
            .run(fixture.request()) { cancelled }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `system interruption requested after journal persistence cannot interrupt bounded commit`() {
        val fixture = fixture("post-journal-system-stop")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var stop = GhostUpdateStopReason.NONE
        val fileOperations = object : GhostUpdateFileOperations {
            override fun rename(source: File, destination: File): Boolean =
                source.renameTo(destination).also {
                    if (source.canonicalFile == fixture.ghostRoot.canonicalFile) {
                        stop = GhostUpdateStopReason.SYSTEM_INTERRUPTED
                    }
                }
        }

        val result = fixture.repository(fileOperations = fileOperations)
            .runInterruptible(fixture.request()) { stop }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `user or system stop inside commit gate interrupts final resync before journal`() {
        listOf(
            GhostUpdateStopReason.USER_CANCELLED to GhostUpdateResult.Cancelled,
            GhostUpdateStopReason.SYSTEM_INTERRUPTED to GhostUpdateResult.Interrupted,
        ).forEachIndexed { index, (requested, expected) ->
            val fixture = fixture("commit-gate-stop-$index")
            fixture.writeLive("ghost/master.txt", "old")
            fixture.writeLiveBytes("ghost/save.dat", ByteArray(64 * 1024) { 5 })
            fixture.network.manifest("ghost/master.txt" to bytes("new"))
            var commitStarted = false
            var resyncPolls = 0

            val result = fixture.repository(
                onProgress = { phase, _ -> if (phase == "Committing update") commitStarted = true },
            ).runInterruptible(fixture.request()) {
                if (commitStarted && ++resyncPolls >= 3) requested else GhostUpdateStopReason.NONE
            }

            assertEquals(requested.name, expected, result)
            assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
            assertFalse(requested.name, fixture.transactionRoot().exists())
            assertTrue(requested.name, resyncPolls >= 3)
        }
    }

    @Test
    fun `failed user cancellation cleanup inside commit gate retains exact recovery journal`() {
        val fixture = fixture("commit-gate-cancel-cleanup")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLiveBytes("ghost/save.dat", ByteArray(64 * 1024) { 5 })
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val request = fixture.request().copy(attemptId = AttemptId(3), workManagerUuid = "work-3")
        var commitStarted = false
        var resyncPolls = 0
        val failingDelete = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (root.canonicalFile != File(fixture.transactionRoot(), "candidate").canonicalFile) {
                    return root.deleteRecursively()
                }
                File(root, "ghost").deleteRecursively()
                return false
            }
        }

        val result = fixture.repository(
            fileOperations = failingDelete,
            onProgress = { phase, _ -> if (phase == "Committing update") commitStarted = true },
        ).runInterruptible(request) {
            if (commitStarted && ++resyncPolls >= 3) {
                GhostUpdateStopReason.USER_CANCELLED
            } else {
                GhostUpdateStopReason.NONE
            }
        }

        assertEquals(GhostUpdateResult.Cancelled, result)
        val journal = GhostUpdateJournalStore.read(
            File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
        )
        assertEquals(CommitPhase.PREPARED, journal.phase)
        assertEquals(request.operationId, journal.operationId)
        assertEquals(request.attemptId, journal.attemptId)
        assertEquals(request.workManagerUuid, journal.workManagerUuid)
        assertTrue(File(fixture.transactionRoot(), "candidate").isDirectory)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `user or system stop while awaiting commit gate removes pre-journal transaction`() {
        listOf(
            GhostUpdateStopReason.USER_CANCELLED to GhostUpdateResult.Cancelled,
            GhostUpdateStopReason.SYSTEM_INTERRUPTED to GhostUpdateResult.Interrupted,
        ).forEachIndexed { index, (requested, expected) ->
            val fixture = fixture("commit-gate-wait-stop-$index")
            fixture.writeLive("ghost/master.txt", "old")
            fixture.network.manifest("ghost/master.txt" to bytes("new"))
            var stop = GhostUpdateStopReason.NONE
            val blockedGuard = object : GhostUpdateCommitGuard {
                override fun commit(
                    ghostId: String,
                    ghostRoot: File,
                    onFailure: (Throwable) -> GhostUpdateResult,
                    action: () -> GhostUpdateResult,
                ): GhostUpdateResult = error("stop-aware guard overload required")

                override fun commit(
                    ghostId: String,
                    ghostRoot: File,
                    onFailure: (Throwable) -> GhostUpdateResult,
                    shouldStop: () -> Boolean,
                    onStopped: () -> GhostUpdateResult,
                    action: () -> GhostUpdateResult,
                ): GhostUpdateResult {
                    stop = requested
                    assertTrue(shouldStop())
                    return onStopped()
                }
            }

            val result = fixture.repository(commitGuard = blockedGuard)
                .runInterruptible(fixture.request()) { stop }

            assertEquals(requested.name, expected, result)
            assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
            assertFalse(requested.name, fixture.transactionRoot().exists())
        }
    }

    @Test
    fun `failure after live rename finishes verified commit`() {
        val fixture = fixture("crash-backup")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var renameCount = 0
        val fileOperations = object : GhostUpdateFileOperations {
            override fun rename(source: File, destination: File): Boolean {
                val renamed = source.renameTo(destination)
                if (source.canonicalFile == fixture.ghostRoot.canonicalFile && ++renameCount == 1) {
                    throw IOException("simulated process death after backup rename")
                }
                return renamed
            }
        }
        val lifecycle = RecordingLifecycleCommitGuard()

        val result = fixture.repository(fileOperations = fileOperations, commitGuard = lifecycle)
            .run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertEquals(listOf("unload", "recover", "reload:new"), lifecycle.values)
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `recovery rejects a persisted traversal path without touching either tree`() {
        val fixture = fixture("corrupt-journal")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.PREPARED, listOf("../outside.txt"))

        val result = GhostUpdateRepository.recoverBeforeGhostLoad(fixture.ghostRoot)

        assertTrue(result is RecoveryResult.Failed)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `all owned manifest and candidate streams close on cancellation`() {
        val fixture = fixture("stream-close")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to ByteArray(32 * 1024) { 1 })
        var cancelled = false
        fixture.network.onCandidateRead = { cancelled = true }

        fixture.repository().run(fixture.request()) { cancelled }

        assertTrue(fixture.network.openedStreams.isNotEmpty())
        assertTrue(fixture.network.openedStreams.all { it.closed })
    }

    @Test
    fun `operation id cannot escape the ghost parent or delete unrelated data`() {
        val fixture = fixture("operation-path")
        fixture.writeLive("ghost/master.txt", "old")
        val unrelated = File(fixture.parent.parentFile, "unrelated-update-target").apply {
            mkdirs()
        }
        write(File(unrelated, "sentinel.txt"), bytes("keep"))
        val request = fixture.request().copy(operationId = OperationId("../../unrelated-update-target"))

        val result = fixture.repository().run(request) { false }

        assertTrue(result is GhostUpdateResult.Failed)
        assertBytes("keep", File(unrelated, "sentinel.txt"))
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `candidate download cannot overwrite an authored temp-looking file`() {
        val fixture = fixture("temp-collision")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeLive("ghost/.master.txt.nanidroid-update", "authored")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))

        val result = fixture.repository().run(fixture.request()) { false }

        assertTrue(result is GhostUpdateResult.Completed)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertBytes("authored", File(fixture.ghostRoot, "ghost/.master.txt.nanidroid-update"))
    }

    @Test
    fun `journal phase write failure after backup finishes verified commit`() {
        val fixture = fixture("journal-backup-fault")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val journalIo = FailingJournalIo(CommitPhase.BACKED_UP)

        val result = fixture.repository(journalIo = journalIo).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `journal phase write failure after publish completes the already swapped tree`() {
        val fixture = fixture("journal-publish-fault")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val journalIo = FailingJournalIo(CommitPhase.PUBLISHED)
        val lifecycle = RecordingLifecycleCommitGuard()

        val result = fixture.repository(journalIo = journalIo, commitGuard = lifecycle)
            .run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertEquals(listOf("unload", "recover", "reload:new"), lifecycle.values)
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `opaque staging directory is always a direct canonical sibling`() {
        val fixture = fixture("opaque-operation")
        val transaction = GhostUpdateRepository.transactionRootFor(
            fixture.ghostRoot,
            OperationId("../../../outside/with\\separators"),
        )

        assertEquals(fixture.ghostRoot.canonicalFile.parentFile, transaction.parentFile)
        assertTrue(transaction.name.matches(Regex("\\.nanidroid-update-[0-9a-f]{32}")))
    }

    @Test
    fun `worker fences stale WorkManager binding before network work`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-stale"), AttemptId(1))
        val current = workManagerBinding("current-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, current))
        var invoked = false

        val result = GhostUpdateWorker.execute(
            supervisor,
            handle,
            workManagerBinding("stale-work"),
            { false },
        ) {
            invoked = true
            GhostUpdateResult.Completed(emptyList())
        }

        assertFalse(invoked)
        assertEquals(ListenableWorker.Result.success().toString(), result.toString())
        assertEquals(OperationStatus.RUNNING, durableStore.read().single().status)
    }

    @Test
    fun `worker terminalizes only its exact bound attempt`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-current"), AttemptId(1))
        val binding = workManagerBinding("current-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        val result = GhostUpdateWorker.execute(supervisor, handle, binding, { false }) {
            GhostUpdateResult.Completed(listOf("ghost/master.txt"))
        }

        assertEquals(ListenableWorker.Result.success().toString(), result.toString())
        assertEquals(OperationStatus.COMPLETED, durableStore.read().single().status)
    }

    @Test
    fun `worker retries instead of terminalizing when terminal event persistence failed`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-terminal-persistence-retry"), AttemptId(1))
        val binding = workManagerBinding("terminal-persistence-retry-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        val result = GhostUpdateWorker.execute(
            supervisor,
            handle,
            binding,
            { false },
            terminalEventPersistenceFailed = { true },
        ) {
            GhostUpdateResult.Failed("terminal event could not be persisted")
        }

        assertEquals(ListenableWorker.Result.retry().toString(), result.toString())
        assertEquals(OperationStatus.RUNNING, durableStore.read().single().status)
    }

    @Test
    fun `worker retries system interruption without terminalizing exact running attempt`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-interrupted"), AttemptId(1))
        val binding = workManagerBinding("interrupted-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        val result = GhostUpdateWorker.execute(supervisor, handle, binding, { true }) {
            GhostUpdateResult.Interrupted
        }

        assertEquals(ListenableWorker.Result.retry().toString(), result.toString())
        assertEquals(OperationStatus.RUNNING, durableStore.read().single().status)
    }

    @Test
    fun `worker retry runs when exact binding replays identical initial progress`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-identical-progress-retry"), AttemptId(1))
        val binding = workManagerBinding("identical-progress-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        var runs = 0

        val interrupted = GhostUpdateWorker.execute(supervisor, handle, binding, { true }) {
            runs++
            GhostUpdateResult.Interrupted
        }
        val completed = GhostUpdateWorker.execute(supervisor, handle, binding, { false }) {
            runs++
            GhostUpdateResult.Completed(listOf("ghost/master.txt"))
        }

        assertEquals(ListenableWorker.Result.retry().toString(), interrupted.toString())
        assertEquals(ListenableWorker.Result.success().toString(), completed.toString())
        assertEquals(2, runs)
        assertEquals(OperationStatus.COMPLETED, durableStore.read().single().status)
    }

    @Test
    fun `worker terminalizes exact cancellation when initial progress is unchanged`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-unchanged-cancelled"), AttemptId(1))
        val binding = workManagerBinding("unchanged-cancelled-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Fetching update manifest", 0, binding))
        assertTrue(supervisor.requestStop(handle))
        var invoked = false

        val result = GhostUpdateWorker.execute(supervisor, handle, binding, { true }) {
            invoked = true
            GhostUpdateResult.Interrupted
        }

        assertEquals(ListenableWorker.Result.success().toString(), result.toString())
        assertFalse(invoked)
        assertEquals(OperationStatus.CANCELLED, durableStore.read().single().status)
    }

    @Test
    fun `worker terminalizes cancellation racing after unchanged progress rejection`() {
        val delegate = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        var armed = false
        var armedReads = 0
        val racingStore = object : DurableOperationStore {
            override fun read(): List<DurableOperationRecord> {
                val records = delegate.read()
                if (armed && ++armedReads == 2) {
                    val current = records.single()
                    assertTrue(delegate.compareAndSet(
                        current,
                        current.copy(status = OperationStatus.CANCEL_REQUESTED),
                    ))
                    return delegate.read()
                }
                return records
            }

            override fun putIfAbsent(record: DurableOperationRecord): Boolean =
                delegate.putIfAbsent(record)

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ): Boolean = delegate.compareAndSet(expected, updated)
        }
        val supervisor = DurableOperationSupervisor(racingStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-progress-cancel-race"), AttemptId(1))
        val binding = workManagerBinding("progress-cancel-race-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Fetching update manifest", 0, binding))
        armed = true
        var invoked = false

        val result = GhostUpdateWorker.execute(supervisor, handle, binding, { true }) {
            invoked = true
            GhostUpdateResult.Interrupted
        }

        assertEquals(ListenableWorker.Result.success().toString(), result.toString())
        assertFalse(invoked)
        assertEquals(OperationStatus.CANCELLED, delegate.read().single().status)
    }

    @Test
    fun `worker stop reason distinguishes exact user request from system interruption`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-stop-reason"), AttemptId(1))
        val binding = workManagerBinding("stop-reason-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        assertEquals(
            GhostUpdateStopReason.SYSTEM_INTERRUPTED,
            GhostUpdateWorker.stopReason(supervisor, handle, binding) { true },
        )
        assertTrue(supervisor.requestStop(handle))
        assertEquals(
            GhostUpdateStopReason.USER_CANCELLED,
            GhostUpdateWorker.stopReason(supervisor, handle, binding) { true },
        )
    }

    @Test
    fun `worker upgrades interrupted result when exact cancellation wins the stop race`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-interrupted-cancel-race"), AttemptId(1))
        val binding = workManagerBinding("interrupted-cancel-race-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        val result = GhostUpdateWorker.execute(supervisor, handle, binding, { true }) {
            assertTrue(supervisor.requestStop(handle))
            GhostUpdateResult.Interrupted
        }

        assertEquals(ListenableWorker.Result.success().toString(), result.toString())
        assertEquals(OperationStatus.CANCELLED, durableStore.read().single().status)
    }

    @Test
    fun `worker stop reason rechecks cancellation after WorkManager stop becomes visible`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-stop-race"), AttemptId(1))
        val binding = workManagerBinding("stop-race-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        val reason = GhostUpdateWorker.stopReason(supervisor, handle, binding) {
            assertTrue(supervisor.requestStop(handle))
            true
        }

        assertEquals(GhostUpdateStopReason.USER_CANCELLED, reason)
    }

    @Test
    fun `SHIORI adapter uses official update indexes and failure spelling`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val events = ShioriGhostUpdateEvents { name, references -> calls += name to references }
        val files = listOf("ghost/master.txt", "shell/master.txt")

        events.ready(files)
        events.downloadBegin(files[0], 0, 1)
        events.failure("timeout", files)

        assertEquals(
            listOf(
                "OnUpdateReady" to listOf("1", "ghost/master.txt,shell/master.txt"),
                "OnUpdate.OnDownloadBegin" to listOf("ghost/master.txt", "0", "1"),
                "OnUpdateFailure" to listOf("timeout", "ghost/master.txt,shell/master.txt"),
            ),
            calls,
        )
    }

    @Test
    fun `full manifest downloads and reports only files whose local digest differs`() {
        val fixture = fixture("delta")
        fixture.writeLive("ghost/matching.txt", "same")
        fixture.writeLive("ghost/changed.txt", "old")
        fixture.network.manifest(
            "ghost/matching.txt" to bytes("same"),
            "ghost/changed.txt" to bytes("new"),
        )
        val events = RecordingEvents()

        val result = fixture.repository(events = events).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/changed.txt")), result)
        assertFalse(fixture.network.openedPaths.drop(1).contains("ghost/matching.txt"))
        assertTrue(fixture.network.openedPaths.contains("ghost/changed.txt"))
        assertEquals("ready:ghost/changed.txt", events.values.first())
        assertBytes("same", File(fixture.ghostRoot, "ghost/matching.txt"))
        assertBytes("new", File(fixture.ghostRoot, "ghost/changed.txt"))
    }

    @Test
    fun `all matching manifest is a no-change success without publishing candidate`() {
        val fixture = fixture("no-change")
        fixture.writeLive("ghost/master.txt", "same")
        fixture.network.manifest("ghost/master.txt" to bytes("same"))
        val events = RecordingEvents()
        val originalRoot = fixture.ghostRoot.canonicalFile

        val result = fixture.repository(events = events).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.NoChanges, result)
        assertEquals(listOf("complete:none"), events.values)
        assertEquals(originalRoot, fixture.ghostRoot.canonicalFile)
        assertBytes("same", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
        assertFalse(fixture.network.openedPaths.drop(1).contains("ghost/master.txt"))
    }

    @Test
    fun `failed no-change cleanup retains its journal phase that an exact retry clears`() {
        val fixture = fixture("no-change-cleanup-failure")
        fixture.writeLive("ghost/master.txt", "same")
        fixture.network.manifest("ghost/master.txt" to bytes("same"))
        val request = fixture.request().copy(attemptId = AttemptId(9), workManagerUuid = "work-9")
        var failCleanup = true
        val fileOperations = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (failCleanup && root.canonicalFile == File(fixture.transactionRoot(), "candidate").canonicalFile) {
                    return false
                }
                return root.deleteRecursively()
            }
        }
        val repository = fixture.repository(fileOperations = fileOperations)

        assertTrue(repository.run(request) { false } is GhostUpdateResult.Failed)
        val journal = GhostUpdateJournalStore.read(
            File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
        )
        assertEquals(CommitPhase.NO_CHANGES_PENDING, journal.phase)
        assertTrue(File(fixture.transactionRoot(), "candidate").isDirectory)

        failCleanup = false

        assertEquals(GhostUpdateResult.NoChanges, repository.run(request) { false })
        assertFalse(fixture.transactionRoot().exists())
        assertBytes("same", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `no-change cleanup recovers after candidate deletion but transaction deletion fails`() {
        val fixture = fixture("no-change-transaction-cleanup-failure")
        fixture.writeLive("ghost/master.txt", "same")
        fixture.network.manifest("ghost/master.txt" to bytes("same"))
        val request = fixture.request().copy(attemptId = AttemptId(9), workManagerUuid = "work-9")
        val repository = fixture.repository(
            fileOperations = object : GhostUpdateFileOperations {
                override fun deleteTree(root: File): Boolean {
                    val deleted = root.deleteRecursively()
                    if (root.canonicalFile == File(fixture.transactionRoot(), "candidate").canonicalFile) {
                        fixture.writeTransaction("interrupted-cleanup", "keep transaction root nonempty")
                    }
                    return deleted
                }
            },
        )

        assertTrue(repository.run(request) { false } is GhostUpdateResult.Failed)
        assertEquals(
            CommitPhase.NO_CHANGES_PENDING,
            GhostUpdateJournalStore.read(
                File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
            ).phase,
        )
        assertFalse(File(fixture.transactionRoot(), "candidate").exists())

        assertTrue(File(fixture.transactionRoot(), "interrupted-cleanup").delete())

        assertEquals(GhostUpdateResult.NoChanges, repository.run(request) { false })
        assertFalse(fixture.transactionRoot().exists())
        assertBytes("same", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `no-change cleanup failure never replaces its journal with prepared state`() {
        val fixture = fixture("no-change-cleanup-journal-write-failure")
        fixture.writeLive("ghost/master.txt", "same")
        fixture.network.manifest("ghost/master.txt" to bytes("same"))
        var writes = 0
        val journalIo = object : GhostUpdateJournalIo {
            override fun write(file: File, journal: GhostUpdateJournal) {
                writes++
                if (writes == 3) throw IOException("cannot rewrite no-change journal")
                GhostUpdateJournalStore.write(file, journal)
            }

            override fun read(file: File): GhostUpdateJournal = GhostUpdateJournalStore.read(file)
        }
        val repository = fixture.repository(
            fileOperations = object : GhostUpdateFileOperations {
                override fun deleteTree(root: File): Boolean {
                    val deleted = root.deleteRecursively()
                    if (root.canonicalFile == File(fixture.transactionRoot(), "candidate").canonicalFile) {
                        fixture.writeTransaction("residual", "blocks transaction cleanup")
                    }
                    return deleted
                }
            },
            journalIo = journalIo,
        )

        assertTrue(repository.run(fixture.request()) { false } is GhostUpdateResult.Failed)
        val journalFile = File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME)
        assertFalse(journalFile.isFile && GhostUpdateJournalStore.read(journalFile).phase == CommitPhase.PREPARED)
    }

    @Test
    fun `no-change completion payload survives process death before transaction cleanup`() {
        val fixture = fixture("no-change-terminal-before-cleanup")
        fixture.writeLive("ghost/master.txt", "same")
        fixture.network.manifest("ghost/master.txt" to bytes("same"))
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = workManagerBinding("no-change-terminal-before-cleanup")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Updating", 0, binding))

        try {
            fixture.repository(
                onNoChangesClassified = {
                    assertTrue(
                        GhostUpdateWorker.persistNoChangesTerminalEvent(
                            supervisor,
                            handle,
                            binding,
                            "configured-ghost-id",
                            fixture.ghostRoot,
                        ),
                    )
                    throw SimulatedProcessDeath()
                },
            ).run(
                fixture.request().copy(
                    attemptId = handle.attemptId,
                    workManagerUuid = binding.uuid,
                ),
            ) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The durable completion record and payload must precede staging cleanup.
        }

        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertEquals(
            GhostUpdateTerminalEvent(
                "configured-ghost-id",
                fixture.ghostRoot.canonicalPath,
                "OnUpdateComplete",
                listOf("none", ""),
            ),
            store.read().single().pendingGhostUpdateEvent,
        )
        assertTrue(fixture.transactionRoot().exists())
        val retainedJournal = GhostUpdateJournalStore.read(
            File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
        )
        assertEquals(CommitPhase.NO_CHANGES_PENDING, retainedJournal.phase)
        assertEquals("ghost-id", retainedJournal.ghostId)

        assertEquals(
            RecoveryResult.NoChangesCommit,
            GhostUpdateWorker.recoverBeforeGhostLoad(
                fixture.parent,
                fixture.ghostRoot,
                store,
                queryWork = { throw AssertionError("completed no-change recovery must not query WorkManager") },
                finish = { _, _ -> throw AssertionError("completed no-change recovery must not reclassify") },
            ),
        )
        assertFalse(fixture.transactionRoot().exists())
        assertEquals(
            GhostUpdateTerminalEvent(
                "configured-ghost-id",
                fixture.ghostRoot.canonicalPath,
                "OnUpdateComplete",
                listOf("none", ""),
            ),
            store.read().single().pendingGhostUpdateEvent,
        )
    }

    @Test
    fun `stop during journaled commit cannot overwrite completed transaction status`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-stop-commit"), AttemptId(1))
        val binding = workManagerBinding("commit-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        GhostUpdateWorker.execute(supervisor, handle, binding, { true }) {
            GhostUpdateWorker.workerStopped(supervisor, handle, binding, hasStarted = true)
            GhostUpdateResult.Completed(listOf("ghost/master.txt"))
        }

        assertEquals(OperationStatus.COMPLETED, durableStore.read().single().status)
    }

    @Test
    fun `system stop before doWork leaves exact queued attempt running`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-stop-before"), AttemptId(1))
        val binding = workManagerBinding("queued-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        GhostUpdateWorker.workerStopped(supervisor, handle, binding, hasStarted = false)

        assertEquals(OperationStatus.RUNNING, durableStore.read().single().status)
    }

    @Test
    fun `system stop after doWork starts leaves exact attempt running`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-system-stop-started"), AttemptId(1))
        val binding = workManagerBinding("system-stop-started-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Preparing candidate", 0, binding))

        GhostUpdateWorker.workerStopped(supervisor, handle, binding, hasStarted = true)

        assertEquals(OperationStatus.RUNNING, durableStore.read().single().status)
    }

    @Test
    fun `unstarted worker stop terminalizes only after exact user cancellation request`() {
        val durableStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("worker-user-stop"), AttemptId(1))
        val binding = workManagerBinding("user-stop-work")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        assertTrue(supervisor.requestStop(handle))

        GhostUpdateWorker.workerStopped(supervisor, handle, binding, hasStarted = false)

        assertEquals(OperationStatus.CANCELLED, durableStore.read().single().status)
    }

    @Test
    fun `started user stop stays requested until postcommit topology rolls forward`() {
        val fixture = fixture("started-user-stop-postcommit")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Published", 0, binding))
        assertTrue(supervisor.requestStop(handle))
        fixture.writeJournal(CommitPhase.PUBLISHED, emptyList(), handle.attemptId, binding.uuid)

        GhostUpdateWorker.workerStopped(supervisor, handle, binding, hasStarted = true)

        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
        val recovered = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.CANCELLED },
            finish = { journal, status ->
                GhostUpdateWorker.finishRecoveredTerminalEvent(supervisor, journal, status)
            },
        )

        assertEquals(RecoveryResult.CompletedCommit, recovered)
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `download progress remains cumulative across multiple files`() {
        val fixture = fixture("cumulative-progress")
        fixture.writeLive("ghost/first.txt", "old-one")
        fixture.writeLive("ghost/second.txt", "old-two")
        fixture.network.manifest(
            "ghost/first.txt" to ByteArray(20 * 1024) { 1 },
            "ghost/second.txt" to ByteArray(20 * 1024) { 2 },
        )
        val progress = mutableListOf<Long>()

        fixture.repository(onProgress = { phase, completed ->
            if (phase == "Downloading update") progress += completed
        }).run(fixture.request()) { false }

        assertTrue(progress.zipWithNext().all { (left, right) -> right >= left })
        assertEquals(40L * 1024L, progress.maxOrNull())
    }

    @Test
    fun `candidate copy and local digest comparison publish real byte heartbeats`() {
        val fixture = fixture("copy-hash-progress")
        val content = ByteArray(32 * 1024) { 3 }
        fixture.writeLiveBytes("ghost/master.txt", content)
        fixture.network.manifest("ghost/master.txt" to content)
        val progress = mutableMapOf<String, MutableList<Long>>()

        fixture.repository(onProgress = { phase, completed ->
            progress.getOrPut(phase) { mutableListOf() } += completed
        }).run(fixture.request()) { false }

        assertTrue(progress.getValue("Preparing candidate").any { it > 0 })
        assertTrue(progress.getValue("Comparing installed files").any { it > 0 })
    }

    @Test
    fun `published tree keeps journal and backup until durable completion succeeds`() {
        val fixture = fixture("terminal-before-cleanup")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var observedPublishedEvidence = false

        val interrupted = fixture.repository(onCommitClassified = {
            val journal = GhostUpdateJournalStore.read(
                File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
            )
            observedPublishedEvidence =
                journal.phase == CommitPhase.PUBLISHED && File(fixture.transactionRoot(), "backup").isDirectory
            false
        }).run(fixture.request()) { false }

        assertTrue(interrupted is GhostUpdateResult.PublishPending)
        assertTrue(observedPublishedEvidence)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())

        val resumed = fixture.repository(onCommitClassified = { true }).run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), resumed)
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `rollback classification failure never retries completion or cleans evidence`() {
        val fixture = fixture("rollback-classification-pending")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var completionClassifications = 0
        var rollbackClassifications = 0
        val failBeforeBackup = object : GhostUpdateFileOperations {
            override fun rename(source: File, destination: File): Boolean = false
        }

        val result = fixture.repository(
            fileOperations = failBeforeBackup,
            onCommitClassified = {
                completionClassifications += 1
                true
            },
            onRollbackClassified = {
                rollbackClassifications += 1
                false
            },
        ).run(fixture.request()) { false }

        assertEquals(
            GhostUpdateResult.RollbackPending(OperationStatus.FAILED, listOf("ghost/master.txt")),
            result,
        )
        assertEquals(0, completionClassifications)
        assertEquals(1, rollbackClassifications)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertBytes("new", File(fixture.transactionRoot(), "candidate/ghost/master.txt"))
        assertEquals(
            CommitPhase.PREPARED,
            GhostUpdateJournalStore.read(
                File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
            ).phase,
        )
    }

    @Test
    fun `worker leaves classification pending result durable and requests correct follow-up`() {
        listOf(
            GhostUpdateResult.PublishPending(emptyList()) to ListenableWorker.Result.retry(),
            GhostUpdateResult.RollbackPending(OperationStatus.FAILED, emptyList()) to
                ListenableWorker.Result.failure(),
        ).forEachIndexed { index, (pending, expected) ->
            val durableStore = SharedPreferencesDurableOperationStore(
                SharedPreferencesDurableOperationStore.MemoryStorage(),
            )
            val supervisor = DurableOperationSupervisor(durableStore, MonotonicClock { 0L }) { _, _, _ -> }
            val handle = OperationHandle(OperationId("pending-$index"), AttemptId(1))
            val binding = workManagerBinding("pending-work-$index")
            assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

            val result = GhostUpdateWorker.execute(supervisor, handle, binding, { false }) { pending }

            assertEquals(expected.toString(), result.toString())
            assertEquals(OperationStatus.RUNNING, durableStore.read().single().status)
        }
    }

    @Test
    fun `terminal persistence before cleanup failure leaves retryable published evidence`() {
        val fixture = fixture("terminal-cleanup-crash")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        var terminalPersisted = false
        val failingDelete = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (root.name == "backup") throw SimulatedProcessDeath()
                return root.deleteRecursively()
            }
        }

        try {
            fixture.repository(
                fileOperations = failingDelete,
                onCommitClassified = { terminalPersisted = true; true },
            ).run(fixture.request()) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // A process death is not catchable by repository recovery.
        }

        assertTrue(terminalPersisted)
        assertTrue(fixture.transactionRoot().exists())

        val resumed = fixture.repository(onCommitClassified = { true }).run(fixture.request()) { false }

        assertTrue(resumed is GhostUpdateResult.Completed)
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `failure terminal payload is persisted before rollback cleanup`() {
        val fixture = fixture("failure-terminal-before-cleanup")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = workManagerBinding("failure-terminal-before-cleanup")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Updating", 0, binding))
        val failBeforeBackup = object : GhostUpdateFileOperations {
            override fun rename(source: File, destination: File): Boolean = false
        }
        val crashAfterClassification = object : GhostUpdateJournalIo {
            override fun write(file: File, journal: GhostUpdateJournal) {
                if (journal.phase == CommitPhase.ROLLBACK_CLASSIFIED) throw SimulatedProcessDeath()
                GhostUpdateJournalStore.write(file, journal)
            }

            override fun read(file: File): GhostUpdateJournal = GhostUpdateJournalStore.read(file)
        }

        try {
            fixture.repository(
                fileOperations = failBeforeBackup,
                journalIo = crashAfterClassification,
                onRollbackJournalClassified = { journal, status ->
                    GhostUpdateWorker.persistRollbackTerminalEvent(
                        supervisor,
                        handle,
                        binding,
                        journal,
                        status,
                    )
                },
            ).run(fixture.request()) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The terminal failure record and payload must precede rollback cleanup.
        }

        assertEquals(OperationStatus.FAILED, store.read().single().status)
        assertEquals(
            GhostUpdateTerminalEvent(
                "ghost-id",
                fixture.ghostRoot.canonicalPath,
                "OnUpdateFailure",
                listOf("ghost update failed", "ghost/master.txt"),
            ),
            store.read().single().pendingGhostUpdateEvent,
        )
    }

    @Test
    fun `completion payload survives process death before transaction cleanup and clears after delivery`() {
        val fixture = fixture("terminal-event-before-cleanup")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.network.manifest("ghost/master.txt" to bytes("new"))
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = workManagerBinding("terminal-event-before-cleanup")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Updating", 0, binding))
        val failingDelete = object : GhostUpdateFileOperations {
            override fun deleteTree(root: File): Boolean {
                if (root.name == "backup") throw SimulatedProcessDeath()
                return root.deleteRecursively()
            }
        }

        try {
            fixture.repository(
                fileOperations = failingDelete,
                onCommitClassified = { completed ->
                    GhostUpdateWorker.persistCompletedTerminalEvent(
                        supervisor,
                        handle,
                        binding,
                        "configured-ghost-id",
                        fixture.ghostRoot,
                        completed,
                    )
                },
            ).run(fixture.request()) { false }
            throw AssertionError("simulated process death was not reached")
        } catch (_: SimulatedProcessDeath) {
            // The terminal record and payload must precede the cleanup side effect.
        }

        val event = GhostUpdateTerminalEvent(
            "configured-ghost-id",
            fixture.ghostRoot.canonicalPath,
            "OnUpdateComplete",
            listOf("changed", "ghost/master.txt"),
        )
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertEquals(event, store.read().single().pendingGhostUpdateEvent)
        assertTrue(
            GhostUpdateWorker.deliverPendingTerminalEvent(supervisor, "configured-ghost-id", fixture.ghostRoot) {
                assertEquals(event, it)
                true
            },
        )
        assertNull(store.read().single().pendingGhostUpdateEvent)
    }

    @Test
    fun `cold start cleans published evidence only for exact durable completion`() {
        val fixture = fixture("cold-terminal-cleanup")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        fixture.writeJournal(
            CommitPhase.PUBLISHED,
            listOf("ghost/master.txt"),
            AttemptId(7),
            "exact-work",
        )

        val stale = GhostUpdateRepository.recoverAllBeforeGhostLoad(
            fixture.parent,
            authorize = { _, _ -> RecoveryAuthorization.WAIT },
        )

        assertTrue(stale is RecoveryResult.CommitPending)
        assertTrue(fixture.transactionRoot().exists())

        val exact = GhostUpdateRepository.recoverAllBeforeGhostLoad(
            fixture.parent,
            authorize = { journal, _ ->
                if (journal.attemptId == AttemptId(7) && journal.workManagerUuid == "exact-work") {
                    RecoveryAuthorization.ROLL_FORWARD
                } else RecoveryAuthorization.FAIL_CLOSED
            },
            classify = { journal, status ->
                journal.attemptId == AttemptId(7) &&
                    journal.workManagerUuid == "exact-work" &&
                    status == OperationStatus.COMPLETED
            },
        )

        assertEquals(RecoveryResult.CompletedCommit, exact)
        assertFalse(fixture.transactionRoot().exists())
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `missing WorkInfo recreates the exact persisted UUID`() {
        val uuid = UUID.randomUUID()
        var reenqueued: UUID? = null

        val recovered = GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.RUNNING,
            uuid.toString(),
            journalExists = false,
            query = { GhostUpdateWorker.Companion.ObservedWork.MISSING },
            reenqueue = { reenqueued = it },
            terminalize = { _, _ -> throw AssertionError("must not terminalize") },
        )

        assertTrue(recovered)
        assertEquals(uuid, reenqueued)
    }

    @Test
    fun `orphan terminal and invalid bindings terminalize deterministically`() {
        val uuid = UUID.randomUUID()
        val terminal = mutableListOf<OperationStatus>()
        fun reconcile(observed: GhostUpdateWorker.Companion.ObservedWork) =
            GhostUpdateWorker.reconcileActiveAttempt(
                OperationStatus.RUNNING,
                uuid.toString(),
                journalExists = false,
                query = { observed },
                reenqueue = { throw AssertionError("must not enqueue") },
                terminalize = { status, _ -> terminal += status },
            )

        assertFalse(reconcile(GhostUpdateWorker.Companion.ObservedWork.CANCELLED))
        assertFalse(reconcile(GhostUpdateWorker.Companion.ObservedWork.FAILED))
        GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.RUNNING, "not-a-uuid", false,
            { GhostUpdateWorker.Companion.ObservedWork.ACTIVE }, {},
            { status, _ -> terminal += status },
        )
        GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.RUNNING, uuid.toString(), false,
            { throw IOException("query failed") }, {},
            { status, _ -> terminal += status },
        )

        assertEquals(
            listOf(OperationStatus.CANCELLED, OperationStatus.FAILED, OperationStatus.FAILED),
            terminal,
        )
    }

    @Test
    fun `cancel request cannot terminalize while commit journal exists`() {
        val terminal = mutableListOf<OperationStatus>()

        val recovered = GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.CANCEL_REQUESTED,
            UUID.randomUUID().toString(),
            journalExists = true,
            query = { GhostUpdateWorker.Companion.ObservedWork.CANCELLED },
            reenqueue = { throw AssertionError("must not enqueue") },
            terminalize = { status, _ -> terminal += status },
        )

        assertFalse(recovered)
        assertTrue(terminal.isEmpty())
    }

    @Test
    fun `same-session failed worker reconciles rollback journal before next attempt`() {
        val fixture = fixture("same-session-rollback")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        fixture.writeJournal(
            CommitPhase.PREPARED,
            listOf("ghost/master.txt"),
            handle.attemptId,
            binding.uuid,
        )
        var recoveries = 0

        val accepted = GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.RUNNING,
            binding.uuid,
            journalExists = true,
            query = { GhostUpdateWorker.Companion.ObservedWork.FAILED },
            reenqueue = { throw AssertionError("must not enqueue before recovery") },
            terminalize = { _, _ -> throw AssertionError("recovery owns classification") },
            recoverJournal = {
                recoveries += 1
                GhostUpdateRepository.recoverAllBeforeGhostLoad(
                    fixture.parent,
                    fixture.ghostRoot,
                    authorize = { _, _ -> RecoveryAuthorization.ROLL_BACK_FAILED },
                    classify = { _, status -> supervisor.finish(handle, binding, status) },
                )
            },
        )

        assertFalse(accepted)
        assertEquals(1, recoveries)
        assertEquals(OperationStatus.FAILED, store.read().single().status)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
        assertTrue(
            GhostUpdateWorker.reconcileBeforeAttemptRollover(
                OperationStatus.FAILED,
                journalExists = false,
            ) { throw AssertionError("no recovery evidence remains") },
        )
    }

    @Test
    fun `missing cancelled worker classifies and rolls back prepared journal`() {
        val fixture = fixture("missing-cancelled-worker")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        assertTrue(supervisor.requestStop(handle))
        fixture.writeJournal(CommitPhase.PREPARED, emptyList(), handle.attemptId, binding.uuid)

        GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.CANCEL_REQUESTED,
            binding.uuid,
            journalExists = true,
            query = { GhostUpdateWorker.Companion.ObservedWork.MISSING },
            reenqueue = { throw AssertionError("cancelled operation must not be re-enqueued") },
            terminalize = { _, _ -> throw AssertionError("recovery owns classification") },
            recoverJournal = {
                GhostUpdateRepository.recoverAllBeforeGhostLoad(
                    fixture.parent,
                    fixture.ghostRoot,
                    authorize = { journal, topology ->
                        when (GhostUpdateWorker.recoveryTransition(
                            journal.phase,
                            topology,
                            OperationStatus.CANCEL_REQUESTED,
                            exactIdentity = true,
                            GhostUpdateWorker.Companion.RecoveryWorkState.MISSING,
                        )) {
                            GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_CANCELLED ->
                                RecoveryAuthorization.ROLL_BACK_CANCELLED
                            else -> RecoveryAuthorization.FAIL_CLOSED
                        }
                    },
                    classify = { _, status -> supervisor.finish(handle, binding, status) },
                )
            },
        )

        assertEquals(OperationStatus.CANCELLED, store.read().single().status)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `succeeded WorkInfo without journal terminalizes invariant failure`() {
        var terminal: Pair<OperationStatus, String?>? = null

        GhostUpdateWorker.reconcileActiveAttempt(
            OperationStatus.RUNNING,
            UUID.randomUUID().toString(),
            journalExists = false,
            query = { GhostUpdateWorker.Companion.ObservedWork.SUCCEEDED },
            reenqueue = { throw AssertionError("must not enqueue") },
            terminalize = { status, diagnostic -> terminal = status to diagnostic },
        )

        assertEquals(OperationStatus.FAILED, terminal?.first)
        assertEquals("worker succeeded without durable repository classification", terminal?.second)
    }

    @Test
    fun `updates v3 ignores extension records while validating file records`() {
        val fixture = fixture("v3-extension")
        val content = bytes("new")
        fixture.network.rawManifestBytes(
            "updates.txt",
            (
                "charset,Shift_JIS\r\n" +
                    "extension,ssp-compatible\r\n" +
                    "file,ghost/master.txt\u0001${md5(content)}\u0001\r\n"
                ).toByteArray(Charset.forName("Windows-31J")),
        )
        fixture.network.file("ghost/master.txt", content)

        val result = fixture.repository().run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
    }

    @Test
    fun `updates v3 honors utf8 charset after ignored leading record`() {
        val fixture = fixture("v3-leading-extension-utf8")
        val content = bytes("new")
        val japanesePath = "ghost/日本語.txt"
        fixture.network.rawManifestBytes(
            "updates.txt",
            (
                "extension,ssp-compatible\r\n" +
                    "charset,UTF-8\r\n" +
                    "file,$japanesePath\u0001${md5(content)}\u0001\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        fixture.network.file(japanesePath, content)

        val result = fixture.repository().run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf(japanesePath)), result)
        assertBytes("new", File(fixture.ghostRoot, japanesePath))
    }

    @Test
    fun `manifest byte limit accepts limit and rejects limit plus one`() {
        val digest = md5(byteArrayOf())
        val record = "ghost/master.txt\u0001$digest"
        val accepted = fixture("manifest-limit")
        val acceptedBytes = record.padEnd(8 * 1024 * 1024, '\n').toByteArray()
        accepted.network.rawManifestBytes("updates2.dau", acceptedBytes)
        accepted.network.file("ghost/master.txt", byteArrayOf())

        assertTrue(accepted.repository().run(accepted.request()) { false } is GhostUpdateResult.Completed)

        val rejected = fixture("manifest-over-limit")
        rejected.network.rawManifestBytes("updates2.dau", ByteArray(8 * 1024 * 1024 + 1) { 'x'.code.toByte() })

        assertTrue(rejected.repository().run(rejected.request()) { false } is GhostUpdateResult.Failed)
        assertFalse(rejected.transactionRoot().exists())
    }

    @Test
    fun `delete manifest streams bounded lines and rejects cap plus one`() {
        val root = temporaryDirectory("delete-manifest-bounds")
        val file = File(root, "delete.txt")
        write(file, ByteArray(GhostUpdateRepository.MAX_DELETE_BYTES))

        var byteCapLines = 0
        GhostUpdateRepository.forEachDeleteManifestLine(file, Charsets.UTF_8) {
            byteCapLines += 1
        }
        assertEquals(1, byteCapLines)

        write(file, ByteArray(GhostUpdateRepository.MAX_DELETE_BYTES + 1))
        try {
            GhostUpdateRepository.forEachDeleteManifestLine(file, Charsets.UTF_8) {}
            throw AssertionError("delete manifest accepted byte cap plus one")
        } catch (_: IOException) {
            // Exact byte cap is enforced while streaming.
        }

        val atLineCap = List(GhostUpdateRepository.MAX_DELETE_LINES) { "x" }
            .joinToString("\n")
            .toByteArray()
        write(file, atLineCap)
        var lineCapCount = 0
        GhostUpdateRepository.forEachDeleteManifestLine(file, Charsets.UTF_8) {
            lineCapCount += 1
        }
        assertEquals(GhostUpdateRepository.MAX_DELETE_LINES, lineCapCount)

        val overLineCap = List(GhostUpdateRepository.MAX_DELETE_LINES + 1) { "x" }
            .joinToString("\n")
            .toByteArray()
        try {
            write(file, overLineCap)
            GhostUpdateRepository.forEachDeleteManifestLine(file, Charsets.UTF_8) {}
            throw AssertionError("delete manifest accepted line cap plus one")
        } catch (_: IOException) {
            // Exact line cap is enforced before path resolution.
        }
    }

    @Test
    fun `malformed gzip construction closes input and disconnects`() {
        val connection = mockk<HttpsURLConnection>(relaxed = true)
        val input = TrackingInputStream(ByteArrayInputStream(bytes("not-gzip")))
        every { connection.inputStream } returns input
        every { connection.contentEncoding } returns "gzip"

        try {
            NetworkUtil.responseStream(connection)
            throw AssertionError("malformed gzip was accepted")
        } catch (_: IOException) {
            // Expected construction failure.
        }

        assertTrue(input.closed)
        verify(exactly = 1) { connection.disconnect() }
    }

    @Test
    fun `update events never cross a mid-update ghost switch`() {
        var currentGhost: String? = "ghost-a"
        val delivered = mutableListOf<String>()
        val sink = GhostBoundEventSink("ghost-a", { expected, name, _ ->
            if (currentGhost != expected) false else {
                delivered += name
                true
            }
        })

        sink.send("OnUpdateReady", emptyList())
        currentGhost = "ghost-b"
        sink.send("OnUpdateComplete", emptyList())
        currentGhost = null
        sink.send("OnUpdateFailure", emptyList())

        assertEquals(listOf("OnUpdateReady"), delivered)
    }

    @Test
    fun `prepared old-live journal allows construction while retaining evidence`() {
        val fixture = fixture("prepared-bootable")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.PREPARED, listOf("ghost/master.txt"))
        var constructed = false

        val (recovery, value) = GhostUpdateRepository.withRecoveredGhostRoot(fixture.ghostRoot) {
            constructed = true
            "ghost"
        }

        assertTrue(recovery is RecoveryResult.CommitPending)
        assertEquals("ghost", value)
        assertTrue(constructed)
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `blocked update root does not block unrelated ghost root`() {
        val storage = temporaryDirectory("ghost-update-isolation")
        val blockedRoot = File(storage, "blocked").apply { mkdirs() }
        val healthyRoot = File(storage, "healthy").apply { mkdirs() }
        write(File(blockedRoot, "ghost/master.txt"), bytes("new"))
        write(File(healthyRoot, "ghost/master.txt"), bytes("healthy"))
        val operation = OperationId("blocked-operation")
        val transaction = GhostUpdateRepository.transactionRootFor(blockedRoot, operation)
        write(File(transaction, "backup/ghost/master.txt"), bytes("old"))
        GhostUpdateJournalStore.write(
            File(transaction, GhostUpdateJournalStore.FILE_NAME),
            GhostUpdateJournal(
                operation,
                blockedRoot.canonicalPath,
                File(transaction, "candidate").canonicalPath,
                File(transaction, "backup").canonicalPath,
                CommitPhase.PUBLISHED,
                listOf("ghost/master.txt"),
            ),
        )

        val blocked = GhostUpdateRepository.blockedGhostRoots(storage)

        assertEquals(setOf(blockedRoot.canonicalFile), blocked)
        assertFalse(healthyRoot.canonicalFile in blocked)
    }

    @Test
    fun `corrupt expected journal blocks only its target ghost`() {
        val storage = temporaryDirectory("ghost-update-corrupt-isolation")
        val corruptRoot = File(storage, "corrupt").apply { mkdirs() }
        val healthyRoot = File(storage, "healthy").apply { mkdirs() }
        write(File(corruptRoot, "ghost/master.txt"), bytes("new"))
        write(File(healthyRoot, "ghost/master.txt"), bytes("healthy"))
        val transaction = GhostUpdateRepository.transactionRootFor(
            corruptRoot,
            GhostUpdateRepository.canonicalOperationIdFor(corruptRoot),
        )
        write(File(transaction, "backup/ghost/master.txt"), bytes("old"))
        write(File(transaction, GhostUpdateJournalStore.FILE_NAME), bytes("corrupt"))
        var healthyConstructed = false

        val (_, healthy) = GhostUpdateRepository.withRecoveredGhostRoot(healthyRoot) {
            healthyConstructed = true
            "healthy"
        }
        val blocked = GhostUpdateRepository.blockedGhostRoots(storage)

        assertEquals("healthy", healthy)
        assertTrue(healthyConstructed)
        assertTrue(corruptRoot.canonicalFile in blocked)
        assertFalse(healthyRoot.canonicalFile in blocked)
    }

    @Test
    fun `corrupt durable store fails closed for affected journal without blocking healthy ghost`() {
        val fixture = fixture("corrupt-durable-store")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(
            CommitPhase.PREPARED,
            emptyList(),
            AttemptId(1),
            UUID.randomUUID().toString(),
        )
        val healthy = File(fixture.parent, "healthy").apply { mkdirs() }
        write(File(healthy, "ghost/master.txt"), bytes("healthy"))
        val corruptStore = object : DurableOperationStore {
            override fun read(): List<DurableOperationRecord> =
                throw DurableOperationStoreCorruptionException("corrupt durable operation store")

            override fun putIfAbsent(record: DurableOperationRecord) = false

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ) = false
        }
        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            corruptStore,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE },
            finish = { _, _ -> throw AssertionError("corrupt store must fail before classification") },
        )
        var healthyConstructed = false
        val (_, healthyValue) = GhostUpdateRepository.withRecoveredGhostRoot(healthy) {
            healthyConstructed = true
            "healthy"
        }

        assertTrue(recovery is RecoveryResult.Failed)
        assertTrue(fixture.transactionRoot().exists())
        assertEquals("healthy", healthyValue)
        assertTrue(healthyConstructed)
        assertTrue(
            GhostUpdateWorker.readAttemptForEnqueue(corruptStore, fixture.operationId).isFailure,
        )
    }

    @Test
    fun `wrong durable operation kind cannot authorize ghost filesystem recovery`() {
        val fixture = fixture("wrong-operation-kind")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        val attempt = AttemptId(1)
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        fixture.writeJournal(CommitPhase.PREPARED, emptyList(), attempt, binding.uuid)
        val wrongKind = DurableOperationRecord(
            fixture.operationId,
            attempt,
            OperationKind.NAR_INSTALL,
            binding,
            OperationProgress("Installing", 0),
            OperationStatus.FAILED,
            showStallPrompt = false,
        )
        val wrongStore = object : DurableOperationStore {
            override fun read() = listOf(wrongKind)
            override fun putIfAbsent(record: DurableOperationRecord) = false
            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ) = false
        }

        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            wrongStore,
            queryWork = { throw AssertionError("wrong kind must fail before WorkManager query") },
            finish = { _, _ -> throw AssertionError("wrong kind must not classify") },
        )

        assertTrue(recovery is RecoveryResult.Failed)
        assertTrue(GhostUpdateWorker.readAttemptForEnqueue(wrongStore, fixture.operationId).isFailure)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `WorkManager observation timeout retains evidence without cancelling future`() {
        val neverCompletes = CompletableFuture<WorkInfo?>()
        val started = System.nanoTime()

        val observed = GhostUpdateWorker.observeRecoveryWork(neverCompletes, timeoutMillis = 10)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(GhostUpdateWorker.Companion.RecoveryWorkState.QUERY_ERROR, observed)
        assertTrue(elapsedMillis < 1_000)
        assertFalse(neverCompletes.isCancelled)
        assertFalse(neverCompletes.isDone)
    }

    @Test
    fun `no-journal recovery follows exact durable and WorkManager terminal matrix`() {
        data class Row(
            val label: String,
            val durable: OperationStatus,
            val observed: GhostUpdateWorker.Companion.RecoveryWorkState,
            val expectedRecovery: GhostUpdateWorker.Companion.NoJournalRecovery,
            val expectedDurable: OperationStatus,
        )
        listOf(
            Row("active running", OperationStatus.RUNNING, GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE, GhostUpdateWorker.Companion.NoJournalRecovery.RETRY, OperationStatus.RUNNING),
            Row("query error", OperationStatus.RUNNING, GhostUpdateWorker.Companion.RecoveryWorkState.QUERY_ERROR, GhostUpdateWorker.Companion.NoJournalRecovery.RETRY, OperationStatus.RUNNING),
            Row("stopped missing", OperationStatus.CANCEL_REQUESTED, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.CANCELLED),
            Row("stopped cancelled", OperationStatus.CANCEL_REQUESTED, GhostUpdateWorker.Companion.RecoveryWorkState.CANCELLED, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.CANCELLED),
            Row("stopped failed", OperationStatus.CANCEL_REQUESTED, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.FAILED),
            Row("stopped succeeded", OperationStatus.CANCEL_REQUESTED, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.FAILED),
            Row("running missing", OperationStatus.RUNNING, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.FAILED),
            Row("running cancelled", OperationStatus.RUNNING, GhostUpdateWorker.Companion.RecoveryWorkState.CANCELLED, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.FAILED),
            Row("running failed", OperationStatus.RUNNING, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.FAILED),
            Row("running succeeded", OperationStatus.RUNNING, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE, OperationStatus.FAILED),
        ).forEachIndexed { index, row ->
            val store = SharedPreferencesDurableOperationStore(
                SharedPreferencesDurableOperationStore.MemoryStorage(),
            )
            val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
            val handle = OperationHandle(OperationId("no-journal-$index"), AttemptId(1))
            val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
            assertTrue(row.label, supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
            if (row.durable == OperationStatus.CANCEL_REQUESTED) {
                assertTrue(row.label, supervisor.requestStop(handle))
            }

            val recovery = GhostUpdateWorker.reconcileNoJournalAttempt(
                store,
                supervisor,
                handle.operationId,
            ) { row.observed }

            assertEquals(row.label, row.expectedRecovery, recovery)
            assertEquals(row.label, row.expectedDurable, store.read().single().status)
        }
    }

    @Test
    fun `active no-journal attempt schedules only its canonical ghost target`() {
        val storage = temporaryDirectory("active-no-journal-target")
        val target = File(storage, "target").apply { mkdirs() }
        val unrelated = File(storage, "unrelated").apply { mkdirs() }
        val operation = GhostUpdateRepository.canonicalOperationIdFor(target)
        val record = DurableOperationRecord(
            operation,
            AttemptId(1),
            OperationKind.GHOST_UPDATE,
            ExternalJobBinding.WorkManager(UUID.randomUUID().toString()),
            OperationProgress("Stopping", 0),
            OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = false,
        )

        val targets = GhostUpdateWorker.pendingAttemptRecoveryTargets(storage, listOf(record))

        assertEquals(setOf(target.canonicalFile), targets)
        assertFalse(unrelated.canonicalFile in targets)
    }

    @Test
    fun `journal writer accepts reader cap and rejects cap plus one`() {
        val root = temporaryDirectory("journal-file-cap")
        val file = File(root, GhostUpdateJournalStore.FILE_NAME)
        val base = GhostUpdateJournal(
            OperationId("journal-cap"),
            File(root, "live").canonicalPath,
            File(root, "candidate").canonicalPath,
            File(root, "backup").canonicalPath,
            CommitPhase.PREPARED,
            emptyList(),
        )
        val atCap = List(GhostUpdateJournalStore.MAX_FILES) { "f" }

        GhostUpdateJournalStore.write(file, base.copy(files = atCap))
        assertEquals(GhostUpdateJournalStore.MAX_FILES, GhostUpdateJournalStore.read(file).files.size)

        try {
            GhostUpdateJournalStore.write(
                file,
                base.copy(files = List(GhostUpdateJournalStore.MAX_FILES + 1) { "f" }),
            )
            throw AssertionError("journal writer accepted cap plus one")
        } catch (_: IOException) {
            // Exact writer/reader cap is enforced before replacing durable evidence.
        }
        assertEquals(GhostUpdateJournalStore.MAX_FILES, GhostUpdateJournalStore.read(file).files.size)
    }

    @Test
    fun `private ownership marker does not use a fixed live-content path`() {
        val root = temporaryDirectory("interrupted-preparing-marker")
        val journal = GhostUpdateJournal(
            OperationId("operation"),
            File(root, "live").canonicalPath,
            File(root, "candidate").canonicalPath,
            File(root, "backup").canonicalPath,
            CommitPhase.PREPARED,
            emptyList(),
        )

        val marker = GhostUpdateJournalStore.createPrivateMarker(root, journal)

        assertTrue(marker.parentFile == root)
        assertTrue(marker.name.startsWith(".nanidroid-update-owner-"))
        assertEquals(journal, GhostUpdateJournalStore.read(marker))
    }

    @Test
    fun `recovery cannot reclaim a private marker while its journal is being written`() {
        val root = temporaryDirectory("private-marker-write-race")
        val journal = GhostUpdateJournal(
            OperationId("operation"),
            File(root, "live").canonicalPath,
            File(root, "candidate").canonicalPath,
            File(root, "backup").canonicalPath,
            CommitPhase.PREPARED,
            emptyList(),
        )

        val marker = GhostUpdateJournalStore.createPrivateMarker(root, journal, { writing ->
            assertTrue(writing.name.startsWith(".nanidroid-update-writing-"))
            assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(root))
            assertTrue(writing.exists())
        })

        assertTrue(marker.exists())
        assertEquals(journal, GhostUpdateJournalStore.read(marker))
    }

    @Test
    fun `recovery cannot reclaim a private marker after writing before publication`() {
        val root = temporaryDirectory("private-marker-publication-race")
        val journal = GhostUpdateJournal(
            OperationId("operation"),
            File(root, "live").canonicalPath,
            File(root, "candidate").canonicalPath,
            File(root, "backup").canonicalPath,
            CommitPhase.PREPARED,
            emptyList(),
        )

        val marker = GhostUpdateJournalStore.createPrivateMarker(root, journal, {}, { writing ->
            assertTrue(writing.exists())
            assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(root))
            assertTrue(writing.exists())
        })

        assertTrue(marker.exists())
        assertEquals(journal, GhostUpdateJournalStore.read(marker))
    }

    @Test
    fun `startup recovery preserves an interrupted private marker write temporary`() {
        val root = temporaryDirectory("interrupted-private-marker-write")
        val writing = File.createTempFile(".nanidroid-update-writing-", ".tmp", root)

        assertEquals(RecoveryResult.NoJournal, GhostUpdateRepository.recoverAllBeforeGhostLoad(root))

        assertTrue(writing.exists())
    }

    @Test
    fun `terminal published recovery skips WorkManager observation`() {
        val fixture = fixture("terminal-no-work-query")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Published", 0, binding))
        assertTrue(supervisor.finish(handle, binding, OperationStatus.COMPLETED))
        fixture.writeJournal(CommitPhase.PUBLISHED, emptyList(), handle.attemptId, binding.uuid)

        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { throw AssertionError("terminal recovery must not query WorkManager") },
            finish = { _, _ -> throw AssertionError("already terminal record must not be rewritten") },
        )

        assertEquals(RecoveryResult.CompletedCommit, recovery)
        assertFalse(fixture.transactionRoot().exists())
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
    }

    @Test
    fun `boot retains active prepared evidence without query and later async reconciliation resolves`() {
        val fixture = fixture("boot-zero-query-later-reconcile")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Preparing candidate", 0, binding))
        fixture.writeJournal(CommitPhase.PREPARED, emptyList(), handle.attemptId, binding.uuid)
        assertTrue(File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME).isFile)

        val bootRecovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = null,
            finish = { _, _ -> throw AssertionError("boot must retain active evidence") },
        )
        val (_, booted) = GhostUpdateRepository.withRecoveredGhostRoot(fixture.ghostRoot) {
            "old-ghost"
        }

        assertTrue("unexpected boot recovery: $bootRecovery", bootRecovery is RecoveryResult.CommitPending)
        assertEquals("old-ghost", booted)
        assertEquals(OperationStatus.RUNNING, store.read().single().status)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())

        val activeAsyncRecovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE },
            finish = { _, _ -> throw AssertionError("active work must not be terminalized") },
        )
        assertTrue(activeAsyncRecovery is RecoveryResult.CommitPending)
        assertEquals(OperationStatus.RUNNING, store.read().single().status)
        assertTrue(fixture.transactionRoot().exists())

        val laterRecovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.FAILED },
            finish = { _, status -> supervisor.finish(handle, binding, status) },
        )

        assertEquals(RecoveryResult.RolledBack, laterRecovery)
        assertEquals(OperationStatus.FAILED, store.read().single().status)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
    }

    @Test
    fun `active or unavailable work retains cleanup-only cancellation evidence`() {
        listOf(
            GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE,
            GhostUpdateWorker.Companion.RecoveryWorkState.QUERY_ERROR,
        ).forEachIndexed { index, workState ->
            val fixture = fixture("cleanup-only-wait-$index")
            fixture.writeLive("ghost/master.txt", "old")
            val store = SharedPreferencesDurableOperationStore(
                SharedPreferencesDurableOperationStore.MemoryStorage(),
            )
            val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
            val handle = OperationHandle(fixture.operationId, AttemptId(1))
            val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
            assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Stopping", 0, binding))
            assertTrue(supervisor.requestStop(handle))
            fixture.writeJournal(CommitPhase.PREPARED, emptyList(), handle.attemptId, binding.uuid)

            val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
                fixture.parent,
                fixture.ghostRoot,
                store,
                queryWork = { workState },
                finish = { _, _ -> throw AssertionError("active cleanup must not terminalize") },
            )

            assertEquals(workState.name, RecoveryResult.CommitPending(emptyList()), recovery)
            assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
            assertTrue(fixture.transactionRoot().exists())
        }
    }

    @Test
    fun `recovery work identity is stable and target scoped`() {
        val storage = temporaryDirectory("recovery-work-name")
        val first = File(storage, "first").apply { mkdirs() }
        val second = File(storage, "second").apply { mkdirs() }

        assertEquals(
            GhostUpdateWorker.recoveryWorkName(storage, null),
            GhostUpdateWorker.recoveryWorkName(storage, null),
        )
        assertEquals(
            GhostUpdateWorker.recoveryWorkName(storage, first),
            GhostUpdateWorker.recoveryWorkName(storage, first),
        )
        assertTrue(
            GhostUpdateWorker.recoveryWorkName(storage, first) !=
                GhostUpdateWorker.recoveryWorkName(storage, second),
        )
    }

    @Test
    fun `corrupt target cannot starve unrelated terminal target recovery`() {
        val storage = temporaryDirectory("per-target-recovery-isolation")
        val corrupt = File(storage, "corrupt").apply { mkdirs() }
        val terminal = File(storage, "terminal").apply { mkdirs() }
        write(File(corrupt, "ghost/master.txt"), bytes("corrupt-old"))
        write(File(terminal, "ghost/master.txt"), bytes("terminal-new"))
        val corruptOperation = GhostUpdateRepository.canonicalOperationIdFor(corrupt)
        val corruptTransaction = GhostUpdateRepository.transactionRootFor(corrupt, corruptOperation)
        write(File(corruptTransaction, GhostUpdateJournalStore.FILE_NAME), bytes("corrupt"))

        val terminalOperation = GhostUpdateRepository.canonicalOperationIdFor(terminal)
        val terminalTransaction = GhostUpdateRepository.transactionRootFor(terminal, terminalOperation)
        write(File(terminalTransaction, "backup/ghost/master.txt"), bytes("terminal-old"))
        val attempt = AttemptId(1)
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        GhostUpdateJournalStore.write(
            File(terminalTransaction, GhostUpdateJournalStore.FILE_NAME),
            GhostUpdateJournal(
                terminalOperation,
                terminal.canonicalPath,
                File(terminalTransaction, "candidate").canonicalPath,
                File(terminalTransaction, "backup").canonicalPath,
                CommitPhase.PUBLISHED,
                emptyList(),
                attempt,
                binding.uuid,
            ),
        )
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(terminalOperation, attempt)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Published", 0, binding))
        assertTrue(supervisor.finish(handle, binding, OperationStatus.COMPLETED))

        val targets = GhostUpdateRepository.recoveryTargets(storage)
        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            storage,
            terminal,
            store,
            queryWork = { throw AssertionError("terminal recovery must not query WorkManager") },
            finish = { _, _ -> throw AssertionError("already terminal") },
        )

        assertEquals(setOf(terminal.canonicalFile), targets)
        assertEquals(RecoveryResult.CompletedCommit, recovery)
        assertTrue(corruptTransaction.exists())
        assertFalse(terminalTransaction.exists())
        assertBytes("terminal-new", File(terminal, "ghost/master.txt"))
    }

    @Test
    fun `foreign-root journal in expected directory blocks actual owner only`() {
        val storage = temporaryDirectory("ghost-update-foreign-journal")
        val owner = File(storage, "owner").apply { mkdirs() }
        val foreign = File(storage, "foreign").apply { mkdirs() }
        write(File(owner, "ghost/master.txt"), bytes("owner"))
        write(File(foreign, "ghost/master.txt"), bytes("foreign"))
        val operation = GhostUpdateRepository.canonicalOperationIdFor(owner)
        val transaction = GhostUpdateRepository.transactionRootFor(owner, operation)
        write(File(transaction, "candidate/ghost/master.txt"), bytes("candidate"))
        GhostUpdateJournalStore.write(
            File(transaction, GhostUpdateJournalStore.FILE_NAME),
            GhostUpdateJournal(
                operation,
                foreign.canonicalPath,
                File(transaction, "candidate").canonicalPath,
                File(transaction, "backup").canonicalPath,
                CommitPhase.PREPARED,
                listOf("ghost/master.txt"),
            ),
        )

        val blocked = GhostUpdateRepository.blockedGhostRoots(storage)

        assertTrue(owner.canonicalFile in blocked)
        assertFalse(foreign.canonicalFile in blocked)
    }

    @Test
    fun `target recovery rejects foreign root journal and blocks attempt rollover`() {
        val storage = temporaryDirectory("ghost-update-foreign-target")
        val owner = File(storage, "owner").apply { mkdirs() }
        val foreign = File(storage, "foreign").apply { mkdirs() }
        write(File(owner, "ghost/master.txt"), bytes("owner-old"))
        write(File(foreign, "ghost/master.txt"), bytes("foreign-old"))
        val operation = GhostUpdateRepository.canonicalOperationIdFor(owner)
        val transaction = GhostUpdateRepository.transactionRootFor(owner, operation)
        write(File(transaction, "candidate/ghost/master.txt"), bytes("owner-new"))
        GhostUpdateJournalStore.write(
            File(transaction, GhostUpdateJournalStore.FILE_NAME),
            GhostUpdateJournal(
                operation,
                foreign.canonicalPath,
                File(transaction, "candidate").canonicalPath,
                File(transaction, "backup").canonicalPath,
                CommitPhase.PREPARED,
                listOf("ghost/master.txt"),
            ),
        )

        val recovery = GhostUpdateRepository.recoverAllBeforeGhostLoad(
            storage,
            owner,
            authorize = { _, _ -> RecoveryAuthorization.ROLL_BACK_FAILED },
            classify = { _, _ -> true },
        )
        val mayStart = GhostUpdateWorker.reconcileBeforeAttemptRollover(
            OperationStatus.FAILED,
            journalExists = true,
        ) { recovery }

        assertTrue(recovery is RecoveryResult.Failed)
        assertFalse(mayStart)
        assertBytes("owner-old", File(owner, "ghost/master.txt"))
        assertBytes("foreign-old", File(foreign, "ghost/master.txt"))
        assertTrue(transaction.exists())
    }

    @Test
    fun `exact replay after live backup finishes commit without redownload`() {
        val fixture = fixture("replay-post-commit")
        assertTrue(fixture.ghostRoot.delete())
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.PREPARED, listOf("ghost/master.txt"))

        val result = fixture.repository().run(fixture.request()) { false }

        assertEquals(GhostUpdateResult.Completed(listOf("ghost/master.txt")), result)
        assertBytes("new", File(fixture.ghostRoot, "ghost/master.txt"))
        assertFalse(fixture.transactionRoot().exists())
        assertTrue(fixture.network.openedPaths.isEmpty())
    }

    @Test
    fun `stale replay identity retains journal and blocks attempt rollover`() {
        val fixture = fixture("stale-replay")
        fixture.writeLive("ghost/master.txt", "old")
        fixture.writeTransaction("candidate/ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.PREPARED, listOf("ghost/master.txt"), AttemptId(1), "old-work")

        val result = fixture.repository().run(
            fixture.request().copy(attemptId = AttemptId(2), workManagerUuid = "new-work"),
        ) { false }

        assertTrue(result is GhostUpdateResult.Failed)
        assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
        assertTrue(fixture.transactionRoot().exists())
    }

    @Test
    fun `invalid no-change replay does not publish a completion event`() {
        val fixture = fixture("invalid-no-change-replay")
        fixture.writeLive("ghost/master.txt", "same")
        fixture.writeTransaction("candidate/ghost/master.txt", "same")
        fixture.writeJournal(
            CommitPhase.NO_CHANGES_PENDING,
            listOf("../invalid"),
            AttemptId(1),
            "work-1",
        )
        var classifications = 0

        val result = fixture.repository(
            onNoChangesClassified = {
                classifications++
                true
            },
        ).run(
            fixture.request().copy(attemptId = AttemptId(1), workManagerUuid = "work-1"),
        ) { false }

        assertTrue(result is GhostUpdateResult.Failed)
        assertEquals(0, classifications)
    }

    @Test
    fun `terminal attempt journal blocks rollover until exact recovery completes`() {
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0 }) { _, _, _ -> }
        val handle = OperationHandle(OperationId("rollover"), AttemptId(4))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        assertTrue(supervisor.finish(handle, binding, OperationStatus.COMPLETED))
        var recoveries = 0

        val mayStart = GhostUpdateWorker.reconcileBeforeAttemptRollover(
            OperationStatus.COMPLETED,
            journalExists = true,
        ) {
            recoveries += 1
            RecoveryResult.CommitPending(emptyList())
        }

        assertFalse(mayStart)
        assertEquals(1, recoveries)
        assertEquals(AttemptId(4), store.read().single().attemptId)
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
    }

    @Test
    fun `completed no-change recovery permits attempt rollover`() {
        assertTrue(
            GhostUpdateWorker.reconcileBeforeAttemptRollover(
                OperationStatus.COMPLETED,
                journalExists = true,
            ) { RecoveryResult.NoChangesCommit },
        )
    }

    @Test
    fun `published recovery defers completion event for its exact terminal attempt`() {
        val fixture = fixture("recovery-terminal-event")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(5))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Published", 0, binding))
        fixture.writeJournal(
            CommitPhase.PUBLISHED,
            listOf("ghost/master.txt"),
            handle.attemptId,
            binding.uuid,
            ghostId = "configured-ghost-id",
        )

        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.FAILED },
            finish = { journal, status ->
                GhostUpdateWorker.finishRecoveredTerminalEvent(supervisor, journal, status)
            },
            onClassified = { journal, status ->
                GhostUpdateWorker.deferRecoveredTerminalEvent(supervisor, journal, status)
            },
        )

        assertEquals(RecoveryResult.CompletedCommit, recovery)
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertEquals(
            GhostUpdateTerminalEvent(
                "configured-ghost-id",
                fixture.ghostRoot.canonicalPath,
                "OnUpdateComplete",
                listOf("changed", "ghost/master.txt"),
            ),
            store.read().single().pendingGhostUpdateEvent,
        )
    }

    @Test
    fun `all-roots recovery retries every pending terminal event root`() {
        val fixture = fixture("all-roots-terminal-delivery")
        val otherRoot = File(fixture.parent, "other-ghost").apply { check(mkdir()) }
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val firstHandle = OperationHandle(fixture.operationId, AttemptId(1))
        val firstBinding = workManagerBinding("first-work")
        assertTrue(supervisor.start(firstHandle, OperationKind.GHOST_UPDATE, "update", 0, firstBinding))
        assertTrue(
            supervisor.finishWithTerminalEvent(
                firstHandle,
                firstBinding,
                OperationStatus.COMPLETED,
                GhostUpdateTerminalEvent(
                    "ghost-id",
                    fixture.ghostRoot.canonicalPath,
                    "OnUpdateComplete",
                    emptyList(),
                ),
            ),
        )
        val secondHandle = OperationHandle(
            GhostUpdateRepository.canonicalOperationIdFor(otherRoot),
            AttemptId(1),
        )
        val secondBinding = workManagerBinding("second-work")
        assertTrue(supervisor.start(secondHandle, OperationKind.GHOST_UPDATE, "update", 0, secondBinding))
        assertTrue(
            supervisor.finishWithTerminalEvent(
                secondHandle,
                secondBinding,
                OperationStatus.FAILED,
                GhostUpdateTerminalEvent(
                    "other-ghost",
                    otherRoot.canonicalPath,
                    "OnUpdateFailure",
                    emptyList(),
                ),
            ),
        )

        assertEquals(
            setOf(fixture.ghostRoot.canonicalFile, otherRoot.canonicalFile),
            GhostUpdateWorker.recoveryDeliveryRoots(supervisor, null).toSet(),
        )
    }

    @Test
    fun `no-change recovery cleanup removes authenticated residual files`() {
        val fixture = fixture("no-change-recovery-partial-cleanup")
        fixture.writeTransaction("candidate/ghost/tmp.txt", "stale")
        fixture.writeTransaction("residual", "blocks root delete")
        fixture.writeJournal(CommitPhase.NO_CHANGES_PENDING, emptyList())
        val transaction = fixture.transactionRoot()
        val journalFile = File(transaction, GhostUpdateJournalStore.FILE_NAME)
        val journal = GhostUpdateJournalStore.read(journalFile)

        assertTrue(
            GhostUpdateRepository.cleanNoChangesRecoveryTransaction(
                transaction,
                journalFile,
                journal,
                File(transaction, "candidate"),
            ),
        )
        assertFalse(transaction.exists())
    }

    @Test
    fun `legacy journal without ghost identity does not defer a terminal event`() {
        val fixture = fixture("recovery-terminal-event-legacy")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(5))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Published", 0, binding))
        fixture.writeJournal(CommitPhase.PUBLISHED, listOf("ghost/master.txt"), handle.attemptId, binding.uuid)

        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.FAILED },
            finish = { _, status -> supervisor.finish(handle, binding, status) },
            onClassified = { journal, status ->
                GhostUpdateWorker.deferRecoveredTerminalEvent(supervisor, journal, status)
            },
        )

        assertEquals(RecoveryResult.CompletedCommit, recovery)
        assertNull(store.read().single().pendingGhostUpdateEvent)
    }

    @Test
    fun `recovery retains published journal when terminal event persistence fails`() {
        val fixture = fixture("recovery-terminal-event-persistence-failure")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeTransaction("backup/ghost/master.txt", "old")
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        val handle = OperationHandle(fixture.operationId, AttemptId(5))
        val binding = ExternalJobBinding.WorkManager(UUID.randomUUID().toString())
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Published", 0, binding))
        fixture.writeJournal(
            CommitPhase.PUBLISHED,
            listOf("ghost/master.txt"),
            handle.attemptId,
            binding.uuid,
            ghostId = "configured-ghost-id",
        )

        val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(
            fixture.parent,
            fixture.ghostRoot,
            store,
            queryWork = { GhostUpdateWorker.Companion.RecoveryWorkState.FAILED },
            finish = { _, status -> supervisor.finish(handle, binding, status) },
            onClassified = { _, _ -> false },
        )

        assertEquals(RecoveryResult.PublishPending(listOf("ghost/master.txt")), recovery)
        assertTrue(File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME).isFile)
    }

    @Test
    fun `rollback classified topologies restore old tree idempotently`() {
        listOf("candidate-backup", "live-backup", "live-candidate", "live-only").forEach { topology ->
            val fixture = fixture("rollback-$topology")
            when (topology) {
                "candidate-backup" -> {
                    assertTrue(fixture.ghostRoot.delete())
                    fixture.writeTransaction("candidate/ghost/master.txt", "new")
                    fixture.writeTransaction("backup/ghost/master.txt", "old")
                }
                "live-backup" -> {
                    fixture.writeLive("ghost/master.txt", "new")
                    fixture.writeTransaction("backup/ghost/master.txt", "old")
                }
                "live-candidate" -> {
                    fixture.writeLive("ghost/master.txt", "old")
                    fixture.writeTransaction("candidate/ghost/master.txt", "new")
                }
                "live-only" -> fixture.writeLive("ghost/master.txt", "old")
            }
            fixture.writeJournal(CommitPhase.ROLLBACK_CLASSIFIED, listOf("ghost/master.txt"))

            val result = GhostUpdateRepository.recoverAllBeforeGhostLoad(
                fixture.parent,
                authorize = { _, _ -> RecoveryAuthorization.ROLL_BACK_FAILED },
                classify = { _, status -> status == OperationStatus.FAILED },
            )

            assertEquals(topology, RecoveryResult.RolledBack, result)
            assertBytes("old", File(fixture.ghostRoot, "ghost/master.txt"))
            assertFalse(fixture.transactionRoot().exists())
        }
    }

    @Test
    fun `published after backup deletion cleans only after exact completion`() {
        val fixture = fixture("published-live-only")
        fixture.writeLive("ghost/master.txt", "new")
        fixture.writeJournal(CommitPhase.PUBLISHED, listOf("ghost/master.txt"))

        val pending = GhostUpdateRepository.recoverAllBeforeGhostLoad(
            fixture.parent,
            authorize = { _, _ -> RecoveryAuthorization.ROLL_FORWARD },
            classify = { _, _ -> false },
        )
        assertTrue(pending is RecoveryResult.PublishPending)
        assertTrue(fixture.transactionRoot().exists())

        val completed = GhostUpdateRepository.recoverAllBeforeGhostLoad(
            fixture.parent,
            authorize = { _, _ -> RecoveryAuthorization.ROLL_FORWARD },
            classify = { _, status -> status == OperationStatus.COMPLETED },
        )
        assertEquals(RecoveryResult.CompletedCommit, completed)
        assertFalse(fixture.transactionRoot().exists())
    }

    private class SimulatedProcessDeath : Error()

    private class Fixture(label: String, private val ghostId: String = "ghost-id") {
        val parent: File = temporaryDirectory("ghost-update-$label")
        val ghostRoot = File(parent, ghostId).apply { check(mkdir()) }
        val operationId = GhostUpdateRepository.canonicalOperationIdFor(ghostRoot)
        val network = FakeNetwork()

        fun request() = GhostUpdateRequest(
            operationId = operationId,
            ghostId = ghostId,
            ghostRoot = ghostRoot,
            baseUri = mockk<Uri>(),
        )

        fun repository(
            events: GhostUpdateEvents = GhostUpdateEvents.NONE,
            fileOperations: GhostUpdateFileOperations = GhostUpdateFileOperations.DEFAULT,
            journalIo: GhostUpdateJournalIo = GhostUpdateJournalIo.DEFAULT,
            onProgress: (String, Long) -> Unit = { _, _ -> },
            onCommitClassified: (GhostUpdateResult.Completed) -> Boolean = { true },
            onNoChangesClassified: () -> Boolean = { true },
            onRollbackClassified: (OperationStatus) -> Boolean = { true },
            commitGuard: GhostUpdateCommitGuard = GhostUpdateCommitGuard.NONE,
            onRollbackJournalClassified: (GhostUpdateJournal, OperationStatus) -> Boolean = { _, status ->
                onRollbackClassified(status)
            },
        ) = GhostUpdateRepository(
            network,
            events,
            fileOperations,
            journalIo,
            onProgress,
            onCommitClassified,
            onNoChangesClassified,
            onRollbackClassified,
            commitGuard,
            onRollbackJournalClassified = onRollbackJournalClassified,
        )

        fun transactionRoot() = GhostUpdateRepository.transactionRootFor(ghostRoot, operationId)

        fun writeLive(path: String, value: String) = write(File(ghostRoot, path), bytes(value))

        fun writeLiveBytes(path: String, value: ByteArray) = write(File(ghostRoot, path), value)

        fun writeTransaction(path: String, value: String) =
            write(File(transactionRoot(), path), bytes(value))

        fun writeJournal(
            phase: CommitPhase,
            files: List<String>,
            attemptId: AttemptId? = null,
            workManagerUuid: String? = null,
            ghostId: String? = null,
        ) {
            GhostUpdateJournalStore.write(
                File(transactionRoot(), GhostUpdateJournalStore.FILE_NAME),
                GhostUpdateJournal(
                    operationId = operationId,
                    ghostRoot = ghostRoot.canonicalPath,
                    candidateRoot = File(transactionRoot(), "candidate").canonicalPath,
                    backupRoot = File(transactionRoot(), "backup").canonicalPath,
                    phase = phase,
                    files = files,
                    attemptId = attemptId,
                    workManagerUuid = workManagerUuid,
                    ghostId = ghostId,
                ),
            )
        }
    }

    private class RecordingLifecycleCommitGuard : GhostUpdateCommitGuard {
        val values = mutableListOf<String>()

        override fun commit(
            ghostId: String,
            ghostRoot: File,
            onFailure: (Throwable) -> GhostUpdateResult,
            action: () -> GhostUpdateResult,
        ): GhostUpdateResult {
            values += "unload"
            val result = try {
                action()
            } catch (error: Throwable) {
                values += "recover"
                onFailure(error)
            }
            values += "reload:${File(ghostRoot, "ghost/master.txt").readText()}"
            return result
        }
    }

    private class FakeNetwork : GhostUpdateNetwork {
        private val content = linkedMapOf<String, ByteArray>()
        var beforeOpen: (String) -> Boolean = { true }
        var onCandidateRead: () -> Unit = {}
        var onManifestRead: () -> Unit = {}
        val openedStreams = mutableListOf<TrackingInputStream>()
        val openedPaths = mutableListOf<String>()
        val retryablePaths = mutableSetOf<String>()
        val closeFailures = mutableSetOf<String>()

        fun manifest(vararg files: Pair<String, ByteArray>) {
            rawManifest(files.joinToString("\n") { (path, bytes) -> "$path\u0001${md5(bytes)}" })
            files.forEach { (path, bytes) -> content[path] = bytes }
        }

        fun manifestWithDigest(path: String, digest: String, bytes: ByteArray) {
            rawManifest("$path\u0001$digest")
            content[path] = bytes
        }

        fun rawManifest(value: String) {
            content["updates2.dau"] = bytes(value)
        }

        fun rawManifestBytes(name: String, value: ByteArray) {
            content[name] = value
        }

        fun file(path: String, value: ByteArray) {
            content[path] = value
        }

        override fun open(baseUri: Uri, relativePath: String): GhostUpdateOpenResult {
            if (relativePath in retryablePaths) {
                return GhostUpdateOpenResult.RetryableFailure(IOException("temporary network failure"))
            }
            if (!beforeOpen(relativePath)) return GhostUpdateOpenResult.NotFound
            val bytes = content[relativePath] ?: return GhostUpdateOpenResult.NotFound
            openedPaths += relativePath
            val delegate = ByteArrayInputStream(bytes)
            val stream = if (relativePath == "updates2.dau" || relativePath == "updates.txt") {
                TrackingInputStream(object : InputStream() {
                    override fun read(): Int = delegate.read().also { if (it >= 0) onManifestRead() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        delegate.read(buffer, offset, length).also { if (it > 0) onManifestRead() }
                    override fun close() {
                        delegate.close()
                        if (relativePath in closeFailures) throw IOException("connection close failed")
                    }
                })
            } else {
                TrackingInputStream(object : InputStream() {
                    override fun read(): Int = delegate.read().also { if (it >= 0) onCandidateRead() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        delegate.read(buffer, offset, length).also { if (it > 0) onCandidateRead() }
                    override fun close() {
                        delegate.close()
                        if (relativePath in closeFailures) throw IOException("connection close failed")
                    }
                })
            }
            openedStreams += stream
            return GhostUpdateOpenResult.Found(stream)
        }
    }

    private class TrackingInputStream(private val delegate: InputStream) : InputStream() {
        var closed = false
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class FailingJournalIo(private val failedPhase: CommitPhase) : GhostUpdateJournalIo {
        private var failed = false
        override fun write(file: File, journal: GhostUpdateJournal) {
            if (!failed && journal.phase == failedPhase) {
                failed = true
                throw IOException("simulated ${journal.phase} journal failure")
            }
            GhostUpdateJournalStore.write(file, journal)
        }

        override fun read(file: File): GhostUpdateJournal = GhostUpdateJournalStore.read(file)
    }

    private class RecordingEvents : GhostUpdateEvents {
        val values = mutableListOf<String>()
        override fun ready(files: List<String>) { values += "ready:${files.joinToString(",")}" }
        override fun downloadBegin(file: String, index: Int, total: Int) {
            values += "download:$file:$index:$total"
        }
        override fun digestCompareBegin(file: String, expected: String, actual: String) {
            values += "digest-begin:$file"
        }
        override fun digestCompareComplete(file: String, expected: String, actual: String) {
            values += "digest-complete:$file"
        }
        override fun digestCompareFailure(file: String, expected: String, actual: String) {
            values += "digest-failure:$file"
        }
        override fun complete(files: List<String>) { values += "complete:${files.joinToString(",")}" }
        override fun noChanges() { values += "complete:none" }
        override fun failure(reason: String, files: List<String>) { values += "failure:$reason" }
    }

    companion object {
        private fun fixture(label: String, ghostId: String = "ghost-id") = Fixture(label, ghostId)

        private fun temporaryDirectory(label: String): File {
            val root = File.createTempFile(label, "")
            check(root.delete() && root.mkdir())
            return root
        }

        private fun write(file: File, value: ByteArray) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            FileOutputStream(file).use { it.write(value) }
        }

        private fun assertBytes(expected: String, file: File) {
            FileInputStream(file).use { input ->
                assertArrayEquals(bytes(expected), input.readBytes())
            }
        }

        private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

        private fun md5(value: ByteArray): String = MessageDigest.getInstance("MD5")
            .digest(value)
            .joinToString("") { "%02x".format(it) }
    }
}

@RunWith(Parameterized::class)
internal class GhostUpdateTransitionTableTest(
    private val label: String,
    private val phase: CommitPhase,
    private val topology: GhostTreeTopology,
    private val durableStatus: OperationStatus?,
    private val exactIdentity: Boolean,
    private val workState: GhostUpdateWorker.Companion.RecoveryWorkState,
    private val expected: GhostUpdateWorker.Companion.RecoveryTransition,
) {
    @Test
    fun `transition matches approved durable table`() {
        assertEquals(
            label,
            expected,
            GhostUpdateWorker.recoveryTransition(
                phase,
                topology,
                durableStatus,
                exactIdentity,
                workState,
            ),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any?>> = listOf(
            row("prepared old live waits for active replay", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE, GhostUpdateWorker.Companion.RecoveryTransition.WAIT),
            row("prepared query error retains evidence", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.QUERY_ERROR, GhostUpdateWorker.Companion.RecoveryTransition.WAIT),
            row("prepared succeeded without publish fails", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_FAILED),
            row("prepared cancellation terminalizes cancelled", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_CANCELLED),
            row("prepared missing cancelled worker rolls back", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_CANCELLED),
            row("prepared cleanup-only missing cancelled worker rolls back", CommitPhase.PREPARED, GhostTreeTopology.LIVE_ONLY, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_CANCELLED),
            row("prepared after live backup commits despite failed worker", CommitPhase.PREPARED, GhostTreeTopology.CANDIDATE_BACKUP, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_FORWARD_COMPLETED),
            row("backed up commits despite cancelled worker", CommitPhase.BACKED_UP, GhostTreeTopology.CANDIDATE_BACKUP, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.CANCELLED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_FORWARD_COMPLETED),
            row("backed up published tree commits despite stop request", CommitPhase.BACKED_UP, GhostTreeTopology.LIVE_BACKUP, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_FORWARD_COMPLETED),
            row("published active commits without waiting", CommitPhase.PUBLISHED, GhostTreeTopology.LIVE_BACKUP, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_FORWARD_COMPLETED),
            row("published after backup cleanup finishes", CommitPhase.PUBLISHED, GhostTreeTopology.LIVE_ONLY, OperationStatus.COMPLETED, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_FORWARD_COMPLETED),
            row("published no backup cannot honor failure", CommitPhase.PUBLISHED, GhostTreeTopology.LIVE_ONLY, OperationStatus.FAILED, true, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("cleaned exact completion removes evidence", CommitPhase.CLEANED, GhostTreeTopology.LIVE_ONLY, OperationStatus.COMPLETED, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_FORWARD_COMPLETED),
            row("cleaned active never manufactures completion", CommitPhase.CLEANED, GhostTreeTopology.LIVE_ONLY, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("cleaned missing cancellation fails closed", CommitPhase.CLEANED, GhostTreeTopology.LIVE_ONLY, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("completed no-change staging is cleaned", CommitPhase.NO_CHANGES_PENDING, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.COMPLETED, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.CLEAN_NO_CHANGES),
            row("completed no-change cleanup-only state is cleaned", CommitPhase.NO_CHANGES_PENDING, GhostTreeTopology.LIVE_ONLY, OperationStatus.COMPLETED, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.CLEAN_NO_CHANGES),
            row("terminal prepared failure authorizes rollback", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.FAILED, true, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_FAILED),
            row("terminal backed cancellation authorizes rollback", CommitPhase.BACKED_UP, GhostTreeTopology.CANDIDATE_BACKUP, OperationStatus.CANCELLED, true, GhostUpdateWorker.Companion.RecoveryWorkState.CANCELLED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_CANCELLED),
            row("stale attempt blocks prepared cleanup", CommitPhase.PREPARED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.FAILED, false, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("stale binding blocks published cleanup", CommitPhase.PUBLISHED, GhostTreeTopology.LIVE_BACKUP, OperationStatus.COMPLETED, false, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("prepared cleanup-only state waits for active replay", CommitPhase.PREPARED, GhostTreeTopology.LIVE_ONLY, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.ACTIVE, GhostUpdateWorker.Companion.RecoveryTransition.WAIT),
            row("invalid backed topology blocks", CommitPhase.BACKED_UP, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.RUNNING, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("invalid published topology blocks", CommitPhase.PUBLISHED, GhostTreeTopology.CANDIDATE_BACKUP, OperationStatus.COMPLETED, true, GhostUpdateWorker.Companion.RecoveryWorkState.SUCCEEDED, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("rollback classified exact failure continues rollback", CommitPhase.ROLLBACK_CLASSIFIED, GhostTreeTopology.CANDIDATE_BACKUP, OperationStatus.FAILED, true, GhostUpdateWorker.Companion.RecoveryWorkState.FAILED, GhostUpdateWorker.Companion.RecoveryTransition.ROLL_BACK_FAILED),
            row("rollback classified live-candidate missing active fails closed", CommitPhase.ROLLBACK_CLASSIFIED, GhostTreeTopology.LIVE_CANDIDATE, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("rollback classified candidate-backup missing active fails closed", CommitPhase.ROLLBACK_CLASSIFIED, GhostTreeTopology.CANDIDATE_BACKUP, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("rollback classified live-backup missing active fails closed", CommitPhase.ROLLBACK_CLASSIFIED, GhostTreeTopology.LIVE_BACKUP, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
            row("rollback classified live-only missing active fails closed", CommitPhase.ROLLBACK_CLASSIFIED, GhostTreeTopology.LIVE_ONLY, OperationStatus.CANCEL_REQUESTED, true, GhostUpdateWorker.Companion.RecoveryWorkState.MISSING, GhostUpdateWorker.Companion.RecoveryTransition.FAIL_CLOSED),
        )

        private fun row(
            label: String,
            phase: CommitPhase,
            topology: GhostTreeTopology,
            status: OperationStatus?,
            exact: Boolean,
            work: GhostUpdateWorker.Companion.RecoveryWorkState,
            expected: GhostUpdateWorker.Companion.RecoveryTransition,
        ): Array<Any?> = arrayOf(label, phase, topology, status, exact, work, expected)
    }
}
