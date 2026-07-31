package com.cattailsw.nanidroid.install

import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

class NarFilesystemInspectorTest {
    @Test
    fun backendIsLazyOrderedImmutableAndDefensivelyCopied() {
        val loads = AtomicInteger()
        val calls = AtomicInteger()
        val paths = arrayOf<String>("empty", "nested/file-\ud83d\ude00")
        val types = intArrayOf(2, 1)
        val facts = longArrayOf(0, 11, 12, 7, 21, 22)
        val made: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result =
            result(2, 0, paths, types, facts)
        val value: com.cattailsw.nanidroid.install.NarFilesystemInspector = inspector(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Loader { loads.incrementAndGet() },
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Backend { root: String?, target: String? ->
                calls.incrementAndGet()
                made
            })
        paths[0] = "changed"
        types[0] = 1
        facts[0] = 99

        val root: com.cattailsw.nanidroid.install.NarFilesystemInspector.TrustedRoot =
            com.cattailsw.nanidroid.install.NarFilesystemInspector.TrustedRoot("/trusted/root")
        val first: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result =
            value.inspect(root, "ghost")
        Assert.assertSame(first, value.inspect(root, "ghost"))
        Assert.assertEquals(1, loads.get().toLong())
        Assert.assertEquals(2, calls.get().toLong())
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.State.PRESENT,
            first.state()
        )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.OK,
            first.error()
        )
        Assert.assertEquals(2, first.entryCount().toLong())
        Assert.assertEquals(7, first.totalFileSize())
        val entries: List<com.cattailsw.nanidroid.install.NarFilesystemInspector.Entry> =
            first.entries()
        Assert.assertEquals("empty", entries.get(0).path())
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Type.DIRECTORY,
            entries.get(0).type()
        )
        Assert.assertEquals(0, entries.get(0).size())
        Assert.assertEquals(11, entries.get(0).device())
        Assert.assertEquals(12, entries.get(0).inode())
        Assert.assertEquals("nested/file-\ud83d\ude00", entries.get(1).path())
        Assert.assertThrows(
            UnsupportedOperationException::class.java,
            ThrowingRunnable { (entries as MutableList<com.cattailsw.nanidroid.install.NarFilesystemInspector.Entry>).clear() })
    }

    @Test
    fun loaderAndRuntimeFailuresAreTypedButOomePropagates() {
        val root: com.cattailsw.nanidroid.install.NarFilesystemInspector.TrustedRoot =
            com.cattailsw.nanidroid.install.NarFilesystemInspector.TrustedRoot("/trusted")
        val link: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result = inspector(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Loader {
                throw UnsatisfiedLinkError(
                    "missing"
                )
            },
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Backend { r: String?, t: String? -> throw AssertionError() }).inspect(
            root,
            "x"
        )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.LINKAGE,
            link.error()
        )
        val security: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result = inspector(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Loader {
                throw SecurityException(
                    "denied"
                )
            },
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Backend { r: String?, t: String? -> throw AssertionError() }).inspect(
            root,
            "x"
        )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.SECURITY,
            security.error()
        )
        val nativeFailure: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result =
            inspector(
                com.cattailsw.nanidroid.install.NarFilesystemInspector.Loader {},
                com.cattailsw.nanidroid.install.NarFilesystemInspector.Backend { r: String?, t: String? -> null }).inspect(
                root,
                "x"
            )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.NATIVE,
            nativeFailure.error()
        )
        Assert.assertThrows(OutOfMemoryError::class.java, ThrowingRunnable {
            inspector(
                com.cattailsw.nanidroid.install.NarFilesystemInspector.Loader { throw OutOfMemoryError() },
                com.cattailsw.nanidroid.install.NarFilesystemInspector.Backend { r: String?, t: String? -> null }).inspect(
                root,
                "x"
            )
        })
    }

    @Test
    fun nativeCodesAndMalformedDtosHaveStableTypedResults() {
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.State.ABSENT,
            result(1, 0, emptyArray<String>(), IntArray(0), LongArray(0)).state()
        )
        for (code in 0..20) {
            Assert.assertNotNull(
                result(
                    0,
                    code,
                    emptyArray<String>(),
                    IntArray(0),
                    LongArray(0)
                ).error()
            )
        }
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.INPUT,
            result(0, 100, emptyArray<String>(), IntArray(0), LongArray(0)).error()
        )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.SECURITY,
            result(0, 103, emptyArray<String>(), IntArray(0), LongArray(0)).error()
        )
        val malformed: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result =
            com.cattailsw.nanidroid.install.NarFilesystemInspector.fromNative(
                2, 0, 0, 1, 0, arrayOf<String>("x"), IntArray(0), LongArray(0)
            )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.State.ERROR,
            malformed.state()
        )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.NATIVE,
            malformed.error()
        )
        Assert.assertTrue(malformed.entries().isEmpty())
        val nullPath: com.cattailsw.nanidroid.install.NarFilesystemInspector.Result =
            com.cattailsw.nanidroid.install.NarFilesystemInspector.fromNative(
                2, 0, 0, 1, 0, (arrayOfNulls<String>(1) as Array<String>), intArrayOf(1),
                longArrayOf(0, 0, 0)
            )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarFilesystemInspector.Error.NATIVE,
            nullPath.error()
        )
    }

    @Test
    fun kotlinPackageSeamExposesNoFilesystemCapabilityTypes() {
        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarFilesystemInspector::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        for (nested in com.cattailsw.nanidroid.install.NarFilesystemInspector::class.java.getDeclaredClasses()) {
            Assert.assertNotNull(nested.getAnnotation<Metadata?>(Metadata::class.java))
        }
        for (method in com.cattailsw.nanidroid.install.NarFilesystemInspector::class.java.getDeclaredMethods()) {
            Assert.assertFalse(
                method.getReturnType().getName()
                    .matches("(java\\.io\\..*|java\\.nio\\.channels\\..*)".toRegex())
            )
        }
        for (field in com.cattailsw.nanidroid.install.NarFilesystemInspector.Entry::class.java.getDeclaredFields()) {
            if (field.isSynthetic() || field.getName() == "\$stable") continue
            Assert.assertTrue(Modifier.isPrivate(field.getModifiers()))
            Assert.assertFalse(
                field.getType().getName()
                    .matches("(java\\.io\\..*|java\\.nio\\.channels\\..*)".toRegex())
            )
        }
    }

    companion object {
        private fun result(
            state: Int, error: Int, paths: Array<String>, types: IntArray?, facts: LongArray?
        ): com.cattailsw.nanidroid.install.NarFilesystemInspector.Result {
            return com.cattailsw.nanidroid.install.NarFilesystemInspector.fromNative(
                state, error, 0, paths.size, 7, paths, types, facts
            )
        }

        private fun inspector(
            loader: com.cattailsw.nanidroid.install.NarFilesystemInspector.Loader,
            backend: com.cattailsw.nanidroid.install.NarFilesystemInspector.Backend
        ): com.cattailsw.nanidroid.install.NarFilesystemInspector {
            return com.cattailsw.nanidroid.install.NarFilesystemInspector(loader, backend)
        }
    }
}
