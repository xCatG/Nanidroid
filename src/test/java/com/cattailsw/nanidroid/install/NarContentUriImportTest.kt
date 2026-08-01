package com.cattailsw.nanidroid.install

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarContentUriImportTest {
    @Test fun streamImportCopiesPrivatelyInvokesInstallerAndDeletesStage() {
        val root = temporaryDirectory()
        val bytes = "downloaded nar bytes".toByteArray()
        var stage: File? = null

        val result = NarContentUriImport.importStream(root, { ByteArrayInputStream(bytes) }) { staged ->
            stage = staged
            assertArrayEquals(bytes, staged.readBytes())
            "/private/ghosts/downloaded"
        }

        assertTrue(result.isSuccess)
        assertFalse(stage!!.exists())
    }

    @Test fun streamImportRejectsArchivesThatExceedTheConfiguredLimit() {
        val root = temporaryDirectory()
        var installed = false

        val result = NarContentUriImport.importStream(
            root,
            { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
            maxBytes = 3,
        ) { installed = true; "/private/ghosts/oversized" }

        assertFalse(result.isSuccess)
        assertFalse(installed)
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
        assertTrue(unreadable.retryable)
        assertFalse(root.listFiles()!!.any { it.name.startsWith("nar-import-") })

        var failedStage: File? = null
        val failed = NarContentUriImport.importContent(
            "content", root, { ByteArrayInputStream(byteArrayOf(7)) },
        ) { staged -> failedStage = staged; null }
        assertFalse(failed.isSuccess)
        assertFalse(failed.retryable)
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
}
