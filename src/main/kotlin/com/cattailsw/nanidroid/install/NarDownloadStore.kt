package com.cattailsw.nanidroid.install

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Persists NAR download records as one atomic, serialized preference value. */
class NarDownloadStore internal constructor(private val storage: Storage) {
    constructor(context: Context) : this(
        SharedPreferencesStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    fun create(download: NarDownload): NarDownload = synchronized(operationLock) {
        val records = readRecords()
        require(download.id !in records) { "download already exists: ${download.id}" }
        val createdAtMillis = maxOf(
            System.currentTimeMillis(),
            records.values.maxOfOrNull(NarDownload::createdAtMillis)?.plus(1) ?: 0,
        )
        records[download.id] = download.copy(createdAtMillis = createdAtMillis)
        writeRecords(records)
        records.getValue(download.id)
    }

    fun update(id: String, transform: (NarDownload) -> NarDownload): NarDownload? = synchronized(operationLock) {
        val records = readRecords()
        val current = records[id] ?: return@synchronized null
        val updated = transform(current).let { candidate ->
            if (candidate.state is NarDownloadState.NeedsAttention) {
                candidate.copy(
                    source = current.source,
                    retainedUri = current.retainedUri,
                    downloadManagerId = current.downloadManagerId,
                )
            } else {
                candidate
            }
        }
        require(updated.id == id) { "download id cannot change" }
        records[id] = updated
        writeRecords(records)
        updated
    }

    fun get(id: String): NarDownload? = synchronized(operationLock) { readRecords()[id] }

    fun getAll(): List<NarDownload> = synchronized(operationLock) {
        readRecords().values.sortedWith(compareBy(NarDownload::createdAtMillis, NarDownload::id))
    }

    fun delete(id: String): NarDownload? = synchronized(operationLock) {
        val records = readRecords()
        val removed = records.remove(id) ?: return@synchronized null
        writeRecords(records)
        removed
    }

    interface Storage {
        fun read(): String?
        fun write(value: String)
    }

    class MemoryStorage : Storage {
        private var value: String? = null

        @Synchronized override fun read() = value
        @Synchronized override fun write(value: String) {
            this.value = value
        }
    }

    private fun readRecords(): MutableMap<String, NarDownload> = decode(storage.read())

    private fun writeRecords(records: Map<String, NarDownload>) {
        storage.write(encode(records.values))
    }

    private class SharedPreferencesStorage(private val preferences: SharedPreferences) : Storage {
        override fun read() = preferences.getString(RECORDS, null)

        override fun write(value: String) {
            check(preferences.edit().putString(RECORDS, value).commit()) {
                "could not persist NAR downloads"
            }
        }
    }

    private companion object {
        const val PREFERENCES = "nar-download-queue"
        const val RECORDS = "records-v1"
        const val VERSION = "v1"
        val operationLock = Any()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder = Base64.getUrlDecoder()

        fun encode(records: Collection<NarDownload>): String = buildString {
            append(VERSION)
            records.sortedBy { it.id }.forEach { download ->
                append('\n')
                append(encoded(download.id))
                append('\t')
                when (val source = download.source) {
                    is NarDownloadSource.Local -> append("local\t").append(encoded(source.uri))
                    is NarDownloadSource.Remote -> append("remote\t").append(encoded(source.uri))
                }
                append('\t').append(encoded(download.retainedUri))
                append('\t').append(download.downloadManagerId ?: "-")
                append('\t')
                when (val state = download.state) {
                    NarDownloadState.Copying -> append("copying\t-")
                    NarDownloadState.Queued -> append("queued\t-")
                    NarDownloadState.Downloading -> append("downloading\t-")
                    NarDownloadState.Installing -> append("installing\t-")
                    NarDownloadState.Complete -> append("complete\t-")
                    is NarDownloadState.NeedsAttention -> append("attention\t").append(encoded(state.failure.message))
                }
                append('\t').append(download.createdAtMillis)
            }
        }

        fun decode(value: String?): MutableMap<String, NarDownload> {
            if (value.isNullOrEmpty()) return linkedMapOf()
            if (value.lineSequence().firstOrNull() != VERSION) return linkedMapOf()
            return linkedMapOf<String, NarDownload>().apply {
                value.lineSequence().drop(1).forEach { line -> decodeRecord(line)?.let { put(it.id, it) } }
            }
        }

        fun decodeRecord(line: String): NarDownload? = try {
            val fields = line.split('\t')
            if (fields.size !in 6..8) return null
            val hasDownloadManagerId = fields.size >= 7
            val stateIndex = if (hasDownloadManagerId) 5 else 4
            val failureIndex = stateIndex + 1
            val source = when (fields[1]) {
                "local" -> NarDownloadSource.Local(decoded(fields[2]) ?: return null)
                "remote" -> NarDownloadSource.Remote(decoded(fields[2]) ?: return null)
                else -> return null
            }
            val state = when (fields[stateIndex]) {
                "copying" -> NarDownloadState.Copying
                "queued" -> NarDownloadState.Queued
                "downloading" -> NarDownloadState.Downloading
                "installing" -> NarDownloadState.Installing
                "complete" -> NarDownloadState.Complete
                "attention" -> NarDownloadState.NeedsAttention(
                    NarDownloadState.Failure(decoded(fields[failureIndex]) ?: return null),
                )
                else -> return null
            }
            val downloadManagerId = if (hasDownloadManagerId && fields[4] != "-") {
                fields[4].toLong()
            } else {
                null
            }
            NarDownload(
                id = decoded(fields[0]) ?: return null,
                source = source,
                retainedUri = decoded(fields[3]),
                downloadManagerId = downloadManagerId,
                createdAtMillis = fields.getOrNull(7)?.toLongOrNull() ?: 0,
                state = state,
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
