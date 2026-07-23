package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class NarGhostTreePolicyTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long MIB = 1024L * 1024L;

    @Test
    public void stateTargetAndStorageIdentityAreFingerprintBound()
            throws Exception {
        NarGhostTreePolicy.Manifest absent =
                success("ghost", bytes(1), state("ABSENT"), entries());
        NarGhostTreePolicy.Manifest empty =
                success("ghost", bytes(1), state("PRESENT"), entries());
        NarGhostTreePolicy.Manifest otherTarget =
                success("other", bytes(1), state("ABSENT"), entries());
        NarGhostTreePolicy.Manifest otherRoot =
                success("ghost", bytes(2), state("ABSENT"), entries());

        assertNotEquals(hex(absent.getFingerprint()),
                hex(empty.getFingerprint()));
        assertNotEquals(hex(absent.getFingerprint()),
                hex(otherTarget.getFingerprint()));
        assertNotEquals(hex(absent.getFingerprint()),
                hex(otherRoot.getFingerprint()));
        assertEquals("ABSENT", absent.getState().name());
        assertEquals("PRESENT", empty.getState().name());
        assertTrue(empty.getEntries().isEmpty());
        assertEquals(1, empty.getFingerprintVersion());
    }

    @Test
    public void manifestIsSortedTypedAndHasGoldenVersionOneDigest()
            throws Exception {
        byte[] abc = digest("abc");
        List<NarGhostTreePolicy.InputEntry> reversed = entries(
                file("shell/a.txt", 3, abc),
                directory("shell"));
        List<NarGhostTreePolicy.InputEntry> ordered = entries(
                directory("shell"),
                file("shell/a.txt", 3, abc));

        NarGhostTreePolicy.Manifest first = success(
                "ghost", bytes(1, 2), state("PRESENT"), reversed);
        NarGhostTreePolicy.Manifest second = success(
                "ghost", bytes(1, 2), state("PRESENT"), ordered);

        assertEquals("shell", first.getEntries().get(0).getPath());
        assertEquals("DIRECTORY",
                first.getEntries().get(0).getType().name());
        assertEquals("shell/a.txt",
                first.getEntries().get(1).getPath());
        assertEquals("FILE",
                first.getEntries().get(1).getType().name());
        assertEquals(hex(first.getFingerprint()),
                hex(second.getFingerprint()));
        assertEquals(
                "966ad96c948762e3d02dba2ef721cc9a2083b73e59deed274"
                        + "fae05b1a9c16f55",
                hex(first.getFingerprint()));

        NarGhostTreePolicy.Manifest empty = success(
                "ghost", bytes(1), state("PRESENT"), entries());
        NarGhostTreePolicy.Manifest emptyDirectory = success(
                "ghost",
                bytes(1),
                state("PRESENT"),
                entries(directory("empty")));
        NarGhostTreePolicy.Manifest emptyFile = success(
                "ghost",
                bytes(1),
                state("PRESENT"),
                entries(file("empty", 0, digest(""))));
        assertNotEquals(hex(empty.getFingerprint()),
                hex(emptyDirectory.getFingerprint()));
        assertNotEquals(hex(emptyDirectory.getFingerprint()),
                hex(emptyFile.getFingerprint()));
    }

    @Test
    public void pathsNormalizeAndRejectAmbiguityAndUnsafeInput()
            throws Exception {
        String nfc = Normalizer.normalize(
                "caf\u00e9", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize(
                nfc, Normalizer.Form.NFD);
        NarGhostTreePolicy.Manifest normalized = success(
                "ghost",
                bytes(1),
                state("PRESENT"),
                entries(directory(nfd)));
        assertEquals(nfc,
                normalized.getEntries().get(0).getPath());

        rejects("NORMALIZED_COLLISION", entries(
                directory("Alpha"), directory("alpha")));
        rejects("NORMALIZED_COLLISION", entries(
                directory(nfc), directory(nfd)));
        rejects("FILE_DIRECTORY_COLLISION", entries(
                file("a", 0, digest("")),
                file("a/b", 0, digest(""))));
        rejects("NORMALIZED_COLLISION", entries(
                directory("A"),
                file("a/b", 0, digest(""))));
        rejects("MISSING_DIRECTORY", entries(
                file("a/b", 0, digest(""))));

        for (String unsafe : Arrays.asList(
                "", ".", "..", "/root", "a//b", "a/./b",
                "a/../b", "a\\b", "a:b", "a\u0000b",
                "\ud800")) {
            rejects("INVALID_PATH", entries(directory(unsafe)));
        }
    }

    @Test
    public void everyStructuralLimitAcceptsExactAndRejectsPlusOne()
            throws Exception {
        assertTrue(build(chain(32, 1)).isSuccess());
        assertError("PATH_DEPTH_LIMIT", build(chain(33, 1)));

        assertTrue(build(entries(directory(repeat('a', 255))))
                .isSuccess());
        assertError("COMPONENT_LENGTH_LIMIT",
                build(entries(directory(repeat('a', 256)))));

        assertTrue(build(chain(5, 204)).isSuccess());
        List<NarGhostTreePolicy.InputEntry> longPath =
                chain(5, 204);
        String last = longPath.get(4).getPath();
        longPath.set(4, directory(last + "x"));
        assertError("PATH_LENGTH_LIMIT", build(longPath));

        List<NarGhostTreePolicy.InputEntry> tenThousand =
                rootFiles(10000, 0);
        assertTrue(build(tenThousand).isSuccess());
        assertError("ENTRY_COUNT_LIMIT",
                build(rootFiles(10001, 0)));

        assertTrue(build(entries(file(
                "exact", 128L * MIB, digest("")))).isSuccess());
        assertError("FILE_SIZE_LIMIT", build(entries(file(
                "large", 128L * MIB + 1, digest("")))));

        assertTrue(build(rootFiles(4, 128L * MIB)).isSuccess());
        List<NarGhostTreePolicy.InputEntry> total =
                rootFiles(4, 128L * MIB);
        total.add(file("extra", 1, digest("")));
        assertError("TOTAL_SIZE_LIMIT", build(total));
    }

    @Test
    public void arraysEntriesAndCollectionsAreDefensivelyImmutable()
            throws Exception {
        byte[] root = bytes(7, 8);
        byte[] content = digest("payload");
        NarGhostTreePolicy.InputEntry input =
                file("payload", 7, content);
        List<NarGhostTreePolicy.InputEntry> inputs =
                entries(input);
        NarGhostTreePolicy.Manifest manifest = success(
                "ghost", root, state("PRESENT"), inputs);
        String fingerprint = hex(manifest.getFingerprint());

        root[0] = 99;
        content[0] = 99;
        inputs.clear();
        byte[] exposedRoot = manifest.getStorageRootIdentity();
        byte[] exposedFingerprint = manifest.getFingerprint();
        byte[] exposedContent =
                manifest.getEntries().get(0).getContentDigest();
        exposedRoot[0] = 88;
        exposedFingerprint[0] = 88;
        exposedContent[0] = 88;

        assertEquals(fingerprint, hex(manifest.getFingerprint()));
        assertEquals(7, manifest.getStorageRootIdentity()[0]);
        assertEquals(hex(digest("payload")),
                hex(manifest.getEntries().get(0)
                        .getContentDigest()));
        assertEquals(1, manifest.getEntries().size());
        try {
            manifest.getEntries().clear();
            throw new AssertionError("entries were mutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, manifest.getEntries().size());
        }
    }

    @Test
    public void lengthPrefixesDefeatConcatenationAmbiguity()
            throws Exception {
        NarGhostTreePolicy.Manifest first = success(
                "a", "bc".getBytes(UTF_8), state("PRESENT"),
                entries());
        NarGhostTreePolicy.Manifest second = success(
                "ab", "c".getBytes(UTF_8), state("PRESENT"),
                entries());
        assertNotEquals(hex(first.getFingerprint()),
                hex(second.getFingerprint()));

        NarGhostTreePolicy.Manifest fileThenDirectory = success(
                "ghost", bytes(1), state("PRESENT"), entries(
                        file("a", 0, digest("")),
                        directory("b")));
        NarGhostTreePolicy.Manifest directoryThenFile = success(
                "ghost", bytes(1), state("PRESENT"), entries(
                        directory("a"),
                        file("b", 0, digest(""))));
        assertNotEquals(hex(fileThenDirectory.getFingerprint()),
                hex(directoryThenFile.getFingerprint()));
    }

    @Test
    public void failuresAreTypedAndApiRemainsPackagePrivatePurePolicy()
            throws Exception {
        assertError("TARGET_ID_INVALID", NarGhostTreePolicy.build(
                "../ghost", bytes(1), state("ABSENT"), entries()));
        assertError("STORAGE_ROOT_ID_INVALID",
                NarGhostTreePolicy.build(
                        "ghost", null, state("ABSENT"), entries()));
        assertError("STATE_INVALID", NarGhostTreePolicy.build(
                "ghost", bytes(1), state("ABSENT"),
                entries(directory("unexpected"))));
        assertError("CONTENT_DIGEST_INVALID", build(entries(
                file("bad", 0, bytes(1)))));
        assertError("ENTRY_INVALID", NarGhostTreePolicy.build(
                "ghost",
                bytes(1),
                state("PRESENT"),
                new AbstractList<NarGhostTreePolicy.InputEntry>() {
                    @Override
                    public NarGhostTreePolicy.InputEntry get(
                            int index) {
                        throw new SecurityException("get");
                    }

                    @Override
                    public int size() {
                        throw new SecurityException("size");
                    }
                }));

        assertPureType(NarGhostTreePolicy.class);
        assertPureType(NarRelativePathPolicy.class);
    }

    private static void assertPureType(Class<?> outer) {
        assertFalse(Modifier.isPublic(outer.getModifiers()));
        List<Class<?>> types = new ArrayList<Class<?>>();
        types.add(outer);
        types.addAll(Arrays.asList(outer.getDeclaredClasses()));
        for (Class<?> type : types) {
            for (Constructor<?> constructor
                    : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(
                        constructor.getModifiers()));
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertTrue(type.isEnum());
                    assertTrue("values".equals(method.getName())
                            || "valueOf".equals(method.getName()));
                }
                assertFalse(forbidden(method.getReturnType()));
                for (Class<?> parameter
                        : method.getParameterTypes()) {
                    assertFalse(forbidden(parameter));
                }
            }
        }
    }

    private static boolean forbidden(Class<?> type) {
        String name = type.getName();
        return name.equals("java.io.File")
                || name.startsWith("java.io.")
                || name.contains("OutputStream")
                || name.contains("Writer");
    }

    private static NarGhostTreePolicy.Result build(
            List<NarGhostTreePolicy.InputEntry> entries) {
        return NarGhostTreePolicy.build(
                "ghost", bytes(1), state("PRESENT"), entries);
    }

    private static NarGhostTreePolicy.Manifest success(
            String target,
            byte[] root,
            NarGhostTreePolicy.State state,
            List<NarGhostTreePolicy.InputEntry> entries) {
        NarGhostTreePolicy.Result result =
                NarGhostTreePolicy.build(
                        target, root, state, entries);
        assertTrue(result.getDetail(), result.isSuccess());
        assertNotNull(result.getManifest());
        return result.getManifest();
    }

    private static void rejects(
            String expected,
            List<NarGhostTreePolicy.InputEntry> entries) {
        assertError(expected, build(entries));
    }

    private static void assertError(
            String expected, NarGhostTreePolicy.Result result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.getError().name());
        assertNotNull(result.getDetail());
    }

    private static NarGhostTreePolicy.State state(String name) {
        return Enum.valueOf(NarGhostTreePolicy.State.class, name);
    }

    private static NarGhostTreePolicy.InputEntry directory(
            String path) {
        return NarGhostTreePolicy.InputEntry.directory(path);
    }

    private static NarGhostTreePolicy.InputEntry file(
            String path, long length, byte[] digest) {
        return NarGhostTreePolicy.InputEntry.file(
                path, length, digest);
    }

    private static List<NarGhostTreePolicy.InputEntry> entries(
            NarGhostTreePolicy.InputEntry... values) {
        return new ArrayList<NarGhostTreePolicy.InputEntry>(
                Arrays.asList(values));
    }

    private static List<NarGhostTreePolicy.InputEntry> chain(
            int depth, int componentLength) {
        List<NarGhostTreePolicy.InputEntry> result = entries();
        String path = "";
        for (int index = 0; index < depth; index++) {
            String component = repeat(
                    (char) ('a' + index % 26),
                    componentLength);
            path += path.length() == 0
                    ? component : "/" + component;
            result.add(directory(path));
        }
        return result;
    }

    private static List<NarGhostTreePolicy.InputEntry> rootFiles(
            int count, long length) throws Exception {
        List<NarGhostTreePolicy.InputEntry> result =
                new ArrayList<NarGhostTreePolicy.InputEntry>();
        byte[] empty = digest("");
        for (int index = 0; index < count; index++) {
            result.add(file(
                    String.format("f%05d", index),
                    length,
                    empty));
        }
        return result;
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static byte[] digest(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(UTF_8));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}
