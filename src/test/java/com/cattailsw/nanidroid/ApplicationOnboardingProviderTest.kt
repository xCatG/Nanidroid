package com.cattailsw.nanidroid

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationOnboardingProviderTest {
    // Mutation caught: blank and comment lines are queued or authored lines are reordered.
    @Test
    fun parserKeepsOnlyAuthoredLinesInOrder() {
        val parsed = parseApplicationOnboardingScript(
            StringReader("\n# ignored\n\\0First\\e\n   \n  # also ignored\n\\1Second\\e\n"),
        )

        assertEquals(listOf("\\0First\\e", "\\1Second\\e"), parsed)
    }

    // Mutation caught: the durable version marker is committed before resource parsing succeeds.
    @Test
    fun failedReadDoesNotConsumeTheClaim() {
        var storedVersion = 0
        var loads = 0
        val provider = PersistentApplicationOnboardingProvider(
            storedVersion = { storedVersion },
            commitVersion = { version -> storedVersion = version; true },
            loadScript = {
                loads += 1
                if (loads == 1) throw IllegalStateException("resource unavailable")
                listOf("\\hRECOVERED\\e")
            },
        )

        assertTrue(provider.claimScript().isEmpty())
        assertEquals(0, storedVersion)
        assertEquals(listOf("\\hRECOVERED\\e"), provider.claimScript())
        assertEquals(PersistentApplicationOnboardingProvider.CURRENT_VERSION, storedVersion)
        assertTrue(provider.claimScript().isEmpty())
        assertEquals(2, loads)
    }

    // Mutation caught: a blank/comment-only resource permanently consumes application onboarding.
    @Test
    fun emptyParsedScriptDoesNotConsumeTheClaim() {
        var storedVersion = 0
        var script = emptyList<String>()
        val provider = PersistentApplicationOnboardingProvider(
            storedVersion = { storedVersion },
            commitVersion = { version -> storedVersion = version; true },
            loadScript = { script },
        )

        assertTrue(provider.claimScript().isEmpty())
        assertEquals(0, storedVersion)
        script = listOf("\\hWELCOME\\e")
        assertEquals(script, provider.claimScript())
        assertEquals(PersistentApplicationOnboardingProvider.CURRENT_VERSION, storedVersion)
    }

    // Mutation caught: a failed durable commit still exposes a script that can replay after restart.
    @Test
    fun failedMarkerCommitDoesNotExposeOrConsumeTheClaim() {
        var storedVersion = 0
        var commitSucceeds = false
        val provider = PersistentApplicationOnboardingProvider(
            storedVersion = { storedVersion },
            commitVersion = { version ->
                if (commitSucceeds) storedVersion = version
                commitSucceeds
            },
            loadScript = { listOf("\\hWELCOME\\e") },
        )

        assertTrue(provider.claimScript().isEmpty())
        assertEquals(0, storedVersion)
        commitSucceeds = true
        assertEquals(listOf("\\hWELCOME\\e"), provider.claimScript())
        assertEquals(PersistentApplicationOnboardingProvider.CURRENT_VERSION, storedVersion)
    }

    // Mutation caught: a reconstructed provider ignores the shared durable version marker.
    @Test
    fun reconstructedProviderDoesNotReclaimCommittedOnboarding() {
        var storedVersion = 0
        fun provider() = PersistentApplicationOnboardingProvider(
            storedVersion = { storedVersion },
            commitVersion = { version -> storedVersion = version; true },
            loadScript = { listOf("\\hWELCOME\\e") },
        )

        assertEquals(listOf("\\hWELCOME\\e"), provider().claimScript())
        assertTrue(provider().claimScript().isEmpty())
    }
}
