package com.cattailsw.nanidroid.durable

class DurableOperationStoreCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

interface DurableOperationStore {
    fun read(): List<DurableOperationRecord>

    /**
     * Registers for successful writes visible through this store instance.
     *
     * The default preserves the contract for stores that cannot observe external changes.
     */
    fun addChangeListener(listener: () -> Unit): () -> Unit = {}

    fun putIfAbsent(record: DurableOperationRecord): Boolean

    fun compareAndSet(
        expected: DurableOperationRecord,
        updated: DurableOperationRecord,
    ): Boolean
}
