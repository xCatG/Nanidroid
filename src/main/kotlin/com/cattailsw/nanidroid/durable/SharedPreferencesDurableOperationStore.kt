package com.cattailsw.nanidroid.durable

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Persists non-archive durable operations as one atomic preference value. */
class SharedPreferencesDurableOperationStore internal constructor(private val storage: Storage) :
    DurableOperationStore {
    constructor(context: Context) : this(
        SharedPreferencesStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    override fun read(): List<DurableOperationRecord> = synchronized(operationLock) {
        readRecords().values.sortedBy { it.id.value }
    }

    override fun putIfAbsent(record: DurableOperationRecord): Boolean = synchronized(operationLock) {
        val records = readRecords()
        if (record.id in records) return@synchronized false
        records[record.id] = record
        writeRecords(records)
        true
    }

    override fun compareAndSet(
        handle: OperationHandle,
        expected: OperationStatus,
        updated: DurableOperationRecord,
    ): Boolean = synchronized(operationLock) {
        require(updated.id == handle.operationId) { "operation id cannot change" }
        val records = readRecords()
        val current = records[handle.operationId] ?: return@synchronized false
        if (current.attemptId != handle.attemptId || current.status != expected) {
            return@synchronized false
        }
        records[handle.operationId] = updated
        writeRecords(records)
        true
    }

    internal interface Storage {
        fun read(): String?
        fun write(value: String)
    }

    internal class MemoryStorage : Storage {
        private var value: String? = null

        @Synchronized override fun read() = value

        @Synchronized override fun write(value: String) {
            this.value = value
        }
    }

    private fun readRecords(): MutableMap<OperationId, DurableOperationRecord> = decode(storage.read())

    private fun writeRecords(records: Map<OperationId, DurableOperationRecord>) {
        storage.write(encode(records.values))
    }

    private class SharedPreferencesStorage(private val preferences: SharedPreferences) : Storage {
        override fun read() = preferences.getString(RECORDS, null)

        override fun write(value: String) {
            check(preferences.edit().putString(RECORDS, value).commit()) {
                "could not persist durable operations"
            }
        }
    }

    private companion object {
        const val PREFERENCES = "durable_operations_v1"
        const val RECORDS = "records"
        const val VERSION = "v1"
        val operationLock = Any()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder = Base64.getUrlDecoder()

        fun encode(records: Collection<DurableOperationRecord>): String = buildString {
            append(VERSION)
            records.sortedBy { it.id.value }.forEach { record ->
                append('\n')
                append(encoded(record.id.value))
                append('\t').append(record.attemptId.value)
                append('\t').append(record.kind.name)
                when (val binding = record.externalJob) {
                    null -> append("\t-\t-")
                    is ExternalJobBinding.DownloadManager -> append("\tdm\t").append(binding.id)
                    is ExternalJobBinding.WorkManager -> append("\twm\t").append(encoded(binding.uuid))
                }
                append('\t').append(encoded(record.progress.phase))
                append('\t').append(record.progress.completed)
                append('\t').append(record.status.name)
                append('\t').append(if (record.showStallPrompt) "1" else "0")
                append('\t').append(encoded(record.diagnostics))
            }
        }

        fun decode(value: String?): MutableMap<OperationId, DurableOperationRecord> {
            if (value.isNullOrEmpty()) return linkedMapOf()
            if (value.lineSequence().firstOrNull() != VERSION) return linkedMapOf()
            return linkedMapOf<OperationId, DurableOperationRecord>().apply {
                value.lineSequence().drop(1).forEach { line ->
                    decodeRecord(line)?.let { put(it.id, it) }
                }
            }
        }

        fun decodeRecord(line: String): DurableOperationRecord? = try {
            val fields = line.split('\t')
            if (fields.size != 10) return null
            val binding = when (fields[3]) {
                "-" -> null
                "dm" -> ExternalJobBinding.DownloadManager(fields[4].toLong())
                "wm" -> ExternalJobBinding.WorkManager(decoded(fields[4]) ?: return null)
                else -> return null
            }
            DurableOperationRecord(
                id = OperationId(decoded(fields[0]) ?: return null),
                attemptId = AttemptId(fields[1].toLong()),
                kind = OperationKind.valueOf(fields[2]),
                externalJob = binding,
                progress = OperationProgress(
                    phase = decoded(fields[5]) ?: return null,
                    completed = fields[6].toLong(),
                ),
                status = OperationStatus.valueOf(fields[7]),
                showStallPrompt = when (fields[8]) {
                    "0" -> false
                    "1" -> true
                    else -> return null
                },
                diagnostics = decoded(fields[9]),
            )
        } catch (_: IllegalArgumentException) {
            null
        }

        fun encoded(value: String?): String = value?.let {
            encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        } ?: "-"

        fun decoded(value: String): String? = if (value == "-") null else {
            String(decoder.decode(value), StandardCharsets.UTF_8)
        }
    }
}
