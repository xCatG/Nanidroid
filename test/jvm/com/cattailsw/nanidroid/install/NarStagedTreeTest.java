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
                new NarStagedTree.Error[] {NarStagedTree.Error.PERMISSION};
        NarStagedTree.StageResult linked =
                session(linkage).stage(ROOT, "ghost");
        assertFailure(NarStagedTree.Error.NATIVE, linked);
        assertEquals(NarStagedTree.Error.PERMISSION,
                linked.cleanup().discardError());
        assertEquals(1, linkage.discards);
        FakeBackend oome = failingDescription(
                new OutOfMemoryError("hostile"));
        assertThrows(OutOfMemoryError.class,
                () -> session(oome).stage(ROOT, "ghost"));
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
                NarStagedTree.Error.PERMISSION
        };

        NarStagedTree.StageResult result =
                session(backend).stage(ROOT, "ghost");

        assertFailure(NarStagedTree.Error.IO, result);
        assertEquals(NarStagedTree.Error.CLOSE,
                result.cleanup().nativeError());
        assertEquals(NarStagedTree.Error.PERMISSION,
                result.cleanup().discardError());
        assertEquals(1, backend.discards);
    }

    @Test
    public void transferTypesMisuseAndTreeClaimDiscardAreRetrySafe() {
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
        assertMethods(NarStagedTree.Claim.class, "discard");
        assertMethods(NarStagedTree.StageResult.class,
                "cleanup", "detail", "error", "failure", "isSuccess",
                "success", "tree", "withDiscard");
        assertMethods(NarStagedTree.Cleanup.class,
                "discardError", "nativeError");
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

    private static boolean forbidden(Class<?> type) {
        String name = type.getName();
        return name.equals("java.io.File")
                || name.startsWith("java.nio.file")
                || name.contains("InputStream")
                || name.contains("OutputStream")
                || name.contains("Reader")
                || name.contains("Writer");
    }

    private static NarStagedTree.Session session(FakeBackend backend) {
        return new NarStagedTree.Stager(backend).session(CONTEXT);
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
        private final FakeHandle handle = new FakeHandle();
        private NarStagedTree.BeginResult begin;
        private NarStagedTreeInventory.Description description;
        private Throwable beginFailure;
        private Throwable describeFailure;
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
            int index = Math.min(
                    discardIndex++, discardResults.length - 1);
            discards++;
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
