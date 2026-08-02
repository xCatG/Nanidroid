package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.DurableOperationRecord
import com.cattailsw.nanidroid.durable.DurableOperationStore
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.OperationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarInstallProgressReporterTest {
    private val clock = FakeClock()
    private val store = CountingStore()
    private val supervisor = DurableOperationSupervisor(store, clock) { _, _ -> }
    private val handle = OperationHandle(OperationId("large-install"), AttemptId(1L))
    private val binding = ExternalJobBinding.WorkManager("install-worker")
    private val reporter = ThrottledNarInstallProgressReporter(supervisor, clock)

    @Test fun largeArchiveChunkCallbacksProduceBoundedDurableWritesAndExactFinalProgress() {
        startInstall()
        val archiveBytes = 512L * 1024L * 1024L
        val chunkBytes = 8L * 1024L

        var completed = chunkBytes
        while (completed <= archiveBytes) {
            reporter.report(handle, binding, "Extracting archive", completed)
            completed += chunkBytes
        }
        reporter.complete(handle, binding)

        assertEquals(513, store.updateCount)
        assertEquals("Extracting archive", store.read().single().progress.phase)
        assertEquals(archiveBytes, store.read().single().progress.completed)
    }

    @Test fun phaseBoundariesAndFinalProgressPersistWhileDuplicatesAndRegressionsDoNot() {
        startInstall()

        reporter.report(handle, binding, "Staging archive", 8_192L)
        reporter.report(handle, binding, "Staging archive", 8_192L)
        reporter.report(handle, binding, "Staging archive", 4_096L)
        reporter.report(handle, binding, "Staging archive", 16_384L)
        reporter.report(handle, binding, "Extracting archive", 0L)
        reporter.report(handle, binding, "Extracting archive", 8_192L)
        reporter.complete(handle, binding)

        assertEquals(3, store.updateCount)
        assertEquals("Extracting archive", store.read().single().progress.phase)
        assertEquals(8_192L, store.read().single().progress.completed)
    }

    @Test fun slowContinuousProgressPersistsHeartbeatBeforeThirtySecondStallWindow() {
        startInstall()
        reporter.report(handle, binding, "Extracting archive", 8_192L)

        clock.value = 19_999L
        reporter.report(handle, binding, "Extracting archive", 16_384L)
        assertEquals(1, store.updateCount)

        clock.value = 20_000L
        reporter.report(handle, binding, "Extracting archive", 24_576L)
        assertEquals(2, store.updateCount)

        clock.value = 49_999L
        assertTrue(!supervisor.snapshot().single().showStallPrompt)
    }

    @Test fun losingWorkerBufferCannotFlushThroughWinningWorker() {
        startInstall()
        val losingBinding = ExternalJobBinding.WorkManager("losing-worker")

        assertTrue(!reporter.report(handle, losingBinding, "Extracting archive", 512L))
        assertTrue(!reporter.complete(handle, binding))

        val current = store.read().single()
        assertEquals(OperationProgress("Installing archive", 0L), current.progress)
        assertEquals(binding, current.externalJob)
    }

    private fun startInstall() {
        assertTrue(
            supervisor.start(
                handle,
                OperationKind.NAR_INSTALL,
                "Installing archive",
                0L,
                binding,
            ),
        )
    }

    private class FakeClock(var value: Long = 0L) : MonotonicClock {
        override fun nowMillis() = value
    }

    private class CountingStore : DurableOperationStore {
        private var record: DurableOperationRecord? = null
        var updateCount = 0
            private set

        override fun read() = listOfNotNull(record)

        override fun putIfAbsent(record: DurableOperationRecord): Boolean {
            if (this.record != null) return false
            this.record = record
            return true
        }

        override fun compareAndSet(
            expected: DurableOperationRecord,
            updated: DurableOperationRecord,
        ): Boolean {
            if (record != expected) return false
            record = updated
            updateCount += 1
            return true
        }
    }
}
