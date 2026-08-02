package com.cattailsw.nanidroid.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesDurableOperationStoreTest {
    @Test fun emptyPresentValueFailsClosedWithoutRewritingRawData() {
        assertCorruptionIsPreserved("", "missing durable operation version")
    }

    @Test fun unknownVersionFailsClosedWithoutRewritingRawData() {
        assertCorruptionIsPreserved("v2", "unsupported durable operation version")
    }

    @Test fun malformedRowFailsClosedWithoutRewritingRawData() {
        assertCorruptionIsPreserved("v1\nnot-a-record", "malformed durable operation row")
    }

    @Test fun duplicateIdFailsClosedWithoutRewritingRawData() {
        val first = "YQ\t1\tGHOST_UPDATE\twm\td29yay0x\tUXVldWVk\t0\tRUNNING\t0\t-"
        val second = "YQ\t2\tGHOST_UPDATE\twm\td29yay0y\tUXVldWVk\t0\tRUNNING\t0\t-"
        assertCorruptionIsPreserved("v1\n$first\n$second", "duplicate durable operation id")
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
                OperationHandle(OperationId("a"), AttemptId(1)),
                OperationStatus.RUNNING,
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

    private class RecordingStorage(initialValue: String) : SharedPreferencesDurableOperationStore.Storage {
        var value = initialValue
        var writeCount = 0

        override fun read() = value

        override fun write(value: String) {
            this.value = value
            writeCount += 1
        }
    }
}
