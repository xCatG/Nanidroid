package com.cattailsw.nanidroid.durable

class DurableOperationStoreCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

interface DurableOperationStore {
    fun read(): List<DurableOperationRecord>

    fun putIfAbsent(record: DurableOperationRecord): Boolean

    fun compareAndSet(
        handle: OperationHandle,
        expected: OperationStatus,
        updated: DurableOperationRecord,
    ): Boolean
}
