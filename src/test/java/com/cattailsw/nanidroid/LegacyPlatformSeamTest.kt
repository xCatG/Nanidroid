package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirms the host seams retain legacy parser and ghost observables. */
class LegacyPlatformSeamTest {
    @Test
    fun deterministicClockAndLoggerKeepDescriptorResultAndElapsedLog() {
        val descriptor = File.createTempFile("nanidroid-desc", ".txt")
        descriptor.writeText("name,Cat\n")
        val logs = mutableListOf<String>()
        val ticks = ArrayDeque(listOf(100L, 125L))
        try {
            val result = LegacyPlatform.withTestSeams(
                clock = { ticks.removeFirst() },
                logger = { tag, message -> logs += "$tag:$message" },
            ) { DescReader(descriptor.path).parse() }

            assertEquals("Cat", result["name"])
            assertTrue(logs.contains("DescReader:parsing took:25ms"))
        } finally {
            descriptor.delete()
        }
    }

    @Test
    fun ghostConstructionStillLogsItsDerivedDirectoryName() {
        val logs = mutableListOf<String>()
        LegacyPlatform.withTestSeams(
            clock = { 0L },
            logger = { tag, message -> logs += "$tag:$message" },
        ) {
            RuntimeFixture(id = "seam-ghost", autoAttach = false).close()
        }

        assertTrue(logs.contains("Ghost:gdname=seam-ghost"))
    }
}
