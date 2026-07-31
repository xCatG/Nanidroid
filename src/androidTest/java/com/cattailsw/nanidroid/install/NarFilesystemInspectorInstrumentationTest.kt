package com.cattailsw.nanidroid.install

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.install.NarFilesystemInspector.TrustedRoot
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NarFilesystemInspectorInstrumentationTest {
    private var fixtureRoot: File? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        fixtureRoot = File(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            "narfs-device-" + System.nanoTime()
        )
        Assert.assertTrue("Could not create fixture root", fixtureRoot!!.mkdirs())
    }

    @After
    fun tearDown() {
        deleteRecursively(fixtureRoot)
    }

    @Test
    @Throws(Exception::class)
    fun testArm64NativeFilesystemContract() {
        assertSelectedAarch64Library()

        val emptyTarget: File = directory(fixtureRoot, "empty-target")
        val tree: File = directory(fixtureRoot, "tree")
        directory(tree, "a-empty")
        val unicode: File = directory(tree, "b-\u96ea")
        write(
            File(unicode, "nested-\ud83d\ude00.bin"),
            byteArrayOf(0, 1, 0xfe.toByte(), 0xff.toByte())
        )
        write(File(tree, "c.bin"), byteArrayOf(7, 8, 9))
        val bulk: File = directory(tree, "d-bulk")
        for (index in 0..<BULK_FILES) {
            write(
                File(bulk, "f" + threeDigits(index) + ".bin"),
                byteArrayOf(index.toByte())
            )
        }

        val inspector = NarFilesystemInspector()
        val trusted =
            TrustedRoot(
                fixtureRoot!!.getAbsolutePath()
            )

        val absent =
            inspector.inspect(trusted, "missing")
        assertResult(absent, NarFilesystemInspector.State.ABSENT, 0, 0)

        val empty =
            inspector.inspect(trusted, emptyTarget.getName())
        assertResult(empty, NarFilesystemInspector.State.PRESENT, 0, 0)

        val invalid =
            inspector.inspect(trusted, "tree/nested")
        Assert.assertEquals(NarFilesystemInspector.State.ERROR, invalid.state())
        Assert.assertEquals(
            NarFilesystemInspector.Error.INVALID_TARGET, invalid.error()
        )
        Assert.assertEquals(0, invalid.entryCount().toLong())

        val present =
            inspector.inspect(trusted, tree.getName())
        assertResult(
            present,
            NarFilesystemInspector.State.PRESENT,
            BULK_FILES + 5,
            (BULK_FILES + 7).toLong()
        )
        val entries: List<NarFilesystemInspector.Entry> = present.entries()
        Companion.assertEntry(
            entries.get(0)!!, "a-empty",
            NarFilesystemInspector.Type.DIRECTORY, 0
        )
        Companion.assertEntry(
            entries.get(1)!!, "b-\u96ea",
            NarFilesystemInspector.Type.DIRECTORY, 0
        )
        Companion.assertEntry(
            entries.get(2)!!, "b-\u96ea/nested-\ud83d\ude00.bin",
            NarFilesystemInspector.Type.FILE, 4
        )
        Companion.assertEntry(
            entries.get(3)!!, "c.bin",
            NarFilesystemInspector.Type.FILE, 3
        )
        Companion.assertEntry(
            entries.get(4)!!, "d-bulk",
            NarFilesystemInspector.Type.DIRECTORY, 0
        )
        for (index in 0..<BULK_FILES) {
            Companion.assertEntry(
                entries.get(index + 5)!!,
                "d-bulk/f" + threeDigits(index) + ".bin",
                NarFilesystemInspector.Type.FILE, 1
            )
        }
        Assert.assertThrows(UnsupportedOperationException::class.java) {
            (entries as MutableList<NarFilesystemInspector.Entry>).clear()
        }

        for (repeat in 0..63) {
            val repeated =
                inspector.inspect(trusted, tree.getName())
            assertResult(
                repeated,
                NarFilesystemInspector.State.PRESENT,
                BULK_FILES + 5,
                (BULK_FILES + 7).toLong()
            )
            Assert.assertEquals("a-empty", repeated.entries().get(0).path())
            Assert.assertEquals(
                "d-bulk/f095.bin",
                repeated.entries().get(BULK_FILES + 4).path()
            )
        }
    }

    @Throws(Exception::class)
    private fun assertSelectedAarch64Library() {
        val abi = Build.SUPPORTED_ABIS[0]
        val apk = File(
            InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationInfo().sourceDir
        )
        val header = ByteArray(20)
        var offset = 0
        val zip = ZipFile(apk)
        try {
            val library = zip.getEntry("lib/" + abi + "/libnarfs.so")
            Assert.assertNotNull("Selected narfs APK entry is missing: " + abi, library)
            val input = zip.getInputStream(library)
            try {
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) break
                    offset += count
                }
            } finally {
                input.close()
            }
        } finally {
            zip.close()
        }
        Assert.assertEquals(header.size.toLong(), offset.toLong())
        Assert.assertEquals(0x7f, (header[0].toInt() and 0xff).toLong())
        Assert.assertEquals('E'.code.toLong(), header[1].toLong())
        Assert.assertEquals('L'.code.toLong(), header[2].toLong())
        Assert.assertEquals('F'.code.toLong(), header[3].toLong())
        Assert.assertEquals(2, header[4].toLong())
        Assert.assertEquals(1, header[5].toLong())
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        Assert.assertEquals(expectedElfMachine(abi).toLong(), machine.toLong())
    }

    companion object {
        private const val BULK_FILES = 96
        private fun expectedElfMachine(abi: String?): Int {
            if ("arm64-v8a" == abi) return 183
            if ("x86_64" == abi) return 62
            throw AssertionError("Unsupported runtime ABI: " + abi)
        }

        private fun assertResult(
            result: NarFilesystemInspector.Result,
            state: NarFilesystemInspector.State?,
            count: Int,
            total: Long
        ) {
            Assert.assertEquals(state, result.state())
            Assert.assertEquals(NarFilesystemInspector.Error.OK, result.error())
            Assert.assertEquals(
                NarFilesystemInspector.Error.OK, result.cleanupError()
            )
            Assert.assertEquals(count.toLong(), result.entryCount().toLong())
            Assert.assertEquals(total, result.totalFileSize())
            Assert.assertEquals(count.toLong(), result.entries().size.toLong())
        }

        private fun assertEntry(
            entry: NarFilesystemInspector.Entry,
            path: String?,
            type: NarFilesystemInspector.Type?,
            size: Long
        ) {
            Assert.assertEquals(path, entry.path())
            Assert.assertEquals(type, entry.type())
            Assert.assertEquals(size, entry.size())
            Assert.assertTrue(entry.device() > 0)
            Assert.assertTrue(entry.inode() > 0)
        }

        private fun directory(parent: File?, name: String): File {
            val value = File(parent, name)
            Assert.assertTrue("Could not create " + value, value.mkdir())
            return value
        }

        @Throws(Exception::class)
        private fun write(file: File?, value: ByteArray?) {
            val output = FileOutputStream(file)
            try {
                output.write(value)
            } finally {
                output.close()
            }
        }

        private fun threeDigits(value: Int): String {
            if (value < 10) return "00" + value
            if (value < 100) return "0" + value
            return value.toString()
        }

        private fun deleteRecursively(file: File?) {
            if (file == null || !file.exists()) return
            if (file.isDirectory()) {
                val children = file.listFiles()
                if (children != null) {
                    for (child in children) deleteRecursively(child)
                }
            }
            Assert.assertTrue("Could not delete " + file, file.delete())
        }
    }
}
