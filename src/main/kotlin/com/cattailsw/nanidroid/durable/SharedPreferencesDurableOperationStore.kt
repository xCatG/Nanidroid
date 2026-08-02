package com.cattailsw.nanidroid.durable

import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
        const val VERSION = "v2"
        const val LEGACY_VERSION = "v1"
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
                append('\t').append(encodedHistory(record.externalJobHistory))
                append('\t').append(encoded(record.progress.phase))
                append('\t').append(record.progress.completed)
                append('\t').append(record.status.name)
                append('\t').append(if (record.showStallPrompt) "1" else "0")
                append('\t').append(encoded(record.diagnostics))
            }
        }

        fun decode(value: String?): MutableMap<OperationId, DurableOperationRecord> {
            if (value == null) return linkedMapOf()
            if (value.isEmpty()) {
                throw DurableOperationStoreCorruptionException("missing durable operation version")
            }
            val lines = value.lineSequence().toList()
            val version = lines.firstOrNull()
            if (version != VERSION && version != LEGACY_VERSION) {
                throw DurableOperationStoreCorruptionException(
                    "unsupported durable operation version: ${version ?: "missing"}",
                )
            }
            return linkedMapOf<OperationId, DurableOperationRecord>().apply {
                lines.drop(1).forEachIndexed { index, line ->
                    val record = when (version) {
                        VERSION -> decodeRecord(line)
                        LEGACY_VERSION -> decodeLegacyRecord(line)
                        else -> null
                    } ?: throw DurableOperationStoreCorruptionException(
                        "malformed durable operation row ${index + 1}",
                    )
                    if (record.id in this) {
                        throw DurableOperationStoreCorruptionException(
                            "duplicate durable operation id: ${record.id.value}",
                        )
                    }
                    put(record.id, record)
                }
            }
        }

        fun decodeRecord(line: String): DurableOperationRecord? = try {
            val fields = line.split('\t')
            if (fields.size != 11) return null
            val binding = when (fields[3]) {
                "-" -> if (fields[4] == "-") null else return null
                "dm" -> ExternalJobBinding.DownloadManager(fields[4].toLong())
                "wm" -> ExternalJobBinding.WorkManager(decoded(fields[4]) ?: return null)
                else -> return null
            }
            val history = decodedHistory(fields[5]) ?: return null
            DurableOperationRecord(
                id = OperationId(decoded(fields[0]) ?: return null),
                attemptId = AttemptId(fields[1].toLong()),
                kind = OperationKind.valueOf(fields[2]),
                externalJob = binding,
                progress = OperationProgress(
                    phase = decoded(fields[6]) ?: return null,
                    completed = fields[7].toLong(),
                ),
                status = OperationStatus.valueOf(fields[8]),
                showStallPrompt = when (fields[9]) {
                    "0" -> false
                    "1" -> true
                    else -> return null
                },
                diagnostics = decoded(fields[10]),
                externalJobHistory = history,
            )
        } catch (_: IllegalArgumentException) {
            null
        }

        fun decodeLegacyRecord(line: String): DurableOperationRecord? = try {
            val fields = line.split('\t')
            if (fields.size != 10 && fields.size != 12) return null
            val binding = when (fields[3]) {
                "-" -> if (fields[4] == "-") null else return null
                "dm" -> ExternalJobBinding.DownloadManager(fields[4].toLong())
                "wm" -> ExternalJobBinding.WorkManager(decoded(fields[4]) ?: return null)
                else -> return null
            }
            val hasPreviousBinding = fields.size == 12
            val previousBinding = if (hasPreviousBinding) {
                when (fields[5]) {
                    "-" -> if (fields[6] == "-") null else return null
                    "dm" -> ExternalJobBinding.DownloadManager(fields[6].toLong())
                    "wm" -> ExternalJobBinding.WorkManager(decoded(fields[6]) ?: return null)
                    else -> return null
                }
            } else {
                null
            }
            val phaseIndex = if (hasPreviousBinding) 7 else 5
            DurableOperationRecord(
                id = OperationId(decoded(fields[0]) ?: return null),
                attemptId = AttemptId(fields[1].toLong()),
                kind = OperationKind.valueOf(fields[2]),
                externalJob = binding,
                progress = OperationProgress(
                    phase = decoded(fields[phaseIndex]) ?: return null,
                    completed = fields[phaseIndex + 1].toLong(),
                ),
                status = OperationStatus.valueOf(fields[phaseIndex + 2]),
                showStallPrompt = when (fields[phaseIndex + 3]) {
                    "0" -> false
                    "1" -> true
                    else -> return null
                },
                diagnostics = decoded(fields[phaseIndex + 4]),
                externalJobHistory = listOfNotNull(binding, previousBinding).toSet(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }

        fun encodedHistory(history: Set<ExternalJobBinding>): String {
            if (history.isEmpty()) return "-"
            val canonical = history.map { binding ->
                when (binding) {
                    is ExternalJobBinding.DownloadManager -> "d:${binding.id}"
                    is ExternalJobBinding.WorkManager -> "w:${encoded(binding.uuid)}"
                }
            }.sorted()
            return encoded(canonical.joinToString(","))
        }

        fun decodedHistory(value: String): Set<ExternalJobBinding>? {
            if (value == "-") return emptySet()
            val payload = decoded(value) ?: return null
            if (payload.isEmpty()) return null
            val history = linkedSetOf<ExternalJobBinding>()
            payload.split(',').forEach { item ->
                val binding = when {
                    item.startsWith("d:") -> ExternalJobBinding.DownloadManager(
                        item.substring(2).toLongOrNull() ?: return null,
                    )
                    item.startsWith("w:") -> ExternalJobBinding.WorkManager(
                        decoded(item.substring(2)) ?: return null,
                    )
                    else -> return null
                }
                if (!history.add(binding)) {
                    throw DurableOperationStoreCorruptionException(
                        "duplicate external job history binding",
                    )
                }
            }
            return history
        }

        fun encoded(value: String?): String = value?.let {
            encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        } ?: "-"

        fun decoded(value: String): String? {
            if (value == "-") return null
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoder.decode(value)))
                    .toString()
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
