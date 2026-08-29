package com.cattailsw.nanidroid

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationOnboardingResourcesTest {
    // Mutation caught: a locale-qualified resource is missing or loses its retained onboarding guidance.
    @Test
    fun allLocalizedScriptsRetainWelcomeToolbarGhostDownloadAndTermination() {
        listOf(
            Locale.ENGLISH to listOf("Thank you", "Tap on any place", "switching ghosts", "download", "\\e"),
            Locale.JAPANESE to listOf("インストール", "空白", "切り替え", "調達", "\\e"),
            Locale.TRADITIONAL_CHINESE to listOf("感謝", "輕點", "切換", "下載", "\\e"),
        ).forEach { (locale, retained) ->
            val base = ApplicationProvider.getApplicationContext<android.content.Context>()
            val configuration = Configuration(base.resources.configuration).apply {
                setLocale(locale)
            }
            val localized = base.createConfigurationContext(configuration)
            val lines = readApplicationOnboardingScript(localized.resources)
            val script = lines.joinToString(separator = "")

            retained.forEach { phrase ->
                assertTrue("${locale.toLanguageTag()} missing $phrase", script.contains(phrase))
            }
            assertTrue("${locale.toLanguageTag()} must terminate", script.endsWith("\\e"))
        }
    }
}
