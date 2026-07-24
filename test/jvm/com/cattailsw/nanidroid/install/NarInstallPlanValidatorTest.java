package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public final class NarInstallPlanValidatorTest {
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_ARCHIVE_BYTES = 544L * MIB;

    @Test
    public void plansRootAndWrappedRealArchivesWithoutWritingInstallRoot()
            throws Exception {
        File installRoot = temporaryDirectory("plan-root");
        File marker = new File(installRoot, "existing");
        assertTrue(marker.createNewFile());
        File rootArchive = zip(
                "install.txt", descriptor("descriptor-id", "Root Ghost"),
                "ghost/master/file.txt", bytes("payload"));

        NarInstallPlanResult rootResult =
                new NarInstallPlanValidator().validate(
                        rootArchive, installRoot, null);

        assertTrue(rootResult.isSuccess());
        NarInstallPlan rootPlan = rootResult.getPlan();
        assertNull(rootPlan.getWrapperDirectory());
        assertEquals(rootArchive.length(), rootPlan.getSourceLength());
        assertArrayEquals(sha256(rootArchive), rootPlan.getSourceSha256());
        assertEquals("descriptor-id", rootPlan.getDescriptor().getTargetId());
        assertEquals(2, rootPlan.getEntries().size());
        NarInstallPlan.Entry payload = rootPlan.getEntries().get(1);
        assertEquals(1, payload.getOrdinal());
        assertEquals("ghost/master/file.txt", payload.getRawName());
        assertEquals(
                "ghost/master/file.txt", payload.getNormalizedArchivePath());
        assertEquals("ghost/master/file.txt", payload.getRelativePath());
        assertTrue(payload.isInstallEntry());
        assertFalse(payload.isDirectory());
        assertTrue(payload.getCrc() >= 0);
        assertTrue(payload.getMethod() >= 0);
        assertEquals(7, payload.getDeclaredSize());
        assertTrue(payload.getCompressedSize() >= 0);
        assertEquals(
                installRoot.getCanonicalFile(), rootPlan.getInstallRoot());
        assertEquals(
                new File(installRoot.getCanonicalFile(), "descriptor-id"),
                rootPlan.getTargetDirectory());
        assertArrayEquals(
                new String[] {"existing"}, installRoot.list());

        File wrapped = zip(
                "bundle/", null,
                "bundle/install.txt", descriptor("ignored", "Wrapped Ghost"),
                "bundle/ghost/file.txt", bytes("wrapped"));
        NarInstallPlanResult wrappedResult =
                new NarInstallPlanValidator().validate(
                        wrapped, installRoot, "forced-id");
        assertTrue(wrappedResult.isSuccess());
        NarInstallPlan wrappedPlan = wrappedResult.getPlan();
        assertEquals("bundle", wrappedPlan.getWrapperDirectory());
        assertEquals("forced-id", wrappedPlan.getDescriptor().getTargetId());
        assertFalse(wrappedPlan.getEntries().get(0).isInstallEntry());
        assertEquals(
                "install.txt",
                wrappedPlan.getEntries().get(1).getRelativePath());
        assertEquals(
                new File(installRoot.getCanonicalFile(), "forced-id"),
                wrappedPlan.getTargetDirectory());
        assertArrayEquals(
                new String[] {"existing"}, installRoot.list());
    }

    @Test
    public void returnsDetachedImmutablePlanIdentityEntriesAndMetadata()
            throws Exception {
        File archive = zip(
                "install.txt", descriptor("ghost-id", "Ghost"),
                "payload", bytes("payload"));
        NarInstallPlan plan = new NarInstallPlanValidator().validate(
                archive, temporaryDirectory("immutable"), null).getPlan();

        byte[] firstDigest = plan.getSourceSha256();
        byte[] secondDigest = plan.getSourceSha256();
        assertNotSame(firstDigest, secondDigest);
        byte original = secondDigest[0];
        firstDigest[0] ^= 0x7f;
        assertEquals(original, plan.getSourceSha256()[0]);
        try {
            plan.getEntries().clear();
            throw new AssertionError("plan entries must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            plan.getDescriptor().getMetadata().clear();
            throw new AssertionError("descriptor metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        FakeIo io = validFakeIo();
        NarInstallPlan detached = new NarInstallPlanValidator(io).validate(
                new File("archive.nar"),
                new File("install-root"),
                null).getPlan();
        NarInstallPlan.Entry frozen = detached.getEntries().get(0);
        int ordinal = frozen.getOrdinal();
        String rawName = frozen.getRawName();
        String normalized = frozen.getNormalizedArchivePath();
        String relative = frozen.getRelativePath();
        boolean installEntry = frozen.isInstallEntry();
        boolean directory = frozen.isDirectory();
        long crc = frozen.getCrc();
        int method = frozen.getMethod();
        long size = frozen.getDeclaredSize();
        long compressed = frozen.getCompressedSize();
        for (int field = 0; field < 7; field++) {
            mutateCentral(io.archive.entries.get(0), field);
        }
        assertEquals(ordinal, frozen.getOrdinal());
        assertEquals(rawName, frozen.getRawName());
        assertEquals(normalized, frozen.getNormalizedArchivePath());
        assertEquals(relative, frozen.getRelativePath());
        assertEquals(installEntry, frozen.isInstallEntry());
        assertEquals(directory, frozen.isDirectory());
        assertEquals(crc, frozen.getCrc());
        assertEquals(method, frozen.getMethod());
        assertEquals(size, frozen.getDeclaredSize());
        assertEquals(compressed, frozen.getCompressedSize());
    }

    @Test
    public void verifiesDigestLengthAndExactCentralIdentityOnReopen()
            throws Exception {
        File archive = zip(
                "install.txt", descriptor("ghost-id", "Ghost"),
                "payload", bytes("first"));
        File root = temporaryDirectory("verify");
        NarInstallPlanValidator validator = new NarInstallPlanValidator();
        NarInstallPlan plan = validator.validate(archive, root, null).getPlan();
        NarInstallPlanResult unchanged = validator.verify(archive, plan);
        assertTrue(unchanged.isSuccess());
        assertSame(plan, unchanged.getPlan());

        overwriteZip(
                archive,
                "install.txt", descriptor("ghost-id", "Ghost"),
                "payload", bytes("other"));
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                validator.verify(archive, plan));

        FakeIo io = validFakeIo();
        NarInstallPlanValidator fakeValidator = new NarInstallPlanValidator(io);
        NarInstallPlan fakePlan = fakeValidator.validate(
                new File("archive.nar"), root, null).getPlan();
        assertTrue(fakeValidator.verify(
                new File("archive.nar"), fakePlan).isSuccess());
        assertEquals(2, io.archive.closeCount);
        Collections.swap(io.archive.entries, 0, 1);
        reindex(io.archive.entries);
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                fakeValidator.verify(new File("archive.nar"), fakePlan));
        Collections.swap(io.archive.entries, 0, 1);
        reindex(io.archive.entries);

        for (int field = 0; field < 7; field++) {
            FakeIo changed = validFakeIo();
            NarInstallPlanValidator changedValidator =
                    new NarInstallPlanValidator(changed);
            NarInstallPlan changedPlan = changedValidator.validate(
                    new File("archive.nar"), root, null).getPlan();
            mutateCentral(changed.archive.entries.get(0), field);
            assertError(
                    NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                    changedValidator.verify(
                            new File("archive.nar"), changedPlan));
        }

        FakeIo close = validFakeIo();
        NarInstallPlanValidator closeValidator =
                new NarInstallPlanValidator(close);
        NarInstallPlan closePlan = closeValidator.validate(
                new File("archive.nar"), root, null).getPlan();
        close.archive.entries.get(0).crc = 7;
        close.archive.closeFailure = true;
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                closeValidator.verify(new File("archive.nar"), closePlan));
        close.archive.entries.get(0).crc = 0;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                closeValidator.verify(new File("archive.nar"), closePlan));
    }

    @Test
    public void enforcesEarlyAndAuthoritativeStreamedArchiveCap()
            throws Exception {
        FakeIo early = validFakeIo();
        early.hintedLength = MAX_ARCHIVE_BYTES + 1;
        assertError(
                NarInstallError.ARCHIVE_SIZE_LIMIT,
                validate(early));
        assertEquals(0, early.sourceOpenCount);

        FakeIo streamed = validFakeIo();
        streamed.hintedLength = 0;
        streamed.virtualSourceLength = MAX_ARCHIVE_BYTES + 10 * MIB;
        assertError(
                NarInstallError.ARCHIVE_SIZE_LIMIT,
                validate(streamed));
        assertEquals(1, streamed.sourceCloseCount);
        assertEquals(
                MAX_ARCHIVE_BYTES + 1, streamed.sourceBytesRead);
        assertEquals(0, streamed.archiveOpenCount);

        FakeIo exact = validFakeIo();
        exact.hintedLength = 0;
        exact.virtualSourceLength = MAX_ARCHIVE_BYTES;
        assertTrue(validate(exact).isSuccess());
        assertEquals(
                2 * MAX_ARCHIVE_BYTES, exact.sourceBytesRead);
    }

    @Test
    public void handlesZeroReadsAndMapsHashReadAndCloseFailures()
            throws Exception {
        FakeIo zero = validFakeIo();
        zero.zeroFirstSourceRead = true;
        NarInstallPlanResult zeroResult = validate(zero);
        assertTrue(zeroResult.isSuccess());
        assertArrayEquals(
                sha256(zero.source),
                zeroResult.getPlan().getSourceSha256());
        assertEquals(2, zero.sourceCloseCount);

        FakeIo openFailure = validFakeIo();
        openFailure.sourceOpenFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(openFailure));
        assertEquals(0, openFailure.sourceCloseCount);
        assertEquals(0, openFailure.archiveOpenCount);

        FakeIo readFailure = validFakeIo();
        readFailure.sourceReadFailureAt = 2;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(readFailure));
        assertEquals(1, readFailure.sourceCloseCount);
        assertEquals(0, readFailure.archiveOpenCount);

        FakeIo closeFailure = validFakeIo();
        closeFailure.sourceCloseFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(closeFailure));

        FakeIo sizeAndClose = validFakeIo();
        sizeAndClose.hintedLength = 0;
        sizeAndClose.virtualSourceLength = MAX_ARCHIVE_BYTES + 1;
        sizeAndClose.sourceCloseFailure = true;
        assertError(
                NarInstallError.ARCHIVE_SIZE_LIMIT,
                validate(sizeAndClose));
    }

    @Test
    public void mapsZipOpenListAndCloseWithSemanticPrecedence()
            throws Exception {
        FakeIo openFailure = validFakeIo();
        openFailure.archiveOpenFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(openFailure));
        assertEquals(0, openFailure.archive.closeCount);

        FakeIo listFailure = validFakeIo();
        listFailure.archive.listFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(listFailure));
        assertEquals(1, listFailure.archive.closeCount);

        FakeIo closeFailure = validFakeIo();
        closeFailure.archive.closeFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(closeFailure));
        assertEquals(1, closeFailure.archive.closeCount);

        FakeIo semanticAndClose = validFakeIo();
        semanticAndClose.archive.entries.remove(1);
        reindex(semanticAndClose.archive.entries);
        semanticAndClose.archive.closeFailure = true;
        assertError(
                NarInstallError.MISSING_INSTALL_DESCRIPTOR,
                validate(semanticAndClose));
        assertEquals(1, semanticAndClose.archive.closeCount);
    }

    @Test
    public void readsDescriptorByExactOrdinalObjectWithBoundedClosePolicy()
            throws Exception {
        FakeIo exact = validFakeIo();
        NarInstallPlanResult exactResult = validate(exact);
        assertTrue(exactResult.isSuccess());
        assertSame(
                exact.archive.entries.get(1), exact.archive.openedEntry);
        assertEquals(1, exact.archive.descriptorCloseCount);
        assertEquals(1, exact.archive.closeCount);
        assertEquals(1, exact.canonicalCount);
        assertEquals(
                new File("install-root"), exact.canonicalArgument);
        assertFalse(
                exact.canonicalResult.equals(exact.canonicalArgument));
        assertEquals(
                exact.canonicalResult, exactResult.getPlan().getInstallRoot());
        assertEquals(
                new File(exact.canonicalResult, "ghost-id"),
                exactResult.getPlan().getTargetDirectory());

        FakeIo zero = validFakeIo();
        zero.archive.zeroFirstDescriptorRead = true;
        NarInstallPlanResult zeroResult = validate(zero);
        assertTrue(zeroResult.isSuccess());
        assertEquals(
                "Ghost", zeroResult.getPlan().getDescriptor().getName());

        FakeIo openFailure = validFakeIo();
        openFailure.archive.descriptorOpenFailure = true;
        assertError(
                NarInstallError.DESCRIPTOR_READ_FAILED,
                validate(openFailure));
        assertEquals(1, openFailure.archive.closeCount);
        assertEquals(0, openFailure.archive.descriptorCloseCount);

        FakeIo readFailure = validFakeIo();
        readFailure.archive.descriptorReadFailureAt = 1;
        readFailure.archive.closeFailure = true;
        assertError(
                NarInstallError.DESCRIPTOR_READ_FAILED,
                validate(readFailure));
        assertEquals(1, readFailure.archive.descriptorCloseCount);
        assertEquals(1, readFailure.archive.closeCount);

        FakeIo closeFailure = validFakeIo();
        closeFailure.archive.descriptorCloseFailure = true;
        assertError(
                NarInstallError.DESCRIPTOR_READ_FAILED,
                validate(closeFailure));

        FakeIo semanticAndClose = validFakeIo();
        semanticAndClose.archive.descriptorBytes = bytes("name,G\n");
        semanticAndClose.archive.descriptorCloseFailure = true;
        semanticAndClose.archive.closeFailure = true;
        assertError(
                NarInstallError.MISSING_TYPE,
                validate(semanticAndClose));
        assertEquals(1, semanticAndClose.archive.closeCount);

        FakeIo overflowAndClose = validFakeIo();
        overflowAndClose.archive.virtualDescriptorLength =
                64L * 1024L + 10 * MIB;
        overflowAndClose.archive.entries.get(1).size = 1;
        overflowAndClose.archive.descriptorCloseFailure = true;
        assertError(
                NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
                validate(overflowAndClose));
        assertEquals(
                64L * 1024L + 1,
                overflowAndClose.archive.descriptorBytesRead);

        FakeIo exactCap = validFakeIo();
        exactCap.archive.descriptorBytes =
                paddedDescriptor(64 * 1024);
        exactCap.archive.entries.get(1).size = 1;
        assertTrue(validate(exactCap).isSuccess());
    }

    @Test
    public void mapsInstallRootCanonicalizationToSpecificPlanningError()
            throws Exception {
        FakeIo nullRoot = validFakeIo();
        assertError(
                NarInstallError.INSTALL_ROOT_INVALID,
                new NarInstallPlanValidator(nullRoot).validate(
                        new File("archive.nar"), null, null));
        assertEquals(0, nullRoot.sourceOpenCount);

        FakeIo canonicalFailure = validFakeIo();
        canonicalFailure.canonicalFailure = true;
        canonicalFailure.archive.closeFailure = true;
        File absentTarget = new File(
                "missing-root-" + System.nanoTime(),
                "ghost-id");

        assertError(
                NarInstallError.INSTALL_ROOT_INVALID,
                new NarInstallPlanValidator(canonicalFailure).validate(
                        new File("archive.nar"),
                        absentTarget.getParentFile(),
                        null));
        assertFalse(absentTarget.exists());
        assertEquals(1, canonicalFailure.canonicalCount);
        assertEquals(1, canonicalFailure.archive.closeCount);
    }

    @Test
    public void publicSurfaceReturnsDiagnosticPlansOnly()
            throws Exception {
        int publicMethods = 0;
        for (Method method
                : NarInstallPlanValidator.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                publicMethods++;
                assertTrue(
                        "validate".equals(method.getName())
                                || "verify".equals(method.getName()));
                assertEquals(
                        NarInstallPlanResult.class,
                        method.getReturnType());
            }
        }
        assertEquals(2, publicMethods);
        for (Method method
                : NarInstallPlanResult.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse(
                        method.getName().toLowerCase()
                                .contains("session"));
                assertFalse(InputStream.class.isAssignableFrom(
                        method.getReturnType()));
            }
        }
    }

    @Test
    public void preflightsAndCapsEnumerationBeforeZipUse()
            throws Exception {
        FakeIo declaredOverflow = validFakeIo();
        declaredOverflow.preflightCount = 10001;
        assertError(
                NarInstallError.ENTRY_COUNT_LIMIT,
                validate(declaredOverflow));
        assertEquals(0, declaredOverflow.archiveOpenCount);

        FakeIo enumeratedOverflow = validFakeIo();
        enumeratedOverflow.archive.virtualEntryCount = 10001;
        assertError(
                NarInstallError.ENTRY_COUNT_LIMIT,
                validate(enumeratedOverflow));
        assertEquals(10001, enumeratedOverflow.archive.entriesLimit);

        FakeIo countMismatch = validFakeIo();
        countMismatch.preflightCount = 1;
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                validate(countMismatch));
        assertEquals(1, countMismatch.archiveOpenCount);

        FakeIo preflightFailure = validFakeIo();
        preflightFailure.preflightFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                validate(preflightFailure));
        assertEquals(0, preflightFailure.archiveOpenCount);

        FakeIo verified = validFakeIo();
        NarInstallPlanValidator validator =
                new NarInstallPlanValidator(verified);
        NarInstallPlan plan = validator.validate(
                new File("archive.nar"),
                new File("install-root"),
                null).getPlan();
        assertTrue(validator.verify(
                new File("archive.nar"), plan).isSuccess());
        assertEquals(2, verified.preflightCalls);
        assertEquals(10001, verified.archive.entriesLimit);
    }

    @Test
    public void doubleIdentityRejectsRacesAndMismatchBeatsClose()
            throws Exception {
        FakeIo race = validFakeIo();
        race.changedSource = bytes("changed identity");
        race.switchSourceAt = 2;
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                validate(race));
        assertEquals(1, race.archive.closeCount);

        FakeIo changedAndClose = validFakeIo();
        NarInstallPlanValidator validator =
                new NarInstallPlanValidator(changedAndClose);
        NarInstallPlan plan = validator.validate(
                new File("archive.nar"),
                new File("install-root"),
                null).getPlan();
        changedAndClose.currentSource = bytes("changed identity");
        changedAndClose.sourceCloseFailure = true;
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                validator.verify(new File("archive.nar"), plan));

        FakeIo unchangedAndClose = validFakeIo();
        NarInstallPlanValidator unchangedValidator =
                new NarInstallPlanValidator(unchangedAndClose);
        NarInstallPlan unchangedPlan = unchangedValidator.validate(
                new File("archive.nar"),
                new File("install-root"),
                null).getPlan();
        unchangedAndClose.sourceCloseFailure = true;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                unchangedValidator.verify(
                        new File("archive.nar"), unchangedPlan));
    }

    @Test
    public void stagedCapabilityIsUnmintableAndDiagnosticsHaveNoAuthority()
            throws Exception {
        NarInstallPlanResult diagnostic = validate(validFakeIo());
        assertTrue(diagnostic.isSuccess());
        assertNull(diagnostic.getVerifiedSession());
        assertFalse(Modifier.isPublic(
                NarStagedSource.class.getModifiers()));
        Constructor<?> constructor =
                NarStagedSource.class.getDeclaredConstructor(File.class);
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertTrue(Modifier.isSynchronized(
                NarStagedSource.class
                        .getDeclaredMethod("claim")
                        .getModifiers()));
        for (Method method : NarStagedSource.class.getDeclaredMethods()) {
            assertFalse(
                    Modifier.isStatic(method.getModifiers())
                            && method.getReturnType()
                                    == NarStagedSource.class
                            && Arrays.equals(
                                    method.getParameterTypes(),
                                    new Class<?>[] {File.class}));
        }
    }

    @Test
    public void stagedValidationRetainsOwnerAndCleansOneShotSession()
            throws Exception {
        FakeIo io = validFakeIo();
        NarInstallPlanValidator validator = new NarInstallPlanValidator(io);
        NarStagedSource source =
                stagedForTest(new File("private-staged.nar"));

        NarInstallPlanResult result = validator.validateStaged(
                source, new File("install-root"), null);

        assertTrue(result.isSuccess());
        NarVerifiedInstallSession session = result.getVerifiedSession();
        assertSame(result.getPlan(), session.getPlan());
        assertEquals(0, io.archive.closeCount);
        assertEquals(0, io.deleteCount);
        InputStream payload =
                session.open(result.getPlan().getEntries().get(0));
        assertArrayEquals(bytes("payload"), readAll(payload));
        payload.close();
        assertSame(io.archive.entries.get(0), io.archive.openedEntry);
        assertError(
                NarInstallError.STAGED_SOURCE_INVALID,
                validator.validateStaged(
                        source, new File("install-root"), null));
        assertEquals(0, io.deleteCount);

        session.close();
        assertTrue(session.isClosed());
        assertEquals(
                Arrays.asList("archive-close", "delete"),
                io.events);
        session.close();
        assertEquals(1, io.archive.closeCount);
        assertEquals(1, io.deleteCount);
        try {
            session.open(result.getPlan().getEntries().get(0));
            throw new AssertionError("closed session accepted entry");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test
    public void stagedVerificationRetainsTheVerifiedPlan()
            throws Exception {
        FakeIo io = validFakeIo();
        NarInstallPlanValidator validator = new NarInstallPlanValidator(io);
        NarInstallPlan plan = validator.validate(
                new File("archive.nar"),
                new File("install-root"),
                null).getPlan();
        assertEquals(1, io.archive.closeCount);

        NarInstallPlanResult result = validator.verifyStaged(
                stagedForTest(new File("verify-staged.nar")), plan);

        assertTrue(result.isSuccess());
        assertSame(plan, result.getPlan());
        assertSame(plan, result.getVerifiedSession().getPlan());
        assertEquals(1, io.archive.closeCount);
        result.getVerifiedSession().close();
        assertEquals(2, io.archive.closeCount);
        assertEquals(1, io.deleteCount);
    }

    @Test
    public void sessionRejectsForeignDirectoryAndNonInstallEntries()
            throws Exception {
        File stagedFile = zip(
                "bundle/", null,
                "bundle/install.txt", descriptor("ghost-id", "Ghost"),
                "bundle/assets/", null,
                "bundle/assets/file", bytes("payload"));
        NarInstallPlanValidator validator = new NarInstallPlanValidator();
        NarInstallPlanResult result = validator.validateStaged(
                stagedForTest(stagedFile),
                temporaryDirectory("session-root"),
                null);
        assertTrue(result.isSuccess());
        NarVerifiedInstallSession session = result.getVerifiedSession();
        assertSessionOpenRejected(
                session, result.getPlan().getEntries().get(0));
        assertSessionOpenRejected(
                session, result.getPlan().getEntries().get(2));

        NarInstallPlan foreign = validator.validate(
                zip(
                        "install.txt", descriptor("other", "Other"),
                        "payload", bytes("foreign")),
                temporaryDirectory("foreign-root"),
                null).getPlan();
        assertSessionOpenRejected(
                session, foreign.getEntries().get(1));
        session.close();
        assertFalse(stagedFile.exists());
    }

    @Test
    public void everyStagedFailurePhaseCleansClaimedSource()
            throws Exception {
        FakeIo source = validFakeIo();
        source.sourceOpenFailure = true;
        assertStagedFailureCleans(
                source, NarInstallError.ARCHIVE_READ_FAILED, 0);

        FakeIo preflight = validFakeIo();
        preflight.preflightFailure = true;
        assertStagedFailureCleans(
                preflight, NarInstallError.ARCHIVE_READ_FAILED, 0);

        FakeIo open = validFakeIo();
        open.archiveOpenFailure = true;
        assertStagedFailureCleans(
                open, NarInstallError.ARCHIVE_READ_FAILED, 0);

        FakeIo list = validFakeIo();
        list.archive.listFailure = true;
        assertStagedFailureCleans(
                list, NarInstallError.ARCHIVE_READ_FAILED, 1);

        FakeIo descriptor = validFakeIo();
        descriptor.archive.descriptorReadFailureAt = 1;
        assertStagedFailureCleans(
                descriptor, NarInstallError.DESCRIPTOR_READ_FAILED, 1);

        FakeIo canonical = validFakeIo();
        canonical.canonicalFailure = true;
        assertStagedFailureCleans(
                canonical, NarInstallError.INSTALL_ROOT_INVALID, 1);

        FakeIo race = validFakeIo();
        race.changedSource = bytes("changed identity");
        race.switchSourceAt = 2;
        assertStagedFailureCleans(
                race, NarInstallError.ARCHIVE_IDENTITY_MISMATCH, 1);
    }

    @Test
    public void stagedVerificationFailuresConsumeAndCleanAuthority()
            throws Exception {
        FakeIo planIo = validFakeIo();
        NarInstallPlan plan = new NarInstallPlanValidator(planIo)
                .validate(
                        new File("archive.nar"),
                        new File("install-root"),
                        null)
                .getPlan();

        FakeIo missing = validFakeIo();
        NarInstallPlanValidator missingValidator =
                new NarInstallPlanValidator(missing);
        NarStagedSource missingSource =
                stagedForTest(new File("missing-plan.nar"));
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                missingValidator.verifyStaged(missingSource, null));
        assertEquals(0, missing.archive.closeCount);
        assertEquals(1, missing.deleteCount);
        assertError(
                NarInstallError.STAGED_SOURCE_INVALID,
                missingValidator.verifyStaged(missingSource, plan));

        FakeIo changedBytes = validFakeIo();
        changedBytes.currentSource = bytes("changed identity");
        changedBytes.sourceCloseFailure = true;
        changedBytes.deleteFailure = true;
        NarInstallPlanValidator bytesValidator =
                new NarInstallPlanValidator(changedBytes);
        NarStagedSource byteSource =
                stagedForTest(new File("changed-bytes.nar"));
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                bytesValidator.verifyStaged(byteSource, plan));
        assertEquals(0, changedBytes.archive.closeCount);
        assertEquals(1, changedBytes.deleteCount);
        assertError(
                NarInstallError.STAGED_SOURCE_INVALID,
                bytesValidator.verifyStaged(byteSource, plan));

        FakeIo central = validFakeIo();
        central.archive.entries.get(0).crc++;
        central.archive.closeFailure = true;
        central.deleteFailure = true;
        NarInstallPlanValidator centralValidator =
                new NarInstallPlanValidator(central);
        NarStagedSource centralSource =
                stagedForTest(new File("changed-central.nar"));
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                centralValidator.verifyStaged(centralSource, plan));
        assertEquals(1, central.archive.closeCount);
        assertEquals(1, central.deleteCount);
        assertError(
                NarInstallError.STAGED_SOURCE_INVALID,
                centralValidator.verifyStaged(centralSource, plan));
    }

    @Test
    public void cleanupPreservesPrimaryAndSessionCloseIsExplicit()
            throws Exception {
        FakeIo semantic = validFakeIo();
        semantic.archive.entries.remove(1);
        reindex(semantic.archive.entries);
        semantic.archive.closeFailure = true;
        semantic.deleteFailure = true;
        assertStagedFailureCleans(
                semantic,
                NarInstallError.MISSING_INSTALL_DESCRIPTOR,
                1);

        FakeIo cleanup = validFakeIo();
        cleanup.archive.closeFailure = true;
        cleanup.deleteFailure = true;
        NarInstallPlanResult result =
                new NarInstallPlanValidator(cleanup).validateStaged(
                        stagedForTest(new File("cleanup.nar")),
                        new File("install-root"),
                        null);
        assertTrue(result.isSuccess());
        try {
            result.getVerifiedSession().close();
            throw new AssertionError("cleanup failure was hidden");
        } catch (IOException expected) {
            // Expected.
        }
        assertEquals(1, cleanup.archive.closeCount);
        assertEquals(1, cleanup.deleteCount);
        cleanup.archive.closeFailure = false;
        cleanup.deleteFailure = false;
        result.getVerifiedSession().close();
        assertEquals(2, cleanup.archive.closeCount);
        assertEquals(2, cleanup.deleteCount);

        FakeIo runtime = validFakeIo();
        runtime.archive.runtimeCloseFailure = true;
        NarInstallPlanResult runtimeResult =
                new NarInstallPlanValidator(runtime).validateStaged(
                        stagedForTest(new File("runtime.nar")),
                        new File("install-root"),
                        null);
        try {
            runtimeResult.getVerifiedSession().close();
            throw new AssertionError("runtime close was hidden");
        } catch (IOException expected) {
            // Expected.
        }
        assertEquals(1, runtime.deleteCount);
    }

    @Test
    public void verifiedSessionLeaseIsExclusiveExactAndTransferOwned()
            throws Exception {
        FakeIo io = validFakeIo();
        NarVerifiedInstallSession session =
                new NarInstallPlanValidator(io).validateStaged(
                        stagedForTest(new File("lease.nar")),
                        new File("install-root"), null)
                        .getVerifiedSession();
        FakeIo otherIo = validFakeIo();
        NarVerifiedInstallSession other =
                new NarInstallPlanValidator(otherIo).validateStaged(
                        stagedForTest(new File("other.nar")),
                        new File("install-root"), null)
                        .getVerifiedSession();

        assertEquals("READY", session.state().name());
        NarVerifiedInstallSession.Lease lease = session.lease();
        assertSame(session.getPlan(), lease.plan());
        assertEquals("BUSY", session.state().name());
        assertNull(session.lease());
        assertThrows(IllegalStateException.class,
                () -> session.open(session.getPlan().getEntries().get(0)));
        NarVerifiedInstallSession.Lease foreign = other.lease();
        assertEquals("FOREIGN", session.release(foreign).name());
        assertEquals("OK", session.release(lease).name());
        assertEquals("READY", session.state().name());
        assertEquals("STALE", session.release(lease).name());
        assertThrows(IllegalStateException.class, () -> lease.plan());
        assertEquals("OK", other.release(foreign).name());

        NarVerifiedInstallSession.Lease consumed = session.lease();
        assertEquals("OK", session.consume(consumed).name());
        assertEquals("CONSUMED", session.state().name());
        assertNull(session.lease());
        assertThrows(IllegalStateException.class, () -> session.close());
        consumed.cleanup();
        assertTrue(session.isClosed());
        consumed.cleanup();
        assertEquals(1, io.archive.closeCount);
        assertEquals(1, io.deleteCount);
        for (Method method :
                NarVerifiedInstallSession.Lease.class.getDeclaredMethods()) {
            String type = method.getReturnType().getName();
            assertFalse(type.equals("java.io.File")
                    || type.contains("InputStream")
                    || type.contains("OutputStream")
                    || type.contains("Writer"));
        }
        other.close();
    }

    @Test
    public void verifiedCleanupRetriesEachUnfinishedComponentAndFirstFailure()
            throws Exception {
        FakeIo io = validFakeIo();
        NarVerifiedInstallSession session =
                new NarInstallPlanValidator(io).validateStaged(
                        stagedForTest(new File("retry.nar")),
                        new File("install-root"), null)
                        .getVerifiedSession();
        io.archive.closeFailure = true;
        io.deleteFailure = true;
        assertThrows(IOException.class, () -> session.close());
        assertEquals(Arrays.asList("archive-close", "delete"), io.events);
        assertEquals("CONSUMED", session.state().name());
        assertFalse(session.isClosed());
        assertNull(session.lease());
        io.archive.closeFailure = false;
        assertThrows(IOException.class, () -> session.close());
        assertEquals(2, io.archive.closeCount);
        assertEquals(2, io.deleteCount);
        io.deleteFailure = false;
        session.close();
        session.close();
        assertEquals(2, io.archive.closeCount);
        assertEquals(3, io.deleteCount);
        assertTrue(session.isClosed());

        FakeIo fatal = validFakeIo();
        NarVerifiedInstallSession fatalSession =
                new NarInstallPlanValidator(fatal).validateStaged(
                        stagedForTest(new File("fatal.nar")),
                        new File("install-root"), null)
                        .getVerifiedSession();
        OutOfMemoryError first = new OutOfMemoryError("close");
        fatal.archive.closeThrowable = first;
        fatal.deleteThrowable = new LinkageError("delete");
        assertSame(first, assertThrows(OutOfMemoryError.class,
                () -> fatalSession.close()));
        assertEquals(1, fatal.archive.closeCount);
        assertEquals(1, fatal.deleteCount);
        fatal.archive.closeThrowable = null;
        assertThrows(LinkageError.class, () -> fatalSession.close());
        assertEquals(2, fatal.archive.closeCount);
        assertEquals(2, fatal.deleteCount);
        fatal.deleteThrowable = null;
        fatalSession.close();
        assertEquals(2, fatal.archive.closeCount);
        assertEquals(3, fatal.deleteCount);
    }

    @Test
    public void concurrentVerifiedCleanupCompletesEachComponentOnce()
            throws Exception {
        FakeIo io = validFakeIo();
        final NarVerifiedInstallSession session =
                new NarInstallPlanValidator(io).validateStaged(
                        stagedForTest(new File("race.nar")),
                        new File("install-root"), null)
                        .getVerifiedSession();
        final Throwable[] failures = new Throwable[2];
        race(() -> closeInto(session, failures, 0),
                () -> closeInto(session, failures, 1));
        assertNull(failures[0]);
        assertNull(failures[1]);
        assertTrue(session.isClosed());
        assertEquals(1, io.archive.closeCount);
        assertEquals(1, io.deleteCount);
    }

    private static void closeInto(NarVerifiedInstallSession session,
            Throwable[] failures, int index) {
        try { session.close(); }
        catch (Throwable failure) { failures[index] = failure; }
    }

    private static void race(Runnable first, Runnable second)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Thread one = new Thread(() -> { await(start); first.run(); });
        Thread two = new Thread(() -> { await(start); second.run(); });
        one.start(); two.start(); start.countDown();
        one.join(5000); two.join(5000);
        assertFalse(one.isAlive()); assertFalse(two.isAlive());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static NarInstallPlanResult validate(FakeIo io) {
        return new NarInstallPlanValidator(io).validate(
                new File("archive.nar"),
                new File("install-root"),
                null);
    }

    private static NarStagedSource stagedForTest(File file)
            throws Exception {
        Constructor<NarStagedSource> constructor =
                NarStagedSource.class.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        return constructor.newInstance(file);
    }

    private static byte[] readAll(InputStream input)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static void assertSessionOpenRejected(
            NarVerifiedInstallSession session,
            NarInstallPlan.Entry entry) throws IOException {
        try {
            session.open(entry);
            throw new AssertionError("unsafe entry accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertStagedFailureCleans(
            FakeIo io,
            NarInstallError error,
            int expectedCloseCount) throws Exception {
        assertError(
                error,
                new NarInstallPlanValidator(io).validateStaged(
                        stagedForTest(new File("failed-staged.nar")),
                        new File("install-root"),
                        null));
        assertEquals(expectedCloseCount, io.archive.closeCount);
        assertEquals(1, io.deleteCount);
    }

    private static void assertError(
            NarInstallError expected,
            NarInstallPlanResult result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.getError());
        assertNull(result.getPlan());
    }

    private static FakeIo validFakeIo() {
        byte[] descriptor = descriptor("ghost-id", "Ghost");
        FakeEntry install = new FakeEntry("install.txt", false);
        install.size = descriptor.length;
        FakeEntry payload = new FakeEntry("payload", false);
        payload.size = 7;
        FakeArchive archive = new FakeArchive(
                new ArrayList<FakeEntry>(Arrays.asList(payload, install)),
                descriptor);
        reindex(archive.entries);
        return new FakeIo(bytes("source identity"), archive);
    }

    private static void reindex(List<FakeEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            entries.get(index).ordinal = index;
        }
    }

    private static void mutateCentral(FakeEntry entry, int field) {
        if (field == 0) entry.ordinal++;
        if (field == 1) entry.name += "-changed";
        if (field == 2) entry.directory = !entry.directory;
        if (field == 3) entry.crc++;
        if (field == 4) entry.method = entry.method == 8 ? 0 : 8;
        if (field == 5) entry.size++;
        if (field == 6) entry.compressedSize++;
    }

    private static byte[] descriptor(String id, String name) {
        return bytes(
                "type,ghost\nname," + name + "\ndirectory," + id + "\n");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(SHIFT_JIS);
    }

    private static byte[] paddedDescriptor(int byteCount) {
        byte[] prefix = (
                "type,ghost\nname,G\ndirectory,g\npadding,")
                .getBytes(SHIFT_JIS);
        byte[] result = Arrays.copyOf(prefix, byteCount);
        Arrays.fill(result, prefix.length, result.length, (byte) 'a');
        return result;
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        } finally {
            input.close();
        }
        return digest.digest();
    }

    private static File temporaryDirectory(String label) throws IOException {
        File marker = File.createTempFile("nanidroid-" + label, ".tmp");
        assertTrue(marker.delete());
        assertTrue(marker.mkdir());
        marker.deleteOnExit();
        return marker;
    }

    private static File zip(Object... namesAndBytes) throws IOException {
        File archive = File.createTempFile("nanidroid-plan", ".nar");
        archive.deleteOnExit();
        overwriteZip(archive, namesAndBytes);
        return archive;
    }

    private static void overwriteZip(
            File archive,
            Object... namesAndBytes) throws IOException {
        ZipOutputStream output =
                new ZipOutputStream(new FileOutputStream(archive));
        IOException failure = null;
        try {
            for (int index = 0; index < namesAndBytes.length; index += 2) {
                String name = (String) namesAndBytes[index];
                byte[] content = (byte[]) namesAndBytes[index + 1];
                output.putNextEntry(new ZipEntry(name));
                if (content != null) {
                    output.write(content);
                }
                output.closeEntry();
            }
        } catch (IOException error) {
            failure = error;
            throw error;
        } finally {
            try {
                output.close();
            } catch (IOException close) {
                if (failure == null) {
                    throw close;
                }
            }
        }
    }

    private static final class FakeIo
            implements NarInstallPlanValidator.ArchiveIo {
        private final byte[] source;
        private byte[] currentSource;
        private byte[] changedSource;
        private final FakeArchive archive;
        private long hintedLength;
        private long virtualSourceLength = -1;
        private int sourceReadFailureAt = -1;
        private boolean zeroFirstSourceRead;
        private boolean sourceCloseFailure;
        private boolean sourceOpenFailure;
        private boolean archiveOpenFailure;
        private boolean canonicalFailure;
        private boolean preflightFailure;
        private boolean deleteFailure;
        private Throwable deleteThrowable;
        private int switchSourceAt = -1;
        private int preflightCount = -1;
        private int sourceOpenCount;
        private int sourceCloseCount;
        private long sourceBytesRead;
        private int archiveOpenCount;
        private int canonicalCount;
        private File canonicalArgument;
        private int preflightCalls;
        private int deleteCount;
        private final List<String> events =
                new ArrayList<String>();
        private final File canonicalResult =
                new File("canonical-sentinel").getAbsoluteFile();

        private FakeIo(byte[] source, FakeArchive archive) {
            this.source = source;
            currentSource = source;
            this.archive = archive;
            archive.owner = this;
            hintedLength = source.length;
        }

        @Override public long length(File file) {
            return hintedLength;
        }

        @Override public InputStream openSource(File file)
                throws IOException {
            sourceOpenCount++;
            if (sourceOpenFailure) {
                throw new IOException("source open");
            }
            byte[] selected = switchSourceAt > 0
                    && sourceOpenCount >= switchSourceAt
                    ? changedSource
                    : currentSource;
            return new ScriptedInputStream(
                    selected,
                    virtualSourceLength,
                    sourceReadFailureAt,
                    zeroFirstSourceRead,
                    sourceCloseFailure,
                    this,
                    null);
        }

        @Override public int preflight(File file)
                throws IOException {
            preflightCalls++;
            if (preflightFailure) {
                throw new IOException("preflight");
            }
            return preflightCount >= 0
                    ? preflightCount
                    : archive.entries.size();
        }

        @Override public NarInstallPlanValidator.OpenArchive openArchive(
                File file) throws IOException {
            archiveOpenCount++;
            if (archiveOpenFailure) {
                throw new IOException("open");
            }
            return archive;
        }

        @Override public File canonical(File file) throws IOException {
            canonicalCount++;
            canonicalArgument = file;
            if (canonicalFailure) {
                throw new IOException("canonical");
            }
            return canonicalResult;
        }

        @Override public boolean delete(File file) {
            deleteCount++;
            events.add("delete");
            throwUnchecked(deleteThrowable);
            return !deleteFailure;
        }
    }

    private static final class FakeArchive
            implements NarInstallPlanValidator.OpenArchive {
        private FakeIo owner;
        private final List<FakeEntry> entries;
        private byte[] descriptorBytes;
        private long virtualDescriptorLength = -1;
        private int descriptorReadFailureAt = -1;
        private boolean descriptorCloseFailure;
        private boolean descriptorOpenFailure;
        private boolean zeroFirstDescriptorRead;
        private boolean listFailure;
        private boolean closeFailure;
        private boolean runtimeCloseFailure;
        private Throwable closeThrowable;
        private int closeCount;
        private int descriptorCloseCount;
        private long descriptorBytesRead;
        private FakeEntry openedEntry;
        private int virtualEntryCount = -1;
        private int entriesLimit;

        private FakeArchive(
                List<FakeEntry> entries,
                byte[] descriptorBytes) {
            this.entries = entries;
            this.descriptorBytes = descriptorBytes;
        }

        @Override public List<? extends NarInstallPlanValidator.ArchiveEntry>
                entries(int limit) throws IOException {
            entriesLimit = limit;
            if (listFailure) {
                throw new IOException("list");
            }
            if (virtualEntryCount >= 0) {
                List<FakeEntry> virtual = new ArrayList<FakeEntry>();
                for (int index = 0;
                        index < virtualEntryCount && index < limit;
                        index++) {
                    virtual.add(entries.get(index % entries.size()));
                }
                return virtual;
            }
            return new ArrayList<FakeEntry>(entries);
        }

        @Override public InputStream open(
                NarInstallPlanValidator.ArchiveEntry entry)
                throws IOException {
            openedEntry = (FakeEntry) entry;
            if (descriptorOpenFailure) {
                throw new IOException("descriptor open");
            }
            byte[] content = "install.txt".equals(openedEntry.name)
                    ? descriptorBytes
                    : bytes("payload");
            return new ScriptedInputStream(
                    content,
                    "install.txt".equals(openedEntry.name)
                            ? virtualDescriptorLength
                            : -1,
                    descriptorReadFailureAt,
                    zeroFirstDescriptorRead,
                    descriptorCloseFailure,
                    null,
                    this);
        }

        @Override public void close() throws IOException {
            closeCount++;
            if (owner != null) {
                owner.events.add("archive-close");
            }
            if (closeThrowable instanceof IOException) {
                throw (IOException) closeThrowable;
            }
            throwUnchecked(closeThrowable);
            if (runtimeCloseFailure) {
                throw new IllegalStateException("zip close");
            }
            if (closeFailure) {
                throw new IOException("zip close");
            }
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
    }

    private static final class FakeEntry
            implements NarInstallPlanValidator.ArchiveEntry {
        private int ordinal;
        private String name;
        private boolean directory;
        private long crc = 0;
        private int method = 8;
        private long size = 0;
        private long compressedSize = 1;

        private FakeEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }

        @Override public int getOrdinal() { return ordinal; }
        @Override public String getRawName() { return name; }
        @Override public boolean isDirectory() { return directory; }
        @Override public long getCrc() { return crc; }
        @Override public int getMethod() { return method; }
        @Override public long getDeclaredSize() { return size; }
        @Override public long getCompressedSize() { return compressedSize; }
    }

    private static final class ScriptedInputStream extends InputStream {
        private final byte[] content;
        private final int failAt;
        private final boolean zeroFirst;
        private final boolean closeFailure;
        private final FakeIo owner;
        private final FakeArchive archive;
        private long remaining;
        private int position;
        private int reads;
        private boolean zeroReturned;

        private ScriptedInputStream(
                byte[] content,
                long virtualLength,
                int failAt,
                boolean zeroFirst,
                boolean closeFailure,
                FakeIo owner,
                FakeArchive archive) {
            this.content = content;
            this.remaining = virtualLength >= 0
                    ? virtualLength
                    : content.length;
            this.failAt = failAt;
            this.zeroFirst = zeroFirst;
            this.closeFailure = closeFailure;
            this.owner = owner;
            this.archive = archive;
        }

        @Override public int read(byte[] buffer, int offset, int length)
                throws IOException {
            beforeRead();
            if (zeroFirst && !zeroReturned) {
                zeroReturned = true;
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int count = (int) Math.min((long) length, remaining);
            if (position < content.length) {
                int copied = Math.min(count, content.length - position);
                System.arraycopy(content, position, buffer, offset, copied);
                if (copied < count) {
                    Arrays.fill(buffer, offset + copied, offset + count, (byte) 0);
                }
            } else {
                Arrays.fill(buffer, offset, offset + count, (byte) 0);
            }
            position += count;
            remaining -= count;
            recordBytes(count);
            return count;
        }

        @Override public int read() throws IOException {
            beforeRead();
            if (remaining == 0) {
                return -1;
            }
            int value = position < content.length ? content[position] & 0xff : 0;
            position++;
            remaining--;
            recordBytes(1);
            return value;
        }

        private void recordBytes(int count) {
            if (owner != null) {
                owner.sourceBytesRead += count;
            }
            if (archive != null) {
                archive.descriptorBytesRead += count;
            }
        }

        private void beforeRead() throws IOException {
            reads++;
            if (reads == failAt) {
                throw new IOException("scripted read");
            }
        }

        @Override public void close() throws IOException {
            if (owner != null) {
                owner.sourceCloseCount++;
            }
            if (archive != null) {
                archive.descriptorCloseCount++;
            }
            if (closeFailure) {
                throw new IOException("scripted close");
            }
        }
    }
}
