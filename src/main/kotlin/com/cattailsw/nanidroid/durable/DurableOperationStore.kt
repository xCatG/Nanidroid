package com.cattailsw.nanidroid.durable

interface DurableOperationStore {
    fun read(): List<DurableOperationRecord>

    fun putIfAbsent(record: DurableOperationRecord): Boolean

    fun compareAndSet(
        handle: OperationHandle,
        expected: OperationStatus,
        updated: DurableOperationRecord,
    ): Boolean
}
