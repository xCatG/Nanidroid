package com.cattailsw.nanidroid.install;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
public final class NarRetainedOverlayPolicyTest {
    private static final long MIB = 1024L * 1024L;
    @Test
    public void absentBaselineProducesSortedArchiveRecipeWithImplicitDirs()
            throws Exception {
        Baseline baseline = absent("ghost");
        NarInstallPlan plan = plan("ghost", "ghost", "0",
                wrapper(0, "bundle/"),
                archiveFile(1, "install.txt", 4),
                archiveFile(2, "Shell/Master/a.txt", 7));
        NarRetainedOverlayPolicy.Recipe recipe =
                success(plan, baseline);
        assertSame(baseline.manifest, recipe.baselineManifest());
        assertArrayEquals(baseline.manifest.getFingerprint(),
                recipe.baselineFingerprint());
        assertEntries(recipe,
                "Shell:DIRECTORY:DIRECTORY:-1:-1",
                "Shell/Master:DIRECTORY:DIRECTORY:-1:-1",
                "Shell/Master/a.txt:FILE:ARCHIVE:0:2",
                "install.txt:FILE:ARCHIVE:1:1");
        assertEquals(2, recipe.fileCount());
        assertEquals(11, recipe.totalSize());
    }
    @Test
    public void archiveWinsExactPathsWhileUnlistedFilesAndEmptyDirsSurvive()
            throws Exception {
        Baseline baseline = present("ghost",
                directory("empty"),
                directory("shell"),
                retainedFile("shell/master.txt", 3, 0),
                retainedFile("user.txt", 5, 1));
        NarInstallPlan plan = plan("ghost", "ghost", "0",
                archiveFile(0, "shell/master.txt", 9));
        NarRetainedOverlayPolicy.Recipe recipe =
                success(plan, baseline);
        assertEntries(recipe,
                "empty:DIRECTORY:DIRECTORY:-1:-1",
                "shell:DIRECTORY:DIRECTORY:-1:-1",
                "shell/master.txt:FILE:ARCHIVE:0:0",
                "user.txt:FILE:RETAINED:1:1");
        assertEquals(14, recipe.totalSize());
        assertArrayEquals(digest(1),
                recipe.entries().get(3).sha256());
    }
    @Test
    public void archiveFileCannotEraseNonemptyRetainedDirectory()
            throws Exception {
        Baseline nonempty = present("ghost",
                directory("assets"),
                retainedFile("assets/user.txt", 1, 0));
        assertError("INCOMPATIBLE_GHOST_UPDATE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0",
                                archiveFile(0, "assets", 2)),
                        nonempty.manifest, nonempty.inventory));
        Baseline empty = present("ghost", directory("assets"));
        NarRetainedOverlayPolicy.Recipe allowed = success(
                plan("ghost", "ghost", "0",
                        archiveFile(0, "assets", 2)), empty);
        assertEntries(allowed, "assets:FILE:ARCHIVE:0:0");
    }
    @Test
    public void archiveDirectoryMayReplaceRetainedFile()
            throws Exception {
        Baseline baseline = present("ghost",
                retainedFile("shell", 2, 0));
        assertEntries(success(plan("ghost", "ghost", "0",
                        archiveDirectory(0, "shell"),
                        archiveFile(1, "shell/a", 1)), baseline),
                "shell:DIRECTORY:DIRECTORY:-1:-1",
                "shell/a:FILE:ARCHIVE:0:1");
        assertEntries(success(plan("ghost", "ghost", "0",
                        archiveFile(0, "shell/a", 1)), baseline),
                "shell:DIRECTORY:DIRECTORY:-1:-1",
                "shell/a:FILE:ARCHIVE:0:0");
    }
    @Test
    public void spellingTakeoverNeverSilentlyRenamesSurvivingUserPaths()
            throws Exception {
        Baseline file = present("ghost",
                retainedFile("Readme", 1, 0));
        assertEntries(success(plan("ghost", "ghost", "0",
                        archiveFile(0, "README", 2)), file),
                "README:FILE:ARCHIVE:0:0");
        Baseline empty = present("ghost", directory("Foo"));
        assertEntries(success(plan("ghost", "ghost", "0",
                        archiveDirectory(0, "foo")), empty),
                "foo:DIRECTORY:DIRECTORY:-1:-1");
        Baseline survivor = present("ghost",
                directory("Foo"),
                retainedFile("Foo/user", 1, 0));
        assertError("INCOMPATIBLE_GHOST_UPDATE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0",
                                archiveFile(0, "foo/new", 1)),
                        survivor.manifest, survivor.inventory));
        Baseline replaced = present("ghost",
                directory("Foo"),
                retainedFile("Foo/old", 1, 0));
        assertEntries(success(plan("ghost", "ghost", "0",
                        archiveFile(0, "foo/old", 2)), replaced),
                "foo:DIRECTORY:DIRECTORY:-1:-1",
                "foo/old:FILE:ARCHIVE:0:0");
    }
    @Test
    public void unicodeAndCaseCollisionsUseOneCrossTreeKey()
            throws Exception {
        String nfc = "caf\u00e9";
        String nfd = "cafe\u0301";
        Baseline baseline = present("ghost",
                retainedFile(nfd, 1, 0));
        assertEntries(success(plan("ghost", "ghost", "0",
                        archiveFile(0, nfc, 2)), baseline),
                nfc + ":FILE:ARCHIVE:0:0");
        Baseline nested = present("ghost",
                directory(nfc),
                retainedFile(nfc + "/user", 1, 0));
        assertError("INCOMPATIBLE_GHOST_UPDATE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0",
                                archiveFile(0, "CAF\u00c9/new", 1)),
                        nested.manifest, nested.inventory));
        assertError("MALFORMED_PLAN", build(
                plan("ghost", "ghost", "0",
                        archiveFile(0, nfd, 1)), absent("ghost")));
    }
    @Test
    public void rejectsUnsupportedDescriptorTargetAndMalformedPlan()
            throws Exception {
        Baseline baseline = absent("ghost");
        assertError("UNSUPPORTED_TYPE", build(
                plan("ghost", "shell", "0"), baseline));
        assertError("UNSUPPORTED_REFRESH", build(
                plan("ghost", "ghost", "1"), baseline));
        assertError("MALFORMED_PLAN", build(
                descriptorPlan("ghost", "shell", null), baseline));
        assertError("UNSUPPORTED_REFRESH", build(
                descriptorPlan("ghost", "ghost",
                        "plugin0.refresh"), baseline));
        assertError("TARGET_MISMATCH", build(
                plan("other", "ghost", "0"), baseline));
        assertError("MALFORMED_PLAN", build(
                plan("ghost", "ghost", "0",
                        archiveFile(1, "a", 1)), baseline));
        assertError("MALFORMED_PLAN", build(
                plan("ghost", "ghost", "0",
                        archiveFile(0, "A", 1),
                        archiveFile(1, "a", 1)), baseline));
        assertError("MALFORMED_BASELINE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0"),
                        baseline.manifest, null));
        Baseline first = present("ghost",
                retainedFile("first", 1, 0));
        Baseline second = present("ghost",
                retainedFile("second", 1, 0));
        assertError("MALFORMED_BASELINE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0"),
                        first.manifest, second.inventory));
        assertError("MALFORMED_BASELINE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0"),
                        baseline.manifest, first.inventory));
        Baseline twoFiles = present("ghost",
                retainedFile("a", 1, 0),
                retainedFile("b", 1, 1));
        List<NarStagedTreeInventory.Entry> duplicateOrdinals =
                new ArrayList<NarStagedTreeInventory.Entry>();
        duplicateOrdinals.add(twoFiles.inventory.get(0));
        duplicateOrdinals.add(
                present("ghost", retainedFile("b", 1, 0))
                        .inventory.get(0));
        assertError("MALFORMED_BASELINE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0"),
                        twoFiles.manifest, duplicateOrdinals));
        assertError("MALFORMED_BASELINE",
                NarRetainedOverlayPolicy.build(
                        plan("ghost", "ghost", "0"),
                        first.manifest, Arrays.asList(
                                inventoryEntry(null, 1, 0))));
    }
    @Test
    public void mergedLimitsAreAppliedAfterReplacement()
            throws Exception {
        Baseline baseline = present(
                "ghost", rootFiles(10000, 0));
        assertEquals(10000,
                success(plan("ghost", "ghost", "0"), baseline)
                        .entries().size());
        assertError("ENTRY_COUNT_LIMIT", build(
                plan("ghost", "ghost", "0",
                        archiveFile(0, "extra", 0)), baseline));
        NarArchiveInventory.Entry[] expanded =
                new NarArchiveInventory.Entry[5001];
        for (int index = 0; index < expanded.length; index++) {
            expanded[index] = archiveFile(index,
                    String.format("p%05d/file", index), 0);
        }
        assertError("ENTRY_COUNT_LIMIT", build(
                plan("ghost", "ghost", "0", expanded),
                absent("ghost")));
        assertError("FILE_SIZE_LIMIT", build(
                plan("ghost", "ghost", "0",
                        archiveFile(0, "large", 128L * MIB + 1)),
                absent("ghost")));
        NarRetainedOverlayPolicy.Recipe unknown = success(
                plan("ghost", "ghost", "0",
                        archiveFile(0, "unknown", -1)),
                absent("ghost"));
        assertFalse(unknown.hasKnownTotalSize());
        assertEquals(-1, unknown.totalSize());
        assertEquals(-1, unknown.entries().get(0).size());
        Baseline full = present("ghost",
                retainedFile("a", 128L * MIB, 0),
                retainedFile("b", 128L * MIB, 1),
                retainedFile("c", 128L * MIB, 2),
                retainedFile("d", 128L * MIB, 3));
        assertError("TOTAL_SIZE_LIMIT", build(
                plan("ghost", "ghost", "0",
                        archiveFile(0, "extra", 1)), full));
        assertTrue(build(plan("ghost", "ghost", "0",
                archiveFile(0, "a", 1)), full).isSuccess());
    }
    @Test
    public void outputIsImmutableNonAuthorizingAndDefensive()
            throws Exception {
        Baseline baseline = present("ghost",
                retainedFile("user", 1, 0));
        NarRetainedOverlayPolicy.Recipe recipe =
                success(plan("ghost", "ghost", "0"), baseline);
        byte[] fingerprint = recipe.baselineFingerprint();
        byte[] digest = recipe.entries().get(0).sha256();
        fingerprint[0] ^= 1;
        digest[0] ^= 1;
        assertArrayEquals(baseline.manifest.getFingerprint(),
                recipe.baselineFingerprint());
        assertArrayEquals(digest(0), recipe.entries().get(0).sha256());
        try {
            recipe.entries().clear();
            throw new AssertionError("mutable recipe");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, recipe.entries().size());
        }
        assertFalse(Modifier.isPublic(
                NarRetainedOverlayPolicy.class.getModifiers()));
        for (Class<?> type : policyTypes()) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()));
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(File.class.equals(method.getReturnType()));
                assertFalse(InputStream.class.isAssignableFrom(
                        method.getReturnType()));
                assertFalse(OutputStream.class.isAssignableFrom(
                        method.getReturnType()));
                String name = method.getName().toLowerCase();
                for (String forbidden : Arrays.asList(
                        "path", "token", "handle", "open", "write",
                        "copy", "commit", "publish", "session", "claim")) {
                    assertFalse(name, name.contains(forbidden));
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(File.class.equals(parameter));
                    assertFalse(InputStream.class.isAssignableFrom(parameter));
                    assertFalse(OutputStream.class.isAssignableFrom(parameter));
                    assertFalse(parameter.getName().contains("Claim"));
                }
            }
        }
        assertNotSame(recipe.baselineFingerprint(),
                recipe.baselineFingerprint());
    }
    private static NarRetainedOverlayPolicy.Result build(
            NarInstallPlan plan, Baseline baseline) {
        return NarRetainedOverlayPolicy.build(
                plan, baseline.manifest, baseline.inventory);
    }
    private static NarRetainedOverlayPolicy.Recipe success(NarInstallPlan plan, Baseline baseline) {
        NarRetainedOverlayPolicy.Result result = build(plan, baseline);
        assertTrue(result.detail(), result.isSuccess());
        return result.recipe();
    }
    private static void assertError(String expected, NarRetainedOverlayPolicy.Result result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.error().name());
        assertFalse(result.detail().isEmpty());
    }
    private static void assertEntries(NarRetainedOverlayPolicy.Recipe recipe, String... expected) {
        List<String> actual = new ArrayList<String>();
        for (NarRetainedOverlayPolicy.Entry entry : recipe.entries()) {
            actual.add(entry.logicalName() + ":" + entry.type().name() + ":"
                    + entry.source().name() + ":"
                    + entry.finalFileOrdinal() + ":"
                    + entry.sourceOrdinal());
        }
        assertEquals(Arrays.asList(expected), actual);
    }
    private static List<Class<?>> policyTypes() {
        List<Class<?>> result = new ArrayList<Class<?>>();
        result.add(NarRetainedOverlayPolicy.class);
        result.addAll(Arrays.asList(
                NarRetainedOverlayPolicy.class.getDeclaredClasses()));
        return result;
    }
    private static NarInstallPlan plan(String target, String type, String refresh,
            NarArchiveInventory.Entry... entries) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("type", type); metadata.put("name", "Ghost");
        metadata.put("directory", target); metadata.put("refresh", refresh);
        NarInstallDescriptor descriptor = new NarInstallDescriptor(
                type, "Ghost", target, target, null, metadata);
        NarArchiveInventory inventory = new NarArchiveInventory(
                Arrays.asList(entries), null, 0, 0);
        return new NarInstallPlan(0, digest(9), inventory, descriptor,
                new File("ignored-root"), new File("ignored-target"));
    }
    private static NarInstallPlan descriptorPlan(String type, String metadataType,
            String compoundKey) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("type", metadataType); metadata.put("name", "Ghost");
        metadata.put("directory", "ghost"); metadata.put("refresh", "0");
        if (compoundKey != null) metadata.put(compoundKey, "1");
        NarInstallDescriptor descriptor = new NarInstallDescriptor(
                type, "Ghost", "ghost", "ghost", null, metadata);
        return new NarInstallPlan(0, digest(9),
                new NarArchiveInventory(
                        Collections.<NarArchiveInventory.Entry>emptyList(),
                        null, 0, 0),
                descriptor, new File("ignored"), new File("ignored"));
    }
    private static NarArchiveInventory.Entry wrapper(int ordinal, String path) {
        return archive(ordinal, path, null, true, 0);
    }
    private static NarArchiveInventory.Entry archiveDirectory(int ordinal, String path) {
        return archive(ordinal, path + "/", path, true, 0);
    }
    private static NarArchiveInventory.Entry archiveFile(int ordinal, String path, long size) {
        return archive(ordinal, path, path, false, size);
    }
    private static NarArchiveInventory.Entry archive(int ordinal, String raw, String relative,
            boolean directory, long size) {
        return new NarArchiveInventory.Entry(ordinal, raw, raw, relative,
                directory, 0, directory ? 0 : 8, size, size);
    }
    private static Baseline absent(String target) {
        NarStagedTreeInventory.Result result =
                NarStagedTreeInventory.absent(target, 1, 2);
        assertTrue(result.detail(), result.isSuccess());
        return new Baseline(result.manifest(), result.entries());
    }
    private static Baseline present(String target, BaselineEntry... entries) {
        return present(target, Arrays.asList(entries));
    }
    private static Baseline present(
            String target, List<BaselineEntry> entries) {
        String[] paths = new String[entries.size()];
        int[] types = new int[entries.size()];
        long[] sizes = new long[entries.size()];
        int[] ordinals = new int[entries.size()];
        byte[] digests = new byte[entries.size() * 32];
        for (int index = 0; index < entries.size(); index++) {
            BaselineEntry entry = entries.get(index);
            paths[index] = entry.path; sizes[index] = entry.size;
            types[index] = entry.directory ? 2 : 1;
            ordinals[index] = entry.directory ? -1 : entry.ordinal;
            if (!entry.directory) {
                System.arraycopy(digest(entry.ordinal), 0,
                        digests, index * 32, 32);
            }
        }
        NarStagedTreeInventory.Result result =
                NarStagedTreeInventory.present(target,
                        new NarStagedTreeInventory.Description(
                                1, 2, paths, types, sizes,
                                ordinals, digests));
        assertTrue(result.detail(), result.isSuccess());
        return new Baseline(result.manifest(), result.entries());
    }
    private static BaselineEntry directory(String path) {
        return new BaselineEntry(path, true, 0, -1);
    }
    private static BaselineEntry retainedFile(String path, long size, int ordinal) {
        return new BaselineEntry(path, false, size, ordinal);
    }
    private static List<BaselineEntry> rootFiles(int count, long size) {
        List<BaselineEntry> result = new ArrayList<BaselineEntry>();
        for (int index = 0; index < count; index++)
            result.add(retainedFile(
                    String.format("f%05d", index), size, index));
        return result;
    }
    private static byte[] digest(int value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(new byte[] {(byte) value});
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
    private static NarStagedTreeInventory.Entry inventoryEntry(
            String name, long size, int ordinal) throws Exception {
        Constructor<NarStagedTreeInventory.Entry> constructor =
                NarStagedTreeInventory.Entry.class.getDeclaredConstructor(
                        String.class, NarGhostTreePolicy.Type.class,
                        long.class, int.class, byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(name,
                NarGhostTreePolicy.Type.FILE, size, ordinal, digest(ordinal));
    }
    private static final class Baseline {
        private final NarGhostTreePolicy.Manifest manifest;
        private final List<NarStagedTreeInventory.Entry> inventory;
        private Baseline(NarGhostTreePolicy.Manifest manifest,
                List<NarStagedTreeInventory.Entry> inventory) {
            this.manifest = manifest; this.inventory = inventory;
        }
    }
    private static final class BaselineEntry {
        private final String path;
        private final boolean directory;
        private final long size;
        private final int ordinal;
        private BaselineEntry(String path, boolean directory, long size, int ordinal) {
            this.path = path; this.directory = directory;
            this.size = size; this.ordinal = ordinal;
        }
    }
}
