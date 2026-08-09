package com.cattailsw.nanidroid.durable

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.cattailsw.nanidroid.di.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GhostUpdateRecoveryTest {
    @Test
    fun recoveryWorkerQueriesExactDurableWorkIdentityBeforeRollingBackPreparedUpdate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("durable_operations_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        val fixture = fixture("worker-${UUID.randomUUID()}")
        fixture.live("old")
        fixture.candidate("new")
        val handle = OperationHandle(fixture.operationId, AttemptId(1))
        val expectedWorkId = durableWorkManagerId(handle, OperationKind.GHOST_UPDATE)
        val workManager = WorkManager.getInstance(context)
        val updateWork = GhostUpdateWorker.request(
            handle,
            ghostId = "ghost-id",
            ghostRoot = fixture.live,
            baseUri = Uri.parse("https://example.invalid/updates"),
        )
        assertEquals(expectedWorkId, updateWork.id)
        val failedWork = OneTimeWorkRequestBuilder<FailedGhostUpdateWork>()
            .setId(updateWork.id)
            .build()
        workManager.enqueueUniqueWork(
            GhostUpdateWorker.workName(fixture.live),
            ExistingWorkPolicy.KEEP,
            failedWork,
        ).result.get(5, TimeUnit.SECONDS)
        assertEquals(
            WorkInfo.State.FAILED,
            workManager.getWorkInfoById(failedWork.id).get(5, TimeUnit.SECONDS)!!.state,
        )

        val binding = ExternalJobBinding.WorkManager(updateWork.id.toString())
        fixture.journal(CommitPhase.PREPARED, attemptId = handle.attemptId, workManagerUuid = binding.uuid)
        val store = SharedPreferencesDurableOperationStore(context)
        val supervisor = DurableOperationSupervisor(store, MonotonicClock { 0L }) { _, _, _ -> }
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Committing update", 0, binding))
        assertEquals(binding, store.read().single().externalJob)
        assertEquals("old", fixture.value())

        // The recovery worker owns fresh store, supervisor, and repository instances.
        GhostUpdateWorker.enqueueRecovery(context, fixture.parent, fixture.live)

        val recoveryInfo = workManager.getWorkInfosForUniqueWork(
            GhostUpdateWorker.recoveryWorkName(fixture.parent, fixture.live),
        ).get(5, TimeUnit.SECONDS).single()
        assertEquals(WorkInfo.State.SUCCEEDED, recoveryInfo.state)
        assertEquals("old", fixture.value())
        assertFalse(fixture.transaction.exists())
        assertEquals(OperationStatus.FAILED, SharedPreferencesDurableOperationStore(context).read().single().status)
    }

    @Test
    fun recoveryMatrixRollsBackPreparedAndKeepsPublishedEvidencePending() {
        val prepared = fixture("prepared")
        prepared.live("old")
        prepared.candidate("new")
        prepared.journal(CommitPhase.PREPARED)

        assertEquals(
            RecoveryResult.RolledBack,
            GhostUpdateRepository.recoverAllBeforeGhostLoad(
                prepared.live.parentFile!!,
                prepared.live,
                authorize = { _, _ -> RecoveryAuthorization.ADOPT_PREPARED },
            ),
        )
        assertEquals("old", prepared.value())
        assertFalse(prepared.transaction.exists())

        val backedUp = fixture("backed-up")
        backedUp.backup("old")
        backedUp.candidate("new")
        backedUp.journal(CommitPhase.BACKED_UP)

        assertEquals(
            RecoveryResult.PublishPending(listOf(PATH)),
            GhostUpdateRepository.recoverAllBeforeGhostLoad(
                backedUp.live.parentFile!!,
                backedUp.live,
                authorize = { _, _ -> RecoveryAuthorization.ROLL_FORWARD },
            ),
        )
        assertEquals("new", backedUp.value())
        assertTrue(backedUp.transaction.exists())

        val published = fixture("published")
        published.live("new")
        published.backup("old")
        published.journal(CommitPhase.PUBLISHED)

        assertEquals(
            RecoveryResult.CommitPending(listOf(PATH)),
            GhostUpdateRepository.recoverAllBeforeGhostLoad(
                published.live.parentFile!!,
                published.live,
            ),
        )
        assertTrue(published.transaction.exists())
    }

    @Test
    fun cleanedJournalIsIdempotentlyRemovedAfterDurableClassification() {
        val fixture = fixture("cleaned")
        fixture.live("new")
        fixture.journal(CommitPhase.CLEANED)

        assertEquals(
            RecoveryResult.CompletedCommit,
            GhostUpdateRepository.recoverAllBeforeGhostLoad(
                fixture.live.parentFile!!,
                fixture.live,
                authorize = { _, _ -> RecoveryAuthorization.ROLL_FORWARD },
                classify = { _, status -> status == OperationStatus.COMPLETED },
            ),
        )
        assertEquals("new", fixture.value())
        assertFalse(fixture.transaction.exists())
    }

    @Test
    fun corruptPersistedPathFailsClosedWithoutTouchingTrees() {
        val fixture = fixture("corrupt")
        fixture.live("old")
        fixture.candidate("new")
        fixture.journal(CommitPhase.PREPARED, listOf("../outside"))

        assertTrue(
            GhostUpdateRepository.recoverAllBeforeGhostLoad(
                fixture.live.parentFile!!,
                fixture.live,
            ) is RecoveryResult.Failed,
        )
        assertEquals("old", fixture.value())
        assertTrue(fixture.transaction.exists())
    }

    private fun fixture(label: String): Fixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = File(context.cacheDir, "ghost-update-recovery-$label").apply {
            deleteRecursively()
            check(mkdirs())
        }
        return Fixture(parent)
    }

    private class Fixture(val parent: File) {
        val live = File(parent, "ghost-id")
        val operationId = GhostUpdateRepository.canonicalOperationIdFor(live)
        val transaction = GhostUpdateRepository.transactionRootFor(live, operationId)

        fun live(value: String) = write(File(live, PATH), value)
        fun candidate(value: String) = write(File(transaction, "candidate/$PATH"), value)
        fun backup(value: String) = write(File(transaction, "backup/$PATH"), value)
        fun value() = File(live, PATH).readText()

        fun journal(
            phase: CommitPhase,
            files: List<String> = listOf(PATH),
            attemptId: AttemptId? = null,
            workManagerUuid: String? = null,
        ) {
            GhostUpdateJournalStore.write(
                File(transaction, GhostUpdateJournalStore.FILE_NAME),
                GhostUpdateJournal(
                    operationId,
                    live.canonicalPath,
                    File(transaction, "candidate").canonicalPath,
                    File(transaction, "backup").canonicalPath,
                    phase,
                    files,
                    attemptId,
                    workManagerUuid,
                ),
            )
        }

        private fun write(file: File, value: String) {
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            file.writeText(value)
        }
    }

    private companion object {
        const val PATH = "ghost/master.txt"
    }
}

class FailedGhostUpdateWork(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result = Result.failure()
}
