package com.cattailsw.nanidroid.data

import android.content.SharedPreferences

class PreferencesLastGhostStore(private val preferences: SharedPreferences) : LastGhostStore {
    override fun read(): String? = preferences.getString("last_ghost", null)

    override fun write(directoryId: String) {
        check(preferences.edit().putString("last_ghost", directoryId).commit()) {
            "Could not save last ghost"
        }
    }
}
