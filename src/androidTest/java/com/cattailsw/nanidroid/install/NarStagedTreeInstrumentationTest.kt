package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.Arrays
import java.util.TreeSet

/** On-device characterization of the native staged-tree ownership protocol. */
@RunWith(AndroidJUnit4::class)
class NarStagedTreeInstrumentationTest {
    private lateinit var context: Context
    private lateinit var fixtureRoot: File
    private lateinit var stagingRoot: File
    private lateinit var stagingBaseline: Set<String>

    @Before
    @Throws(Exception::class)
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = context.getDir("narfs-fixtures-v1", Context.MODE_PRIVATE)
        fixtureRoot = File(fixtures, "run-" + System.nanoTime())
        assertTrue(fixtureRoot.mkdir())
        stagingRoot = context.getDir("narfs-stage-v1", Context.MODE_PRIVATE)
        stagingBaseline = children(stagingRoot)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        deleteRecursively(fixtureRoot)
        assertEquals(stagingBaseline, children(stagingRoot))
    }

    @Test
    @Throws(Exception::class)
    fun testPresentAbsentInventoryAndTreeClaimTransfer() {
        val session = NarStagedTree.Stager().session(context)
        val trusted = NarFilesystemInspector.TrustedRoot(fixtureRoot.absolutePath)
        var absent: NarStagedTree.Tree? = null
        var tree: NarStagedTree.Tree? = null
        var claim: NarStagedTree.Claim? = null
        try {
            absent = success(session.stage(trusted, "missing"))
            assertEquals(NarGhostTreePolicy.State.ABSENT, absent.manifest().state)
            assertTrue(absent.entries().isEmpty())
            assertEquals(NarStagedTree.Error.OK, absent.discard())

            val ghost = directory(fixtureRoot, "ghost")
            val unicode = directory(ghost, "dir-雪")
            val content = byteArrayOf(0, 1, 0xfe.toByte(), 0xff.toByte())
            write(File(unicode, "nested-😀.bin"), content)
            write(File(ghost, "manifest.txt"), byteArrayOf(7, 8, 9))
            tree = success(session.stage(trusted, "ghost"))
            val manifest = tree.manifest()
            assertEquals("ghost", manifest.targetId)
            assertEquals(NarGhostTreePolicy.State.PRESENT, manifest.state)
            assertEquals(3, manifest.entries.size)
            assertEquals(16, manifest.storageRootIdentity.size)
            assertEquals(1, manifest.fingerprintVersion)
            assertEquals(32, manifest.fingerprint.size)
            val nested = entry(tree, "dir-雪/nested-😀.bin")
            assertEquals(NarGhostTreePolicy.Type.FILE, nested.type())
            assertEquals(content.size.toLong(), nested.size())
            assertTrue(nested.blobOrdinal() >= 0)
            assertTrue(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(content), nested.sha256()))

            val consumed = session.consume(tree)
            if (consumed.isSuccess()) claim = consumed.claim
            assertTrue(consumed.isSuccess())
            assertEquals(NarStagedTree.Error.CONSUMED, session.consume(tree).error)
            assertEquals(NarStagedTree.Error.CONSUMED, tree.discard())
            assertEquals(NarStagedTree.Error.OK, claim!!.discard())
            assertEquals(NarStagedTree.Error.OK, claim.discard())
        } finally {
            try {
                claim?.discard()
            } finally {
                try {
                    tree?.discard()
                } finally {
                    absent?.discard()
                }
            }
        }
        assertEquals(stagingBaseline, children(stagingRoot))
    }

    @Test
    @Throws(Exception::class)
    fun testInodeMismatchFailureRetriesAndMalformedTokenRejects() {
        val ghost = directory(fixtureRoot, "retry")
        write(File(ghost, "file"), byteArrayOf(1))
        val session = NarStagedTree.Stager().session(context)
        val trusted = NarFilesystemInspector.TrustedRoot(fixtureRoot.absolutePath)
        var retryTree: NarStagedTree.Tree? = null
        var claim: NarStagedTree.Claim? = null
        var real: File? = null
        var held: File? = null
        var replacement: File? = null
        try {
            retryTree = success(session.stage(trusted, "retry"))
            val consumed = session.consume(retryTree)
            if (consumed.isSuccess()) claim = consumed.claim
            assertTrue(consumed.isSuccess())
            real = onlyNewChild(stagingBaseline)
            held = File(stagingRoot, real.name + ".held")
            replacement = File(stagingRoot, real.name)
            assertTrue(real.renameTo(held))
            assertTrue(replacement.mkdir())
            assertEquals(NarStagedTree.Error.TREE_CHANGED, claim!!.discard())
            assertTrue(replacement.delete())
            assertTrue(held.renameTo(real))
            assertEquals(NarStagedTree.Error.OK, claim.discard())
            assertEquals(NarStagedTree.Error.OK, claim.discard())
        } finally {
            try {
                deleteBestEffort(replacement)
            } finally {
                try {
                    if (held != null && real != null && held.exists() && !real.exists()) {
                        if (!held.renameTo(real)) deleteBestEffort(held)
                    }
                } finally {
                    try {
                        claim?.discard()
                    } finally {
                        retryTree?.discard()
                    }
                }
            }
        }
        assertEquals(stagingBaseline, children(stagingRoot))

        val discard: Method = NarStagedTree::class.java.getDeclaredMethod(
            "nativeDiscard", String::class.java, ByteArray::class.java,
        )
        discard.isAccessible = true
        assertEquals(100, (discard.invoke(null, stagingRoot.absolutePath, ByteArray(88)) as Int))
    }

    @Test
    @Throws(Exception::class)
    fun testPolicyFailureAutomaticallyCleansNativeSession() {
        val ghost = directory(fixtureRoot, "collision")
        write(File(ghost, "é"), byteArrayOf(1))
        write(File(ghost, "é"), byteArrayOf(2))
        val session = NarStagedTree.Stager().session(context)
        val result = session.stage(
            NarFilesystemInspector.TrustedRoot(fixtureRoot.absolutePath), "collision",
        )
        val unexpected = result.tree
        try {
            assertFalse(result.isSuccess())
            assertEquals(NarStagedTree.Error.POLICY, result.error)
            assertEquals(NarStagedTree.Error.OK, result.cleanup.discardError())
            assertEquals(NarStagedTree.Error.OK, result.cleanup.discard())
        } finally {
            try {
                unexpected?.discard()
            } finally {
                result.cleanup.discard()
            }
        }
        assertEquals(stagingBaseline, children(stagingRoot))
    }

    private fun entry(tree: NarStagedTree.Tree, path: String): NarStagedTreeInventory.Entry {
        for (value in tree.entries()) if (path == value.path()) return value
        fail("Missing inventory path: $path")
        throw AssertionError("fail() must throw")
    }

    private fun onlyNewChild(before: Set<String>): File {
        val added = children(stagingRoot)
        added.removeAll(before)
        assertEquals(1, added.size)
        return File(stagingRoot, added.iterator().next())
    }

    private fun success(result: NarStagedTree.StageResult): NarStagedTree.Tree {
        assertTrue(result.detail, result.isSuccess())
        return result.tree!!
    }

    private fun children(root: File): MutableSet<String> {
        val names = root.list()
        assertNotNull(names)
        return TreeSet(Arrays.asList(*names!!))
    }

    private fun directory(parent: File, name: String): File = File(parent, name).also {
        assertTrue(it.mkdir())
    }

    @Throws(Exception::class)
    private fun write(file: File, value: ByteArray) {
        FileOutputStream(file).use { it.write(value) }
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        assertTrue(file.delete())
    }

    private fun deleteBestEffort(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteBestEffort)
        file.delete()
    }
}
