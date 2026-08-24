package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NarTransactionalInstallerInstrumentationTest {
    @Test
    fun caseVariantTargetConflictPreservesFirstTreeOnAndroidStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "nar-case-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            val first = validGhostZip(root, "Foo", "first")
            val second = validGhostZip(root, "foo", "second")

            assertTrue(NarTransactionalInstaller.install(first, root, null).isSuccess)
            val firstTarget = File(root, "Foo")
            val original = inventory(firstTarget)

            val result = NarTransactionalInstaller.install(second, root, null)

            assertEquals(NarTransactionalInstaller.Error.TARGET_EXISTS, result.error)
            assertFalse(File(root, "foo").exists())
            assertEquals(original.keys, inventory(firstTarget).keys)
            original.forEach { (path, bytes) ->
                assertArrayEquals(bytes, inventory(firstTarget).getValue(path))
            }
        } finally {
            deleteTree(root)
        }
    }

    private fun validGhostZip(root: File, targetId: String, payload: String): File {
        val archive = File(root, "$targetId.nar")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            writeEntry(output, "install.txt", "type,ghost\nname,Test Ghost\ndirectory,$targetId\n")
            writeEntry(output, "ghost/master/descript.txt", "charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n")
            writeEntry(output, "ghost/master/file.txt", payload)
        }
        return archive
    }

    private fun writeEntry(output: ZipOutputStream, path: String, content: String) {
        output.putNextEntry(ZipEntry(path))
        output.write(content.toByteArray())
        output.closeEntry()
    }

    private fun inventory(root: File): Map<String, ByteArray> = buildMap {
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            put(file.relativeTo(root).invariantSeparatorsPath, file.readBytes())
        }
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }
}
