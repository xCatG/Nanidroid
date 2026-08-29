package com.cattailsw.nanidroid

import android.content.Context
import android.content.res.Resources
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.Reader
import java.util.Collections

internal fun interface ApplicationOnboardingProvider {
    fun claimScript(): List<String>

    companion object {
        val None = ApplicationOnboardingProvider { emptyList() }
    }
}

internal class PersistentApplicationOnboardingProvider(
    private val storedVersion: () -> Int,
    private val commitVersion: (Int) -> Boolean,
    private val loadScript: () -> List<String>,
) : ApplicationOnboardingProvider {
    @Synchronized
    override fun claimScript(): List<String> = try {
        if (storedVersion() >= CURRENT_VERSION) return emptyList()
        val parsed = loadScript()
        if (parsed.isEmpty()) return emptyList()
        if (!commitVersion(CURRENT_VERSION)) return emptyList()
        Collections.unmodifiableList(ArrayList(parsed))
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal fun applicationOnboardingProvider(context: Context): ApplicationOnboardingProvider {
    val applicationContext = context.applicationContext
    val preferences = PrefUtil.getSharedPreferences(applicationContext)
    return PersistentApplicationOnboardingProvider(
        storedVersion = { preferences.getInt(APPLICATION_ONBOARDING_VERSION_KEY, 0) },
        commitVersion = { version ->
            preferences.edit().putInt(APPLICATION_ONBOARDING_VERSION_KEY, version).commit()
        },
        loadScript = { readApplicationOnboardingScript(applicationContext.resources) },
    )
}

internal fun readApplicationOnboardingScript(resources: Resources): List<String> =
    resources.openRawResource(R.raw.first_run_script).bufferedReader(Charsets.UTF_8).use {
        parseApplicationOnboardingScript(it)
    }

internal fun parseApplicationOnboardingScript(reader: Reader): List<String> =
    reader.buffered().readLines().filter { line ->
        line.isNotBlank() && !line.trimStart().startsWith("#")
    }

private const val APPLICATION_ONBOARDING_VERSION_KEY = "application_onboarding_version"
