package com.cattailsw.nanidroid.install

import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Arrays
import org.junit.Assert.*
import org.junit.Test

class NarStagedTreeInventoryTest {
    @Test fun absentAndPresentEmptyRemainDistinct() {
        val absent = NarStagedTreeInventory.absent("ghost", 7, 11)
        val present = NarStagedTreeInventory.present("ghost", description(7, 11, emptyArray(), IntArray(0), LongArray(0), IntArray(0), ByteArray(0)))
        assertTrue(absent.detail(), absent.isSuccess()); assertTrue(present.detail(), present.isSuccess())
        assertEquals(NarGhostTreePolicy.State.ABSENT, absent.manifest()!!.state); assertEquals(NarGhostTreePolicy.State.PRESENT, present.manifest()!!.state)
        assertTrue(absent.entries().isEmpty()); assertTrue(present.entries().isEmpty())
        assertNotEquals(hex(absent.manifest()!!.fingerprint), hex(present.manifest()!!.fingerprint))
    }

    @Test fun inventoryIsNormalizedSortedAndDefensivelyImmutable() {
        val first = digest("one"); val second = digest("two")
        val paths = arrayOf("z-e\u0301", "empty", "a.bin"); val types = intArrayOf(1, 2, 1); val sizes = longArrayOf(3, 0, 3); val ordinals = intArrayOf(1, -1, 0); val digests = flat(first, zeros(), second)
        val result = NarStagedTreeInventory.present("ghost", description(7, 11, paths, types, sizes, ordinals, digests))
        paths[0] = "mutated"; sizes[0] = 99; ordinals[0] = 99; Arrays.fill(digests, 99.toByte())
        assertTrue(result.detail(), result.isSuccess()); val entries = result.entries()
        assertEquals(listOf("a.bin", "empty", "z-é"), entries.map { it.path() }); assertEquals(0, entries[0].blobOrdinal()); assertEquals(-1, entries[1].blobOrdinal()); assertEquals(1, entries[2].blobOrdinal())
        assertEquals(NarGhostTreePolicy.Type.FILE, entries[0].type()); assertEquals(NarGhostTreePolicy.Type.DIRECTORY, entries[1].type()); assertEquals(3, entries[0].size()); assertEquals(0, entries[1].size())
        assertArrayEquals(second, entries[0].sha256()); val returned = entries[0].sha256()!!; returned[0] = (returned[0].toInt() xor 1).toByte(); assertArrayEquals(second, entries[0].sha256())
        assertThrows(UnsupportedOperationException::class.java) { (entries as MutableList).clear() }
    }

    @Test fun fingerprintV1BindsTargetRootAndDigestButNotOrdinal() {
        val a = digest("a"); val b = digest("b"); val left = twoFiles("ghost", 7, 11, a, b, intArrayOf(0, 1)); val swapped = twoFiles("ghost", 7, 11, a, b, intArrayOf(1, 0)); val baseline = hex(left.manifest()!!.fingerprint)
        assertEquals(1, left.manifest()!!.fingerprintVersion); assertArrayEquals(left.manifest()!!.fingerprint, swapped.manifest()!!.fingerprint); assertNotEquals(left.entries()[0].blobOrdinal(), swapped.entries()[0].blobOrdinal()); assertEquals("0000000000000007000000000000000b", hex(left.manifest()!!.storageRootIdentity))
        assertNotEquals(baseline, fingerprint(twoFiles("other", 7, 11, a, b, intArrayOf(0, 1)))); assertNotEquals(baseline, fingerprint(twoFiles("ghost", 8, 11, a, b, intArrayOf(0, 1)))); assertNotEquals(baseline, fingerprint(twoFiles("ghost", 7, 12, a, b, intArrayOf(0, 1)))); assertNotEquals(baseline, fingerprint(twoFiles("ghost", 7, 11, digest("x"), b, intArrayOf(0, 1))))
    }

    @Test fun malformedDescriptionsAndPolicyCollisionsAreTyped() {
        rejects("NATIVE", null); rejects("NATIVE", description(7, 11, arrayOf("a"), IntArray(0), LongArray(0), IntArray(0), ByteArray(0))); rejects("NATIVE", description(7, 11, arrayOf("a"), intArrayOf(1), longArrayOf(-1), intArrayOf(0), flat(digest("a")))); rejects("NATIVE", description(7, 11, arrayOf("a"), intArrayOf(9), longArrayOf(0), intArrayOf(-1), flat(zeros()))); rejects("NATIVE", description(7, 11, arrayOf("empty"), intArrayOf(2), longArrayOf(1), intArrayOf(-1), flat(zeros()))); rejects("NATIVE", description(7, 11, arrayOf("empty"), intArrayOf(2), longArrayOf(0), intArrayOf(0), flat(zeros()))); rejects("NATIVE", description(7, 11, arrayOf("empty"), intArrayOf(2), longArrayOf(0), intArrayOf(-1), flat(digest("x")))); rejects("NATIVE", description(7, 11, arrayOf("a", "b"), intArrayOf(1, 1), longArrayOf(1, 1), intArrayOf(0, 0), flat(digest("a"), digest("b")))); rejects("NATIVE", description(7, 11, arrayOf("a", "b"), intArrayOf(1, 1), longArrayOf(1, 1), intArrayOf(0, 2), flat(digest("a"), digest("b")))); rejects("POLICY", description(7, 11, arrayOf("é", "e\u0301"), intArrayOf(1, 1), longArrayOf(1, 1), intArrayOf(0, 1), flat(digest("a"), digest("b"))))
    }

    @Test fun surfaceIsPureImmutablePolicyWithoutLifecycleEscape() {
        // Kotlin `internal` is enforced at the module boundary but is public
        // in the JVM bytecode needed by sibling Kotlin sources.  Check that
        // this remains Kotlin-internal source rather than mistaking that
        // implementation detail for an install authority.
        assertNotNull(NarStagedTreeInventory::class.java.getAnnotation(Metadata::class.java))
        for (nested in NarStagedTreeInventory::class.java.declaredClasses) { assertNotNull(nested.getAnnotation(Metadata::class.java)); for (field in nested.declaredFields) assertFalse(forbidden(field.type)); for (method in nested.declaredMethods) { assertFalse(forbidden(method.returnType)); assertFalse(method.name.matches(Regex("(finalize|close|discard|consume|publish|overlay|handle|token)"))); for (parameter in method.parameterTypes) assertFalse(forbidden(parameter)) } }
    }

    private fun forbidden(type: Class<*>): Boolean { val name = type.name; return name.startsWith("android.") || name == "java.io.File" || name.startsWith("java.nio.file") || name.contains("NarStagedTree\$Handle") || name.contains("Context") }
    private fun twoFiles(target: String, device: Long, inode: Long, first: ByteArray, second: ByteArray, ordinals: IntArray) = NarStagedTreeInventory.present(target, description(device, inode, arrayOf("a", "b"), intArrayOf(1, 1), longArrayOf(1, 1), ordinals, flat(first, second)))
    private fun fingerprint(result: NarStagedTreeInventory.Result): String { assertTrue(result.detail(), result.isSuccess()); return hex(result.manifest()!!.fingerprint) }
    private fun rejects(expected: String, description: NarStagedTreeInventory.Description?) { val result = NarStagedTreeInventory.present("ghost", description); assertFalse(result.isSuccess()); assertEquals(expected, result.error()!!.name); assertNotNull(result.detail()) }
    private fun description(device: Long, inode: Long, paths: Array<String>, types: IntArray, sizes: LongArray, ordinals: IntArray, digests: ByteArray) = NarStagedTreeInventory.Description(device, inode, paths, types, sizes, ordinals, digests)
    private fun flat(vararg values: ByteArray) = ByteArray(values.size * 32).also { result -> values.forEachIndexed { index, value -> System.arraycopy(value, 0, result, index * 32, 32) } }
    private fun zeros() = ByteArray(32)
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
