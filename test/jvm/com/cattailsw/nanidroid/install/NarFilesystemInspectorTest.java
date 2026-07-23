package com.cattailsw.nanidroid.install;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class NarFilesystemInspectorTest {
    private static NarFilesystemInspector.Result result(
            int state, int error, String[] paths, int[] types, long[] facts) {
        return NarFilesystemInspector.fromNative(
                state, error, 0, paths.length, 7, paths, types, facts);
    }

    private static NarFilesystemInspector inspector(
            NarFilesystemInspector.Loader loader,
            NarFilesystemInspector.Backend backend) {
        return new NarFilesystemInspector(loader, backend);
    }

    @Test
    public void backendIsLazyOrderedImmutableAndDefensivelyCopied() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        String[] paths = {"empty", "nested/file-\ud83d\ude00"};
        int[] types = {2, 1};
        long[] facts = {0, 11, 12, 7, 21, 22};
        NarFilesystemInspector.Result made = result(2, 0, paths, types, facts);
        NarFilesystemInspector value = inspector(
                loads::incrementAndGet,
                (root, target) -> { calls.incrementAndGet(); return made; });
        paths[0] = "changed";
        types[0] = 1;
        facts[0] = 99;

        NarFilesystemInspector.TrustedRoot root =
                new NarFilesystemInspector.TrustedRoot("/trusted/root");
        NarFilesystemInspector.Result first = value.inspect(root, "ghost");
        assertSame(first, value.inspect(root, "ghost"));
        assertEquals(1, loads.get());
        assertEquals(2, calls.get());
        assertEquals(NarFilesystemInspector.State.PRESENT, first.state());
        assertEquals(NarFilesystemInspector.Error.OK, first.error());
        assertEquals(2, first.entryCount());
        assertEquals(7, first.totalFileSize());
        List<NarFilesystemInspector.Entry> entries = first.entries();
        assertEquals("empty", entries.get(0).path());
        assertEquals(NarFilesystemInspector.Type.DIRECTORY, entries.get(0).type());
        assertEquals(0, entries.get(0).size());
        assertEquals(11, entries.get(0).device());
        assertEquals(12, entries.get(0).inode());
        assertEquals("nested/file-\ud83d\ude00", entries.get(1).path());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }

    @Test
    public void loaderAndRuntimeFailuresAreTypedButOomePropagates() {
        NarFilesystemInspector.TrustedRoot root =
                new NarFilesystemInspector.TrustedRoot("/trusted");
        NarFilesystemInspector.Result link = inspector(
                () -> { throw new UnsatisfiedLinkError("missing"); },
                (r, t) -> { throw new AssertionError(); }).inspect(root, "x");
        assertEquals(NarFilesystemInspector.Error.LINKAGE, link.error());
        NarFilesystemInspector.Result security = inspector(
                () -> { throw new SecurityException("denied"); },
                (r, t) -> { throw new AssertionError(); }).inspect(root, "x");
        assertEquals(NarFilesystemInspector.Error.SECURITY, security.error());
        NarFilesystemInspector.Result nativeFailure = inspector(
                () -> {}, (r, t) -> null).inspect(root, "x");
        assertEquals(NarFilesystemInspector.Error.NATIVE, nativeFailure.error());
        assertThrows(OutOfMemoryError.class, () -> inspector(
                () -> { throw new OutOfMemoryError(); },
                (r, t) -> null).inspect(root, "x"));
    }

    @Test
    public void nativeCodesAndMalformedDtosHaveStableTypedResults() {
        assertEquals(NarFilesystemInspector.State.ABSENT,
                result(1, 0, new String[0], new int[0], new long[0]).state());
        for (int code = 0; code <= 21; code++) {
            assertNotNull(result(0, code, new String[0], new int[0], new long[0]).error());
        }
        NarFilesystemInspector.Result malformed = NarFilesystemInspector.fromNative(
                2, 0, 0, 1, 0, new String[]{"x"}, new int[0], new long[0]);
        assertEquals(NarFilesystemInspector.State.ERROR, malformed.state());
        assertEquals(NarFilesystemInspector.Error.NATIVE, malformed.error());
        assertTrue(malformed.entries().isEmpty());
    }

    @Test
    public void packageSeamExposesNoPublicCapabilityOrResourceTypes() {
        assertFalse(Modifier.isPublic(NarFilesystemInspector.class.getModifiers()));
        for (Constructor<?> constructor : NarFilesystemInspector.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
        }
        for (Class<?> nested : NarFilesystemInspector.class.getDeclaredClasses()) {
            assertFalse(Modifier.isPublic(nested.getModifiers()));
        }
        for (Method method : NarFilesystemInspector.class.getDeclaredMethods()) {
            assertFalse(Modifier.isPublic(method.getModifiers()));
            assertFalse(method.getReturnType().getName().matches(
                    ".*(File|Descriptor|Stream|Channel|Pointer).*"));
        }
        for (Field field : NarFilesystemInspector.Entry.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertFalse(field.getType().getName().matches(
                    ".*(File|Descriptor|Stream|Channel|Pointer).*"));
        }
    }
}
