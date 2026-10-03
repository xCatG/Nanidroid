package com.cattailsw.nanidroid.data

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PreferencesBootStateStore(private val preferences: SharedPreferences) : BootStateStore {
    override suspend fun recordActivation(directoryId: String): Boolean = withContext(Dispatchers.IO) {
        val key = "activated_$directoryId"
        if (preferences.getBoolean(key, false)) return@withContext false
        check(preferences.edit().putBoolean(key, true).commit()) { "Could not save ghost activation" }
        true
    }

    override suspend fun consumeOnboarding(): Boolean = withContext(Dispatchers.IO) {
        if (preferences.getBoolean("onboarding_seen", false)) return@withContext false
        check(preferences.edit().putBoolean("onboarding_seen", true).commit()) { "Could not save onboarding state" }
        true
    }
}
