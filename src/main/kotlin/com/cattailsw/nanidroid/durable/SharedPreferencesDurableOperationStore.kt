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

    private var recoveryState = RecoveryState.HEALTHY
    private var recoveryMode = false
    private var recoveryRawPayload: String? = null

    override fun read(): List<DurableOperationRecord> = synchronized(operationLock) {
        if (recoveryMode) return@synchronized emptyList()
        if (isRecoverySignalRequired()) {
            throw DurableOperationStoreCorruptionException("durable operation recovery required")
        }
        try {
            readRecords().values.sortedBy { it.id.value }
        } catch (error: DurableOperationStoreCorruptionException) {
            handleCorruption(error)
        }
    }

    override fun putIfAbsent(record: DurableOperationRecord): Boolean = synchronized(operationLock) {
        if (!isWritable()) return@synchronized false
        val records = readRecords()
        if (record.id in records) return@synchronized false
        records[record.id] = record
        writeRecords(records)
        true
    }

    override fun compareAndSet(
        expected: DurableOperationRecord,
        updated: DurableOperationRecord,
    ): Boolean = synchronized(operationLock) {
        if (!isWritable()) return@synchronized false
        require(updated.id == expected.id) { "operation id cannot change" }
        val records = readRecords()
        val current = records[expected.id] ?: return@synchronized false
        if (current != expected) return@synchronized false
        records[expected.id] = updated
        writeRecords(records)
        true
    }

    internal interface Storage {
        fun read(): String?
        fun write(value: String)
        fun readQuarantine(): String? = null
        fun hasRecoveryMarker(): Boolean
        fun writeQuarantine(value: String)
        fun writeQuarantineAndReset(value: String)
        fun clearQuarantine()
    }

    internal class MemoryStorage(initialValue: String? = null) : Storage {
        private var value: String? = initialValue
        private var quarantinedValue: String? = null
        private var recoveryMarker = false

        @Synchronized override fun read() = value

        @Synchronized override fun readQuarantine() = quarantinedValue

        @Synchronized override fun hasRecoveryMarker() = recoveryMarker

        @Synchronized override fun write(value: String) {
            this.value = value
        }

        @Synchronized override fun writeQuarantine(value: String) {
            quarantinedValue = value
        }

        @Synchronized override fun writeQuarantineAndReset(value: String) {
            quarantinedValue = value.take(MAX_QUARANTINE_CHARS)
            recoveryMarker = true
            write("v3")
        }

        @Synchronized override fun clearQuarantine() {
            quarantinedValue = null
            recoveryMarker = false
        }
    }

    private fun readRecords(): MutableMap<OperationId, DurableOperationRecord> {
        if (recoveryMode) {
            return linkedMapOf()
        }
        return decode(storage.read())
    }

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

        override fun readQuarantine() = preferences.getString(QUARANTINE, null)

        override fun hasRecoveryMarker() = preferences.getBoolean(RECOVERY_MARKER, false)

        override fun writeQuarantine(value: String) {
            check(preferences.edit().putString(QUARANTINE, value).commit()) {
                "could not persist durable operation quarantine"
            }
        }

        override fun writeQuarantineAndReset(value: String) {
            val bounded = value.take(MAX_QUARANTINE_CHARS)
            check(preferences.edit()
                .putString(QUARANTINE, bounded)
                .putBoolean(RECOVERY_MARKER, true)
                .putString(RECORDS, encode(emptyList()))
                .commit()) {
                "could not persist durable operation quarantine"
            }
        }

        override fun clearQuarantine() {
            check(preferences.edit()
                .remove(QUARANTINE)
                .remove(RECOVERY_MARKER)
                .commit()) {
                "could not clear durable operation quarantine"
            }
        }
    }

    internal fun acknowledgeRecoverySignal() = synchronized(operationLock) {
        if (isRecoverySignalRequired()) recoveryMode = true
    }

    internal fun isRecoveryRequired(): Boolean = synchronized(operationLock) {
        isRecoverySignalRequired()
    }

    internal fun resolveRecovery(): Boolean = synchronized(operationLock) {
        if (!isRecoverySignalRequired()) {
            return@synchronized false
        }
        if (
            recoveryState == RecoveryState.PRIMARY_RESET_PENDING &&
            !retryPrimaryReset()
        ) {
            return@synchronized false
        }
        if (recoveryState != RecoveryState.PRIMARY_RESET_SUCCESS) return@synchronized false
        return@synchronized runCatching {
            storage.clearQuarantine()
            recoveryState = RecoveryState.HEALTHY
            recoveryMode = false
            recoveryRawPayload = null
            true
        }.getOrElse { false }
    }

    private fun retryPrimaryReset(): Boolean {
        val payload = recoveryRawPayload ?: return false
        return try {
            storage.writeQuarantineAndReset(payload)
            recoveryState = RecoveryState.PRIMARY_RESET_SUCCESS
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPersistedRecoveryMarker(): Boolean = storage.hasRecoveryMarker()

    private fun isRecoverySignalRequired(): Boolean {
        if (hasPersistedRecoveryMarker() && recoveryState == RecoveryState.HEALTHY) {
            // The production Storage contract makes marker + primary reset one atomic write.
            recoveryState = RecoveryState.PRIMARY_RESET_SUCCESS
        }
        return recoveryState != RecoveryState.HEALTHY
    }

    private fun handleCorruption(error: DurableOperationStoreCorruptionException): Nothing {
        quarantineAndReset(storage.read())
        throw error
    }

    private fun quarantineAndReset(rawValue: String?) {
        require(rawValue != null) { "missing durable operation primary payload for corruption recovery" }
        val bounded = boundedQuarantine(rawValue)
        recoveryRawPayload = bounded
        recoveryState = try {
            storage.writeQuarantineAndReset(bounded)
            RecoveryState.PRIMARY_RESET_SUCCESS
        } catch (_: Exception) {
            RecoveryState.PRIMARY_RESET_PENDING
        }
    }

    private fun isWritable(): Boolean = !isRecoverySignalRequired()

    private fun boundedQuarantine(value: String): String = value.take(MAX_QUARANTINE_CHARS)

    private companion object {
        const val PREFERENCES = "durable_operations_v1"
        const val RECORDS = "records"
        const val QUARANTINE = "records_corruption_quarantine"
        const val RECOVERY_MARKER = "records_corruption_recovery_required"
        const val MAX_QUARANTINE_CHARS = 16_384
        const val VERSION = "v5"
        const val PREVIOUS_VERSION = "v4"
        const val PREVIOUS_PREVIOUS_VERSION = "v3"
        const val PREVIOUS_PREVIOUS_PREVIOUS_VERSION = "v2"
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
                append('\t').append(record.attentionRetryGeneration)
                append('\t').append(record.progressGeneration)
                append('\t').append(record.attentionKeepWaitingGeneration)
            }
        }

        fun decode(value: String?): MutableMap<OperationId, DurableOperationRecord> {
            if (value == null) return linkedMapOf()
            if (value.isEmpty()) {
                throw DurableOperationStoreCorruptionException("missing durable operation version")
            }
            val lines = value.lineSequence().toList()
            val version = lines.firstOrNull()
            if (
                version != VERSION &&
                version != PREVIOUS_VERSION &&
                version != PREVIOUS_PREVIOUS_VERSION &&
                version != PREVIOUS_PREVIOUS_PREVIOUS_VERSION &&
                version != LEGACY_VERSION
            ) {
                throw DurableOperationStoreCorruptionException(
                    "unsupported durable operation version: ${version ?: "missing"}",
                )
            }
            return linkedMapOf<OperationId, DurableOperationRecord>().apply {
                lines.drop(1).forEach { line ->
                    val record = when (version) {
                        VERSION -> decodeRecord(line, hasRetryGeneration = true, hasProgressGeneration = true, hasKeepWaitingGeneration = true)
                        PREVIOUS_VERSION -> decodeRecord(line, hasRetryGeneration = true, hasProgressGeneration = true, hasKeepWaitingGeneration = false)
                        PREVIOUS_PREVIOUS_VERSION -> decodeRecord(line, hasRetryGeneration = true, hasProgressGeneration = false, hasKeepWaitingGeneration = false)
                        PREVIOUS_PREVIOUS_PREVIOUS_VERSION -> decodeRecord(line, hasRetryGeneration = false, hasProgressGeneration = false, hasKeepWaitingGeneration = false)
                        LEGACY_VERSION -> decodeLegacyRecord(line)
                        else -> throw DurableOperationStoreCorruptionException(
                            "unsupported durable operation version: $version",
                        )
                    }
                    if (record.id in this) {
                        throw DurableOperationStoreCorruptionException(
                            "duplicate durable operation id: ${record.id.value}",
                        )
                    }
                    put(record.id, record)
                }
            }
        }

        fun decodeRecord(
            line: String,
            hasRetryGeneration: Boolean,
            hasProgressGeneration: Boolean,
            hasKeepWaitingGeneration: Boolean,
        ): DurableOperationRecord = try {
            val fields = line.split('\t')
            val fieldCount = when {
                hasKeepWaitingGeneration -> 14
                hasProgressGeneration -> 13
                hasRetryGeneration -> 12
                else -> 11
            }
            if (fields.size != fieldCount) throw IllegalArgumentException()
            val id = decoded(fields[0]) ?: throw IllegalArgumentException()
            val binding = when (fields[3]) {
                "-" -> if (fields[4] == "-") null else throw IllegalArgumentException()
                "dm" -> ExternalJobBinding.DownloadManager(fields[4].toLong())
                "wm" -> decoded(fields[4])?.let(::decodeWorkManagerBinding)
                    ?: throw IllegalArgumentException()
                else -> throw IllegalArgumentException()
            }
            val history = decodedHistory(fields[5]) ?: throw IllegalArgumentException()
            DurableOperationRecord(
                id = OperationId(id),
                attemptId = AttemptId(fields[1].toLong()),
                kind = OperationKind.valueOf(fields[2]),
                externalJob = binding,
                progress = OperationProgress(
                    phase = decoded(fields[6]) ?: throw IllegalArgumentException(),
                    completed = fields[7].toLong(),
                ),
                status = OperationStatus.valueOf(fields[8]),
                showStallPrompt = when (fields[9]) {
                    "0" -> false
                    "1" -> true
                    else -> throw IllegalArgumentException()
                },
                diagnostics = decodedDiagnostics(fields[10]),
                externalJobHistory = history,
                attentionRetryGeneration = if (hasRetryGeneration) fields[11].toLong() else 0L,
                progressGeneration = if (hasProgressGeneration) fields[12].toLong() else 0L,
                attentionKeepWaitingGeneration = if (hasKeepWaitingGeneration) fields[13].toLong() else 0L,
            )
        } catch (_: IllegalArgumentException) {
            throw DurableOperationStoreCorruptionException("malformed durable operation row")
        }

        fun decodeLegacyRecord(line: String): DurableOperationRecord = try {
            val fields = line.split('\t')
            if (fields.size != 10 && fields.size != 12) throw IllegalArgumentException()
            val id = decoded(fields[0]) ?: throw IllegalArgumentException()
            val binding = when (fields[3]) {
                "-" -> if (fields[4] == "-") null else throw IllegalArgumentException()
                "dm" -> ExternalJobBinding.DownloadManager(fields[4].toLong())
                "wm" -> decoded(fields[4])?.let(::decodeWorkManagerBinding)
                    ?: throw IllegalArgumentException()
                else -> throw IllegalArgumentException()
            }
            val hasPreviousBinding = fields.size == 12
            val previousBinding = if (hasPreviousBinding) {
                when (fields[5]) {
                    "-" -> if (fields[6] == "-") null else throw IllegalArgumentException()
                    "dm" -> ExternalJobBinding.DownloadManager(fields[6].toLong())
                    "wm" -> decoded(fields[6])?.let(::decodeWorkManagerBinding)
                        ?: throw IllegalArgumentException()
                    else -> throw IllegalArgumentException()
                }
            } else {
                null
            }
            val phaseIndex = if (hasPreviousBinding) 7 else 5
            DurableOperationRecord(
                id = OperationId(id),
                attemptId = AttemptId(fields[1].toLong()),
                kind = OperationKind.valueOf(fields[2]),
                externalJob = binding,
                progress = OperationProgress(
                    phase = decoded(fields[phaseIndex]) ?: throw IllegalArgumentException(),
                    completed = fields[phaseIndex + 1].toLong(),
                ),
                status = OperationStatus.valueOf(fields[phaseIndex + 2]),
                showStallPrompt = when (fields[phaseIndex + 3]) {
                    "0" -> false
                    "1" -> true
                    else -> throw IllegalArgumentException()
                },
                diagnostics = decodedDiagnostics(fields[phaseIndex + 4]),
                externalJobHistory = listOfNotNull(binding, previousBinding).toSet(),
            )
        } catch (_: IllegalArgumentException) {
            throw DurableOperationStoreCorruptionException("malformed durable operation row")
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

                    item.startsWith("w:") -> decoded(item.substring(2))
                        ?.let(::decodeWorkManagerBinding) ?: return null
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

        fun decodedDiagnostics(value: String): String? {
            if (value == "-") return null
            return decoded(value) ?: throw DurableOperationStoreCorruptionException(
                "malformed durable operation row: invalid diagnostics",
            )
        }

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

        fun decodeWorkManagerBinding(value: String): ExternalJobBinding.WorkManager =
            ExternalJobBinding.WorkManager(value)
    }

    private enum class RecoveryState {
        HEALTHY,
        PRIMARY_RESET_PENDING,
        PRIMARY_RESET_SUCCESS,
    }
}
