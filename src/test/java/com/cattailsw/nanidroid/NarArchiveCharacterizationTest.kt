package com.cattailsw.nanidroid

import android.content.Intent
import com.cattailsw.nanidroid.util.NarUtil
import com.cattailsw.nanidroid.install.NarLocalArchiveStager
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Arrays
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Characterizes only successful, trusted forced-id extraction of a bounded,
 * collision-free archive. Rejection and containment policy belong to D9b.
 */
class NarArchiveCharacterizationTest {
    @Rule
    @JvmField
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun externalArchiveIntent_acceptsOnlyGrantedContentArchives() {
        Assert.assertTrue(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_VIEW,
                "content",
                "application/x-nar",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        Assert.assertFalse(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_VIEW,
                "https",
                "application/x-nar",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        Assert.assertFalse(
            ArchiveIntentAdapter.accepts(
                Intent.ACTION_VIEW,
                "content",
                "application/x-nar",
                0,
            ),
        )
    }

    @Test
    fun temporaryArchiveStageFailureLeavesNoPrivateFile() {
        val directory = temporaryFolder.newFolder("temporary-archive")

        val result = NarLocalArchiveStager.stage(directory) { null }

        Assert.assertTrue(result is NarLocalArchiveStager.Result.Failed)
        Assert.assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_trustedForcedIdPreservesExactTreeAndBytes() {
        val sourceRoot = temporaryFolder.newFolder("source")
        val installRoot = temporaryFolder.newFolder("install")
        val archive = File(sourceRoot, "synthetic.nar")
        writeArchive(archive)

        val installed: Boolean = NarUtil.readNarArchive(
            archive.getAbsolutePath(),
            installRoot.getAbsolutePath(),
            "seed-ghost"
        )

        Assert.assertTrue(installed)
        Assert.assertArrayEquals(arrayOf<String>("seed-ghost"), sortedNames(installRoot))
        Assert.assertFalse(File(installRoot, "descriptor-ghost").exists())
        Assert.assertEquals(
            mutableListOf<String>(
                "seed-ghost/",
                "seed-ghost/ghost/",
                "seed-ghost/ghost/master/",
                "seed-ghost/ghost/master/data/",
                "seed-ghost/ghost/master/data/payload.bin",
                "seed-ghost/ghost/master/descript.txt",
                "seed-ghost/install.txt",
                "seed-ghost/readme.txt",
                "seed-ghost/shell/",
                "seed-ghost/shell/master/",
                "seed-ghost/shell/master/descript.txt"
            ),
            sortedTree(installRoot)
        )

        val installedRoot = File(installRoot, "seed-ghost")
        Assert.assertArrayEquals(
            INSTALL_DESCRIPTOR,
            readBytes(File(installedRoot, "install.txt"))
        )
        Assert.assertArrayEquals(
            GHOST_DESCRIPTOR,
            readBytes(File(installedRoot, "ghost/master/descript.txt"))
        )
        Assert.assertArrayEquals(
            SHELL_DESCRIPTOR,
            readBytes(File(installedRoot, "shell/master/descript.txt"))
        )
        Assert.assertArrayEquals(
            README,
            readBytes(File(installedRoot, "readme.txt"))
        )
        val installedPayload: ByteArray = readBytes(
            File(installedRoot, "ghost/master/data/payload.bin")
        )
        Assert.assertArrayEquals(BINARY_PAYLOAD, installedPayload)
        Assert.assertEquals(
            "89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3",
            sha256(installedPayload)
        )
    }

    companion object {
        private val ASCII: Charset = Charset.forName("US-ASCII")
        private val INSTALL_DESCRIPTOR: ByteArray = ascii(
            "type,ghost\n"
                    + "directory,descriptor-ghost\n"
        )
        private val GHOST_DESCRIPTOR: ByteArray = ascii(
            "name,Seed Ghost\n"
                    + "sakura.name,Seed Sakura\n"
        )
        private val SHELL_DESCRIPTOR: ByteArray = ascii("name,Master Shell\n")
        private val README: ByteArray = ascii("Synthetic forced-id archive.\n")
        private val BINARY_PAYLOAD = byteArrayOf(
            0x00.toByte(),
            0x7f.toByte(),
            0x80.toByte(),
            0xff.toByte(),
        )

        @Throws(Exception::class)
        private fun writeArchive(archive: File) {
            val output = ZipOutputStream(FileOutputStream(archive))
            try {
                // Deliberately avoid descriptor-first ordering.
                writeEntry(output, "ghost/master/data/payload.bin", BINARY_PAYLOAD)
                writeEntry(output, "readme.txt", README)
                writeEntry(output, "ghost/master/descript.txt", GHOST_DESCRIPTOR)
                writeEntry(output, "install.txt", INSTALL_DESCRIPTOR)
                writeEntry(output, "shell/master/descript.txt", SHELL_DESCRIPTOR)
            } finally {
                output.close()
            }
        }

        @Throws(Exception::class)
        private fun writeEntry(
            output: ZipOutputStream,
            name: String,
            content: ByteArray
        ) {
            output.putNextEntry(ZipEntry(name))
            output.write(content)
            output.closeEntry()
        }

        private fun sortedNames(directory: File): Array<String> {
            val names = directory.list()
            if (names == null) {
                return emptyArray()
            }
            Arrays.sort(names)
            return names.filterNotNull().toTypedArray()
        }

        private fun sortedTree(root: File): MutableList<String> {
            val paths: MutableList<String> = ArrayList<String>()
            collectTree(root, "", paths)
            Collections.sort<String>(paths)
            return paths
        }

        private fun collectTree(
            directory: File,
            prefix: String,
            paths: MutableList<String>
        ) {
            val children = directory.listFiles()
            if (children == null) {
                return
            }
            Arrays.sort<File>(children, object : Comparator<File> {
                override fun compare(left: File, right: File): Int {
                    return left.getName().compareTo(right.getName())
                }
            })
            for (child in children) {
                val relative = prefix + child.getName()
                if (child.isDirectory()) {
                    paths.add(relative + "/")
                    collectTree(child, relative + "/", paths)
                } else {
                    paths.add(relative)
                }
            }
        }

        @Throws(Exception::class)
        private fun readBytes(file: File): ByteArray {
            val input = FileInputStream(file)
            try {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(256)
                var count: Int
                while ((input.read(buffer).also { count = it }) != -1) {
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            } finally {
                input.close()
            }
        }

        @Throws(Exception::class)
        private fun sha256(content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            val result = StringBuilder(digest.size * 2)
            for (value in digest) {
                result.append(String.format("%02x", value.toInt() and 0xff))
            }
            return result.toString()
        }

        private fun ascii(value: String): ByteArray {
            return value.toByteArray(ASCII)
        }
    }
}
