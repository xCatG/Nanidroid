package com.cattailsw.nanidroid

import android.app.Instrumentation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupRecoveryTest {
    @Test fun startupRemovesAbandonedImportWithoutTouchingPublishedGhost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = instrumentation.targetContext.filesDir
        val suffix = System.nanoTime().toString()
        val attempt = File(files, "import-staging/attempt-startup-$suffix")
        val published = File(files, "ghost/startup-test-$suffix")
        try {
            assertTrue(attempt.mkdirs())
            File(attempt, "source.nar").writeText("incomplete")
            assertTrue(published.mkdirs())
            File(published, "sentinel").writeText("published")

            val app = Instrumentation.newApplication(NanidroidApplication::class.java,
                instrumentation.targetContext) as NanidroidApplication
            val started = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { app.onCreate() }
            assertTrue("Application.onCreate blocked the UI thread",
                SystemClock.elapsedRealtime() - started < 2_000)

            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (attempt.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertFalse("Abandoned attempt survived startup", attempt.exists())
            assertEquals("published", File(published, "sentinel").readText())
        } finally {
            attempt.deleteRecursively()
            published.deleteRecursively()
        }
    }

    @Test fun failedRecoveryDoesNotPreventReadingPublishedGhost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = instrumentation.targetContext.filesDir
        val suffix = System.nanoTime().toString()
        val attempt = File(files, "import-staging/attempt-unsafe-$suffix")
        val outside = File(files, "startup-outside-$suffix")
        val published = File(files, "ghost/startup-test-$suffix")
        try {
            assertTrue(attempt.mkdirs())
            assertTrue(outside.mkdirs())
            File(outside, "sentinel").writeText("outside")
            Files.createSymbolicLink(File(attempt, "link").toPath(), outside.toPath())
            assertTrue(published.mkdirs())
            File(published, "sentinel").writeText("published")

            val app = Instrumentation.newApplication(NanidroidApplication::class.java,
                instrumentation.targetContext) as NanidroidApplication
            instrumentation.runOnMainSync { app.onCreate() }
            assertTrue(runCatching { runBlocking { app.ghostImporter.recoverAbandonedAttempts() } }.isFailure)

            assertEquals("published", File(published, "sentinel").readText())
            assertEquals("outside", File(outside, "sentinel").readText())
        } finally {
            Files.deleteIfExists(File(attempt, "link").toPath())
            attempt.deleteRecursively()
            outside.deleteRecursively()
            published.deleteRecursively()
        }
    }
}
