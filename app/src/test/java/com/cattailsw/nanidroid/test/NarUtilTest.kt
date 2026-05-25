package com.cattailsw.nanidroid.test

import com.cattailsw.nanidroid.util.NarUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
