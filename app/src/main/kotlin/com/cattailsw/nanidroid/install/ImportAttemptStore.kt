package com.cattailsw.nanidroid.install

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Remembers the last result admitted to copying, without retaining its URI. */
class ImportAttemptStore(
    private val read: suspend (String) -> Boolean,
    private val mark: suspend (String) -> Unit,
) {
    suspend fun wasConsumed(id: String): Boolean = read(id)
    suspend fun markConsumed(id: String) = mark(id)

    companion object {
        fun fromPreferences(preferences: SharedPreferences) = ImportAttemptStore(
            read = { id -> withContext(Dispatchers.IO) {
                preferences.getString("consumed_id", null) == id
            } },
            mark = { id -> withContext(Dispatchers.IO) {
                if (!preferences.edit().putString("consumed_id", id).commit()) {
                    throw IllegalStateException("Unable to remember import attempt")
                }
            } },
        )
    }
}
