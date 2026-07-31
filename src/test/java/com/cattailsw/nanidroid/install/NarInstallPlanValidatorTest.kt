package com.cattailsw.nanidroid.install

import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

class NarInstallPlanValidatorTest {
    @Test
    @Throws(Exception::class)
    fun plansRootAndWrappedRealArchivesWithoutWritingInstallRoot() {
        val installRoot: File = temporaryDirectory("plan-root")
        val marker = File(installRoot, "existing")
        Assert.assertTrue(marker.createNewFile())
        val rootArchive: File = zip(
            "install.txt", descriptor("descriptor-id", "Root Ghost"),
            "ghost/master/file.txt", bytes("payload")
        )

        val rootResult: com.cattailsw.nanidroid.install.NarInstallPlanResult =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator().validate(
                rootArchive, installRoot, null
            )

        Assert.assertTrue(rootResult.isSuccess())
        val rootPlan: com.cattailsw.nanidroid.install.NarInstallPlan = requireNotNull(rootResult.plan)
        Assert.assertNull(rootPlan.wrapperDirectory)
        Assert.assertEquals(rootArchive.length(), rootPlan.sourceLength)
        Assert.assertArrayEquals(sha256(rootArchive), rootPlan.getSourceSha256())
        Assert.assertEquals("descriptor-id", rootPlan.descriptor.getTargetId())
        Assert.assertEquals(2, rootPlan.entries.size.toLong())
        val payload: com.cattailsw.nanidroid.install.NarInstallPlan.Entry = rootPlan.entries.get(1)
        Assert.assertEquals(1, payload.ordinal)
        Assert.assertEquals("ghost/master/file.txt", payload.rawName)
        Assert.assertEquals(
            "ghost/master/file.txt", payload.normalizedArchivePath
        )
        Assert.assertEquals("ghost/master/file.txt", payload.relativePath)
        Assert.assertTrue(payload.isInstallEntry)
        Assert.assertFalse(payload.isDirectory)
        Assert.assertTrue(payload.crc >= 0)
        Assert.assertTrue(payload.method >= 0)
        Assert.assertEquals(7, payload.declaredSize)
        Assert.assertTrue(payload.compressedSize >= 0)
        Assert.assertEquals(
            installRoot.getCanonicalFile(), rootPlan.installRoot
        )
        Assert.assertEquals(
            File(installRoot.getCanonicalFile(), "descriptor-id"),
            rootPlan.targetDirectory
        )
        Assert.assertArrayEquals(
            arrayOf<String>("existing"), installRoot.list()
        )

        val wrapped: File = zip(
            "bundle/", null,
            "bundle/install.txt", descriptor("ignored", "Wrapped Ghost"),
            "bundle/ghost/file.txt", bytes("wrapped")
        )
        val wrappedResult: com.cattailsw.nanidroid.install.NarInstallPlanResult =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator().validate(
                wrapped, installRoot, "forced-id"
            )
        Assert.assertTrue(wrappedResult.isSuccess())
        val wrappedPlan: com.cattailsw.nanidroid.install.NarInstallPlan = requireNotNull(wrappedResult.plan)
        Assert.assertEquals("bundle", wrappedPlan.wrapperDirectory)
        Assert.assertEquals("forced-id", wrappedPlan.descriptor.getTargetId())
        Assert.assertFalse(wrappedPlan.entries.get(0).isInstallEntry)
        Assert.assertEquals(
            "install.txt",
            wrappedPlan.entries.get(1).relativePath
        )
        Assert.assertEquals(
            File(installRoot.getCanonicalFile(), "forced-id"),
            wrappedPlan.targetDirectory
        )
        Assert.assertArrayEquals(
            arrayOf<String>("existing"), installRoot.list()
        )
    }

    @Test
    @Throws(Exception::class)
    fun returnsDetachedImmutablePlanIdentityEntriesAndMetadata() {
        val archive: File = zip(
            "install.txt", descriptor("ghost-id", "Ghost"),
            "payload", bytes("payload")
        )
        val plan: com.cattailsw.nanidroid.install.NarInstallPlan =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator().validate(
                archive, temporaryDirectory("immutable"), null
            ).plan!!

        val firstDigest: ByteArray = plan.getSourceSha256()
        val secondDigest: ByteArray = plan.getSourceSha256()
        Assert.assertNotSame(firstDigest, secondDigest)
        val original = secondDigest[0]
        firstDigest[0] = (firstDigest[0].toInt() xor 0x7f).toByte()
        Assert.assertEquals(original.toLong(), plan.getSourceSha256()[0].toLong())
        try {
            (plan.entries as MutableList<NarInstallPlan.Entry>).clear()
            throw AssertionError("plan entries must be immutable")
        } catch (expected: UnsupportedOperationException) {
            // Expected.
        }
        try {
            (plan.descriptor.getMetadata() as MutableMap<String, String>).clear()
            throw AssertionError("descriptor metadata must be immutable")
        } catch (expected: UnsupportedOperationException) {
            // Expected.
        }

        val io: FakeIo = validFakeIo()
        val detached: com.cattailsw.nanidroid.install.NarInstallPlan =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validate(
                File("archive.nar"),
                File("install-root"),
                null
            ).plan!!
        val frozen: com.cattailsw.nanidroid.install.NarInstallPlan.Entry = detached.entries.get(0)
        val ordinal: Int = frozen.ordinal
        val rawName: String = requireNotNull(frozen.rawName)
        val normalized: String = requireNotNull(frozen.normalizedArchivePath)
        val relative: String = requireNotNull(frozen.relativePath)
        val installEntry: Boolean = frozen.isInstallEntry
        val directory: Boolean = frozen.isDirectory
        val crc: Long = frozen.crc
        val method: Int = frozen.method
        val size: Long = frozen.declaredSize
        val compressed: Long = frozen.compressedSize
        for (field in 0..6) {
            Companion.mutateCentral(io.archive.entries.get(0)!!, field)
        }
        Assert.assertEquals(ordinal, frozen.ordinal)
        Assert.assertEquals(rawName, frozen.rawName)
        Assert.assertEquals(normalized, frozen.normalizedArchivePath)
        Assert.assertEquals(relative, frozen.relativePath)
        Assert.assertEquals(installEntry, frozen.isInstallEntry)
        Assert.assertEquals(directory, frozen.isDirectory)
        Assert.assertEquals(crc, frozen.crc)
        Assert.assertEquals(method, frozen.method)
        Assert.assertEquals(size, frozen.declaredSize)
        Assert.assertEquals(compressed, frozen.compressedSize)
    }

    @Test
    @Throws(Exception::class)
    fun verifiesDigestLengthAndExactCentralIdentityOnReopen() {
        val archive: File = zip(
            "install.txt", descriptor("ghost-id", "Ghost"),
            "payload", bytes("first")
        )
        val root: File = temporaryDirectory("verify")
        val validator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator()
        val plan: com.cattailsw.nanidroid.install.NarInstallPlan =
            validator.validate(archive, root, null).plan!!
        val unchanged: com.cattailsw.nanidroid.install.NarInstallPlanResult =
            validator.verify(archive, plan)
        Assert.assertTrue(unchanged.isSuccess())
        Assert.assertSame(plan, unchanged.plan)

        overwriteZip(
            archive,
            "install.txt", descriptor("ghost-id", "Ghost"),
            "payload", bytes("other")
        )
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            validator.verify(archive, plan)
        )

        val io: FakeIo = validFakeIo()
        val fakeValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io)
        val fakePlan: com.cattailsw.nanidroid.install.NarInstallPlan = fakeValidator.validate(
            File("archive.nar"), root, null
        ).plan!!
        Assert.assertTrue(
            fakeValidator.verify(
                File("archive.nar"), fakePlan
            ).isSuccess()
        )
        Assert.assertEquals(2, io.archive.closeCount.toLong())
        Collections.swap(io.archive.entries, 0, 1)
        reindex(io.archive.entries)
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            fakeValidator.verify(File("archive.nar"), fakePlan)
        )
        Collections.swap(io.archive.entries, 0, 1)
        reindex(io.archive.entries)

        for (field in 0..6) {
            val changed: FakeIo = validFakeIo()
            val changedValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
                com.cattailsw.nanidroid.install.NarInstallPlanValidator(changed)
            val changedPlan: com.cattailsw.nanidroid.install.NarInstallPlan =
                changedValidator.validate(
                    File("archive.nar"), root, null
                ).plan!!
            Companion.mutateCentral(changed.archive.entries.get(0)!!, field)
            assertError(
                com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                changedValidator.verify(
                    File("archive.nar"), changedPlan
                )
            )
        }

        val close: FakeIo = validFakeIo()
        val closeValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(close)
        val closePlan: com.cattailsw.nanidroid.install.NarInstallPlan = closeValidator.validate(
            File("archive.nar"), root, null
        ).plan!!
        close.archive.entries.get(0)!!.crc = 7
        close.archive.closeFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            closeValidator.verify(File("archive.nar"), closePlan)
        )
        close.archive.entries.get(0)!!.crc = 0
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            closeValidator.verify(File("archive.nar"), closePlan)
        )
    }

    @Test
    @Throws(Exception::class)
    fun enforcesEarlyAndAuthoritativeStreamedArchiveCap() {
        val early: FakeIo = validFakeIo()
        early.hintedLength = MAX_ARCHIVE_BYTES + 1
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_SIZE_LIMIT,
            validate(early)
        )
        Assert.assertEquals(0, early.sourceOpenCount.toLong())

        val streamed: FakeIo = validFakeIo()
        streamed.hintedLength = 0
        streamed.virtualSourceLength = MAX_ARCHIVE_BYTES + 10 * MIB
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_SIZE_LIMIT,
            validate(streamed)
        )
        Assert.assertEquals(1, streamed.sourceCloseCount.toLong())
        Assert.assertEquals(
            MAX_ARCHIVE_BYTES + 1, streamed.sourceBytesRead
        )
        Assert.assertEquals(0, streamed.archiveOpenCount.toLong())

        val exact: FakeIo = validFakeIo()
        exact.hintedLength = 0
        exact.virtualSourceLength = MAX_ARCHIVE_BYTES
        Assert.assertTrue(validate(exact).isSuccess())
        Assert.assertEquals(
            2 * MAX_ARCHIVE_BYTES, exact.sourceBytesRead
        )
    }

    @Test
    @Throws(Exception::class)
    fun handlesZeroReadsAndMapsHashReadAndCloseFailures() {
        val zero: FakeIo = validFakeIo()
        zero.zeroFirstSourceRead = true
        val zeroResult: com.cattailsw.nanidroid.install.NarInstallPlanResult = validate(zero)
        Assert.assertTrue(zeroResult.isSuccess())
        Assert.assertArrayEquals(
            Companion.sha256(zero.source!!),
            requireNotNull(zeroResult.plan).getSourceSha256()
        )
        Assert.assertEquals(2, zero.sourceCloseCount.toLong())

        val openFailure: FakeIo = validFakeIo()
        openFailure.sourceOpenFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(openFailure)
        )
        Assert.assertEquals(0, openFailure.sourceCloseCount.toLong())
        Assert.assertEquals(0, openFailure.archiveOpenCount.toLong())

        val readFailure: FakeIo = validFakeIo()
        readFailure.sourceReadFailureAt = 2
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(readFailure)
        )
        Assert.assertEquals(1, readFailure.sourceCloseCount.toLong())
        Assert.assertEquals(0, readFailure.archiveOpenCount.toLong())

        val closeFailure: FakeIo = validFakeIo()
        closeFailure.sourceCloseFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(closeFailure)
        )

        val sizeAndClose: FakeIo = validFakeIo()
        sizeAndClose.hintedLength = 0
        sizeAndClose.virtualSourceLength = MAX_ARCHIVE_BYTES + 1
        sizeAndClose.sourceCloseFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_SIZE_LIMIT,
            validate(sizeAndClose)
        )
    }

    @Test
    @Throws(Exception::class)
    fun mapsZipOpenListAndCloseWithSemanticPrecedence() {
        val openFailure: FakeIo = validFakeIo()
        openFailure.archiveOpenFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(openFailure)
        )
        Assert.assertEquals(0, openFailure.archive.closeCount.toLong())

        val listFailure: FakeIo = validFakeIo()
        listFailure.archive.listFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(listFailure)
        )
        Assert.assertEquals(1, listFailure.archive.closeCount.toLong())

        val closeFailure: FakeIo = validFakeIo()
        closeFailure.archive.closeFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(closeFailure)
        )
        Assert.assertEquals(1, closeFailure.archive.closeCount.toLong())

        val semanticAndClose: FakeIo = validFakeIo()
        semanticAndClose.archive.entries.removeAt(1)
        reindex(semanticAndClose.archive.entries)
        semanticAndClose.archive.closeFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.MISSING_INSTALL_DESCRIPTOR,
            validate(semanticAndClose)
        )
        Assert.assertEquals(1, semanticAndClose.archive.closeCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun readsDescriptorByExactOrdinalObjectWithBoundedClosePolicy() {
        val exact: FakeIo = validFakeIo()
        val exactResult: com.cattailsw.nanidroid.install.NarInstallPlanResult = validate(exact)
        Assert.assertTrue(exactResult.isSuccess())
        Assert.assertSame(
            exact.archive.entries.get(1), exact.archive.openedEntry
        )
        Assert.assertEquals(1, exact.archive.descriptorCloseCount.toLong())
        Assert.assertEquals(1, exact.archive.closeCount.toLong())
        Assert.assertEquals(1, exact.canonicalCount.toLong())
        Assert.assertEquals(
            File("install-root"), exact.canonicalArgument
        )
        Assert.assertFalse(
            exact.canonicalResult == exact.canonicalArgument
        )
        Assert.assertEquals(
            exact.canonicalResult, requireNotNull(exactResult.plan).installRoot
        )
        Assert.assertEquals(
            File(exact.canonicalResult, "ghost-id"),
            requireNotNull(exactResult.plan).targetDirectory
        )

        val zero: FakeIo = validFakeIo()
        zero.archive.zeroFirstDescriptorRead = true
        val zeroResult: com.cattailsw.nanidroid.install.NarInstallPlanResult = validate(zero)
        Assert.assertTrue(zeroResult.isSuccess())
        Assert.assertEquals(
            "Ghost", requireNotNull(zeroResult.plan).descriptor.getName()
        )

        val openFailure: FakeIo = validFakeIo()
        openFailure.archive.descriptorOpenFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.DESCRIPTOR_READ_FAILED,
            validate(openFailure)
        )
        Assert.assertEquals(1, openFailure.archive.closeCount.toLong())
        Assert.assertEquals(0, openFailure.archive.descriptorCloseCount.toLong())

        val readFailure: FakeIo = validFakeIo()
        readFailure.archive.descriptorReadFailureAt = 1
        readFailure.archive.closeFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.DESCRIPTOR_READ_FAILED,
            validate(readFailure)
        )
        Assert.assertEquals(1, readFailure.archive.descriptorCloseCount.toLong())
        Assert.assertEquals(1, readFailure.archive.closeCount.toLong())

        val closeFailure: FakeIo = validFakeIo()
        closeFailure.archive.descriptorCloseFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.DESCRIPTOR_READ_FAILED,
            validate(closeFailure)
        )

        val semanticAndClose: FakeIo = validFakeIo()
        semanticAndClose.archive.descriptorBytes = bytes("name,G\n")
        semanticAndClose.archive.descriptorCloseFailure = true
        semanticAndClose.archive.closeFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.MISSING_TYPE,
            validate(semanticAndClose)
        )
        Assert.assertEquals(1, semanticAndClose.archive.closeCount.toLong())

        val overflowAndClose: FakeIo = validFakeIo()
        overflowAndClose.archive.virtualDescriptorLength =
            64L * 1024L + 10 * MIB
        overflowAndClose.archive.entries.get(1)!!.declaredSize = 1
        overflowAndClose.archive.descriptorCloseFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
            validate(overflowAndClose)
        )
        Assert.assertEquals(
            64L * 1024L + 1,
            overflowAndClose.archive.descriptorBytesRead
        )

        val exactCap: FakeIo = validFakeIo()
        exactCap.archive.descriptorBytes =
            paddedDescriptor(64 * 1024)
        exactCap.archive.entries.get(1)!!.declaredSize = 1
        Assert.assertTrue(validate(exactCap).isSuccess())
    }

    @Test
    @Throws(Exception::class)
    fun mapsInstallRootCanonicalizationToSpecificPlanningError() {
        val nullRoot: FakeIo = validFakeIo()
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.INSTALL_ROOT_INVALID,
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(nullRoot).validate(
                File("archive.nar"), null, null
            )
        )
        Assert.assertEquals(0, nullRoot.sourceOpenCount.toLong())

        val canonicalFailure: FakeIo = validFakeIo()
        canonicalFailure.canonicalFailure = true
        canonicalFailure.archive.closeFailure = true
        val absentTarget = File(
            "missing-root-" + System.nanoTime(),
            "ghost-id"
        )

        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.INSTALL_ROOT_INVALID,
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(canonicalFailure).validate(
                File("archive.nar"),
                absentTarget.getParentFile(),
                null
            )
        )
        Assert.assertFalse(absentTarget.exists())
        Assert.assertEquals(1, canonicalFailure.canonicalCount.toLong())
        Assert.assertEquals(1, canonicalFailure.archive.closeCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun publicSurfaceReturnsDiagnosticPlansOnly() {
        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarInstallPlan::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarInstallPlan.Entry::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarInstallPlanResult::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        Assert.assertNotNull(
            com.cattailsw.nanidroid.install.NarInstallPlanValidator::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            )
        )
        var publicMethods = 0
        for (method
        in com.cattailsw.nanidroid.install.NarInstallPlanValidator::class.java.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                publicMethods++
                Assert.assertTrue(
                    "validate" == method.getName()
                            || "verify" == method.getName()
                            || "validateStaged" == method.getName()
                            || "verifyStaged" == method.getName()
                )
                Assert.assertEquals(
                    com.cattailsw.nanidroid.install.NarInstallPlanResult::class.java,
                    method.getReturnType()
                )
            }
        }
        Assert.assertEquals(4, publicMethods.toLong())
        for (method
        in com.cattailsw.nanidroid.install.NarInstallPlanResult::class.java.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                if (method.getName().lowercase(Locale.getDefault()).contains("session")) {
                    Assert.assertEquals(
                        "getVerifiedSession", method.getName()
                    )
                    Assert.assertEquals(
                        com.cattailsw.nanidroid.install.NarVerifiedInstallSession::class.java,
                        method.getReturnType()
                    )
                }
                Assert.assertFalse(
                    InputStream::class.java.isAssignableFrom(
                        method.getReturnType()
                    )
                )
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun preflightsAndCapsEnumerationBeforeZipUse() {
        val declaredOverflow: FakeIo = validFakeIo()
        declaredOverflow.preflightCount = 10001
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ENTRY_COUNT_LIMIT,
            validate(declaredOverflow)
        )
        Assert.assertEquals(0, declaredOverflow.archiveOpenCount.toLong())

        val enumeratedOverflow: FakeIo = validFakeIo()
        enumeratedOverflow.archive.virtualEntryCount = 10001
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ENTRY_COUNT_LIMIT,
            validate(enumeratedOverflow)
        )
        Assert.assertEquals(10001, enumeratedOverflow.archive.entriesLimit.toLong())

        val countMismatch: FakeIo = validFakeIo()
        countMismatch.preflightCount = 1
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            validate(countMismatch)
        )
        Assert.assertEquals(1, countMismatch.archiveOpenCount.toLong())

        val preflightFailure: FakeIo = validFakeIo()
        preflightFailure.preflightFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            validate(preflightFailure)
        )
        Assert.assertEquals(0, preflightFailure.archiveOpenCount.toLong())

        val verified: FakeIo = validFakeIo()
        val validator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(verified)
        val plan: com.cattailsw.nanidroid.install.NarInstallPlan = validator.validate(
            File("archive.nar"),
            File("install-root"),
            null
        ).plan!!
        Assert.assertTrue(
            validator.verify(
                File("archive.nar"), plan
            ).isSuccess()
        )
        Assert.assertEquals(2, verified.preflightCalls.toLong())
        Assert.assertEquals(10001, verified.archive.entriesLimit.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun doubleIdentityRejectsRacesAndMismatchBeatsClose() {
        val race: FakeIo = validFakeIo()
        race.changedSource = bytes("changed identity")
        race.switchSourceAt = 2
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            validate(race)
        )
        Assert.assertEquals(1, race.archive.closeCount.toLong())

        val changedAndClose: FakeIo = validFakeIo()
        val validator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(changedAndClose)
        val plan: com.cattailsw.nanidroid.install.NarInstallPlan = validator.validate(
            File("archive.nar"),
            File("install-root"),
            null
        ).plan!!
        changedAndClose.currentSource = bytes("changed identity")
        changedAndClose.sourceCloseFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            validator.verify(File("archive.nar"), plan)
        )

        val unchangedAndClose: FakeIo = validFakeIo()
        val unchangedValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(unchangedAndClose)
        val unchangedPlan: com.cattailsw.nanidroid.install.NarInstallPlan =
            unchangedValidator.validate(
                File("archive.nar"),
                File("install-root"),
                null
            ).plan!!
        unchangedAndClose.sourceCloseFailure = true
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED,
            unchangedValidator.verify(
                File("archive.nar"), unchangedPlan
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun stagedCapabilityIsUnmintableAndDiagnosticsHaveNoAuthority() {
        val diagnostic: com.cattailsw.nanidroid.install.NarInstallPlanResult =
            validate(validFakeIo())
        Assert.assertTrue(diagnostic.isSuccess())
        Assert.assertNull(diagnostic.getVerifiedSession())
        Assert.assertTrue(
            !Modifier.isPublic(com.cattailsw.nanidroid.install.NarStagedSource::class.java.getModifiers())
                    || com.cattailsw.nanidroid.install.NarStagedSource::class.java.getAnnotation<Metadata?>(
                Metadata::class.java
            ) != null
        )
        val constructor: Constructor<*> =
            com.cattailsw.nanidroid.install.NarStagedSource::class.java.getDeclaredConstructor(File::class.java)
        Assert.assertTrue(Modifier.isPrivate(constructor.getModifiers()))
        Assert.assertTrue(
            Modifier.isSynchronized(
                com.cattailsw.nanidroid.install.NarStagedSource::class.java
                    .getDeclaredMethod("claim")
                    .getModifiers()
            )
        )
        for (method in com.cattailsw.nanidroid.install.NarStagedSource::class.java.getDeclaredMethods()) {
            Assert.assertFalse(
                Modifier.isStatic(method.getModifiers())
                        && (method.getReturnType()
                        == com.cattailsw.nanidroid.install.NarStagedSource::class.java) && method.getParameterTypes()
                    .contentEquals(
                        arrayOf<Class<*>?>(File::class.java)
                    )
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun stagedValidationRetainsOwnerAndCleansOneShotSession() {
        val io: FakeIo = validFakeIo()
        val validator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io)
        val source: com.cattailsw.nanidroid.install.NarStagedSource =
            stagedForTest(File("private-staged.nar"))

        val result: com.cattailsw.nanidroid.install.NarInstallPlanResult = validator.validateStaged(
            source, File("install-root"), null
        )

        Assert.assertTrue(result.isSuccess())
        val session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            result.getVerifiedSession()!!
        Assert.assertSame(result.plan, session.getPlan())
        Assert.assertEquals(0, io.archive.closeCount.toLong())
        Assert.assertEquals(0, io.deleteCount.toLong())
        val payload: InputStream =
            session.open(requireNotNull(result.plan).entries.get(0))
        Assert.assertArrayEquals(bytes("payload"), readAll(payload))
        payload.close()
        Assert.assertSame(io.archive.entries.get(0), io.archive.openedEntry)
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.STAGED_SOURCE_INVALID,
            validator.validateStaged(
                source, File("install-root"), null
            )
        )
        Assert.assertEquals(0, io.deleteCount.toLong())

        session.close()
        Assert.assertTrue(session.isClosed())
        Assert.assertEquals(
            mutableListOf<String>("archive-close", "delete"),
            io.events
        )
        session.close()
        Assert.assertEquals(1, io.archive.closeCount.toLong())
        Assert.assertEquals(1, io.deleteCount.toLong())
        try {
            session.open(requireNotNull(result.plan).entries.get(0))
            throw AssertionError("closed session accepted entry")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    @Throws(Exception::class)
    fun stagedVerificationRetainsTheVerifiedPlan() {
        val io: FakeIo = validFakeIo()
        val validator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io)
        val plan: com.cattailsw.nanidroid.install.NarInstallPlan = validator.validate(
            File("archive.nar"),
            File("install-root"),
            null
        ).plan!!
        Assert.assertEquals(1, io.archive.closeCount.toLong())

        val result: com.cattailsw.nanidroid.install.NarInstallPlanResult = validator.verifyStaged(
            stagedForTest(File("verify-staged.nar")), plan
        )

        Assert.assertTrue(result.isSuccess())
        Assert.assertSame(plan, result.plan)
        Assert.assertSame(plan, result.getVerifiedSession()!!.getPlan())
        Assert.assertEquals(1, io.archive.closeCount.toLong())
        requireNotNull(result.getVerifiedSession()!!).close()
        Assert.assertEquals(2, io.archive.closeCount.toLong())
        Assert.assertEquals(1, io.deleteCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun sessionRejectsForeignDirectoryAndNonInstallEntries() {
        val stagedFile: File = zip(
            "bundle/", null,
            "bundle/install.txt", descriptor("ghost-id", "Ghost"),
            "bundle/assets/", null,
            "bundle/assets/file", bytes("payload")
        )
        val validator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator()
        val result: com.cattailsw.nanidroid.install.NarInstallPlanResult = validator.validateStaged(
            stagedForTest(stagedFile),
            temporaryDirectory("session-root"),
            null
        )
        Assert.assertTrue(result.isSuccess())
        val session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            result.getVerifiedSession()!!
        assertSessionOpenRejected(
            session, requireNotNull(result.plan).entries.get(0)
        )
        assertSessionOpenRejected(
            session, requireNotNull(result.plan).entries.get(2)
        )

        val foreign: com.cattailsw.nanidroid.install.NarInstallPlan = validator.validate(
            zip(
                "install.txt", descriptor("other", "Other"),
                "payload", bytes("foreign")
            ),
            temporaryDirectory("foreign-root"),
            null
        ).plan!!
        assertSessionOpenRejected(
            session, foreign.entries.get(1)
        )
        session.close()
        Assert.assertFalse(stagedFile.exists())
    }

    @Test
    @Throws(Exception::class)
    fun everyStagedFailurePhaseCleansClaimedSource() {
        val source: FakeIo = validFakeIo()
        source.sourceOpenFailure = true
        assertStagedFailureCleans(
            source, com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED, 0
        )

        val preflight: FakeIo = validFakeIo()
        preflight.preflightFailure = true
        assertStagedFailureCleans(
            preflight, com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED, 0
        )

        val open: FakeIo = validFakeIo()
        open.archiveOpenFailure = true
        assertStagedFailureCleans(
            open, com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED, 0
        )

        val list: FakeIo = validFakeIo()
        list.archive.listFailure = true
        assertStagedFailureCleans(
            list, com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_READ_FAILED, 1
        )

        val descriptor: FakeIo = validFakeIo()
        descriptor.archive.descriptorReadFailureAt = 1
        assertStagedFailureCleans(
            descriptor, com.cattailsw.nanidroid.install.NarInstallError.DESCRIPTOR_READ_FAILED, 1
        )

        val canonical: FakeIo = validFakeIo()
        canonical.canonicalFailure = true
        assertStagedFailureCleans(
            canonical, com.cattailsw.nanidroid.install.NarInstallError.INSTALL_ROOT_INVALID, 1
        )

        val race: FakeIo = validFakeIo()
        race.changedSource = bytes("changed identity")
        race.switchSourceAt = 2
        assertStagedFailureCleans(
            race, com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH, 1
        )
    }

    @Test
    @Throws(Exception::class)
    fun stagedVerificationFailuresConsumeAndCleanAuthority() {
        val planIo: FakeIo = validFakeIo()
        val plan: com.cattailsw.nanidroid.install.NarInstallPlan =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(planIo)
                .validate(
                    File("archive.nar"),
                    File("install-root"),
                    null
                )
                .plan!!

        val missing: FakeIo = validFakeIo()
        val missingValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(missing)
        val missingSource: com.cattailsw.nanidroid.install.NarStagedSource =
            stagedForTest(File("missing-plan.nar"))
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            missingValidator.verifyStaged(missingSource, null)
        )
        Assert.assertEquals(0, missing.archive.closeCount.toLong())
        Assert.assertEquals(1, missing.deleteCount.toLong())
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.STAGED_SOURCE_INVALID,
            missingValidator.verifyStaged(missingSource, plan)
        )

        val changedBytes: FakeIo = validFakeIo()
        changedBytes.currentSource = bytes("changed identity")
        changedBytes.sourceCloseFailure = true
        changedBytes.deleteFailure = true
        val bytesValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(changedBytes)
        val byteSource: com.cattailsw.nanidroid.install.NarStagedSource =
            stagedForTest(File("changed-bytes.nar"))
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            bytesValidator.verifyStaged(byteSource, plan)
        )
        Assert.assertEquals(0, changedBytes.archive.closeCount.toLong())
        Assert.assertEquals(1, changedBytes.deleteCount.toLong())
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.STAGED_SOURCE_INVALID,
            bytesValidator.verifyStaged(byteSource, plan)
        )

        val central: FakeIo = validFakeIo()
        central.archive.entries.get(0)!!.crc++
        central.archive.closeFailure = true
        central.deleteFailure = true
        val centralValidator: com.cattailsw.nanidroid.install.NarInstallPlanValidator =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(central)
        val centralSource: com.cattailsw.nanidroid.install.NarStagedSource =
            stagedForTest(File("changed-central.nar"))
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
            centralValidator.verifyStaged(centralSource, plan)
        )
        Assert.assertEquals(1, central.archive.closeCount.toLong())
        Assert.assertEquals(1, central.deleteCount.toLong())
        assertError(
            com.cattailsw.nanidroid.install.NarInstallError.STAGED_SOURCE_INVALID,
            centralValidator.verifyStaged(centralSource, plan)
        )
    }

    @Test
    @Throws(Exception::class)
    fun cleanupPreservesPrimaryAndSessionCloseIsExplicit() {
        val semantic: FakeIo = validFakeIo()
        semantic.archive.entries.removeAt(1)
        reindex(semantic.archive.entries)
        semantic.archive.closeFailure = true
        semantic.deleteFailure = true
        assertStagedFailureCleans(
            semantic,
            com.cattailsw.nanidroid.install.NarInstallError.MISSING_INSTALL_DESCRIPTOR,
            1
        )

        val cleanup: FakeIo = validFakeIo()
        cleanup.archive.closeFailure = true
        cleanup.deleteFailure = true
        val result: com.cattailsw.nanidroid.install.NarInstallPlanResult =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(cleanup).validateStaged(
                stagedForTest(File("cleanup.nar")),
                File("install-root"),
                null
            )
        Assert.assertTrue(result.isSuccess())
        try {
            requireNotNull(result.getVerifiedSession()!!).close()
            throw AssertionError("cleanup failure was hidden")
        } catch (expected: IOException) {
            // Expected.
        }
        Assert.assertEquals(1, cleanup.archive.closeCount.toLong())
        Assert.assertEquals(1, cleanup.deleteCount.toLong())
        cleanup.archive.closeFailure = false
        cleanup.deleteFailure = false
        requireNotNull(result.getVerifiedSession()!!).close()
        Assert.assertEquals(2, cleanup.archive.closeCount.toLong())
        Assert.assertEquals(2, cleanup.deleteCount.toLong())

        val runtime: FakeIo = validFakeIo()
        runtime.archive.runtimeCloseFailure = true
        val runtimeResult: com.cattailsw.nanidroid.install.NarInstallPlanResult =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(runtime).validateStaged(
                stagedForTest(File("runtime.nar")),
                File("install-root"),
                null
            )
        try {
            runtimeResult.getVerifiedSession()!!.close()
            throw AssertionError("runtime close was hidden")
        } catch (expected: IOException) {
            // Expected.
        }
        Assert.assertEquals(1, runtime.deleteCount.toLong())
        runtime.archive.runtimeCloseFailure = false
        runtimeResult.getVerifiedSession()!!.close()
        Assert.assertEquals(2, runtime.archive.closeCount.toLong())
        Assert.assertEquals(1, runtime.deleteCount.toLong())
        Assert.assertTrue(runtimeResult.getVerifiedSession()!!.isClosed())
    }

    @Test
    @Throws(Exception::class)
    fun verifiedSessionLeaseIsExclusiveExactAndTransferOwned() {
        val io: FakeIo = validFakeIo()
        val session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validateStaged(
                stagedForTest(File("lease.nar")),
                File("install-root"), null
            )
                .getVerifiedSession()!!
        val otherIo: FakeIo = validFakeIo()
        val other: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(otherIo).validateStaged(
                stagedForTest(File("other.nar")),
                File("install-root"), null
            )
                .getVerifiedSession()!!

        Assert.assertEquals("READY", session.state().name)
        val lease: NarVerifiedInstallSession.Lease? = session.lease()
        Assert.assertSame(session.getPlan(), requireNotNull(lease).plan())
        Assert.assertEquals("BUSY", session.state().name)
        Assert.assertNull(session.lease())
        Assert.assertThrows<IllegalStateException?>(
            IllegalStateException::class.java,
            ThrowingRunnable { session.open(session.getPlan().entries.get(0)) })
        val foreign: NarVerifiedInstallSession.Lease? = other.lease()
        Assert.assertEquals("FOREIGN", session.release(foreign).name)
        Assert.assertEquals("OK", session.release(lease).name)
        Assert.assertEquals("READY", session.state().name)
        Assert.assertEquals("STALE", session.release(lease).name)
        Assert.assertThrows<IllegalStateException?>(
            IllegalStateException::class.java,
            ThrowingRunnable { requireNotNull(lease).plan() })
        Assert.assertEquals("OK", other.release(foreign).name)

        val consumed: NarVerifiedInstallSession.Lease? = session.lease()
        Assert.assertEquals("OK", session.consume(consumed).name)
        Assert.assertEquals("CONSUMED", session.state().name)
        Assert.assertEquals("CONSUMED", session.consume(consumed).name)
        Assert.assertEquals("CONSUMED", session.release(consumed).name)
        Assert.assertNull(session.lease())
        Assert.assertThrows<IllegalStateException?>(
            IllegalStateException::class.java,
            ThrowingRunnable { session.close() })
        requireNotNull(consumed).cleanup()
        Assert.assertTrue(session.isClosed())
        requireNotNull(consumed).cleanup()
        Assert.assertEquals(1, io.archive.closeCount.toLong())
        Assert.assertEquals(1, io.deleteCount.toLong())
        assertLeaseSurface()
        other.close()
    }

    @Test
    @Throws(Exception::class)
    fun verifiedCleanupRetriesEachUnfinishedComponentAndFirstFailure() {
        val io: FakeIo = validFakeIo()
        val session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validateStaged(
                stagedForTest(File("retry.nar")),
                File("install-root"), null
            )
                .getVerifiedSession()!!
        io.archive.closeFailure = true
        io.deleteFailure = true
        Assert.assertThrows<IOException?>(
            IOException::class.java,
            ThrowingRunnable { session.close() })
        Assert.assertEquals(mutableListOf<String>("archive-close", "delete"), io.events)
        Assert.assertEquals("CONSUMED", session.state().name)
        Assert.assertFalse(session.isClosed())
        Assert.assertNull(session.lease())
        io.archive.closeFailure = false
        Assert.assertThrows<IOException?>(
            IOException::class.java,
            ThrowingRunnable { session.close() })
        Assert.assertEquals(2, io.archive.closeCount.toLong())
        Assert.assertEquals(2, io.deleteCount.toLong())
        io.deleteFailure = false
        session.close()
        session.close()
        Assert.assertEquals(2, io.archive.closeCount.toLong())
        Assert.assertEquals(3, io.deleteCount.toLong())
        Assert.assertTrue(session.isClosed())

        val fatal: FakeIo = validFakeIo()
        val fatalSession: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(fatal).validateStaged(
                stagedForTest(File("fatal.nar")),
                File("install-root"), null
            )
                .getVerifiedSession()!!
        val first = OutOfMemoryError("close")
        fatal.archive.closeThrowable = first
        fatal.deleteThrowable = LinkageError("delete")
        Assert.assertSame(
            first, Assert.assertThrows<OutOfMemoryError?>(
                OutOfMemoryError::class.java,
                ThrowingRunnable { fatalSession.close() })
        )
        Assert.assertEquals(1, fatal.archive.closeCount.toLong())
        Assert.assertEquals(1, fatal.deleteCount.toLong())
        fatal.archive.closeThrowable = null
        Assert.assertThrows<LinkageError?>(
            LinkageError::class.java,
            ThrowingRunnable { fatalSession.close() })
        Assert.assertEquals(2, fatal.archive.closeCount.toLong())
        Assert.assertEquals(2, fatal.deleteCount.toLong())
        fatal.deleteThrowable = null
        fatalSession.close()
        Assert.assertEquals(2, fatal.archive.closeCount.toLong())
        Assert.assertEquals(3, fatal.deleteCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun concurrentVerifiedCleanupCompletesEachComponentOnce() {
        val io: FakeIo = validFakeIo()
        val session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validateStaged(
                stagedForTest(File("race.nar")),
                File("install-root"), null
            )
                .getVerifiedSession()!!
        val failures = arrayOfNulls<Throwable>(2)
        race(
            Runnable { closeInto(session, failures, 0) },
            Runnable { closeInto(session, failures, 1) })
        Assert.assertNull(failures[0])
        Assert.assertNull(failures[1])
        Assert.assertTrue(session.isClosed())
        Assert.assertEquals(1, io.archive.closeCount.toLong())
        Assert.assertEquals(1, io.deleteCount.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun concurrentVerifiedLeaseAndCleanupAreLinearized() {
        val io: FakeIo = validFakeIo()
        val session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession =
            com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validateStaged(
                stagedForTest(File("lease-race.nar")),
                File("install-root"), null
            )
                .getVerifiedSession()!!
        val lease: Array<NarVerifiedInstallSession.Lease?> =
            kotlin.arrayOfNulls<NarVerifiedInstallSession.Lease>(1)
        val closeFailure = arrayOfNulls<Throwable>(1)
        race(
            Runnable { lease[0] = session.lease() },
            Runnable { closeInto(session, closeFailure, 0) })
        Assert.assertThrows<IllegalStateException?>(
            IllegalStateException::class.java,
            ThrowingRunnable { session.open(session.getPlan().entries.get(0)) })
        if (lease[0] == null) {
            Assert.assertNull(closeFailure[0])
            Assert.assertTrue(session.isClosed())
        } else {
            Assert.assertTrue(closeFailure[0] is IllegalStateException)
            Assert.assertEquals("BUSY", session.state().name)
            Assert.assertEquals(0, io.archive.closeCount.toLong())
            Assert.assertEquals(0, io.deleteCount.toLong())
            Assert.assertEquals("OK", session.consume(lease[0]).name)
            requireNotNull(lease[0]).cleanup()
        }
        Assert.assertEquals("CONSUMED", session.state().name)
        Assert.assertEquals(1, io.archive.closeCount.toLong())
        Assert.assertEquals(1, io.deleteCount.toLong())
    }

    private class FakeIo
        (source: ByteArray, archive: FakeArchive) :
        com.cattailsw.nanidroid.install.NarInstallPlanValidator.ArchiveIo {
        val source: ByteArray?
        var currentSource: ByteArray?
        var changedSource: ByteArray? = null
        val archive: FakeArchive
        var hintedLength: Long
        var virtualSourceLength: Long = -1
        var sourceReadFailureAt = -1
        var zeroFirstSourceRead = false
        var sourceCloseFailure = false
        var sourceOpenFailure = false
        var archiveOpenFailure = false
        var canonicalFailure = false
        var preflightFailure = false
        var deleteFailure = false
        var deleteThrowable: Throwable? = null
        var switchSourceAt = -1
        var preflightCount = -1
        var sourceOpenCount = 0
        var sourceCloseCount = 0
        var sourceBytesRead: Long = 0
        var archiveOpenCount = 0
        var canonicalCount = 0
        var canonicalArgument: File? = null
        var preflightCalls = 0
        var deleteCount = 0
        val events: MutableList<String> = ArrayList<String>()
        val canonicalResult: File = File("canonical-sentinel").getAbsoluteFile()

        init {
            this.source = source
            currentSource = source
            this.archive = archive
            archive.owner = this
            hintedLength = source.size.toLong()
        }

        override fun length(file: File): Long {
            return hintedLength
        }

        @Throws(IOException::class)
        override fun openSource(file: File): InputStream {
            sourceOpenCount++
            if (sourceOpenFailure) {
                throw IOException("source open")
            }
            val selected = (if (switchSourceAt > 0
                && sourceOpenCount >= switchSourceAt
            )
                changedSource
            else
                currentSource)!!
            return ScriptedInputStream(
                selected,
                virtualSourceLength,
                sourceReadFailureAt,
                zeroFirstSourceRead,
                sourceCloseFailure,
                this,
                null
            )
        }

        @Throws(IOException::class)
        override fun preflight(file: File): Int {
            preflightCalls++
            if (preflightFailure) {
                throw IOException("preflight")
            }
            return if (preflightCount >= 0)
                preflightCount
            else
                archive.entries.size
        }

        @Throws(IOException::class)
        override fun openArchive(
            file: File
        ): com.cattailsw.nanidroid.install.NarInstallPlanValidator.OpenArchive {
            archiveOpenCount++
            if (archiveOpenFailure) {
                throw IOException("open")
            }
            return archive
        }

        @Throws(IOException::class)
        override fun canonical(file: File): File {
            canonicalCount++
            canonicalArgument = file
            if (canonicalFailure) {
                throw IOException("canonical")
            }
            return canonicalResult
        }

        override fun delete(file: File): Boolean {
            deleteCount++
            events.add("delete")
            throwUnchecked(deleteThrowable)
            return !deleteFailure
        }
    }

    private class FakeArchive
        (
        val entries: MutableList<FakeEntry?>,
        var descriptorBytes: ByteArray?
    ) : com.cattailsw.nanidroid.install.NarInstallPlanValidator.OpenArchive {
        var owner: FakeIo? = null
        var virtualDescriptorLength: Long = -1
        var descriptorReadFailureAt = -1
        var descriptorCloseFailure = false
        var descriptorOpenFailure = false
        var zeroFirstDescriptorRead = false
        var listFailure = false
        var closeFailure = false
        var runtimeCloseFailure = false
        var closeThrowable: Throwable? = null
        var closeCount = 0
        var descriptorCloseCount = 0
        var descriptorBytesRead: Long = 0
        var openedEntry: FakeEntry? = null
        var virtualEntryCount = -1
        var entriesLimit = 0

        @Throws(IOException::class)
        override fun entries(limit: Int): List<com.cattailsw.nanidroid.install.NarInstallPlanValidator.ArchiveEntry> {
            entriesLimit = limit
            if (listFailure) {
                throw IOException("list")
            }
            if (virtualEntryCount >= 0) {
                val virtual: MutableList<FakeEntry?> = ArrayList<FakeEntry?>()
                var index = 0
                while (index < virtualEntryCount && index < limit
                ) {
                    virtual.add(entries.get(index % entries.size))
                    index++
                }
                return virtual.map(::requireNotNull)
            }
            return entries.map(::requireNotNull)
        }

        @Throws(IOException::class)
        override fun open(
            entry: com.cattailsw.nanidroid.install.NarInstallPlanValidator.ArchiveEntry
        ): InputStream {
            openedEntry = entry as FakeEntry
            if (descriptorOpenFailure) {
                throw IOException("descriptor open")
            }
            val content = (if ("install.txt" == openedEntry!!.rawName)
                descriptorBytes
            else
                NarInstallPlanValidatorTest.Companion.bytes("payload"))!!
            return ScriptedInputStream(
                content,
                if ("install.txt" == openedEntry!!.rawName)
                    virtualDescriptorLength
                else
                    -1,
                descriptorReadFailureAt,
                zeroFirstDescriptorRead,
                descriptorCloseFailure,
                null,
                this
            )
        }

        @Throws(IOException::class)
        override fun close() {
            closeCount++
            if (owner != null) {
                owner?.events?.add("archive-close")
            }
            if (closeThrowable is IOException) {
                throw closeThrowable as IOException
            }
            throwUnchecked(closeThrowable)
            check(!runtimeCloseFailure) { "zip close" }
            if (closeFailure) {
                throw IOException("zip close")
            }
        }
    }

    private class FakeEntry
        (@JvmField var rawName: String?, @JvmField var isDirectory: Boolean) :
        com.cattailsw.nanidroid.install.NarInstallPlanValidator.ArchiveEntry {
        @JvmField var ordinal: Int = 0

        @JvmField var crc: Long = 0

        @JvmField var method: Int = 8

        @JvmField var declaredSize: Long = 0

        @JvmField var compressedSize: Long = 1


        override fun isDirectory(): Boolean = isDirectory
        override fun getOrdinal(): Int = ordinal
        override fun getRawName(): String? = rawName
        override fun getCrc(): Long = crc
        override fun getMethod(): Int = method
        override fun getDeclaredSize(): Long = declaredSize
        override fun getCompressedSize(): Long = compressedSize    }

    private class ScriptedInputStream(
        val content: ByteArray,
        virtualLength: Long,
        val failAt: Int,
        val zeroFirst: Boolean,
        val closeFailure: Boolean,
        val owner: FakeIo?,
        val archive: FakeArchive?
    ) : InputStream() {
        var remaining: Long
        var position = 0
        var reads = 0
        var zeroReturned = false

        init {
            this.remaining = if (virtualLength >= 0)
                virtualLength
            else
                content.size.toLong()
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            beforeRead()
            if (zeroFirst && !zeroReturned) {
                zeroReturned = true
                return 0
            }
            if (remaining == 0L) {
                return -1
            }
            val count = min(length.toLong(), remaining).toInt()
            if (position < content.size) {
                val copied = min(count, content.size - position)
                System.arraycopy(content, position, buffer, offset, copied)
                if (copied < count) {
                    Arrays.fill(buffer, offset + copied, offset + count, 0.toByte())
                }
            } else {
                Arrays.fill(buffer, offset, offset + count, 0.toByte())
            }
            position += count
            remaining -= count.toLong()
            recordBytes(count)
            return count
        }

        @Throws(IOException::class)
        override fun read(): Int {
            beforeRead()
            if (remaining == 0L) {
                return -1
            }
            val value = if (position < content.size) content[position].toInt() and 0xff else 0
            position++
            remaining--
            recordBytes(1)
            return value
        }

        fun recordBytes(count: Int) {
            if (owner != null) {
                owner.sourceBytesRead += count.toLong()
            }
            if (archive != null) {
                archive.descriptorBytesRead += count.toLong()
            }
        }

        @Throws(IOException::class)
        fun beforeRead() {
            reads++
            if (reads == failAt) {
                throw IOException("scripted read")
            }
        }

        @Throws(IOException::class)
        override fun close() {
            if (owner != null) {
                owner.sourceCloseCount++
            }
            if (archive != null) {
                archive.descriptorCloseCount++
            }
            if (closeFailure) {
                throw IOException("scripted close")
            }
        }
    }

    companion object {
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
        val MIB = 1024L * 1024L
        val MAX_ARCHIVE_BYTES: Long = 544L * MIB

        private fun assertLeaseSurface() {
            val type: Class<*> = NarVerifiedInstallSession.Lease::class.java
            Assert.assertTrue(
                !Modifier.isPublic(type.getModifiers())
                        || type.getAnnotation<Metadata?>(Metadata::class.java) != null
            )
            for (field in type.getDeclaredFields()) {
                Assert.assertFalse(forbiddenLeaseType(field.getType()))
            }
            val actual: MutableList<String> = ArrayList<String>()
            for (method in type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.getName().contains("$")) continue
                actual.add(method.getName())
                Assert.assertTrue(
                    !Modifier.isPublic(method.getModifiers())
                            || type.getAnnotation<Metadata?>(Metadata::class.java) != null
                )
                Assert.assertFalse(
                    method.getName()
                        .matches("(finalize|publish|overlay|path|token|handle)".toRegex())
                )
                Assert.assertFalse(forbiddenLeaseType(method.getReturnType()))
                for (parameter in method.getParameterTypes()) {
                    Assert.assertFalse(forbiddenLeaseType(parameter))
                }
            }
            Collections.sort<String?>(actual)
            Assert.assertEquals(mutableListOf<String>("cleanup", "plan"), actual)
        }

        private fun forbiddenLeaseType(type: Class<*>): Boolean {
            val name = type.getName()
            return name == "java.io.File"
                    || name.startsWith("java.nio.file")
                    || name.contains("InputStream")
                    || name.contains("OutputStream")
                    || name.contains("Reader")
                    || name.contains("Writer")
                    || name.contains("Handle")
        }

        private fun closeInto(
            session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession,
            failures: Array<Throwable?>, index: Int
        ) {
            try {
                session.close()
            } catch (failure: Throwable) {
                failures[index] = failure
            }
        }

        @Throws(Exception::class)
        private fun race(first: Runnable, second: Runnable) {
            val start = CountDownLatch(1)
            val one = Thread(Runnable {
                await(start)
                first.run()
            })
            val two = Thread(Runnable {
                await(start)
                second.run()
            })
            one.start()
            two.start()
            start.countDown()
            one.join(5000)
            two.join(5000)
            Assert.assertFalse(one.isAlive())
            Assert.assertFalse(two.isAlive())
        }

        private fun await(latch: CountDownLatch) {
            try {
                latch.await()
            } catch (failure: InterruptedException) {
                throw AssertionError(failure)
            }
        }

        private fun validate(io: FakeIo): com.cattailsw.nanidroid.install.NarInstallPlanResult {
            return com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validate(
                File("archive.nar"),
                File("install-root"),
                null
            )
        }

        @Throws(Exception::class)
        private fun stagedForTest(file: File?): com.cattailsw.nanidroid.install.NarStagedSource {
            val constructor: Constructor<com.cattailsw.nanidroid.install.NarStagedSource> =
                com.cattailsw.nanidroid.install.NarStagedSource::class.java.getDeclaredConstructor(
                    File::class.java
                )
            constructor.setAccessible(true)
            return constructor.newInstance(file)
        }

        @Throws(IOException::class)
        private fun readAll(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32)
            var count: Int
            while ((input.read(buffer).also { count = it }) >= 0) {
                if (count > 0) {
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }

        @Throws(IOException::class)
        private fun assertSessionOpenRejected(
            session: com.cattailsw.nanidroid.install.NarVerifiedInstallSession,
            entry: com.cattailsw.nanidroid.install.NarInstallPlan.Entry
        ) {
            try {
                session.open(entry)
                throw AssertionError("unsafe entry accepted")
            } catch (expected: IllegalArgumentException) {
                // Expected.
            }
        }

        @Throws(Exception::class)
        private fun assertStagedFailureCleans(
            io: FakeIo,
            error: com.cattailsw.nanidroid.install.NarInstallError?,
            expectedCloseCount: Int
        ) {
            assertError(
                error,
                com.cattailsw.nanidroid.install.NarInstallPlanValidator(io).validateStaged(
                    stagedForTest(File("failed-staged.nar")),
                    File("install-root"),
                    null
                )
            )
            Assert.assertEquals(expectedCloseCount.toLong(), io.archive.closeCount.toLong())
            Assert.assertEquals(1, io.deleteCount.toLong())
        }

        private fun assertError(
            expected: com.cattailsw.nanidroid.install.NarInstallError?,
            result: com.cattailsw.nanidroid.install.NarInstallPlanResult
        ) {
            Assert.assertFalse(result.isSuccess())
            Assert.assertEquals(expected, result.error)
            Assert.assertNull(result.plan)
        }

        private fun validFakeIo(): FakeIo {
            val descriptor: ByteArray = descriptor("ghost-id", "Ghost")
            val install = FakeEntry("install.txt", false)
            install.declaredSize = descriptor.size.toLong()
            val payload = FakeEntry("payload", false)
            payload.declaredSize = 7
            val archive = FakeArchive(
                ArrayList<FakeEntry?>(Arrays.asList<FakeEntry?>(payload, install)),
                descriptor
            )
            reindex(archive.entries)
            return FakeIo(bytes("source identity"), archive)
        }

        private fun reindex(entries: MutableList<FakeEntry?>) {
            for (index in entries.indices) {
                entries.get(index)!!.ordinal = index
            }
        }

        private fun mutateCentral(entry: FakeEntry, field: Int) {
            if (field == 0) entry.ordinal++
            if (field == 1) entry.rawName += "-changed"
            if (field == 2) entry.isDirectory = !entry.isDirectory
            if (field == 3) entry.crc++
            if (field == 4) entry.method = if (entry.method == 8) 0 else 8
            if (field == 5) entry.declaredSize++
            if (field == 6) entry.compressedSize++
        }

        private fun descriptor(id: String?, name: String?): ByteArray {
            return bytes(
                "type,ghost\nname," + name + "\ndirectory," + id + "\n"
            )
        }

        private fun bytes(value: String): ByteArray {
            return value.toByteArray(SHIFT_JIS)
        }

        private fun paddedDescriptor(byteCount: Int): ByteArray {
            val prefix = ("type,ghost\nname,G\ndirectory,g\npadding,")
                .toByteArray(SHIFT_JIS)
            val result = prefix.copyOf(byteCount)
            Arrays.fill(result, prefix.size, result.size, 'a'.code.toByte())
            return result
        }

        @Throws(Exception::class)
        private fun sha256(content: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(content)
        }

        @Throws(Exception::class)
        private fun sha256(file: File?): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val input: InputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            try {
                var count: Int
                while ((input.read(buffer).also { count = it }) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                    }
                }
            } finally {
                input.close()
            }
            return digest.digest()
        }

        @Throws(IOException::class)
        private fun temporaryDirectory(label: String?): File {
            val marker = File.createTempFile("nanidroid-" + label, ".tmp")
            Assert.assertTrue(marker.delete())
            Assert.assertTrue(marker.mkdir())
            marker.deleteOnExit()
            return marker
        }

        @Throws(IOException::class)
        private fun zip(vararg namesAndBytes: Any?): File {
            val archive = File.createTempFile("nanidroid-plan", ".nar")
            archive.deleteOnExit()
            overwriteZip(archive, *namesAndBytes)
            return archive
        }

        @Throws(IOException::class)
        private fun overwriteZip(
            archive: File?,
            vararg namesAndBytes: Any?
        ) {
            val output =
                ZipOutputStream(FileOutputStream(archive))
            var failure: IOException? = null
            try {
                var index = 0
                while (index < namesAndBytes.size) {
                    val name = namesAndBytes[index] as String?
                    val content = namesAndBytes[index + 1] as ByteArray?
                    output.putNextEntry(ZipEntry(name))
                    if (content != null) {
                        output.write(content)
                    }
                    output.closeEntry()
                    index += 2
                }
            } catch (error: IOException) {
                failure = error
                throw error
            } finally {
                try {
                    output.close()
                } catch (close: IOException) {
                    if (failure == null) {
                        throw close
                    }
                }
            }
        }

        private fun throwUnchecked(failure: Throwable?) {
            if (failure is RuntimeException) {
                throw failure
            }
            if (failure is Error) throw failure
        }
    }
}
