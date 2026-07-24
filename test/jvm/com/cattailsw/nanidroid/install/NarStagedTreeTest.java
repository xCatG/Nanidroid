package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.test.mock.MockContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

public final class NarStagedTreeTest {
    private static final Context CONTEXT = new MockContext();
    private static final NarFilesystemInspector.TrustedRoot ROOT =
            new NarFilesystemInspector.TrustedRoot("/trusted");

    @Test
    public void absentHasNoOwnershipAndPresentEmptyOwnsHandle() {
        FakeBackend absentBackend = new FakeBackend();
        absentBackend.begin = NarStagedTree.BeginResult.absent(7, 11);
        NarStagedTree.Tree absent =
                success(session(absentBackend).stage(ROOT, "ghost"));
        FakeBackend presentBackend = new FakeBackend();
        presentBackend.present(empty());
        NarStagedTree.Tree present =
                success(session(presentBackend).stage(ROOT, "ghost"));

        assertEquals(NarGhostTreePolicy.State.ABSENT,
                absent.manifest().getState());
        assertEquals(NarGhostTreePolicy.State.PRESENT,
                present.manifest().getState());
        assertTrue(absent.entries().isEmpty());
        assertTrue(present.entries().isEmpty());
        assertEquals(NarStagedTree.Error.OK, absent.discard());
        assertEquals(0, absentBackend.discards);
        assertEquals(NarStagedTree.Error.OK, present.discard());
        assertEquals(1, presentBackend.discards);

        FakeBackend transferredAbsent = new FakeBackend();
        transferredAbsent.begin =
                NarStagedTree.BeginResult.absent(7, 11);
        NarStagedTree.Session absentSession = session(transferredAbsent);
        NarStagedTree.ConsumeResult absentClaim = absentSession.consume(
                success(absentSession.stage(ROOT, "other")));
        assertTrue(absentClaim.isSuccess());
        assertEquals(NarStagedTree.Error.OK,
                absentClaim.claim().discard());
        assertEquals(NarStagedTree.Error.OK,
                absentClaim.claim().discard());
        assertEquals(0, transferredAbsent.discards);
    }

    @Test
    public void malformedRuntimeLinkageAndOomeAlwaysReleaseReturnedHandle()
            throws Exception {
        FakeBackend nullBegin = new FakeBackend();
        assertFailure(NarStagedTree.Error.NATIVE,
                session(nullBegin).stage(ROOT, "ghost"));
        assertEquals(0, nullBegin.discards);
        FakeBackend linkBegin = new FakeBackend();
        linkBegin.beginFailure = new UnsatisfiedLinkError("missing");
        assertFailure(NarStagedTree.Error.NATIVE,
                session(linkBegin).stage(ROOT, "ghost"));
        assertEquals(0, linkBegin.discards);
        FakeBackend nullHandle = new FakeBackend();
        nullHandle.begin = NarStagedTree.BeginResult.present(null);
        assertFailure(NarStagedTree.Error.NATIVE,
                session(nullHandle).stage(ROOT, "ghost"));
        assertEquals(0, nullHandle.discards);
        FakeBackend malformedFailure = new FakeBackend();
        malformedFailure.begin = NarStagedTree.BeginResult.failure(
                null, null, malformedFailure.handle);
        malformedFailure.discardResults =
                new NarStagedTree.Error[] {null};
        NarStagedTree.StageResult malformed =
                session(malformedFailure).stage(ROOT, "ghost");
        assertFailure(NarStagedTree.Error.NATIVE, malformed);
        assertEquals(NarStagedTree.Error.NATIVE,
                malformed.cleanup().nativeError());
        assertEquals(NarStagedTree.Error.NATIVE,
                malformed.cleanup().discardError());
        malformedFailure.discardResults = new NarStagedTree.Error[] {
                NarStagedTree.Error.OK
        };
        assertEquals(NarStagedTree.Error.OK,
                malformed.cleanup().discard());
        assertEquals(2, malformedFailure.discards);
        FakeBackend okFailure = new FakeBackend();
        okFailure.begin = NarStagedTree.BeginResult.failure(
                NarStagedTree.Error.OK, NarStagedTree.Error.OK,
                okFailure.handle);
        NarStagedTree.StageResult okAsFailure =
                session(okFailure).stage(ROOT, "ghost");
        assertFailure(NarStagedTree.Error.NATIVE, okAsFailure);
        assertEquals(1, okFailure.discards);

        FakeBackend nullDescription = new FakeBackend();
        nullDescription.present(null);
        assertFailure(NarStagedTree.Error.NATIVE,
                session(nullDescription).stage(ROOT, "ghost"));
        assertEquals(1, nullDescription.discards);
        FakeBackend runtime = failingDescription(
                new IllegalStateException("hostile"));
        assertFailure(NarStagedTree.Error.NATIVE,
                session(runtime).stage(ROOT, "ghost"));
        assertEquals(1, runtime.discards);
        FakeBackend linkage = failingDescription(
                new UnsatisfiedLinkError("missing"));
        linkage.discardResults =
                new NarStagedTree.Error[] {
                    NarStagedTree.Error.PERMISSION, NarStagedTree.Error.OK
                };
        NarStagedTree.StageResult linked =
                session(linkage).stage(ROOT, "ghost");
        assertFailure(NarStagedTree.Error.NATIVE, linked);
        assertEquals(NarStagedTree.Error.PERMISSION,
                linked.cleanup().discardError());
        assertEquals(NarStagedTree.Error.OK,
                linked.cleanup().discard());
        assertEquals(2, linkage.discards);
        OutOfMemoryError original = new OutOfMemoryError("hostile");
        FakeBackend oome = failingDescription(original);
        oome.discardFailure =
                new OutOfMemoryError("cleanup must not mask");
        assertSame(original, assertThrows(OutOfMemoryError.class,
                () -> session(oome).stage(ROOT, "ghost")));
        assertEquals(1, oome.discards);

        FakeBackend collision = new FakeBackend();
        collision.present(description(
                new String[] {"\u00e9", "\u0065\u0301"},
                new int[] {1, 1}, new long[] {1, 1},
                new int[] {0, 1},
                flat(digest("a"), digest("b"))));
        assertFailure(NarStagedTree.Error.POLICY,
                session(collision).stage(ROOT, "ghost"));
        assertEquals(1, collision.discards);
    }

    @Test
    public void primaryAndCleanupFailuresStaySeparate() {
        FakeBackend backend = new FakeBackend();
        backend.begin = NarStagedTree.BeginResult.failure(
                NarStagedTree.Error.IO, NarStagedTree.Error.CLOSE,
                backend.handle);
        backend.discardResults = new NarStagedTree.Error[] {
                NarStagedTree.Error.PERMISSION, NarStagedTree.Error.OK
        };

        NarStagedTree.StageResult result =
                session(backend).stage(ROOT, "ghost");

        assertFailure(NarStagedTree.Error.IO, result);
        assertEquals(NarStagedTree.Error.CLOSE,
                result.cleanup().nativeError());
        assertEquals(NarStagedTree.Error.PERMISSION,
                result.cleanup().discardError());
        assertEquals(NarStagedTree.Error.OK,
                result.cleanup().discard());
        assertEquals(NarStagedTree.Error.OK,
                result.cleanup().discard());
        assertEquals(2, backend.discards);

        FakeBackend throwingCleanup = failingDescription(
                new UnsatisfiedLinkError("primary"));
        throwingCleanup.discardFailure =
                new UnsatisfiedLinkError("cleanup");
        NarStagedTree.StageResult throwing =
                session(throwingCleanup).stage(ROOT, "ghost");
        assertFailure(NarStagedTree.Error.NATIVE, throwing);
        assertEquals(NarStagedTree.Error.NATIVE,
                throwing.cleanup().discardError());
        throwingCleanup.discardFailure = null;
        assertEquals(NarStagedTree.Error.OK,
                throwing.cleanup().discard());
        assertEquals(2, throwingCleanup.discards);
    }

    @Test
    public void transferTypesMisuseAndTreeClaimDiscardAreRetrySafe()
            throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.present(empty());
        NarStagedTree.Stager owner = new NarStagedTree.Stager(backend);
        NarStagedTree.Session session = owner.session(CONTEXT);
        NarStagedTree.Tree tree = success(session.stage(ROOT, "ghost"));
        NarStagedTree.Session wrongSession = owner.session(CONTEXT);
        NarStagedTree.Stager foreign =
                new NarStagedTree.Stager(new FakeBackend());

        assertEquals(NarStagedTree.Error.WRONG_SESSION,
                wrongSession.consume(tree).error());
        assertEquals(NarStagedTree.Error.FOREIGN,
                foreign.session(CONTEXT).consume(tree).error());
        NarStagedTree.ConsumeResult consumed = session.consume(tree);
        assertTrue(consumed.isSuccess());
        assertNotNull(consumed.claim());
        Field treeResource =
                NarStagedTree.Tree.class.getDeclaredField("resource");
        Field claimResource =
                NarStagedTree.Claim.class.getDeclaredField("resource");
        treeResource.setAccessible(true);
        claimResource.setAccessible(true);
        assertSame(treeResource.get(tree),
                claimResource.get(consumed.claim()));
        assertEquals(NarStagedTree.Error.CONSUMED,
                session.consume(tree).error());
        assertEquals(NarStagedTree.Error.CONSUMED, tree.discard());
        backend.discardResults = new NarStagedTree.Error[] {
                NarStagedTree.Error.PERMISSION, NarStagedTree.Error.OK
        };
        assertEquals(NarStagedTree.Error.PERMISSION,
                consumed.claim().discard());
        assertEquals(NarStagedTree.Error.OK, consumed.claim().discard());
        assertEquals(NarStagedTree.Error.OK, consumed.claim().discard());
        assertEquals(2, backend.discards);

        backend.present(empty());
        NarStagedTree.Tree closed = success(session.stage(ROOT, "other"));
        backend.discardResults = new NarStagedTree.Error[] {
                NarStagedTree.Error.IO, NarStagedTree.Error.OK
        };
        assertEquals(NarStagedTree.Error.IO, closed.discard());
        assertEquals(NarStagedTree.Error.OK, closed.discard());
        assertEquals(NarStagedTree.Error.OK, closed.discard());
        assertEquals(NarStagedTree.Error.CLOSED,
                session.consume(closed).error());
        assertEquals(4, backend.discards);

        FakeBackend nullThenSuccess = new FakeBackend();
        nullThenSuccess.present(empty());
        NarStagedTree.Session nullSession = session(nullThenSuccess);
        NarStagedTree.Tree nullTree =
                success(nullSession.stage(ROOT, "ghost"));
        nullThenSuccess.discardResults = new NarStagedTree.Error[] {
                null, NarStagedTree.Error.OK
        };
        assertEquals(NarStagedTree.Error.NATIVE, nullTree.discard());
        assertEquals(NarStagedTree.Error.OK, nullTree.discard());
        assertEquals(NarStagedTree.Error.OK, nullTree.discard());
        assertEquals(2, nullThenSuccess.discards);

        FakeBackend failedThenTransferred = new FakeBackend();
        failedThenTransferred.present(empty());
        NarStagedTree.Session transferSession =
                session(failedThenTransferred);
        NarStagedTree.Tree transferTree =
                success(transferSession.stage(ROOT, "ghost"));
        failedThenTransferred.discardResults = new NarStagedTree.Error[] {
                NarStagedTree.Error.IO, NarStagedTree.Error.OK
        };
        assertEquals(NarStagedTree.Error.IO, transferTree.discard());
        NarStagedTree.ConsumeResult afterFailure =
                transferSession.consume(transferTree);
        assertTrue(afterFailure.isSuccess());
        assertEquals(NarStagedTree.Error.OK,
                afterFailure.claim().discard());
        assertEquals(2, failedThenTransferred.discards);

        FakeBackend throwingRetry = new FakeBackend();
        throwingRetry.present(empty());
        NarStagedTree.Session throwingSession = session(throwingRetry);
        NarStagedTree.Claim throwingClaim = throwingSession.consume(
                success(throwingSession.stage(ROOT, "ghost"))).claim();
        OutOfMemoryError discardOome = new OutOfMemoryError("retry");
        throwingRetry.discardFailure = discardOome;
        assertSame(discardOome, assertThrows(OutOfMemoryError.class,
                () -> throwingClaim.discard()));
        throwingRetry.discardFailure = null;
        assertEquals(NarStagedTree.Error.OK, throwingClaim.discard());
        assertEquals(NarStagedTree.Error.OK, throwingClaim.discard());
        assertEquals(2, throwingRetry.discards);
    }

    @Test
    public void claimLeaseIsExclusiveExactAndTransferredCleanupOnly()
            throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.present(empty());
        NarStagedTree.Claim claim = claim(backend, "ghost");
        FakeBackend otherBackend = new FakeBackend();
        otherBackend.present(empty());
        NarStagedTree.Claim other = claim(otherBackend, "other");

        assertEquals("READY", claim.state().name());
        NarStagedTree.Claim.Lease lease = claim.lease();
        assertEquals(NarGhostTreePolicy.State.PRESENT,
                lease.manifest().getState());
        assertTrue(lease.entries().isEmpty());
        assertEquals("BUSY", claim.state().name());
        assertNull(claim.lease());
        assertEquals(NarStagedTree.Error.BUSY, claim.discard());
        NarStagedTree.Claim.Lease foreign = other.lease();
        assertEquals(NarStagedTree.Error.FOREIGN,
                claim.release(foreign));
        assertEquals(NarStagedTree.Error.FOREIGN,
                claim.consume(foreign));
        assertEquals(NarStagedTree.Error.OK, claim.release(lease));
        assertEquals("READY", claim.state().name());
        assertEquals(NarStagedTree.Error.CONSUMED,
                claim.release(lease));
        assertThrows(IllegalStateException.class, () -> lease.manifest());
        assertEquals(NarStagedTree.Error.OK, other.release(foreign));

        backend.discardResults = new NarStagedTree.Error[] {
                NarStagedTree.Error.PERMISSION, NarStagedTree.Error.OK
        };
        NarStagedTree.Claim.Lease consumed = claim.lease();
        assertEquals(NarStagedTree.Error.OK, claim.consume(consumed));
        assertEquals("CONSUMED", claim.state().name());
        assertEquals(NarStagedTree.Error.CONSUMED, claim.discard());
        assertNull(claim.lease());
        assertEquals(NarStagedTree.Error.PERMISSION, consumed.discard());
        assertEquals(NarStagedTree.Error.OK, consumed.discard());
        assertEquals(NarStagedTree.Error.OK, consumed.discard());
        assertEquals(2, backend.discards);
        assertMethods(NarStagedTree.Claim.Lease.class,
                "discard", "entries", "manifest");
        assertEquals(NarStagedTree.Error.OK, other.discard());
    }

    @Test
    public void absentAndOomeClaimLeaseCleanupRemainRetryable()
            throws Exception {
        FakeBackend absentBackend = new FakeBackend();
        absentBackend.begin =
                NarStagedTree.BeginResult.absent(7, 11);
        NarStagedTree.Claim absent = claim(absentBackend, "absent");
        NarStagedTree.Claim.Lease absentLease = absent.lease();
        assertEquals(NarGhostTreePolicy.State.ABSENT,
                absentLease.manifest().getState());
        assertEquals(NarStagedTree.Error.OK,
                absent.consume(absentLease));
        assertEquals(NarStagedTree.Error.CONSUMED, absent.discard());
        assertEquals(NarStagedTree.Error.OK, absentLease.discard());
        assertEquals(NarStagedTree.Error.OK, absentLease.discard());
        assertEquals(0, absentBackend.discards);

        FakeBackend oomeBackend = new FakeBackend();
        oomeBackend.present(empty());
        NarStagedTree.Claim oome = claim(oomeBackend, "oome");
        NarStagedTree.Claim.Lease oomeLease = oome.lease();
        assertEquals(NarStagedTree.Error.OK, oome.consume(oomeLease));
        OutOfMemoryError failure = new OutOfMemoryError("discard");
        oomeBackend.discardFailure = failure;
        assertSame(failure, assertThrows(OutOfMemoryError.class,
                () -> oomeLease.discard()));
        oomeBackend.discardFailure = null;
        assertEquals(NarStagedTree.Error.OK, oomeLease.discard());
        assertEquals(NarStagedTree.Error.OK, oomeLease.discard());
        assertEquals(2, oomeBackend.discards);
    }

    @Test
    public void concurrentClaimLeaseAndDirectDiscardAreLinearized()
            throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.present(empty());
        final NarStagedTree.Claim claim = claim(backend, "race");
        final NarStagedTree.Claim.Lease[] lease =
                new NarStagedTree.Claim.Lease[1];
        final NarStagedTree.Error[] discard = new NarStagedTree.Error[1];
        race(() -> lease[0] = claim.lease(),
                () -> discard[0] = claim.discard());
        if (lease[0] == null) {
            assertEquals(NarStagedTree.Error.OK, discard[0]);
        } else {
            assertEquals(NarStagedTree.Error.BUSY, discard[0]);
            assertEquals(NarStagedTree.Error.OK,
                    claim.consume(lease[0]));
            assertEquals(NarStagedTree.Error.OK,
                    lease[0].discard());
        }
        assertEquals("CONSUMED", claim.state().name());
        assertEquals(1, backend.discards);
    }

    @Test
    public void concurrentDiscardAndTransferAreLinearized()
            throws Exception {
        final FakeBackend claimBackend = new FakeBackend();
        claimBackend.present(empty());
        NarStagedTree.Session claimSession = session(claimBackend);
        final NarStagedTree.Claim claim = claimSession.consume(
                success(claimSession.stage(ROOT, "ghost"))).claim();
        final NarStagedTree.Error[] claimResults = new NarStagedTree.Error[2];
        race(() -> claimResults[0] = claim.discard(),
                () -> claimResults[1] = claim.discard());
        assertEquals(NarStagedTree.Error.OK, claimResults[0]);
        assertEquals(NarStagedTree.Error.OK, claimResults[1]);
        assertEquals(1, claimBackend.discards);
        final FakeBackend treeBackend = new FakeBackend();
        treeBackend.present(empty());
        final NarStagedTree.Session treeSession = session(treeBackend);
        final NarStagedTree.Tree tree = success(
                treeSession.stage(ROOT, "ghost"));
        final NarStagedTree.ConsumeResult[] transfer =
                new NarStagedTree.ConsumeResult[1];
        final NarStagedTree.Error[] discard = new NarStagedTree.Error[1];
        race(() -> transfer[0] = treeSession.consume(tree),
                () -> discard[0] = tree.discard());
        if (transfer[0].isSuccess()) {
            assertEquals(NarStagedTree.Error.CONSUMED, discard[0]);
            assertEquals(NarStagedTree.Error.OK, transfer[0].claim().discard());
        } else {
            assertEquals(NarStagedTree.Error.CLOSED, transfer[0].error());
            assertEquals(NarStagedTree.Error.OK, discard[0]);
        }
        assertEquals(1, treeBackend.discards);
    }

    @Test
    public void nativeFactoryMapsCodesDefensivelyAndCachesDescription()
            throws Exception {
        byte[] token = new byte[88];
        token[0] = 7;
        String[] paths = new String[] {"file"};
        int[] types = new int[] {1};
        long[] sizes = new long[] {3};
        int[] ordinals = new int[] {0};
        byte[] digests = digest("abc");
        NarStagedTree.BeginResult present = NarStagedTree.fromNativeBegin(
                2, 0, 0, 7, 11, token,
                paths, types, sizes, ordinals, digests);
        NarStagedTree.Handle handle = (NarStagedTree.Handle)
                field(present, "handle");
        token[0] = 99;
        paths[0] = "changed";
        digests[0] = 99;
        byte[] owned = (byte[]) field(handle, "token");
        assertEquals(7, owned[0]);
        NarStagedTreeInventory.Result inventory =
                NarStagedTreeInventory.present(
                "ghost", new NarStagedTree.NativeBackend().describe(handle));
        assertTrue(inventory.isSuccess());
        assertEquals("file", inventory.entries().get(0).path());
        assertEquals(digest("abc")[0],
                inventory.entries().get(0).sha256()[0]);

        NarStagedTree.BeginResult invalid = NarStagedTree.fromNativeBegin(
                0, Integer.MAX_VALUE, Integer.MIN_VALUE, 0, 0,
                null, new String[0], new int[0], new long[0],
                new int[0], new byte[0]);
        assertEquals(NarStagedTree.Error.NATIVE,
                field(invalid, "primaryError"));
        assertEquals(NarStagedTree.Error.NATIVE,
                field(invalid, "cleanupError"));
        NarStagedTree.BeginResult absent = NarStagedTree.fromNativeBegin(
                1, 0, 0, 7, 11, null,
                new String[0], new int[0], new long[0],
                new int[0], new byte[0]);
        assertEquals(null, field(absent, "primaryError"));
        for (Object malformed : new Object[] {
                null, new String[] {"unexpected"},
                new int[] {1}, new long[] {1},
                new int[] {0}, new byte[] {0}
        }) {
            String[] absentPaths = new String[0];
            int[] absentTypes = new int[0];
            long[] absentSizes = new long[0];
            int[] absentOrdinals = new int[0];
            byte[] absentDigests = new byte[0];
            if (malformed == null || malformed instanceof String[]) {
                absentPaths = (String[]) malformed;
            } else if (malformed instanceof int[]) {
                if (((int[]) malformed)[0] == 0) {
                    absentOrdinals = (int[]) malformed;
                } else {
                    absentTypes = (int[]) malformed;
                }
            } else if (malformed instanceof long[]) {
                absentSizes = (long[]) malformed;
            } else {
                absentDigests = (byte[]) malformed;
            }
            NarStagedTree.BeginResult malformedAbsent =
                    NarStagedTree.fromNativeBegin(
                    1, 0, 0, 7, 11, null,
                    absentPaths, absentTypes, absentSizes,
                    absentOrdinals, absentDigests);
            assertEquals(NarStagedTree.Error.NATIVE,
                    field(malformedAbsent, "primaryError"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NarStagedTree.fromNativeBegin(
                2, 0, 0, 7, 11, new byte[87],
                new String[0], new int[0], new long[0],
                new int[0], new byte[0]));
    }

    @Test
    public void nativeFactoryRejectsMalformedStateShapesAndRetainsCleanup()
            throws Exception {
        byte[] token = new byte[88];
        token[0] = 9;
        int io = NarStagedTree.Error.IO.ordinal();
        int close = NarStagedTree.Error.CLOSE.ordinal();
        NarStagedTree.BeginResult absentWithToken =
                nativeResult(1, 0, 0, token);
        NarStagedTree.BeginResult absentWithError =
                nativeResult(1, io, close, null);
        NarStagedTree.BeginResult absentWithPrimaryOnly =
                nativeResult(1, io, 0, null);
        NarStagedTree.BeginResult presentWithoutToken =
                nativeResult(2, 0, 0, null);
        NarStagedTree.BeginResult presentWithError =
                nativeResult(2, io, 0, token);
        NarStagedTree.BeginResult presentWithCleanupOnly =
                nativeResult(2, 0, close, token);
        NarStagedTree.BeginResult errorWithToken =
                nativeResult(0, io, close, token);
        NarStagedTree.BeginResult unknownWithToken =
                nativeResult(Integer.MAX_VALUE, 0, 0, token);

        assertBeginFailure(
                absentWithToken, NarStagedTree.Error.NATIVE,
                NarStagedTree.Error.OK, true);
        assertBeginFailure(
                absentWithError, NarStagedTree.Error.IO,
                NarStagedTree.Error.CLOSE, false);
        assertBeginFailure(
                absentWithPrimaryOnly, NarStagedTree.Error.IO,
                NarStagedTree.Error.OK, false);
        assertBeginFailure(
                presentWithoutToken, NarStagedTree.Error.NATIVE,
                NarStagedTree.Error.OK, false);
        assertBeginFailure(
                presentWithError, NarStagedTree.Error.IO,
                NarStagedTree.Error.OK, true);
        assertBeginFailure(
                presentWithCleanupOnly, NarStagedTree.Error.NATIVE,
                NarStagedTree.Error.CLOSE, true);
        assertBeginFailure(
                errorWithToken, NarStagedTree.Error.IO,
                NarStagedTree.Error.CLOSE, true);
        assertBeginFailure(
                unknownWithToken, NarStagedTree.Error.NATIVE,
                NarStagedTree.Error.OK, true);

        for (NarStagedTree.BeginResult begun :
                new NarStagedTree.BeginResult[] {
                    absentWithToken, presentWithError,
                    presentWithCleanupOnly, errorWithToken,
                    unknownWithToken
                }) {
            FakeBackend backend = new FakeBackend();
            backend.handle = (NarStagedTree.Handle)
                    field(begun, "handle");
            backend.begin = begun;
            backend.discardResults = new NarStagedTree.Error[] {
                    NarStagedTree.Error.PERMISSION,
                    NarStagedTree.Error.OK
            };
            NarStagedTree.StageResult result =
                    session(backend).stage(ROOT, "ghost");
            assertFailure((NarStagedTree.Error)
                    field(begun, "primaryError"), result);
            assertEquals(1, backend.discards);
            assertEquals(NarStagedTree.Error.PERMISSION,
                    result.cleanup().discardError());
            assertEquals(NarStagedTree.Error.OK,
                    result.cleanup().discard());
            assertEquals(NarStagedTree.Error.OK,
                    result.cleanup().discard());
            assertEquals(2, backend.discards);
        }
    }

    @Test
    public void nativeErrorCodeMappingAndEnumPrefixStayExact()
            throws Exception {
        NarStagedTree.Error[] nativePrefix = {
            NarStagedTree.Error.OK,
            NarStagedTree.Error.INVALID_OPTIONS,
            NarStagedTree.Error.INVALID_TARGET,
            NarStagedTree.Error.ROOT_TYPE,
            NarStagedTree.Error.TARGET_TYPE,
            NarStagedTree.Error.SYMLINK,
            NarStagedTree.Error.SPECIAL_TYPE,
            NarStagedTree.Error.INVALID_NAME,
            NarStagedTree.Error.COMPONENT_LIMIT,
            NarStagedTree.Error.PATH_LIMIT,
            NarStagedTree.Error.DEPTH_LIMIT,
            NarStagedTree.Error.ENTRY_COUNT_LIMIT,
            NarStagedTree.Error.FILE_SIZE_LIMIT,
            NarStagedTree.Error.TOTAL_SIZE_LIMIT,
            NarStagedTree.Error.CYCLE,
            NarStagedTree.Error.TREE_CHANGED,
            NarStagedTree.Error.PERMISSION,
            NarStagedTree.Error.RESOURCE,
            NarStagedTree.Error.IO,
            NarStagedTree.Error.VISITOR,
            NarStagedTree.Error.CLOSE
        };
        assertEquals(29, NarStagedTree.Error.values().length);
        assertEquals(28, NarStagedTree.Error.BUSY.ordinal());
        Method mapper = NarStagedTree.class.getDeclaredMethod(
                "fromNativeError", int.class);
        mapper.setAccessible(true);
        for (int code = 0; code < nativePrefix.length; code++) {
            assertEquals(code, nativePrefix[code].ordinal());
            assertEquals(nativePrefix[code], mapper.invoke(null, code));
        }
        assertEquals(NarStagedTree.Error.INPUT,
                mapper.invoke(null, 100));
        assertEquals(NarStagedTree.Error.NATIVE,
                mapper.invoke(null, 101));
        assertEquals(NarStagedTree.Error.NATIVE,
                mapper.invoke(null, -1));
        assertEquals(NarStagedTree.Error.NATIVE,
                mapper.invoke(null, Integer.MAX_VALUE));
    }

    @Test
    public void surfaceHasNoDestinationHandleGetterOrOverlayEndpoint()
            throws Exception {
        assertFalse(Modifier.isPublic(NarStagedTree.class.getModifiers()));
        for (Class<?> nested : NarStagedTree.class.getDeclaredClasses()) {
            assertFalse(Modifier.isPublic(nested.getModifiers()));
            for (Field field : nested.getDeclaredFields()) {
                assertFalse(forbidden(field.getType()));
            }
        }
        assertMethods(NarStagedTree.Handle.class);
        assertMethods(NarStagedTree.Backend.class,
                "begin", "describe", "discard");
        assertMethods(NarStagedTree.BeginResult.class,
                "absent", "failure", "present");
        assertMethods(NarStagedTree.Stager.class, "session");
        assertMethods(NarStagedTree.Session.class,
                "consume", "stage");
        assertMethods(NarStagedTree.Tree.class,
                "discard", "entries", "manifest");
        assertMethods(NarStagedTree.Claim.class,
                "consume", "discard", "lease", "release", "state");
        assertMethods(NarStagedTree.StageResult.class,
                "cleanup", "detail", "error", "failure", "isSuccess",
                "success", "tree");
        assertMethods(NarStagedTree.Cleanup.class,
                "discard", "discardError", "nativeError");
        assertMethods(NarStagedTree.ConsumeResult.class,
                "claim", "error", "failure", "isSuccess", "success");
        NarStagedTree.Backend.class.getDeclaredMethod(
                "begin", Context.class,
                NarFilesystemInspector.TrustedRoot.class,
                CharSequence.class);
    }

    private static void assertMethods(Class<?> type, String... expected) {
        List<String> actual = new ArrayList<String>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            actual.add(method.getName());
            assertFalse(method.getName().matches(
                    "(finalize|publish|overlay|path|token|handle)"));
            assertFalse(forbidden(method.getReturnType()));
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(forbidden(parameter));
            }
        }
        Collections.sort(actual);
        Arrays.sort(expected);
        assertEquals(Arrays.asList(expected), actual);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static NarStagedTree.BeginResult nativeResult(
            int state, int error, int cleanup, byte[] token) {
        return NarStagedTree.fromNativeBegin(
                state, error, cleanup, 7, 11, token,
                new String[0], new int[0], new long[0],
                new int[0], new byte[0]);
    }

    private static void assertBeginFailure(
            NarStagedTree.BeginResult result,
            NarStagedTree.Error error,
            NarStagedTree.Error cleanup,
            boolean hasHandle) throws Exception {
        assertEquals(error, field(result, "primaryError"));
        assertEquals(cleanup, field(result, "cleanupError"));
        assertEquals(hasHandle, field(result, "handle") != null);
    }

    private static boolean forbidden(Class<?> type) {
        String name = type.getName();
        return name.equals("java.io.File")
                || name.startsWith("java.nio.file")
                || name.contains("InputStream")
                || name.contains("OutputStream")
                || name.contains("Reader")
                || name.contains("Writer");
    }

    private static void race(final Runnable first, final Runnable second)
            throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
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

    private static NarStagedTree.Session session(FakeBackend backend) {
        return new NarStagedTree.Stager(backend).session(CONTEXT);
    }

    private static NarStagedTree.Claim claim(
            FakeBackend backend, String target) {
        NarStagedTree.Session session = session(backend);
        return session.consume(
                success(session.stage(ROOT, target))).claim();
    }

    private static NarStagedTree.Tree success(
            NarStagedTree.StageResult result) {
        assertTrue(result.detail(), result.isSuccess());
        return result.tree();
    }

    private static void assertFailure(
            NarStagedTree.Error expected,
            NarStagedTree.StageResult result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.error());
        assertNull(result.tree());
    }

    private static FakeBackend failingDescription(Throwable failure) {
        FakeBackend backend = new FakeBackend();
        backend.present(empty());
        backend.describeFailure = failure;
        return backend;
    }

    private static NarStagedTreeInventory.Description empty() {
        return description(new String[0], new int[0],
                new long[0], new int[0], new byte[0]);
    }

    private static NarStagedTreeInventory.Description description(
            String[] paths, int[] types, long[] sizes,
            int[] ordinals, byte[] digests) {
        return new NarStagedTreeInventory.Description(
                7, 11, paths, types, sizes, ordinals, digests);
    }

    private static byte[] flat(byte[]... values) {
        byte[] result = new byte[values.length * 32];
        for (int index = 0; index < values.length; index++) {
            System.arraycopy(values[index], 0,
                    result, index * 32, 32);
        }
        return result;
    }

    private static byte[] digest(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes("UTF-8"));
    }

    private static final class FakeHandle
            implements NarStagedTree.Handle {}

    private static final class FakeBackend
            implements NarStagedTree.Backend {
        private NarStagedTree.Handle handle = new FakeHandle();
        private NarStagedTree.BeginResult begin;
        private NarStagedTreeInventory.Description description;
        private Throwable beginFailure;
        private Throwable describeFailure;
        private Throwable discardFailure;
        private NarStagedTree.Error[] discardResults =
                new NarStagedTree.Error[] {NarStagedTree.Error.OK};
        private int discardIndex;
        private int discards;

        private void present(
                NarStagedTreeInventory.Description value) {
            begin = NarStagedTree.BeginResult.present(handle);
            description = value;
            discardIndex = 0;
        }

        public NarStagedTree.BeginResult begin(
                Context context,
                NarFilesystemInspector.TrustedRoot root,
                CharSequence target) {
            assertSame(CONTEXT, context);
            assertSame(ROOT, root);
            throwIfNeeded(beginFailure);
            return begin;
        }

        public NarStagedTreeInventory.Description describe(
                NarStagedTree.Handle supplied) {
            assertSame(handle, supplied);
            throwIfNeeded(describeFailure);
            return description;
        }

        public NarStagedTree.Error discard(
                Context context, NarStagedTree.Handle supplied) {
            assertSame(CONTEXT, context);
            assertSame(handle, supplied);
            discards++;
            throwIfNeeded(discardFailure);
            int index = Math.min(
                    discardIndex++, discardResults.length - 1);
            return discardResults[index];
        }

        private static void throwIfNeeded(Throwable failure) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof java.lang.Error) {
                throw (java.lang.Error) failure;
            }
        }
    }
}
