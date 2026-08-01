package com.cattailsw.nanidroid.install

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarContentUriImportTest {
    @Test fun rejectsMaximumBytesPlusOneBeforeInvokingInstallerAndDeletesStage() {
        val root = temporaryDirectory()
        var installerCalled = false

        val result = NarContentUriImport.importContent(
            scheme = "content",
            cacheDir = root,
            open = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) },
            install = { installerCalled = true; error("installer must not run") },
            isCancelled = { false },
            maximumArchiveBytes = 4,
        )

        assertTrue(result is ArchiveInstallResult.Failed)
        assertFalse(installerCalled)
        assertFalse(root.listFiles()!!.any { it.name.startsWith("nar-import-") })
    }

    @Test fun cancellationDeletesPartialStagingWithoutInvokingInstaller() {
        val root = temporaryDirectory()
        val source = CountingInputStream(ByteArrayInputStream(ByteArray(16 * 1024) { 1 }))
        var installerCalled = false

        val result = NarContentUriImport.importContent(
            scheme = "content",
            cacheDir = root,
            open = { source },
            install = { installerCalled = true; error("installer must not run") },
            isCancelled = { source.bytesRead >= 8192 },
        )

        assertTrue(result === ArchiveInstallResult.Cancelled)
        assertFalse(installerCalled)
        assertFalse(root.listFiles()!!.any { it.name.startsWith("nar-import-") })
    }

    @Test fun rejectsAnythingOtherThanAContentUriBeforeOpeningIt() {
        var opened = false
        val result = NarContentUriImport.importContent(
            "file", temporaryDirectory(),
            open = { opened = true; ByteArrayInputStream(byteArrayOf(1)) },
            install = { error("installer must not run") },
        )

        assertFalse(result.isSuccess)
        assertFalse(opened)
    }

    @Test fun copiesPrivatelyInvokesInstallerAndDeletesStageAfterSuccess() {
        val root = temporaryDirectory()
        val bytes = "valid nar bytes".toByteArray()
        var stage: File? = null
        val result = NarContentUriImport.importContent(
            "content", root, { ByteArrayInputStream(bytes) },
        ) { staged ->
            stage = staged
            assertTrue(staged.parentFile == root)
            assertArrayEquals(bytes, staged.readBytes())
            "/private/ghosts/valid"
        }

        assertTrue(result.isSuccess)
        assertTrue(result.installedPath == "/private/ghosts/valid")
        assertFalse(stage!!.exists())
    }

    @Test fun failedReadAndInstallLeaveNoStageAndPermitRetry() {
        val root = temporaryDirectory()
        val unreadable = NarContentUriImport.importContent("content", root, {
            throw IOException("provider disconnected")
        }) { error("installer must not run") }
        assertFalse(unreadable.isSuccess)
        assertFalse(root.listFiles()!!.any { it.name.startsWith("nar-import-") })

        var failedStage: File? = null
        val failed = NarContentUriImport.importContent(
            "content", root, { ByteArrayInputStream(byteArrayOf(7)) },
        ) { staged -> failedStage = staged; null }
        assertFalse(failed.isSuccess)
        assertNull(failed.installedPath)
        assertFalse(failedStage!!.exists())

        val retry = NarContentUriImport.importContent(
            "content", root, { ByteArrayInputStream(byteArrayOf(8)) },
        ) { "/private/ghosts/retry" }
        assertTrue(retry.isSuccess)
        assertFalse(root.listFiles()!!.any { it.name.startsWith("nar-import-") })
    }

    private fun temporaryDirectory(): File =
        kotlin.io.path.createTempDirectory("nar-content-uri-").toFile()

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }

        override fun close() = delegate.close()
    }
}
