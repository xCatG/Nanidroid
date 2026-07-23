package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
        assertEquals(32, rootPlan.getSourceSha256().length);
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
        Collections.swap(io.archive.entries, 0, 1);
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                fakeValidator.verify(new File("archive.nar"), fakePlan));
        Collections.swap(io.archive.entries, 0, 1);
        io.archive.entries.get(1).crc = 7;
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                fakeValidator.verify(new File("archive.nar"), fakePlan));

        io.archive.closeFailure = true;
        assertError(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                fakeValidator.verify(new File("archive.nar"), fakePlan));
        io.archive.entries.get(1).crc = 0;
        assertError(
                NarInstallError.ARCHIVE_READ_FAILED,
                fakeValidator.verify(new File("archive.nar"), fakePlan));
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
        streamed.virtualSourceLength = MAX_ARCHIVE_BYTES + 1;
        assertError(
                NarInstallError.ARCHIVE_SIZE_LIMIT,
                validate(streamed));
        assertEquals(1, streamed.sourceCloseCount);
        assertEquals(0, streamed.archiveOpenCount);
    }

    @Test
    public void handlesZeroReadsAndMapsHashReadAndCloseFailures()
            throws Exception {
        FakeIo zero = validFakeIo();
        zero.zeroFirstSourceRead = true;
        assertTrue(validate(zero).isSuccess());
        assertTrue(zero.sourceSingleReadCount > 0);
        assertEquals(1, zero.sourceCloseCount);

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

        FakeIo semanticAndClose = validFakeIo();
        semanticAndClose.archive.entries.remove(0);
        reindex(semanticAndClose.archive.entries);
        semanticAndClose.archive.closeFailure = true;
        assertError(
                NarInstallError.MISSING_INSTALL_DESCRIPTOR,
                validate(semanticAndClose));
    }

    @Test
    public void readsDescriptorByExactOrdinalObjectWithBoundedClosePolicy()
            throws Exception {
        FakeIo exact = validFakeIo();
        NarInstallPlanResult exactResult = validate(exact);
        assertTrue(exactResult.isSuccess());
        assertSame(
                exact.archive.entries.get(0), exact.archive.openedEntry);
        assertEquals(1, exact.archive.descriptorCloseCount);

        FakeIo openFailure = validFakeIo();
        openFailure.archive.descriptorOpenFailure = true;
        assertError(
                NarInstallError.DESCRIPTOR_READ_FAILED,
                validate(openFailure));
        assertEquals(1, openFailure.archive.closeCount);
        assertEquals(0, openFailure.archive.descriptorCloseCount);

        FakeIo readFailure = validFakeIo();
        readFailure.archive.descriptorReadFailureAt = 1;
        assertError(
                NarInstallError.DESCRIPTOR_READ_FAILED,
                validate(readFailure));
        assertEquals(1, readFailure.archive.descriptorCloseCount);

        FakeIo closeFailure = validFakeIo();
        closeFailure.archive.descriptorCloseFailure = true;
        assertError(
                NarInstallError.DESCRIPTOR_READ_FAILED,
                validate(closeFailure));

        FakeIo semanticAndClose = validFakeIo();
        semanticAndClose.archive.descriptorBytes = bytes("name,G\n");
        semanticAndClose.archive.descriptorCloseFailure = true;
        assertError(
                NarInstallError.MISSING_TYPE,
                validate(semanticAndClose));

        FakeIo overflowAndClose = validFakeIo();
        overflowAndClose.archive.virtualDescriptorLength = 64L * 1024L + 1;
        overflowAndClose.archive.entries.get(0).size = 1;
        overflowAndClose.archive.descriptorCloseFailure = true;
        assertError(
                NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
                validate(overflowAndClose));
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
        assertEquals(1, canonicalFailure.archive.closeCount);
    }

    private static NarInstallPlanResult validate(FakeIo io) {
        return new NarInstallPlanValidator(io).validate(
                new File("archive.nar"),
                new File("install-root"),
                null);
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
                new ArrayList<FakeEntry>(Arrays.asList(install, payload)),
                descriptor);
        reindex(archive.entries);
        return new FakeIo(bytes("source identity"), archive);
    }

    private static void reindex(List<FakeEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            entries.get(index).ordinal = index;
        }
    }

    private static byte[] descriptor(String id, String name) {
        return bytes(
                "type,ghost\nname," + name + "\ndirectory," + id + "\n");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(SHIFT_JIS);
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
        private final FakeArchive archive;
        private long hintedLength;
        private long virtualSourceLength = -1;
        private int sourceReadFailureAt = -1;
        private boolean zeroFirstSourceRead;
        private boolean sourceCloseFailure;
        private boolean archiveOpenFailure;
        private boolean canonicalFailure;
        private int sourceOpenCount;
        private int sourceCloseCount;
        private int sourceSingleReadCount;
        private int archiveOpenCount;

        private FakeIo(byte[] source, FakeArchive archive) {
            this.source = source;
            this.archive = archive;
            hintedLength = source.length;
        }

        @Override public long length(File file) {
            return hintedLength;
        }

        @Override public InputStream openSource(File file) {
            sourceOpenCount++;
            return new ScriptedInputStream(
                    source,
                    virtualSourceLength,
                    sourceReadFailureAt,
                    zeroFirstSourceRead,
                    sourceCloseFailure,
                    this,
                    null);
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
            if (canonicalFailure) {
                throw new IOException("canonical");
            }
            return file.getAbsoluteFile();
        }
    }

    private static final class FakeArchive
            implements NarInstallPlanValidator.OpenArchive {
        private final List<FakeEntry> entries;
        private byte[] descriptorBytes;
        private long virtualDescriptorLength = -1;
        private int descriptorReadFailureAt = -1;
        private boolean descriptorCloseFailure;
        private boolean descriptorOpenFailure;
        private boolean listFailure;
        private boolean closeFailure;
        private int closeCount;
        private int descriptorCloseCount;
        private FakeEntry openedEntry;

        private FakeArchive(
                List<FakeEntry> entries,
                byte[] descriptorBytes) {
            this.entries = entries;
            this.descriptorBytes = descriptorBytes;
        }

        @Override public List<? extends NarInstallPlanValidator.ArchiveEntry>
                entries() throws IOException {
            if (listFailure) {
                throw new IOException("list");
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
            return new ScriptedInputStream(
                    descriptorBytes,
                    virtualDescriptorLength,
                    descriptorReadFailureAt,
                    false,
                    descriptorCloseFailure,
                    null,
                    this);
        }

        @Override public void close() throws IOException {
            closeCount++;
            if (closeFailure) {
                throw new IOException("zip close");
            }
        }
    }

    private static final class FakeEntry
            implements NarInstallPlanValidator.ArchiveEntry {
        private int ordinal;
        private String name;
        private final boolean directory;
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
            return count;
        }

        @Override public int read() throws IOException {
            beforeRead();
            if (owner != null) {
                owner.sourceSingleReadCount++;
            }
            if (remaining == 0) {
                return -1;
            }
            int value = position < content.length ? content[position] & 0xff : 0;
            position++;
            remaining--;
            return value;
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
