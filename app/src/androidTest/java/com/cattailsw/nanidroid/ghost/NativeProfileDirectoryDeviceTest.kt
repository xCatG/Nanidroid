package com.cattailsw.nanidroid.ghost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeProfileDirectoryDeviceTest {
    @Test fun existingUnwritableProfileIsRejected() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "profile-readonly-test-${System.nanoTime()}")
        val profile = File(root, "profile")
        try {
            assertTrue(profile.mkdirs())
            Files.setPosixFilePermissions(profile.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
            assertFalse("Fixture must be unwritable to the app", profile.canWrite())

            assertTrue(runCatching { NativeProfileDirectory.prepare(root) }.exceptionOrNull() is IllegalStateException)
        } finally {
            if (profile.exists()) {
                Files.setPosixFilePermissions(profile.toPath(), setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE))
            }
            profile.delete()
            root.delete()
        }
    }

    @Test fun escapingProfileSymlinkIsRejected() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "profile-link-test-${System.nanoTime()}")
        val master = File(root, "master")
        val outside = File(root, "outside")
        val profile = File(master, "profile")
        try {
            assertTrue(master.mkdirs())
            assertTrue(outside.mkdir())
            Files.createSymbolicLink(profile.toPath(), outside.toPath())

            assertTrue(runCatching { NativeProfileDirectory.prepare(master) }.exceptionOrNull() is IllegalStateException)
        } finally {
            Files.deleteIfExists(profile.toPath())
            outside.delete()
            master.delete()
            root.delete()
        }
    }
}
