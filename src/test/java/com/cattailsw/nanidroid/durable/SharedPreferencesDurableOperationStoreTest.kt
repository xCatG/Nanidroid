package com.cattailsw.nanidroid.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesDurableOperationStoreTest {
    @Test fun emptyPresentValueFailsClosedWithoutRewritingRawData() {
        assertCorruptionIsPreserved("", "missing durable operation version")
    }

    @Test fun unknownVersionFailsClosedWithoutRewritingRawData() {
        assertCorruptionIsPreserved("v99", "unsupported durable operation version")
    }

    @Test fun malformedRowFailsClosedWithoutRewritingRawData() {
        assertCorruptionIsPreserved("v1\nnot-a-record", "malformed durable operation row")
    }

    @Test fun duplicateIdFailsClosedWithoutRewritingRawData() {
        val first = "YQ\t1\tGHOST_UPDATE\twm\td29yay0x\tUXVldWVk\t0\tRUNNING\t0\t-"
        val second = "YQ\t2\tGHOST_UPDATE\twm\td29yay0y\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsPreserved("v1\n$first\n$second", "duplicate durable operation id")
    }

    @Test fun malformedUtf8FailsClosedWithoutRewritingRawData() {
        val invalidUtf8Id = "wyg"
        val row = "$invalidUtf8Id\t1\tGHOST_UPDATE\twm\td29yay0x\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsPreserved("v1\n$row", "malformed durable operation row")
    }

    @Test fun malformedUtf8DiagnosticsFailsClosedWithoutRewritingRawData() {
        val invalidUtf8Diagnostics = "wyg"
        val row = "YQ\t3\tGHOST_UPDATE\tdm\t101\tZDoxMDE\tUXVldWVk\t0\tRUNNING\t0\t$invalidUtf8Diagnostics"
        assertCorruptionIsPreserved("v2\n$row", "malformed durable operation row")
    }

    @Test fun duplicateBindingHistoryFailsClosedWithoutRewritingRawData() {
        val duplicateHistory = "ZDoxMDEsZDoxMDE"
        val row = "YQ\t3\tGHOST_UPDATE\twm\td29yay0z\t$duplicateHistory\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsPreserved("v2\n$row", "duplicate external job history")
    }

    @Test fun bindingHistoryTagsAreCaseSensitive() {
        val uppercaseHistoryTag = "RDoxMDE"
        val row = "YQ\t3\tGHOST_UPDATE\twm\td29yay0z\t$uppercaseHistoryTag\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsPreserved("v2\n$row", "malformed durable operation row")
    }

    @Test fun legacyPreviousBindingMigratesIntoCompleteHistory() {
        val row = "YQ\t2\tGHOST_UPDATE\twm\td29ya2VyLTI\twm\td29ya2VyLTE\tUXVldWVk\t0\tRUNNING\t0\t-"
        val store = SharedPreferencesDurableOperationStore(RecordingStorage("v1\n$row"))

        val restored = store.read().single()

        assertEquals(ExternalJobBinding.WorkManager("worker-2"), restored.externalJob)
        assertEquals(
            setOf(
                ExternalJobBinding.WorkManager("worker-1"),
                ExternalJobBinding.WorkManager("worker-2"),
            ),
            restored.externalJobHistory,
        )
    }

    @Test fun bindingHistorySerializationIsDeterministic() {
        val current = ExternalJobBinding.WorkManager("worker-3")
        val prior = ExternalJobBinding.DownloadManager(101)
        val firstStorage = RecordingStorage(null)
        val secondStorage = RecordingStorage(null)
        val firstStore = SharedPreferencesDurableOperationStore(firstStorage)
        val secondStore = SharedPreferencesDurableOperationStore(secondStorage)

        assertTrue(
            firstStore.putIfAbsent(
                record("same", 3).copy(externalJobHistory = linkedSetOf(current, prior)),
            ),
        )
        assertTrue(
            secondStore.putIfAbsent(
                record("same", 3).copy(externalJobHistory = linkedSetOf(prior, current)),
            ),
        )

        assertEquals(firstStorage.value, secondStorage.value)
    }

    @Test fun twoUpdatesFromOneExpectedSnapshotCannotBothSucceed() {
        val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
        val firstStore = SharedPreferencesDurableOperationStore(storage)
        val secondStore = SharedPreferencesDurableOperationStore(storage)
        val original = record("same", 3)
        assertTrue(firstStore.putIfAbsent(original))
        val firstExpected = firstStore.read().single()
        val secondExpected = secondStore.read().single()
        val winner = firstExpected.copy(progress = OperationProgress("Downloading", 10))
        val loser = secondExpected.copy(progress = OperationProgress("Downloading", 5))

        assertTrue(
            firstStore.compareAndSet(firstExpected, winner),
        )
        assertFalse(
            secondStore.compareAndSet(secondExpected, loser),
        )

        assertEquals(winner, firstStore.read().single())
    }

    private fun assertCorruptionIsPreserved(raw: String, expectedDiagnostic: String) {
        val storage = RecordingStorage(raw)
        val store = SharedPreferencesDurableOperationStore(storage)

        val readFailure = assertThrows(IllegalStateException::class.java) {
            store.read()
        }
        assertTrue(readFailure.message.orEmpty().contains(expectedDiagnostic, ignoreCase = true))
        assertThrows(IllegalStateException::class.java) {
            store.putIfAbsent(record("new", 1))
        }
        assertThrows(IllegalStateException::class.java) {
            store.compareAndSet(
                record("a", 1),
                record("a", 1).copy(status = OperationStatus.COMPLETED),
            )
        }
        assertEquals(raw, storage.value)
        assertEquals(0, storage.writeCount)
    }

    private fun record(id: String, attempt: Long) = DurableOperationRecord(
        id = OperationId(id),
        attemptId = AttemptId(attempt),
        kind = OperationKind.GHOST_UPDATE,
        externalJob = ExternalJobBinding.WorkManager("worker-$attempt"),
        progress = OperationProgress("Queued", 0),
        status = OperationStatus.RUNNING,
        showStallPrompt = false,
    )

    private class RecordingStorage(initialValue: String?) : SharedPreferencesDurableOperationStore.Storage {
        var value = initialValue
        var writeCount = 0

        override fun read() = value

        override fun write(value: String) {
            this.value = value
            writeCount += 1
        }
    }
}
