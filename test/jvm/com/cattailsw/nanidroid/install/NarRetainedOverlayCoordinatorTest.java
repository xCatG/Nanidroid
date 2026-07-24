package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.test.mock.MockContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

/** TDD contract for exact archive/baseline ownership binding. */
public final class NarRetainedOverlayCoordinatorTest {
    private static final Context CONTEXT = new MockContext();
    private static final NarFilesystemInspector.TrustedRoot ROOT =
            new NarFilesystemInspector.TrustedRoot("/trusted");

    @Test
    public void bindsExactSourcesAndExposesOnlyImmutableFacts()
            throws Exception {
        FakeArchive archive = new FakeArchive();
        FakeIo io = new FakeIo();
        NarVerifiedInstallSession session = session("ghost", io, archive);
        FakeBackend backend = new FakeBackend();
        NarStagedTree.Claim claim = claim("ghost", backend);

        NarRetainedOverlayCoordinator.Result bound =
                NarRetainedOverlayCoordinator.bind(session, claim);

        assertTrue(bound.detail(), bound.isSuccess());
        assertNull(bound.error());
        NarRetainedOverlayCoordinator.Candidate candidate =
                bound.candidate();
        assertNotNull(candidate);
        assertEquals(1, candidate.fileCount());
        assertTrue(candidate.hasKnownTotalSize());
        assertEquals(3, candidate.totalSize());
        byte[] fingerprint = candidate.baselineFingerprint();
        fingerprint[0] ^= 1;
        assertFalse(Arrays.equals(fingerprint,
                candidate.baselineFingerprint()));
        assertEquals("CONSUMED", session.state().name());
        assertEquals("CONSUMED", claim.state().name());
        assertNull(session.lease());
        assertNull(claim.lease());

        candidate.cleanup();
        candidate.cleanup();
        assertTrue(candidate.isCleaned());
        assertEquals(1, archive.closeCount);
        assertEquals(1, io.deleteCount);
        assertEquals(1, backend.discards);
    }

    @Test
    public void policyRejectReleasesBothSourcesWithoutClaimingCleanup()
            throws Exception {
        FakeArchive archive = new FakeArchive();
        FakeIo io = new FakeIo();
        NarVerifiedInstallSession session = session("other", io, archive);
        FakeBackend backend = new FakeBackend();
        NarStagedTree.Claim claim = claim("ghost", backend);

        NarRetainedOverlayCoordinator.Result rejected =
                NarRetainedOverlayCoordinator.bind(session, claim);

        assertFalse(rejected.isSuccess());
        assertNull(rejected.candidate());
        assertEquals("POLICY", rejected.error().name());
        assertEquals("TARGET_MISMATCH", rejected.policyError().name());
        assertFalse(rejected.detail().isEmpty());
        assertEquals("READY", session.state().name());
        assertEquals("READY", claim.state().name());
        assertEquals(0, archive.closeCount);
        assertEquals(0, io.deleteCount);
        assertEquals(0, backend.discards);
        session.close();
        assertEquals(NarStagedTree.Error.OK, claim.discard());
    }

    @Test
    public void nullAndBusySourcesCannotLeakOrStrandLeases()
            throws Exception {
        FakeArchive archive = new FakeArchive();
        FakeIo io = new FakeIo();
        NarVerifiedInstallSession session = session("ghost", io, archive);
        FakeBackend backend = new FakeBackend();
        NarStagedTree.Claim claim = claim("ghost", backend);

        assertEquals("INPUT", NarRetainedOverlayCoordinator.bind(
                null, claim).error().name());
        assertEquals("INPUT", NarRetainedOverlayCoordinator.bind(
                session, null).error().name());
        NarVerifiedInstallSession.Lease archiveBusy = session.lease();
        assertEquals("BUSY", NarRetainedOverlayCoordinator.bind(
                session, claim).error().name());
        assertEquals("BUSY", session.state().name());
        assertEquals("READY", claim.state().name());
        assertEquals("OK", session.release(archiveBusy).name());
        NarStagedTree.Claim.Lease claimBusy = claim.lease();
        assertEquals("BUSY", NarRetainedOverlayCoordinator.bind(
                session, claim).error().name());
        assertEquals("READY", session.state().name());
        assertEquals("BUSY", claim.state().name());
        assertEquals(NarStagedTree.Error.OK, claim.release(claimBusy));
        session.close();
        assertEquals(NarStagedTree.Error.OK, claim.discard());
    }

    @Test
    public void candidateCleanupAttemptsBothSidesAndRetriesOnlyUnfinished()
            throws Exception {
        FakeArchive archive = new FakeArchive();
        FakeIo io = new FakeIo();
        io.deleteFailure = true;
        FakeBackend backend = new FakeBackend();
        backend.results = new NarStagedTree.Error[] {
                NarStagedTree.Error.PERMISSION, NarStagedTree.Error.OK
        };
        NarRetainedOverlayCoordinator.Candidate candidate = success(
                NarRetainedOverlayCoordinator.bind(
                        session("ghost", io, archive),
                        claim("ghost", backend))).candidate();

        assertThrows(IOException.class, () -> candidate.cleanup());
        assertEquals(1, archive.closeCount);
        assertEquals(1, io.deleteCount);
        assertEquals(1, backend.discards);
        assertFalse(candidate.isCleaned());
        io.deleteFailure = false;
        candidate.cleanup();
        assertEquals(1, archive.closeCount);
        assertEquals(2, io.deleteCount);
        assertEquals(2, backend.discards);
        assertTrue(candidate.isCleaned());

        FakeArchive fatalArchive = new FakeArchive();
        OutOfMemoryError archiveOome = new OutOfMemoryError("archive");
        fatalArchive.closeThrowable = archiveOome;
        FakeBackend fatalBackend = new FakeBackend();
        fatalBackend.throwable = new OutOfMemoryError("tree");
        NarRetainedOverlayCoordinator.Candidate fatal = success(
                NarRetainedOverlayCoordinator.bind(
                        session("ghost", new FakeIo(), fatalArchive),
                        claim("ghost", fatalBackend))).candidate();
        assertSame(archiveOome, assertThrows(OutOfMemoryError.class,
                () -> fatal.cleanup()));
        assertEquals(1, fatalArchive.closeCount);
        assertEquals(1, fatalBackend.discards);
        fatalArchive.closeThrowable = null;
        fatalBackend.throwable = null;
        fatal.cleanup();
        assertTrue(fatal.isCleaned());
    }

    @Test
    public void bindAndDirectSourceCleanupAreLinearized()
            throws Exception {
        final FakeArchive archive = new FakeArchive();
        final FakeIo io = new FakeIo();
        final NarVerifiedInstallSession session = session("ghost", io, archive);
        final FakeBackend backend = new FakeBackend();
        final NarStagedTree.Claim claim = claim("ghost", backend);
        final NarRetainedOverlayCoordinator.Result[] bound =
                new NarRetainedOverlayCoordinator.Result[1];
        final Throwable[] direct = new Throwable[1];

        race(() -> bound[0] = NarRetainedOverlayCoordinator.bind(
                        session, claim),
                () -> {
                    try {
                        session.close();
                        claim.discard();
                    } catch (Throwable failure) {
                        direct[0] = failure;
                    }
                });
        assertNotNull(bound[0]);
        if (bound[0].isSuccess()) {
            bound[0].candidate().cleanup();
        }
        assertFalse("BUSY".equals(session.state().name()));
        assertFalse("BUSY".equals(claim.state().name()));
        assertTrue(archive.closeCount <= 1);
        assertTrue(io.deleteCount <= 1);
        assertTrue(backend.discards <= 1);
    }

    @Test
    public void candidateSurfaceCannotRevealAuthority()
            throws Exception {
        assertFalse(Modifier.isPublic(
                NarRetainedOverlayCoordinator.class.getModifiers()));
        assertMethods(NarRetainedOverlayCoordinator.class,
                "bind");
        assertMethods(NarRetainedOverlayCoordinator.Result.class,
                "candidate", "detail", "error", "isSuccess", "policyError");
        assertMethods(NarRetainedOverlayCoordinator.Candidate.class,
                "baselineFingerprint", "cleanup", "fileCount", "hasKnownTotalSize",
                "isCleaned", "totalSize");
        for (Class<?> type : Arrays.asList(
                NarRetainedOverlayCoordinator.class,
                NarRetainedOverlayCoordinator.Result.class,
                NarRetainedOverlayCoordinator.Candidate.class)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()));
            }
        }
    }

    private static NarRetainedOverlayCoordinator.Result success(
            NarRetainedOverlayCoordinator.Result result) {
        assertTrue(result.detail(), result.isSuccess());
        return result;
    }

    private static void assertMethods(Class<?> type, String... expected) {
        List<String> actual = new ArrayList<String>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            actual.add(method.getName());
            assertFalse(Modifier.isPublic(method.getModifiers()));
            String name = method.getName().toLowerCase();
            for (String forbidden : Arrays.asList("path", "file", "stream",
                    "handle", "token", "lease", "session", "claim",
                    "publish", "materialize", "native", "backend")) {
                assertFalse(name, name.contains(forbidden));
            }
            assertFalse(forbidden(method.getReturnType()));
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(forbidden(parameter));
            }
        }
        Collections.sort(actual);
        Arrays.sort(expected);
        assertEquals(Arrays.asList(expected), actual);
    }

    private static boolean forbidden(Class<?> type) {
        String name = type.getName();
        return name.equals("java.io.File") || name.startsWith("java.nio.file")
                || name.contains("InputStream") || name.contains("OutputStream")
                || name.contains("Handle") || name.contains("Lease")
                || name.contains("Session") || name.contains("Claim")
                || name.contains("Manifest") || name.contains("Inventory");
    }

    private static NarVerifiedInstallSession session(String target,
            FakeIo io, FakeArchive archive) {
        return new NarVerifiedInstallSession(io, new File("staged.nar"),
                archive, Collections.<NarInstallPlanValidator.ArchiveEntry>emptyList(),
                plan(target));
    }

    private static NarStagedTree.Claim claim(String target,
            FakeBackend backend) {
        NarStagedTree.Session session =
                new NarStagedTree.Stager(backend).session(CONTEXT);
        return session.consume(success(session.stage(ROOT, target))).claim();
    }

    private static NarStagedTree.Tree success(NarStagedTree.StageResult result) {
        assertTrue(result.detail(), result.isSuccess());
        return result.tree();
    }

    private static NarInstallPlan plan(String target) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("type", "ghost"); metadata.put("name", "Ghost");
        metadata.put("directory", target); metadata.put("refresh", "0");
        NarInstallDescriptor descriptor = new NarInstallDescriptor(
                "ghost", "Ghost", target, target, null, metadata);
        NarArchiveInventory.Entry entry = new NarArchiveInventory.Entry(
                0, "payload", "payload", "payload", false, 0, 8, 3, 3);
        return new NarInstallPlan(0, new byte[32], new NarArchiveInventory(
                Arrays.asList(entry), null, 0, 3), descriptor,
                new File("ignored-root"), new File("ignored-target"));
    }

    private static NarStagedTreeInventory.Description empty() {
        return new NarStagedTreeInventory.Description(1, 2,
                new String[0], new int[0], new long[0], new int[0], new byte[0]);
    }

    private static void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Thread one = new Thread(() -> { await(start); first.run(); });
        Thread two = new Thread(() -> { await(start); second.run(); });
        one.start(); two.start(); start.countDown();
        one.join(5000); two.join(5000);
        assertFalse(one.isAlive()); assertFalse(two.isAlive());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private static final class FakeIo
            implements NarInstallPlanValidator.ArchiveIo {
        private int deleteCount;
        private boolean deleteFailure;
        public long length(File file) { throw new AssertionError(); }
        public InputStream openSource(File file) { throw new AssertionError(); }
        public int preflight(File file) { throw new AssertionError(); }
        public NarInstallPlanValidator.OpenArchive openArchive(File file) {
            throw new AssertionError();
        }
        public File canonical(File file) { return file; }
        public boolean delete(File file) { deleteCount++; return !deleteFailure; }
    }

    private static final class FakeArchive
            implements NarInstallPlanValidator.OpenArchive {
        private int closeCount;
        private Throwable closeThrowable;
        public List<? extends NarInstallPlanValidator.ArchiveEntry> entries(
                int limit) { throw new AssertionError(); }
        public InputStream open(NarInstallPlanValidator.ArchiveEntry entry) {
            throw new AssertionError();
        }
        public void close() throws IOException {
            closeCount++;
            if (closeThrowable instanceof IOException) {
                throw (IOException) closeThrowable;
            }
            if (closeThrowable instanceof RuntimeException) {
                throw (RuntimeException) closeThrowable;
            }
            if (closeThrowable instanceof Error) {
                throw (Error) closeThrowable;
            }
        }
    }

    private static final class FakeHandle implements NarStagedTree.Handle {}

    private static final class FakeBackend implements NarStagedTree.Backend {
        private final NarStagedTree.Handle handle = new FakeHandle();
        private NarStagedTree.Error[] results =
                new NarStagedTree.Error[] {NarStagedTree.Error.OK};
        private int resultIndex;
        private int discards;
        private Throwable throwable;
        public NarStagedTree.BeginResult begin(Context context,
                NarFilesystemInspector.TrustedRoot root, CharSequence target) {
            return NarStagedTree.BeginResult.present(handle);
        }
        public NarStagedTreeInventory.Description describe(
                NarStagedTree.Handle supplied) { return empty(); }
        public NarStagedTree.Error discard(Context context,
                NarStagedTree.Handle supplied) {
            discards++;
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            if (throwable instanceof Error) throw (Error) throwable;
            int index = Math.min(resultIndex++, results.length - 1);
            return results[index];
        }
    }
}
