package com.cattailsw.nanidroid.install

import org.junit.Assert
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.Arrays
import java.util.Locale
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.math.min

class NarStagedSourceCopyTest {
    @Test
    fun stagedCopyErrorsRetainNamesOrderAndKotlinMetadata() {
        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        Assert.assertEquals(
            mutableListOf<String?>(
                "SOURCE_INVALID",
                "STAGING_ROOT_INVALID",
                "STAGING_NAME_INVALID",
                "STAGING_NAME_COLLISION_LIMIT",
                "STAGING_CREATE_FAILED",
                "SOURCE_OPEN_FAILED",
                "STAGING_OPEN_FAILED",
                "SOURCE_READ_FAILED",
                "ARCHIVE_SIZE_LIMIT",
                "STAGING_WRITE_FAILED",
                "STAGING_SYNC_FAILED",
                "STAGING_CLOSE_FAILED",
                "SOURCE_CLOSE_FAILED",
                "STAGING_DELETE_FAILED"
            ),
            Arrays.stream<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>(com.cattailsw.nanidroid.install.NarStagedSourceCopyError.values())
                .map { it.name }
                .collect(Collectors.toList()))
        for (error
        in com.cattailsw.nanidroid.install.NarStagedSourceCopyError.values()) {
            Assert.assertSame(
                error, com.cattailsw.nanidroid.install.NarStagedSourceCopyError.valueOf(
                    error.name
                )
            )
            Assert.assertEquals(
                error,
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.values()[error.ordinal]
            )
        }
    }

    @Test
    fun copyResultSnapshotsFailureStateAndCleanupErrors() {
        val cleanup: MutableList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError> =
            ArrayList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>()
        cleanup.add(com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_CLOSE_FAILED)

        val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSourceCopyResult.failure(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
                "read failed", cleanup
            )
        cleanup.add(com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_DELETE_FAILED)

        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyResult::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        Assert.assertFalse(result.isSuccess())
        Assert.assertNull(result.getSource())
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
            result.getError()
        )
        Assert.assertEquals("read failed", result.getDetail())
        Assert.assertEquals(
            Arrays.asList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_CLOSE_FAILED
            ),
            result.getCleanupErrors()
        )
        try {
            (result.getCleanupErrors() as MutableList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>).add(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_DELETE_FAILED
            )
            throw AssertionError("cleanup errors were mutable")
        } catch (expected: UnsupportedOperationException) {
            // Immutable snapshot is required.
        }

        val nullDetail: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSourceCopyResult.failure(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
                null, cleanup
            )
        Assert.assertNull(nullDetail.getDetail())
        val nullSource: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSourceCopyResult.success(null)
        Assert.assertFalse(nullSource.isSuccess())
        Assert.assertEquals("", nullSource.getDetail())
        Assert.assertTrue(nullSource.getCleanupErrors().isEmpty())
    }

    @Test
    @Throws(Exception::class)
    fun defaultIoCopiesRealBytesIntoCanonicalRoot() {
        val temporary = File(
            System.getProperty("java.io.tmpdir"),
            "nanidroid-stage-" + System.nanoTime()
        )
        val root = File(temporary, "trusted")
        val external = File(temporary, "external.nar")
        val payload = byteArrayOf(0, 1, 2, 3, -1)
        Assert.assertTrue(root.mkdirs())
        val output = FileOutputStream(external)
        try {
            output.write(payload)
        } finally {
            output.close()
        }

        var staged: File? = null
        try {
            val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
                com.cattailsw.nanidroid.install.NarStagedSource.copy(external, root)

            Assert.assertTrue(result.isSuccess())
            staged = requireNotNull(result.getSource()).claim()
            Assert.assertEquals(
                root.getCanonicalFile(),
                staged!!.getCanonicalFile().getParentFile()
            )
            Assert.assertTrue(payload.contentEquals(Companion.readFile(staged)))
            Assert.assertNull(requireNotNull(result.getSource()).claim())
        } finally {
            if (staged != null) {
                Assert.assertTrue(staged.delete())
            }
            Assert.assertTrue(external.delete())
            Assert.assertTrue(root.delete())
            Assert.assertTrue(temporary.delete())
        }
    }

    @Test
    @Throws(Exception::class)
    fun exactCapCopiesSyncsAndClosesBeforeMint() {
        val io = FakeIo()
        io.virtualSourceLength = MAX_ARCHIVE_BYTES
        io.zeroFirstRead = true

        val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                io.root,
                io,
                names("first.nar")
            )

        Assert.assertTrue(result.isSuccess())
        Assert.assertNull(result.getError())
        Assert.assertTrue(result.getCleanupErrors().isEmpty())
        Assert.assertEquals(MAX_ARCHIVE_BYTES, io.sourceBytesRead)
        Assert.assertEquals(MAX_ARCHIVE_BYTES, io.targetBytesWritten)
        Assert.assertEquals(
            mutableListOf<String?>("sync", "writer-close", "source-close"),
            io.terminalEvents
        )
        Assert.assertEquals(0, io.deleteCount.toLong())

        val source: com.cattailsw.nanidroid.install.NarStagedSource = requireNotNull(result.getSource())
        val staged: File? = source.claim()
        Assert.assertEquals(io.created.get(0), staged)
        Assert.assertNull(source.claim())
    }

    @Test
    @Throws(Exception::class)
    fun overCapPreservesPrimaryAndOrderedCleanupFailures() {
        val io = FakeIo()
        io.virtualSourceLength = MAX_ARCHIVE_BYTES + 10 * MIB
        io.writerCloseFailure = true
        io.sourceCloseFailure = true
        io.deleteFailure = true

        val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                io.root,
                io,
                names("large.nar")
            )

        Assert.assertFalse(result.isSuccess())
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.ARCHIVE_SIZE_LIMIT,
            result.getError()
        )
        Assert.assertEquals(MAX_ARCHIVE_BYTES + 1, io.sourceBytesRead)
        Assert.assertEquals(MAX_ARCHIVE_BYTES, io.targetBytesWritten)
        Assert.assertEquals(
            Arrays.asList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_CLOSE_FAILED,
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_CLOSE_FAILED,
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_DELETE_FAILED
            ),
            result.getCleanupErrors()
        )
        Assert.assertEquals(
            mutableListOf<String?>(
                "writer-close", "source-close", "delete"
            ),
            io.terminalEvents
        )
        try {
            (result.getCleanupErrors() as MutableList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>).add(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_WRITE_FAILED
            )
            throw AssertionError("cleanup errors were mutable")
        } catch (expected: UnsupportedOperationException) {
            Assert.assertEquals(3, result.getCleanupErrors().size.toLong())
        }
    }

    @Test
    @Throws(Exception::class)
    fun copyPhasesHaveStableTypedFailures() {
        val create: FakeIo = failingIo("create")
        val createFailure: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            copy(create)
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_CREATE_FAILED,
            createFailure.getError()
        )
        Assert.assertEquals(0, create.deleteCount.toLong())
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_OPEN_FAILED,
            failingIo("source-open")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_OPEN_FAILED,
            failingIo("target-open")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
            failingIo("read")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
            failingIo("invalid-negative-read")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_WRITE_FAILED,
            failingIo("write")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_SYNC_FAILED,
            failingIo("sync")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_CLOSE_FAILED,
            failingIo("writer-close")
        )
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_CLOSE_FAILED,
            failingIo("source-close")
        )
    }

    @Test
    @Throws(Exception::class)
    fun collisionsNeverOverwriteAndRetryIsBounded() {
        val recovers = FakeIo()
        recovers.collisionsRemaining = 2
        val success: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                recovers.root,
                recovers,
                names("same.nar", "same.nar", "fresh.nar")
            )

        Assert.assertTrue(success.isSuccess())
        Assert.assertEquals(3, recovers.createCount.toLong())
        Assert.assertEquals(0, recovers.deleteCount.toLong())
        Assert.assertEquals(
            File(recovers.root, "fresh.nar").getCanonicalFile(),
            requireNotNull(success.getSource()).claim()
        )

        val exhausted = FakeIo()
        exhausted.collisionsRemaining = Int.MAX_VALUE
        val failure: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                exhausted.root,
                exhausted,
                repeatingNames("collision.nar")
            )

        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_NAME_COLLISION_LIMIT,
            failure.getError()
        )
        Assert.assertEquals(16, exhausted.createCount.toLong())
        Assert.assertEquals(0, exhausted.deleteCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun canonicalRootAndSingleChildNameAreRequired() {
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_INVALID,
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                null, FakeIo().root
            )
                .getError()
        )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_ROOT_INVALID,
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"), null
            )
                .getError()
        )

        val canonicalFailure = FakeIo()
        canonicalFailure.rootCanonicalFailure = true
        assertPrimaryWithoutDelete(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_ROOT_INVALID,
            canonicalFailure
        )

        val notDirectory = FakeIo()
        notDirectory.rootDirectory = false
        assertPrimaryWithoutDelete(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_ROOT_INVALID,
            notDirectory
        )

        val escaping = FakeIo()
        escaping.escapeCandidate = true
        val escaped: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                escaping.root,
                escaping,
                names("escape.nar")
            )
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_NAME_INVALID,
            escaped.getError()
        )
        Assert.assertEquals(0, escaping.createCount.toLong())

        var lexical = FakeIo()
        val hostileNames: Array<String?> = arrayOf(
            null,
            "",
            ".",
            "..",
            "../escape.nar",
            "sub/escape.nar",
            "sub\\escape.nar",
            File("absolute.nar").getAbsolutePath(),
            "C:\\escape.nar",
        )
        for (hostileName in hostileNames) {
            lexical = FakeIo()
            val invalidName: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
                com.cattailsw.nanidroid.install.NarStagedSource.copy(
                    File("external.nar"),
                    lexical.root,
                    lexical,
                    names(hostileName)
                )
            Assert.assertEquals(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_NAME_INVALID,
                invalidName.getError()
            )
            Assert.assertEquals(0, lexical.createCount.toLong())
        }
    }

    @Test
    @Throws(Exception::class)
    fun runtimeFailuresAreNormalizedAndNeverEscape() {
        val rootRuntime = FakeIo()
        rootRuntime.rootCanonicalRuntime = true
        assertPrimaryWithoutDelete(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_ROOT_INVALID,
            rootRuntime
        )

        val writeRuntime = FakeIo()
        writeRuntime.virtualSourceLength = 1
        writeRuntime.writeRuntime = true
        assertPrimary(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_WRITE_FAILED,
            writeRuntime
        )

        val deleteRuntime = FakeIo()
        deleteRuntime.virtualSourceLength = 1
        deleteRuntime.readFailure = true
        deleteRuntime.deleteRuntime = true
        val deleteFailure: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            copy(deleteRuntime)
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
            deleteFailure.getError()
        )
        Assert.assertEquals(
            Arrays.asList<com.cattailsw.nanidroid.install.NarStagedSourceCopyError>(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_DELETE_FAILED
            ),
            deleteFailure.getCleanupErrors()
        )
    }

    @Test
    @Throws(Exception::class)
    fun randomNameInitializationIsLazyAndTyped() {
        val implementation = Class.forName(
            com.cattailsw.nanidroid.install.NarStagedSource::class.java.getName()
                    + "\$RandomNameSource"
        )
        val constructor: Constructor<*> =
            implementation.getDeclaredConstructor()
        constructor.setAccessible(true)
        val source: Any = constructor.newInstance()
        val random = implementation.getDeclaredField("random")
        random.setAccessible(true)
        Assert.assertNull(random.get(source))

        val io = FakeIo()
        val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult =
            com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                io.root,
                io,
                object : com.cattailsw.nanidroid.install.NarStagedSource.NameSource {
                    override fun nextName(): String? {
                        throw SecurityException(
                            "provider initialization"
                        )
                    }
                })
        Assert.assertEquals(
            com.cattailsw.nanidroid.install.NarStagedSourceCopyError.STAGING_NAME_INVALID,
            result.getError()
        )
        Assert.assertEquals(0, io.createCount.toLong())
        Assert.assertEquals(0, io.deleteCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun zeroBulkRejectsInvalidSingleByteBounds() {
        for (invalid in intArrayOf(-2, 256)) {
            val io = FakeIo()
            io.zeroFirstRead = true
            io.singleReadOverride = invalid
            val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult = copy(io)

            Assert.assertEquals(
                com.cattailsw.nanidroid.install.NarStagedSourceCopyError.SOURCE_READ_FAILED,
                result.getError()
            )
            Assert.assertEquals(0, io.sourceBytesRead)
            Assert.assertEquals(0, io.targetBytesWritten)
            Assert.assertEquals(1, io.deleteCount.toLong())
        }
    }

    @Test
    @Throws(Exception::class)
    fun apiExposesOnlyOpaqueTokenAndExistingClaimHandoff() {
        val claim =
            com.cattailsw.nanidroid.install.NarStagedSource::class.java.getDeclaredMethod("claim")
        Assert.assertEquals(File::class.java, claim.getReturnType())
        Assert.assertTrue(Modifier.isSynchronized(claim.getModifiers()))

        var fileReturningMethods = 0
        for (method in com.cattailsw.nanidroid.install.NarStagedSource::class.java.getDeclaredMethods()) {
            if (method.getReturnType() == File::class.java) {
                fileReturningMethods++
                Assert.assertEquals("claim", method.getName())
                Assert.assertFalse(Modifier.isStatic(method.getModifiers()))
            }
            Assert.assertFalse(
                Modifier.isStatic(method.getModifiers())
                        && (method.getReturnType()
                        == com.cattailsw.nanidroid.install.NarStagedSource::class.java)
            )
            Assert.assertFalse(
                Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes()
                    .contentEquals(arrayOf<Class<*>>(File::class.java))
            )
        }
        Assert.assertEquals(1, fileReturningMethods.toLong())

        for (method
        in com.cattailsw.nanidroid.install.NarStagedSourceCopyResult::class.java.getDeclaredMethods()) {
            Assert.assertFalse(method.getReturnType() == File::class.java)
            Assert.assertFalse(
                InputStream::class.java.isAssignableFrom(
                    method.getReturnType()
                )
            )
            Assert.assertFalse(method.getName().lowercase(Locale.getDefault()).contains("replace"))
            Assert.assertFalse(method.getName().lowercase(Locale.getDefault()).contains("writer"))
            Assert.assertFalse(method.getName().lowercase(Locale.getDefault()).contains("path"))
        }
    }

    private class FakeIo

        : com.cattailsw.nanidroid.install.NarStagedSource.StageIo {
        val root: File
        val created: MutableList<File?> = ArrayList<File?>()
        val terminalEvents: MutableList<String?> = ArrayList<String?>()
        var rootCanonicalFailure = false
        var rootCanonicalRuntime = false
        var rootDirectory = true
        var escapeCandidate = false
        var collisionsRemaining = 0
        var createFailure = false
        var sourceOpenFailure = false
        var targetOpenFailure = false
        var readFailure = false
        var invalidNegativeRead = false
        var writeFailure = false
        var writeRuntime = false
        var syncFailure = false
        var writerCloseFailure = false
        var sourceCloseFailure = false
        var deleteFailure = false
        var deleteRuntime = false
        var zeroFirstRead = false
        var singleReadOverride: Int? = null
        var virtualSourceLength: Long = 0
        var sourceBytesRead: Long = 0
        var targetBytesWritten: Long = 0
        var createCount = 0
        var deleteCount = 0

        init {
            root = File("trusted-stage").getCanonicalFile()
        }

        @Throws(IOException::class)
        override fun canonical(file: File): File {
            if (file == root && rootCanonicalRuntime) {
                throw SecurityException("root")
            }
            if (file == root && rootCanonicalFailure) {
                throw IOException("root")
            }
            if ((file != root) && escapeCandidate
                && root == file.getParentFile()
            ) {
                return File("outside", file.getName())
                    .getCanonicalFile()
            }
            return file.getCanonicalFile()
        }

        override fun isDirectory(directory: File): Boolean {
            return rootDirectory && root == directory
        }

        @Throws(IOException::class)
        override fun createNew(file: File): Boolean {
            createCount++
            if (createFailure) {
                throw IOException("create")
            }
            if (collisionsRemaining > 0) {
                collisionsRemaining--
                return false
            }
            created.add(file)
            return true
        }

        @Throws(IOException::class)
        override fun openSource(file: File): InputStream {
            if (sourceOpenFailure) {
                throw IOException("source open")
            }
            return object : InputStream() {
                var remaining = virtualSourceLength
                var zeroPending = zeroFirstRead

                @Throws(IOException::class)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readFailure) {
                        throw IOException("read")
                    }
                    if (invalidNegativeRead) {
                        return -2
                    }
                    if (zeroPending) {
                        zeroPending = false
                        return 0
                    }
                    if (remaining == 0L) {
                        return -1
                    }
                    val count = min(length.toLong(), remaining).toInt()
                    remaining -= count.toLong()
                    sourceBytesRead += count.toLong()
                    return count
                }

                @Throws(IOException::class)
                override fun read(): Int {
                    if (readFailure) {
                        throw IOException("read")
                    }
                    if (singleReadOverride != null) {
                        return singleReadOverride!!
                    }
                    if (remaining == 0L) {
                        return -1
                    }
                    remaining--
                    sourceBytesRead++
                    return 0
                }

                @Throws(IOException::class)
                override fun close() {
                    terminalEvents.add("source-close")
                    if (sourceCloseFailure) {
                        throw IOException("source close")
                    }
                }
            }
        }

        @Throws(IOException::class)
        override fun openTarget(file: File): com.cattailsw.nanidroid.install.NarStagedSource.StageOutput {
            if (targetOpenFailure) {
                throw IOException("target open")
            }
            return object : com.cattailsw.nanidroid.install.NarStagedSource.StageOutput {
                @Throws(IOException::class)
                override fun write(
                    buffer: ByteArray, offset: Int, length: Int
                ) {
                    check(!writeRuntime) { "write" }
                    if (writeFailure) {
                        throw IOException("write")
                    }
                    targetBytesWritten += length.toLong()
                }

                @Throws(IOException::class)
                override fun sync() {
                    terminalEvents.add("sync")
                    if (syncFailure) {
                        throw IOException("sync")
                    }
                }

                @Throws(IOException::class)
                override fun close() {
                    terminalEvents.add("writer-close")
                    if (writerCloseFailure) {
                        throw IOException("writer close")
                    }
                }
            }
        }

        override fun delete(file: File): Boolean {
            terminalEvents.add("delete")
            deleteCount++
            if (deleteRuntime) {
                throw SecurityException("delete")
            }
            return !deleteFailure
        }
    }

    companion object {
        val MIB = 1024L * 1024L
        val MAX_ARCHIVE_BYTES: Long = 544L * MIB

        @Throws(Exception::class)
        private fun assertPrimary(
            expected: com.cattailsw.nanidroid.install.NarStagedSourceCopyError?, io: FakeIo
        ) {
            val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult = copy(io)
            Assert.assertFalse(result.isSuccess())
            Assert.assertEquals(expected, result.getError())
            Assert.assertEquals(1, io.deleteCount.toLong())
        }

        @Throws(Exception::class)
        private fun assertPrimaryWithoutDelete(
            expected: com.cattailsw.nanidroid.install.NarStagedSourceCopyError?, io: FakeIo
        ) {
            val result: com.cattailsw.nanidroid.install.NarStagedSourceCopyResult = copy(io)
            Assert.assertFalse(result.isSuccess())
            Assert.assertEquals(expected, result.getError())
            Assert.assertEquals(0, io.deleteCount.toLong())
        }

        @Throws(Exception::class)
        private fun copy(io: FakeIo): com.cattailsw.nanidroid.install.NarStagedSourceCopyResult {
            return com.cattailsw.nanidroid.install.NarStagedSource.copy(
                File("external.nar"),
                io.root,
                io,
                names("staged.nar")
            )
        }

        @Throws(Exception::class)
        private fun failingIo(phase: String?): FakeIo {
            val io = FakeIo()
            io.virtualSourceLength = 1
            if ("create" == phase) {
                io.createFailure = true
            } else if ("source-open" == phase) {
                io.sourceOpenFailure = true
            } else if ("target-open" == phase) {
                io.targetOpenFailure = true
            } else if ("read" == phase) {
                io.readFailure = true
            } else if ("invalid-negative-read" == phase) {
                io.invalidNegativeRead = true
            } else if ("write" == phase) {
                io.writeFailure = true
            } else if ("sync" == phase) {
                io.syncFailure = true
            } else if ("writer-close" == phase) {
                io.writerCloseFailure = true
            } else if ("source-close" == phase) {
                io.sourceCloseFailure = true
            }
            return io
        }

        private fun names(
            vararg values: String?
        ): com.cattailsw.nanidroid.install.NarStagedSource.NameSource {
            return object : com.cattailsw.nanidroid.install.NarStagedSource.NameSource {
                var index = 0

                override fun nextName(): String? {
                    return values[index++]
                }
            }
        }

        private fun repeatingNames(
            value: String
        ): com.cattailsw.nanidroid.install.NarStagedSource.NameSource {
            return object : com.cattailsw.nanidroid.install.NarStagedSource.NameSource {
                override fun nextName(): String {
                    return value
                }
            }
        }

        @Throws(IOException::class)
        private fun readFile(file: File): ByteArray {
            val input = FileInputStream(file)
            try {
                val result = ByteArray(file.length().toInt())
                var offset = 0
                while (offset < result.size) {
                    val count = input.read(
                        result, offset, result.size - offset
                    )
                    if (count < 0) {
                        throw IOException("unexpected end of file")
                    }
                    offset += count
                }
                return result
            } finally {
                input.close()
            }
        }
    }
}
