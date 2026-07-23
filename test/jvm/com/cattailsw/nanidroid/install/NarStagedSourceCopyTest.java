package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class NarStagedSourceCopyTest {
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_ARCHIVE_BYTES = 544L * MIB;

    @Test
    public void exactCapCopiesSyncsAndClosesBeforeMint() throws Exception {
        FakeIo io = new FakeIo();
        io.virtualSourceLength = MAX_ARCHIVE_BYTES;
        io.zeroFirstRead = true;

        NarStagedSourceCopyResult result = NarStagedSource.copy(
                new File("external.nar"),
                io.root,
                io,
                names("first.nar"));

        assertTrue(result.isSuccess());
        assertNull(result.getError());
        assertTrue(result.getCleanupErrors().isEmpty());
        assertEquals(MAX_ARCHIVE_BYTES, io.sourceBytesRead);
        assertEquals(MAX_ARCHIVE_BYTES, io.targetBytesWritten);
        assertEquals(
                Arrays.asList("sync", "writer-close", "source-close"),
                io.terminalEvents);
        assertEquals(0, io.deleteCount);

        NarStagedSource source = result.getSource();
        File staged = source.claim();
        assertEquals(io.created.get(0), staged);
        assertNull(source.claim());
    }

    @Test
    public void overCapPreservesPrimaryAndOrderedCleanupFailures()
            throws Exception {
        FakeIo io = new FakeIo();
        io.virtualSourceLength = MAX_ARCHIVE_BYTES + 10 * MIB;
        io.writerCloseFailure = true;
        io.sourceCloseFailure = true;
        io.deleteFailure = true;

        NarStagedSourceCopyResult result = NarStagedSource.copy(
                new File("external.nar"),
                io.root,
                io,
                names("large.nar"));

        assertFalse(result.isSuccess());
        assertEquals(
                NarStagedSourceCopyError.ARCHIVE_SIZE_LIMIT,
                result.getError());
        assertEquals(MAX_ARCHIVE_BYTES + 1, io.sourceBytesRead);
        assertEquals(MAX_ARCHIVE_BYTES, io.targetBytesWritten);
        assertEquals(
                Arrays.asList(
                        NarStagedSourceCopyError.STAGING_CLOSE_FAILED,
                        NarStagedSourceCopyError.SOURCE_CLOSE_FAILED,
                        NarStagedSourceCopyError.STAGING_DELETE_FAILED),
                result.getCleanupErrors());
        assertEquals(
                Arrays.asList(
                        "writer-close", "source-close", "delete"),
                io.terminalEvents);
    }

    @Test
    public void copyPhasesHaveStableTypedFailures() throws Exception {
        FakeIo create = failingIo("create");
        NarStagedSourceCopyResult createFailure =
                copy(create);
        assertEquals(
                NarStagedSourceCopyError.STAGING_CREATE_FAILED,
                createFailure.getError());
        assertEquals(0, create.deleteCount);
        assertPrimary(
                NarStagedSourceCopyError.SOURCE_OPEN_FAILED,
                failingIo("source-open"));
        assertPrimary(
                NarStagedSourceCopyError.STAGING_OPEN_FAILED,
                failingIo("target-open"));
        assertPrimary(
                NarStagedSourceCopyError.SOURCE_READ_FAILED,
                failingIo("read"));
        assertPrimary(
                NarStagedSourceCopyError.STAGING_WRITE_FAILED,
                failingIo("write"));
        assertPrimary(
                NarStagedSourceCopyError.STAGING_SYNC_FAILED,
                failingIo("sync"));
        assertPrimary(
                NarStagedSourceCopyError.STAGING_CLOSE_FAILED,
                failingIo("writer-close"));
        assertPrimary(
                NarStagedSourceCopyError.SOURCE_CLOSE_FAILED,
                failingIo("source-close"));
    }

    @Test
    public void collisionsNeverOverwriteAndRetryIsBounded()
            throws Exception {
        FakeIo recovers = new FakeIo();
        recovers.collisionsRemaining = 2;
        NarStagedSourceCopyResult success = NarStagedSource.copy(
                new File("external.nar"),
                recovers.root,
                recovers,
                names("same.nar", "same.nar", "fresh.nar"));

        assertTrue(success.isSuccess());
        assertEquals(3, recovers.createCount);
        assertEquals(0, recovers.deleteCount);
        assertEquals(
                new File(recovers.root, "fresh.nar").getCanonicalFile(),
                success.getSource().claim());

        FakeIo exhausted = new FakeIo();
        exhausted.collisionsRemaining = Integer.MAX_VALUE;
        NarStagedSourceCopyResult failure = NarStagedSource.copy(
                new File("external.nar"),
                exhausted.root,
                exhausted,
                repeatingNames("collision.nar"));

        assertEquals(
                NarStagedSourceCopyError.STAGING_NAME_COLLISION_LIMIT,
                failure.getError());
        assertEquals(16, exhausted.createCount);
        assertEquals(0, exhausted.deleteCount);
    }

    @Test
    public void canonicalRootAndSingleChildNameAreRequired()
            throws Exception {
        assertEquals(
                NarStagedSourceCopyError.SOURCE_INVALID,
                NarStagedSource.copy(
                        null, new FakeIo().root)
                        .getError());
        assertEquals(
                NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                NarStagedSource.copy(
                        new File("external.nar"), null)
                        .getError());

        FakeIo canonicalFailure = new FakeIo();
        canonicalFailure.rootCanonicalFailure = true;
        assertPrimary(
                NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                canonicalFailure);

        FakeIo notDirectory = new FakeIo();
        notDirectory.rootDirectory = false;
        assertPrimary(
                NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                notDirectory);

        FakeIo escaping = new FakeIo();
        escaping.escapeCandidate = true;
        NarStagedSourceCopyResult escaped = NarStagedSource.copy(
                new File("external.nar"),
                escaping.root,
                escaping,
                names("escape.nar"));
        assertEquals(
                NarStagedSourceCopyError.STAGING_NAME_INVALID,
                escaped.getError());
        assertEquals(0, escaping.createCount);

        FakeIo lexical = new FakeIo();
        NarStagedSourceCopyResult invalidName = NarStagedSource.copy(
                new File("external.nar"),
                lexical.root,
                lexical,
                names("../escape.nar"));
        assertEquals(
                NarStagedSourceCopyError.STAGING_NAME_INVALID,
                invalidName.getError());
        assertEquals(0, lexical.createCount);
    }

    @Test
    public void apiExposesOnlyOpaqueTokenAndExistingClaimHandoff()
            throws Exception {
        Method claim = NarStagedSource.class.getDeclaredMethod("claim");
        assertEquals(File.class, claim.getReturnType());
        assertTrue(Modifier.isSynchronized(claim.getModifiers()));

        int fileReturningMethods = 0;
        for (Method method : NarStagedSource.class.getDeclaredMethods()) {
            if (method.getReturnType() == File.class) {
                fileReturningMethods++;
                assertEquals("claim", method.getName());
                assertFalse(Modifier.isStatic(method.getModifiers()));
            }
            assertFalse(
                    Modifier.isStatic(method.getModifiers())
                            && method.getReturnType()
                                    == NarStagedSource.class);
            assertFalse(
                    Modifier.isStatic(method.getModifiers())
                            && Arrays.equals(
                                    method.getParameterTypes(),
                                    new Class<?>[] {File.class}));
        }
        assertEquals(1, fileReturningMethods);

        for (Method method
                : NarStagedSourceCopyResult.class.getDeclaredMethods()) {
            assertFalse(method.getReturnType() == File.class);
            assertFalse(InputStream.class.isAssignableFrom(
                    method.getReturnType()));
            assertFalse(method.getName().toLowerCase().contains("replace"));
            assertFalse(method.getName().toLowerCase().contains("writer"));
            assertFalse(method.getName().toLowerCase().contains("path"));
        }
    }

    private static void assertPrimary(
            NarStagedSourceCopyError expected, FakeIo io)
            throws Exception {
        NarStagedSourceCopyResult result = copy(io);
        assertFalse(result.isSuccess());
        assertEquals(expected, result.getError());
        assertEquals(1, io.deleteCount);
    }

    private static NarStagedSourceCopyResult copy(FakeIo io)
            throws Exception {
        return NarStagedSource.copy(
                new File("external.nar"),
                io.root,
                io,
                names("staged.nar"));
    }

    private static FakeIo failingIo(String phase) throws Exception {
        FakeIo io = new FakeIo();
        io.virtualSourceLength = 1;
        if ("create".equals(phase)) {
            io.createFailure = true;
        } else if ("source-open".equals(phase)) {
            io.sourceOpenFailure = true;
        } else if ("target-open".equals(phase)) {
            io.targetOpenFailure = true;
        } else if ("read".equals(phase)) {
            io.readFailure = true;
        } else if ("write".equals(phase)) {
            io.writeFailure = true;
        } else if ("sync".equals(phase)) {
            io.syncFailure = true;
        } else if ("writer-close".equals(phase)) {
            io.writerCloseFailure = true;
        } else if ("source-close".equals(phase)) {
            io.sourceCloseFailure = true;
        }
        return io;
    }

    private static NarStagedSource.NameSource names(
            final String... values) {
        return new NarStagedSource.NameSource() {
            private int index;

            @Override
            public String nextName() {
                return values[index++];
            }
        };
    }

    private static NarStagedSource.NameSource repeatingNames(
            final String value) {
        return new NarStagedSource.NameSource() {
            @Override
            public String nextName() {
                return value;
            }
        };
    }

    private static final class FakeIo
            implements NarStagedSource.StageIo {
        private final File root =
                new File("trusted-stage").getCanonicalFile();
        private final List<File> created = new ArrayList<File>();
        private final List<String> terminalEvents =
                new ArrayList<String>();
        private boolean rootCanonicalFailure;
        private boolean rootDirectory = true;
        private boolean escapeCandidate;
        private int collisionsRemaining;
        private boolean createFailure;
        private boolean sourceOpenFailure;
        private boolean targetOpenFailure;
        private boolean readFailure;
        private boolean writeFailure;
        private boolean syncFailure;
        private boolean writerCloseFailure;
        private boolean sourceCloseFailure;
        private boolean deleteFailure;
        private boolean zeroFirstRead;
        private long virtualSourceLength;
        private long sourceBytesRead;
        private long targetBytesWritten;
        private int createCount;
        private int deleteCount;

        @Override
        public File canonical(File file) throws IOException {
            if (file.equals(root) && rootCanonicalFailure) {
                throw new IOException("root");
            }
            if (!file.equals(root)
                    && escapeCandidate
                    && root.equals(file.getParentFile())) {
                return new File("outside", file.getName())
                        .getCanonicalFile();
            }
            return file.getCanonicalFile();
        }

        @Override
        public boolean isDirectory(File directory) {
            return rootDirectory && root.equals(directory);
        }

        @Override
        public boolean createNew(File file) throws IOException {
            createCount++;
            if (createFailure) {
                throw new IOException("create");
            }
            if (collisionsRemaining > 0) {
                collisionsRemaining--;
                return false;
            }
            created.add(file);
            return true;
        }

        @Override
        public InputStream openSource(File file) throws IOException {
            if (sourceOpenFailure) {
                throw new IOException("source open");
            }
            return new InputStream() {
                private long remaining = virtualSourceLength;
                private boolean zeroPending = zeroFirstRead;

                @Override
                public int read(byte[] buffer, int offset, int length)
                        throws IOException {
                    if (readFailure) {
                        throw new IOException("read");
                    }
                    if (zeroPending) {
                        zeroPending = false;
                        return 0;
                    }
                    if (remaining == 0) {
                        return -1;
                    }
                    int count =
                            (int) Math.min((long) length, remaining);
                    remaining -= count;
                    sourceBytesRead += count;
                    return count;
                }

                @Override
                public int read() throws IOException {
                    if (readFailure) {
                        throw new IOException("read");
                    }
                    if (remaining == 0) {
                        return -1;
                    }
                    remaining--;
                    sourceBytesRead++;
                    return 0;
                }

                @Override
                public void close() throws IOException {
                    terminalEvents.add("source-close");
                    if (sourceCloseFailure) {
                        throw new IOException("source close");
                    }
                }
            };
        }

        @Override
        public NarStagedSource.StageOutput openTarget(File file)
                throws IOException {
            if (targetOpenFailure) {
                throw new IOException("target open");
            }
            return new NarStagedSource.StageOutput() {
                @Override
                public void write(
                        byte[] buffer, int offset, int length)
                        throws IOException {
                    if (writeFailure) {
                        throw new IOException("write");
                    }
                    targetBytesWritten += length;
                }

                @Override
                public void sync() throws IOException {
                    terminalEvents.add("sync");
                    if (syncFailure) {
                        throw new IOException("sync");
                    }
                }

                @Override
                public void close() throws IOException {
                    terminalEvents.add("writer-close");
                    if (writerCloseFailure) {
                        throw new IOException("writer close");
                    }
                }
            };
        }

        @Override
        public boolean delete(File file) {
            terminalEvents.add("delete");
            deleteCount++;
            return !deleteFailure;
        }
    }
}
