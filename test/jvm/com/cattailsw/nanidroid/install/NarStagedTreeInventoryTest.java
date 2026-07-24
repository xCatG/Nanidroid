package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class NarStagedTreeInventoryTest {
    @Test
    public void absentAndPresentEmptyRemainDistinct() {
        NarStagedTreeInventory.Result absent =
                NarStagedTreeInventory.absent("ghost", 7, 11);
        NarStagedTreeInventory.Result present =
                NarStagedTreeInventory.present("ghost",
                        description(7, 11, new String[0], new int[0],
                                new long[0], new int[0], new byte[0]));

        assertTrue(absent.detail(), absent.isSuccess());
        assertTrue(present.detail(), present.isSuccess());
        assertEquals(NarGhostTreePolicy.State.ABSENT,
                absent.manifest().getState());
        assertEquals(NarGhostTreePolicy.State.PRESENT,
                present.manifest().getState());
        assertTrue(absent.entries().isEmpty());
        assertTrue(present.entries().isEmpty());
        assertNotEquals(hex(absent.manifest().getFingerprint()),
                hex(present.manifest().getFingerprint()));
    }

    @Test
    public void inventoryIsNormalizedSortedAndDefensivelyImmutable()
            throws Exception {
        byte[] first = digest("one");
        byte[] second = digest("two");
        String[] paths = {"z-\u0065\u0301", "empty", "a.bin"};
        int[] types = {1, 2, 1};
        long[] sizes = {3, 0, 3};
        int[] ordinals = {1, -1, 0};
        byte[] digests = flat(first, zeros(), second);
        NarStagedTreeInventory.Result result =
                NarStagedTreeInventory.present("ghost",
                        description(7, 11, paths, types,
                                sizes, ordinals, digests));
        paths[0] = "mutated";
        sizes[0] = 99;
        ordinals[0] = 99;
        Arrays.fill(digests, (byte) 99);

        assertTrue(result.detail(), result.isSuccess());
        List<NarStagedTreeInventory.Entry> entries = result.entries();
        assertEquals(Arrays.asList("a.bin", "empty", "z-\u00e9"),
                Arrays.asList(entries.get(0).path(),
                        entries.get(1).path(), entries.get(2).path()));
        assertEquals(0, entries.get(0).blobOrdinal());
        assertEquals(-1, entries.get(1).blobOrdinal());
        assertEquals(1, entries.get(2).blobOrdinal());
        assertEquals(NarGhostTreePolicy.Type.FILE, entries.get(0).type());
        assertEquals(NarGhostTreePolicy.Type.DIRECTORY, entries.get(1).type());
        assertEquals(3, entries.get(0).size());
        assertEquals(0, entries.get(1).size());
        assertArrayEquals(second, entries.get(0).sha256());
        byte[] returned = entries.get(0).sha256();
        returned[0] ^= 1;
        assertArrayEquals(second, entries.get(0).sha256());
        assertThrows(UnsupportedOperationException.class,
                () -> entries.clear());
    }

    @Test
    public void fingerprintV1BindsTargetRootAndDigestButNotOrdinal()
            throws Exception {
        byte[] a = digest("a");
        byte[] b = digest("b");
        NarStagedTreeInventory.Result left =
                twoFiles("ghost", 7, 11, a, b, new int[] {0, 1});
        NarStagedTreeInventory.Result swapped =
                twoFiles("ghost", 7, 11, a, b, new int[] {1, 0});
        String baseline = hex(left.manifest().getFingerprint());

        assertEquals(1, left.manifest().getFingerprintVersion());
        assertArrayEquals(left.manifest().getFingerprint(),
                swapped.manifest().getFingerprint());
        assertNotEquals(left.entries().get(0).blobOrdinal(),
                swapped.entries().get(0).blobOrdinal());
        assertEquals("0000000000000007000000000000000b",
                hex(left.manifest().getStorageRootIdentity()));
        assertNotEquals(baseline, fingerprint(
                twoFiles("other", 7, 11, a, b, new int[] {0, 1})));
        assertNotEquals(baseline, fingerprint(
                twoFiles("ghost", 8, 11, a, b, new int[] {0, 1})));
        assertNotEquals(baseline, fingerprint(
                twoFiles("ghost", 7, 12, a, b, new int[] {0, 1})));
        assertNotEquals(baseline, fingerprint(
                twoFiles("ghost", 7, 11, digest("x"), b,
                        new int[] {0, 1})));
    }

    @Test
    public void malformedDescriptionsAndPolicyCollisionsAreTyped()
            throws Exception {
        rejects("NATIVE", null);
        rejects("NATIVE", description(7, 11, new String[] {"a"},
                new int[0], new long[0], new int[0], new byte[0]));
        rejects("NATIVE", description(7, 11, new String[] {"a"},
                new int[] {1}, new long[] {-1},
                new int[] {0}, flat(digest("a"))));
        rejects("NATIVE", description(7, 11, new String[] {"a"},
                new int[] {9}, new long[] {0},
                new int[] {-1}, flat(zeros())));
        rejects("NATIVE", description(7, 11, new String[] {"empty"},
                new int[] {2}, new long[] {1},
                new int[] {-1}, flat(zeros())));
        rejects("NATIVE", description(7, 11, new String[] {"empty"},
                new int[] {2}, new long[] {0},
                new int[] {0}, flat(zeros())));
        rejects("NATIVE", description(7, 11, new String[] {"empty"},
                new int[] {2}, new long[] {0},
                new int[] {-1}, flat(digest("x"))));
        rejects("NATIVE", description(7, 11,
                new String[] {"a", "b"}, new int[] {1, 1},
                new long[] {1, 1}, new int[] {0, 0},
                flat(digest("a"), digest("b"))));
        rejects("NATIVE", description(7, 11,
                new String[] {"a", "b"}, new int[] {1, 1},
                new long[] {1, 1}, new int[] {0, 2},
                flat(digest("a"), digest("b"))));
        rejects("POLICY", description(7, 11,
                new String[] {"\u00e9", "\u0065\u0301"},
                new int[] {1, 1}, new long[] {1, 1},
                new int[] {0, 1},
                flat(digest("a"), digest("b"))));
    }

    @Test
    public void surfaceIsPureImmutablePolicyWithoutLifecycleEscape() {
        assertFalse(Modifier.isPublic(
                NarStagedTreeInventory.class.getModifiers()));
        for (Class<?> nested
                : NarStagedTreeInventory.class.getDeclaredClasses()) {
            assertFalse(Modifier.isPublic(nested.getModifiers()));
            for (Field field : nested.getDeclaredFields()) {
                assertFalse(forbidden(field.getType()));
            }
            for (Method method : nested.getDeclaredMethods()) {
                assertFalse(forbidden(method.getReturnType()));
                assertFalse(method.getName().matches(
                        "(finalize|close|discard|consume|publish|overlay|handle|token)"));
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(forbidden(parameter));
                }
            }
        }
    }

    private static boolean forbidden(Class<?> type) {
        String name = type.getName();
        return name.startsWith("android.")
                || name.equals("java.io.File")
                || name.startsWith("java.nio.file")
                || name.contains("NarStagedTree$Handle")
                || name.contains("Context");
    }

    private static NarStagedTreeInventory.Result twoFiles(
            String target, long device, long inode,
            byte[] first, byte[] second, int[] ordinals) {
        return NarStagedTreeInventory.present(target,
                description(device, inode,
                        new String[] {"a", "b"}, new int[] {1, 1},
                        new long[] {1, 1}, ordinals, flat(first, second)));
    }

    private static String fingerprint(
            NarStagedTreeInventory.Result result) {
        assertTrue(result.detail(), result.isSuccess());
        return hex(result.manifest().getFingerprint());
    }

    private static void rejects(String expected,
            NarStagedTreeInventory.Description description) {
        NarStagedTreeInventory.Result result =
                NarStagedTreeInventory.present("ghost", description);
        assertFalse(result.isSuccess());
        assertEquals(expected, result.error().name());
        assertNotNull(result.detail());
    }

    private static NarStagedTreeInventory.Description description(
            long device, long inode, String[] paths, int[] types,
            long[] sizes, int[] ordinals, byte[] digests) {
        return new NarStagedTreeInventory.Description(
                device, inode, paths, types, sizes, ordinals, digests);
    }

    private static byte[] flat(byte[]... values) {
        byte[] result = new byte[values.length * 32];
        for (int index = 0; index < values.length; index++) {
            System.arraycopy(values[index], 0,
                    result, index * 32, 32);
        }
        return result;
    }

    private static byte[] zeros() {
        return new byte[32];
    }

    private static byte[] digest(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes("UTF-8"));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}
