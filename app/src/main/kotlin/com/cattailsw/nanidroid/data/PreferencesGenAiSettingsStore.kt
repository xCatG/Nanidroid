package com.cattailsw.nanidroid.data

import android.content.SharedPreferences

class PreferencesGenAiSettingsStore(private val preferences: SharedPreferences) : GenAiSettingsStore {
    override fun read(): Boolean = preferences.getBoolean("enabled", false)

    override fun write(enabled: Boolean) {
        check(preferences.edit().putBoolean("enabled", enabled).commit()) {
            "Could not save AI chat preference"
        }
    }
}
