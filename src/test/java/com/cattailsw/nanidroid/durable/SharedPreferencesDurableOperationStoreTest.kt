package com.cattailsw.nanidroid.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SharedPreferencesDurableOperationStoreTest {
    @Test fun emptyPresentValueCorruptionIsQuarantinedAndStoreResets() {
        assertCorruptionIsRecovered("", "missing durable operation version")
    }

    @Test fun unknownVersionCorruptionIsQuarantinedAndStoreResets() {
        assertCorruptionIsRecovered("v99", "unsupported durable operation version")
    }

    @Test fun malformedRowCorruptionIsQuarantinedAndStoreResets() {
        assertCorruptionIsRecovered("v1\nnot-a-record", "malformed durable operation row")
    }

    @Test fun malformedTerminalRowCorruptionIsQuarantinedAndStoreResets() {
        val malformedTerminal = "wyg\t1\tGHOST_UPDATE\t-\t-\t-\tUXVldWVk\t0\tCANCELLED\t0\t-"
        assertCorruptionIsRecovered("v1\n$malformedTerminal", "malformed durable operation row")
    }

    @Test fun duplicateIdCorruptionIsQuarantinedAndStoreResets() {
        val first = "YQ\t1\tGHOST_UPDATE\twm\tMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx\tUXVldWVk\t0\tRUNNING\t0\t-"
        val second = "YQ\t2\tGHOST_UPDATE\twm\tMjIyMjIyMjItMjIyMi0yMjIyLTIyMjItMjIyMjIyMjIyMjIy\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsRecovered("v1\n$first\n$second", "duplicate durable operation id")
    }

    @Test fun malformedUtf8CorruptionIsQuarantinedAndStoreResets() {
        val invalidUtf8Id = "wyg"
        val row = "$invalidUtf8Id\t1\tGHOST_UPDATE\twm\tMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsRecovered("v1\n$row", "malformed durable operation row")
    }

    @Test fun malformedUtf8DiagnosticsCorruptionIsQuarantinedAndStoreResets() {
        val invalidUtf8Diagnostics = "wyg"
        val row = "YQ\t3\tGHOST_UPDATE\tdm\t101\tZDoxMDE\tUXVldWVk\t0\tRUNNING\t0\t$invalidUtf8Diagnostics"
        assertCorruptionIsRecovered("v2\n$row", "malformed durable operation row")
    }

    @Test fun truncatedRecordCorruptionIsQuarantinedAndStoreResets() {
        assertCorruptionIsRecovered("v2\nYQ\t1", "malformed durable operation row")
    }

    @Test fun malformedWorkManagerUuidInCurrentRecordIsPreserved() {
        val row = "YQ\t3\tGHOST_UPDATE\twm\tbm9uLXV1aWQ\t-\tUXVldWVk\t0\tRUNNING\t0\t-"
        val storage = RecordingStorage("v2\n$row")
        val store = SharedPreferencesDurableOperationStore(storage)

        val restored = store.read().single()
        assertEquals(1, store.read().size)
        assertEquals(ExternalJobBinding.WorkManager("non-uuid"), restored.externalJob)
        assertEquals("v2\n$row", storage.value)
        assertNull(storage.quarantine)
    }

    @Test fun malformedWorkManagerUuidInLegacyPreviousBindingIsPreserved() {
        val row = "YQ\t3\tGHOST_UPDATE\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\twm\tbm9uLXV1aWQ\tUXVldWVk\t0\tRUNNING\t0\t-"
        val storage = RecordingStorage("v1\n$row")
        val store = SharedPreferencesDurableOperationStore(storage)

        val restored = store.read().single()
        val currentBinding = ExternalJobBinding.WorkManager("33333333-3333-3333-3333-333333333333")
        assertEquals(
            setOf(
                currentBinding,
                ExternalJobBinding.WorkManager("non-uuid"),
            ),
            restored.externalJobHistory,
        )
        assertEquals("v1\n$row", storage.value)
        assertNull(storage.quarantine)
    }

    @Test fun malformedWorkManagerUuidInHistoryIsPreserved() {
        val row = "YQ\t4\tGHOST_UPDATE\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\tdzpibTl1TFhWMWFXUQ\tUXVldWVk\t0\tRUNNING\t0\t-"
        val storage = RecordingStorage("v2\n$row")
        val store = SharedPreferencesDurableOperationStore(storage)

        val restored = store.read().single()
        assertEquals(
            setOf(ExternalJobBinding.WorkManager("non-uuid")),
            restored.externalJobHistory,
        )
        assertEquals("v2\n$row", storage.value)
        assertNull(storage.quarantine)
    }

    @Test fun duplicateBindingHistoryCorruptionIsQuarantinedAndStoreResets() {
        val duplicateHistory = "ZDoxMDEsZDoxMDE"
        val row = "YQ\t3\tGHOST_UPDATE\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\t$duplicateHistory\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsRecovered("v2\n$row", "duplicate external job history")
    }

    @Test fun bindingHistoryTagsAreCaseSensitive() {
        val uppercaseHistoryTag = "RDoxMDE"
        val row = "YQ\t3\tGHOST_UPDATE\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\t$uppercaseHistoryTag\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsRecovered("v2\n$row", "malformed durable operation row")
    }

    @Test fun malformedPayloadIsStoredInBoundedQuarantineSlot() {
        val raw = "v2\n${"x".repeat(40_000)}"
        val storage = RecordingStorage(raw)
        val store = SharedPreferencesDurableOperationStore(storage)

        assertTrue(store.read().isEmpty())
        assertEquals("v2", storage.value)
        assertNotNull(storage.quarantine)
        assertTrue(storage.quarantine!!.length < raw.length)
    }

    @Test fun fallbackIsStickyWhenQuarantineResetFails() {
        val storage = ThrowingWriteQuarantineStorage("v1\nwyg\n")
        val store = SharedPreferencesDurableOperationStore(storage)

        assertTrue(store.read().isEmpty())
        assertTrue(store.read().isEmpty())
        assertTrue(storage.quarantineAndResetAttempted)

        val restored = record("new", 1)
        assertTrue(store.putIfAbsent(restored))
        assertTrue(store.compareAndSet(restored, restored.copy(status = OperationStatus.COMPLETED)))
    }

    @Test fun legacyPreviousBindingMigratesIntoCompleteHistory() {
        val row = "YQ\t2\tGHOST_UPDATE\twm\tMjIyMjIyMjItMjIyMi0yMjIyLTIyMjItMjIyMjIyMjIyMjIy\twm\tMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx\tUXVldWVk\t0\tRUNNING\t0\t-"
        val store = SharedPreferencesDurableOperationStore(RecordingStorage("v1\n$row"))

        val restored = store.read().single()

        val currentBinding = ExternalJobBinding.WorkManager("22222222-2222-2222-2222-222222222222")
        val previousBinding = ExternalJobBinding.WorkManager("11111111-1111-1111-1111-111111111111")
        assertEquals(currentBinding, restored.externalJob)
        assertEquals(
            setOf(
                previousBinding,
                currentBinding,
            ),
            restored.externalJobHistory,
        )
    }

    @Test fun bindingHistorySerializationIsDeterministic() {
        val current = workManager("worker-3")
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

    private fun assertCorruptionIsRecovered(raw: String, expectedDiagnostic: String) {
        val storage = RecordingStorage(raw)
        val store = SharedPreferencesDurableOperationStore(storage)

        assertTrue(store.read().isEmpty())
        assertEquals("v2", storage.value)
        assertNotNull(storage.quarantine)
        assertEquals(storage.quarantine, raw)

        val original = record("new", 1)
        assertTrue(store.putIfAbsent(original))
        assertTrue(store.compareAndSet(original, original.copy(status = OperationStatus.COMPLETED)))
    }

    private fun record(id: String, attempt: Long) = DurableOperationRecord(
        id = OperationId(id),
        attemptId = AttemptId(attempt),
        kind = OperationKind.GHOST_UPDATE,
        externalJob = workManager("worker-$attempt"),
        progress = OperationProgress("Queued", 0),
        status = OperationStatus.RUNNING,
        showStallPrompt = false,
    )

    private fun workManager(label: String) = ExternalJobBinding.WorkManager(
        UUID.nameUUIDFromBytes(label.toByteArray()).toString(),
    )

    private class RecordingStorage(initialValue: String?) : SharedPreferencesDurableOperationStore.Storage {
        var value = initialValue
        var quarantine: String? = null
        var writeCount = 0
        var quarantineWriteCount = 0

        override fun read() = value

        override fun readQuarantine() = quarantine

        override fun write(value: String) {
            this.value = value
            writeCount += 1
        }

        override fun writeQuarantine(value: String) {
            quarantine = value
            quarantineWriteCount += 1
        }
    }

    private class ThrowingWriteQuarantineStorage(initialValue: String?) :
        SharedPreferencesDurableOperationStore.Storage {
        var quarantineAndResetAttempted = false
        private var value = initialValue

        override fun read() = value

        override fun write(value: String) {
            this.value = value
        }

        override fun writeQuarantine(value: String) {
            quarantineAndResetAttempted = true
        }

        override fun writeQuarantineAndReset(value: String) {
            quarantineAndResetAttempted = true
            throw RuntimeException("cannot quarantine")
        }
    }
}
