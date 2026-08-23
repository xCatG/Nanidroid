package com.cattailsw.nanidroid.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class SharedPreferencesDurableOperationStoreTest {
    @Test fun everyRetainedKindWritesTheReservedV6ColumnAsASentinel() {
        val storage = RecordingStorage(null)
        val store = SharedPreferencesDurableOperationStore(storage)

        OperationKind.entries.forEachIndexed { index, kind ->
            assertTrue(store.putIfAbsent(record("retained-$index", index.toLong() + 1).copy(kind = kind)))
        }

        storage.value!!.lineSequence().drop(1).forEach { row ->
            assertEquals("-", row.split('\t')[11])
        }
    }

    @Test fun nonSentinelReservedV6ColumnIsQuarantinedAndPrimaryIsAtomicallyReset() {
        val formerTerminalEvent = "Z2hvc3Q,L3N0b3JhZ2UvZ2hvc3Q,T25VcGRhdGVDb21wbGV0ZQ"
        val row = "YQ\t1\tNAR_INSTALL\t-\t-\t-\tUXVldWVk\t0\tRUNNING\t0\t-\t$formerTerminalEvent\t7\t11\t9"
        val raw = "v6\n$row"

        val fixture = assertCorruptionRequiresRecovery(raw, "malformed durable operation row")

        assertEquals(raw, fixture.storage.quarantine)
        assertEquals("v3", fixture.storage.value)
        assertEquals(1, fixture.storage.quarantineWriteCount)
    }

    @Test fun retainedV6RowRoundTripsGenerationsInTheirOriginalColumns() {
        val row = "YQ\t1\tNAR_INSTALL\t-\t-\t-\tUXVldWVk\t0\tRUNNING\t0\t-\t-\t7\t11\t9"

        val restored = SharedPreferencesDurableOperationStore(RecordingStorage("v6\n$row")).read().single()

        assertEquals(7L, restored.attentionRetryGeneration)
        assertEquals(11L, restored.progressGeneration)
        assertEquals(9L, restored.attentionKeepWaitingGeneration)
    }

    @Test fun retainedKindWithNumericV3RetryGenerationStillDecodes() {
        val row = "YQ\t1\tNAR_INSTALL\t-\t-\t-\tUXVldWVk\t0\tRUNNING\t0\t-\t7"

        val restored = SharedPreferencesDurableOperationStore(RecordingStorage("v3\n$row")).read().single()

        assertEquals(OperationKind.NAR_INSTALL, restored.kind)
        assertEquals(7L, restored.attentionRetryGeneration)
    }

    @Test fun v3TerminalPayloadIsQuarantined() {
        val formerTerminalEvent = "Z2hvc3Q,L3N0b3JhZ2UvZ2hvc3Q,T25VcGRhdGVDb21wbGV0ZQ"
        val row = "YQ\t1\tNAR_INSTALL\t-\t-\t-\tUXVldWVk\t0\tRUNNING\t0\t-\t$formerTerminalEvent"

        assertCorruptionRequiresRecovery("v3\n$row", "malformed durable operation row")
    }

    @Test fun historicalGhostUpdateRowIsQuarantined() {
        val row = "YQ\t1\tGHOST_UPDATE\t-\t-\t-\tUXVldWVk\t0\tRUNNING\t0\t-\t-\t0\t0\t0"

        assertCorruptionRequiresRecovery("v6\n$row", "malformed durable operation row")
    }

    @Test fun observationGenerationsRoundTripInTheCurrentRecordFormat() {
        val storage = RecordingStorage(null)
        val store = SharedPreferencesDurableOperationStore(storage)
        val record = record("retry", 1).copy(
            attentionRetryGeneration = 7L,
            attentionKeepWaitingGeneration = 9L,
            progressGeneration = 11L,
        )

        assertTrue(store.putIfAbsent(record))
        assertEquals(record, SharedPreferencesDurableOperationStore(storage).read().single())
        assertTrue(storage.value!!.startsWith("v6\n"))
    }

    @Test fun emptyPresentValueCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        val fixture = assertCorruptionRequiresRecovery("", "missing durable operation version")

        val recreated = SharedPreferencesDurableOperationStore(fixture.storage)
        assertThrows(DurableOperationStoreCorruptionException::class.java) { recreated.read() }
        assertTrue(recreated.isRecoveryRequired())
        assertWritesAreBlocked(recreated)
    }

    @Test fun unknownVersionCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        assertCorruptionRequiresRecovery("v99", "unsupported durable operation version")
    }

    @Test fun malformedRowCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        assertCorruptionRequiresRecovery("v1\nnot-a-record", "malformed durable operation row")
    }

    @Test fun malformedTerminalRowIsCapturedAndNotDropped() {
        val malformedTerminal = "wyg\t1\tNAR_INSTALL\t-\t-\t-\tUXVldWVk\t0\tCANCELLED\t0\t-"
        val fixture = assertCorruptionRequiresRecovery(
            "v1\n$malformedTerminal",
            "malformed durable operation row",
        )
        assertEquals("v1\n$malformedTerminal", fixture.storage.quarantine)
    }

    @Test fun duplicateIdCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        val first = "YQ\t1\tNAR_INSTALL\twm\tMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx\tUXVldWVk\t0\tRUNNING\t0\t-"
        val second = "YQ\t2\tNAR_INSTALL\twm\tMjIyMjIyMjItMjIyMi0yMjIyLTIyMjItMjIyMjIyMjIyMjIy\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionRequiresRecovery("v1\n$first\n$second", "duplicate durable operation id")
    }

    @Test fun malformedUtf8CorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        val invalidUtf8Id = "wyg"
        val row = "$invalidUtf8Id\t1\tNAR_INSTALL\twm\tMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionRequiresRecovery("v1\n$row", "malformed durable operation row")
    }

    @Test fun malformedUtf8DiagnosticsCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        val invalidUtf8Diagnostics = "wyg"
        val row = "YQ\t3\tNAR_INSTALL\tdm\t101\tZDoxMDE\tUXVldWVk\t0\tRUNNING\t0\t$invalidUtf8Diagnostics"
        assertCorruptionRequiresRecovery("v2\n$row", "malformed durable operation row")
    }

    @Test fun truncatedRecordCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        assertCorruptionRequiresRecovery("v2\nYQ\t1", "malformed durable operation row")
    }

    @Test fun duplicateBindingHistoryCorruptionIsQuarantinedAndReadsAreBlockedUntilRecovery() {
        val duplicateHistory = "ZDoxMDEsZDoxMDE"
        val row = "YQ\t3\tNAR_INSTALL\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\t$duplicateHistory\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionRequiresRecovery("v2\n$row", "duplicate external job history")
    }

    @Test fun bindingHistoryTagsAreCaseSensitive() {
        val uppercaseHistoryTag = "RDoxMDE"
        val row = "YQ\t3\tNAR_INSTALL\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\t$uppercaseHistoryTag\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionRequiresRecovery("v2\n$row", "malformed durable operation row")
    }

    @Test fun malformedPayloadIsStoredInBoundedQuarantineSlot() {
        val raw = "v2\n${"x".repeat(40_000)}"
        val fixture = assertCorruptionRequiresRecovery(raw, "malformed durable operation row")
        assertEquals(16_384, fixture.storage.quarantine!!.length)
        assertEquals(raw.take(16_384), fixture.storage.quarantine)
        assertEquals("v3", fixture.storage.value)
    }

    @Test fun atomicRecoveryCanBeResolvedAndWritesReenabled() {
        val fixture = assertCorruptionRequiresRecovery("v1\nwyg\n", "malformed durable operation row")

        assertTrue(fixture.store.resolveRecovery())
        assertFalse(fixture.store.isRecoveryRequired())
        assertEquals("v3", fixture.storage.value)
        assertNull(fixture.storage.quarantine)

        val restored = record("new", 1)
        assertTrue(fixture.store.putIfAbsent(restored))
        assertTrue(fixture.store.compareAndSet(restored, restored.copy(status = OperationStatus.COMPLETED)))
    }

    @Test fun atomicFailureLeavesPrimaryExactAndKeepsWritesBlocked() {
        val storage = ThrowingWriteQuarantineAndResetStorage("v1\nwyg\n")
        val store = SharedPreferencesDurableOperationStore(storage)

        val error = assertThrows(DurableOperationStoreCorruptionException::class.java) {
            store.read()
        }
        assertEquals("malformed durable operation row", error.message)
        assertEquals("v1\nwyg\n", storage.value)
        assertNull(storage.quarantine)

        assertFalse(store.resolveRecovery())
        assertTrue(store.isRecoveryRequired())
        assertWritesAreBlocked(store)
    }

    @Test fun resolvedRecoveryReopensWritesAndClearsMarker() {
        val storage = RecordingStorage("v1\nwyg\n")
        val store = SharedPreferencesDurableOperationStore(storage)

        assertThrows(DurableOperationStoreCorruptionException::class.java) { store.read() }
        assertTrue(store.acknowledgeRecoverySignalForTest())
        assertTrue(store.isRecoveryRequired())
        assertTrue(store.resolveRecovery())

        assertFalse(store.isRecoveryRequired())
        val restored = record("new", 1)
        assertTrue(store.putIfAbsent(restored))
        assertTrue(store.compareAndSet(restored, restored.copy(status = OperationStatus.COMPLETED)))
    }

    @Test fun resolveRecoveryRequiresWriteAndClearsOnlyOnAtomicResetSuccess() {
        val storage = ThrowingWriteQuarantineAndResetStorage("v1\nwyg\n")
        val store = SharedPreferencesDurableOperationStore(storage)

        assertThrows(DurableOperationStoreCorruptionException::class.java) { store.read() }
        assertTrue(store.acknowledgeRecoverySignalForTest())
        assertFalse(store.resolveRecovery())
        assertTrue(store.isRecoveryRequired())
    }

    @Test fun newStoreReadsPersistedRecoveryMarkerAndBlocksWrites() {
        val storage = RecordingStorage("v1\nwyg\n")
        storage.quarantine = "v1\nwyg\n".take(16_384)
        storage.recoveryMarker = true

        val firstStore = SharedPreferencesDurableOperationStore(storage)
        assertThrows(DurableOperationStoreCorruptionException::class.java) { firstStore.read() }
        assertWritesAreBlocked(firstStore)

        val secondStore = SharedPreferencesDurableOperationStore(storage)
        assertThrows(DurableOperationStoreCorruptionException::class.java) { secondStore.read() }
        assertWritesAreBlocked(secondStore)
        assertTrue(secondStore.isRecoveryRequired())
    }

    @Test fun explicitAcknowledgeReturnsEmptyReadButBlocksWritesUntilResolve() {
        val fixture = assertCorruptionRequiresRecovery("v1\nwyg\n", "malformed durable operation row")
        fixture.store.acknowledgeRecoverySignal()
        assertTrue(fixture.store.read().isEmpty())

        val record = record("recovered", 3)
        assertFalse(fixture.store.putIfAbsent(record))
        assertFalse(fixture.store.compareAndSet(record, record.copy(status = OperationStatus.COMPLETED)))
    }

    @Test fun malformedWorkManagerUuidInCurrentRecordIsPreserved() {
        val row = "YQ\t3\tNAR_INSTALL\twm\tbm9uLXV1aWQ\t-\tUXVldWVk\t0\tRUNNING\t0\t-"
        val storage = RecordingStorage("v2\n$row")
        val store = SharedPreferencesDurableOperationStore(storage)

        val restored = store.read().single()
        assertEquals(1, store.read().size)
        assertEquals(ExternalJobBinding.WorkManager("non-uuid"), restored.externalJob)
        assertEquals("v2\n$row", storage.value)
        assertNull(storage.quarantine)
    }

    @Test fun malformedWorkManagerUuidInLegacyPreviousBindingIsPreserved() {
        val row = "YQ\t3\tNAR_INSTALL\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\twm\tbm9uLXV1aWQ\tUXVldWVk\t0\tRUNNING\t0\t-"
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
        val row = "YQ\t4\tNAR_INSTALL\twm\tMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMz\tdzpibTl1TFhWMWFXUQ\tUXVldWVk\t0\tRUNNING\t0\t-"
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

    @Test fun legacyPreviousBindingMigratesIntoCompleteHistory() {
        val row = "YQ\t2\tNAR_INSTALL\twm\tMjIyMjIyMjItMjIyMi0yMjIyLTIyMjItMjIyMjIyMjIyMjIy\twm\tMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx\tUXVldWVk\t0\tRUNNING\t0\t-"
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

        assertTrue(firstStore.compareAndSet(firstExpected, winner))
        assertFalse(secondStore.compareAndSet(secondExpected, loser))

        assertEquals(winner, firstStore.read().single())
    }

    @Test fun sharedPreferencesAdapterRoundTripsAndEnforcesHandleCas() {
        val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
        val firstStore = SharedPreferencesDurableOperationStore(storage)
        val record = DurableOperationRecord(
            id = OperationId("update-1"),
            attemptId = AttemptId(4),
            kind = OperationKind.NAR_INSTALL,
            externalJob = workManager("worker-4"),
            progress = OperationProgress("Verifying", 12),
            status = OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = true,
            diagnostics = "still stopping",
            externalJobHistory = setOf(
                workManager("worker-4"),
                ExternalJobBinding.DownloadManager(12),
            ),
        )
        assertTrue(firstStore.putIfAbsent(record))
        assertFalse(firstStore.putIfAbsent(record))

        val restoredStore = SharedPreferencesDurableOperationStore(storage)
        assertEquals(record, restoredStore.read().single())
        assertFalse(
            restoredStore.compareAndSet(
                record.copy(attemptId = AttemptId(3)),
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertFalse(
            restoredStore.compareAndSet(
                record.copy(status = OperationStatus.RUNNING),
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertTrue(
            restoredStore.compareAndSet(
                record,
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertEquals(OperationStatus.CANCELLED, firstStore.read().single().status)
    }

    private fun assertCorruptionRequiresRecovery(
        raw: String,
        expectedDiagnostic: String,
    ): CorruptionFixture {
        val storage = RecordingStorage(raw)
        val store = SharedPreferencesDurableOperationStore(storage)

        val error = assertThrows(DurableOperationStoreCorruptionException::class.java) {
            store.read()
        }
        assertTrue(error.message.orEmpty().contains(expectedDiagnostic))
        assertEquals("v3", storage.value)
        assertNotNull(storage.quarantine)
        assertEquals(storage.quarantine, raw.take(16_384))
        assertTrue(store.isRecoveryRequired())
        assertWritesAreBlocked(store)

        return CorruptionFixture(storage, store)
    }

    private fun assertWritesAreBlocked(store: SharedPreferencesDurableOperationStore) {
        val record = record("new", 1)
        assertFalse(store.putIfAbsent(record))
        assertFalse(store.compareAndSet(record, record.copy(status = OperationStatus.COMPLETED)))
        assertThrows(DurableOperationStoreCorruptionException::class.java) { store.read() }
    }

    private fun record(id: String, attempt: Long) = DurableOperationRecord(
        id = OperationId(id),
        attemptId = AttemptId(attempt),
        kind = OperationKind.NAR_INSTALL,
        externalJob = workManager("worker-$attempt"),
        progress = OperationProgress("Queued", 0),
        status = OperationStatus.RUNNING,
        showStallPrompt = false,
    )

    private fun workManager(label: String) = ExternalJobBinding.WorkManager(
        UUID.nameUUIDFromBytes(label.toByteArray()).toString(),
    )

    private val invalidUuid = "non-uuid"

    private fun SharedPreferencesDurableOperationStore.acknowledgeRecoverySignalForTest() = run {
        acknowledgeRecoverySignal()
        isRecoveryRequired()
    }

    private data class CorruptionFixture(
        val storage: RecordingStorage,
        val store: SharedPreferencesDurableOperationStore,
    )

    private class RecordingStorage(initialValue: String?) : SharedPreferencesDurableOperationStore.Storage {
        var value = initialValue
        var quarantine: String? = null
        var recoveryMarker = false
        var writeCount = 0
        var quarantineWriteCount = 0

        override fun read() = value

        override fun readQuarantine() = quarantine

        override fun hasRecoveryMarker() = recoveryMarker

        override fun write(value: String) {
            this.value = value
            writeCount += 1
        }

        override fun writeQuarantine(value: String) {
            quarantine = value
            quarantineWriteCount += 1
        }

        override fun writeQuarantineAndReset(value: String) {
            quarantine = value.take(16_384)
            recoveryMarker = true
            quarantineWriteCount += 1
            this.value = "v3"
        }

        override fun clearQuarantine() {
            quarantine = null
            recoveryMarker = false
        }
    }

    private class ThrowingWriteQuarantineAndResetStorage(initialValue: String?) :
        SharedPreferencesDurableOperationStore.Storage {
        var value = initialValue
        var quarantine: String? = null
        var writeCount = 0
        var quarantineAndResetAttempted = false

        override fun read() = value

        override fun write(value: String) {
            this.value = value
            writeCount += 1
        }

        override fun writeQuarantine(value: String) {
            quarantine = value
            throw RuntimeException("atomic-only path should not call separate quarantine write")
        }

        override fun readQuarantine() = quarantine

        override fun hasRecoveryMarker() = false

        override fun writeQuarantineAndReset(value: String) {
            quarantineAndResetAttempted = true
            throw RuntimeException("cannot write quarantine and reset")
        }

        override fun clearQuarantine() {
            quarantine = null
        }
    }
}
