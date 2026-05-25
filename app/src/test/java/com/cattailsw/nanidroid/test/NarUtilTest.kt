package com.cattailsw.nanidroid.test

import com.cattailsw.nanidroid.util.NarUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class NarUtilTest {

    @Test
    fun testReadNarGhostId() {
        // Programmatically generate a temporary test NAR/ZIP file at runtime
        val tempFile = File.createTempFile("test_ghost", ".nar")
        tempFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            val entry = ZipEntry("install.txt")
            zos.putNextEntry(entry)
            val content = "type,ghost\ndirectory,test_ghost_id\n"
            zos.write(content.toByteArray(Charset.forName("Shift_JIS")))
            zos.closeEntry()
        }

        // Test the readNarGhostId method
        val ghostId = NarUtil.readNarGhostId(tempFile.absolutePath)
        println("Extracted ghost ID: $ghostId")
        
        assertNotNull("Ghost ID should not be null", ghostId)
        assertEquals("test_ghost_id", ghostId)

        // Clean up
        tempFile.delete()
    }

    @Test
    fun testZipSlipPrevention() {
        val tempFile = File.createTempFile("zipslip_test", ".nar")
        tempFile.deleteOnExit()

        val targetDir = File.createTempFile("target_dir", "")
        targetDir.delete()
        targetDir.mkdirs()
        targetDir.deleteOnExit()

        // Create a ZIP with a path traversal entry
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            // First we need a valid install.txt for the archive reader to process it
            zos.putNextEntry(ZipEntry("install.txt"))
            val content = "type,ghost\ndirectory,test_ghost_id\n"
            zos.write(content.toByteArray(Charset.forName("Shift_JIS")))
            zos.closeEntry()

            // Path traversal entry trying to escape targetDir
            val badEntry = ZipEntry("../escaped_file.txt")
            zos.putNextEntry(badEntry)
            zos.write("malicious payload".toByteArray())
            zos.closeEntry()
        }

        var exceptionThrown = false
        try {
            NarUtil.readNarArchive(tempFile.absolutePath, targetDir.absolutePath, null)
        } catch (e: SecurityException) {
            exceptionThrown = true
            println("SecurityException correctly caught: ${e.message}")
        }

        org.junit.Assert.assertTrue("Should throw SecurityException for Zip Slip entry", exceptionThrown)

        // Verify that the file was NOT created outside targetDir
        val escapedFile = File(targetDir.parentFile, "escaped_file.txt")
        org.junit.Assert.assertFalse("Escaped file should not exist", escapedFile.exists())

        // Clean up
        tempFile.delete()
        targetDir.deleteRecursively()
    }

    @Test
    fun testInstallDirectoryTraversalPrevention() {
        val tempFile = File.createTempFile("install_dir_traversal", ".nar")
        tempFile.deleteOnExit()

        val installRoot = File.createTempFile("install_root", "")
        installRoot.delete()
        installRoot.mkdirs()
        installRoot.deleteOnExit()
        val escapedDirName = "escaped_ghost_${System.nanoTime()}"
        val escapedDir = File(installRoot.parentFile, escapedDirName)

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry("install.txt"))
            val content = "type,ghost\ndirectory,../$escapedDirName\n"
            zos.write(content.toByteArray(Charset.forName("Shift_JIS")))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ghost/master/descript.txt"))
            zos.write("name,malicious\n".toByteArray(Charset.forName("Shift_JIS")))
            zos.closeEntry()
        }

        var exceptionThrown = false
        try {
            NarUtil.readNarArchive(tempFile.absolutePath, installRoot.absolutePath, null)
        } catch (e: SecurityException) {
            exceptionThrown = true
        }

        assertTrue("Should reject an install.txt directory outside the install root", exceptionThrown)
        assertFalse("Escaped install directory should not be created", escapedDir.exists())

        tempFile.delete()
        installRoot.deleteRecursively()
        escapedDir.deleteRecursively()
    }

    @Test
    fun testIsPathSafe() {
        val rootPath = "/home/user/app/ghosts"

        // Safe paths
        org.junit.Assert.assertTrue(NarUtil.isPathSafe(rootPath, "ghost1/descript.txt"))
        org.junit.Assert.assertTrue(NarUtil.isPathSafe(rootPath, "ghost2/shell/master/surface0.png"))

        // Unsafe paths
        org.junit.Assert.assertFalse(NarUtil.isPathSafe(rootPath, "../malicious.txt"))
        org.junit.Assert.assertFalse(NarUtil.isPathSafe(rootPath, "ghost1/../../../malicious.txt"))
        org.junit.Assert.assertFalse(NarUtil.isPathSafe(rootPath, "/etc/passwd"))
    }
}
