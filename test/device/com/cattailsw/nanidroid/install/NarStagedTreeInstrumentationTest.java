package com.cattailsw.nanidroid.install;

import android.content.Context;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public final class NarStagedTreeInstrumentationTest
        extends InstrumentationTestCase {
    private Context context;
    private File fixtureRoot;
    private File stagingRoot;
    private Set<String> stagingBaseline;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        File fixtures = context.getDir(
                "narfs-fixtures-v1", Context.MODE_PRIVATE);
        fixtureRoot = new File(
                fixtures, "run-" + System.nanoTime());
        assertTrue(fixtureRoot.mkdir());
        stagingRoot = context.getDir(
                "narfs-stage-v1", Context.MODE_PRIVATE);
        stagingBaseline = children(stagingRoot);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            deleteRecursively(fixtureRoot);
            assertEquals(stagingBaseline, children(stagingRoot));
        } finally {
            super.tearDown();
        }
    }

    public void testPresentAbsentInventoryAndTreeClaimTransfer()
            throws Exception {
        NarStagedTree.Session session =
                new NarStagedTree.Stager().session(context);
        NarFilesystemInspector.TrustedRoot trusted =
                new NarFilesystemInspector.TrustedRoot(
                fixtureRoot.getAbsolutePath());
        NarStagedTree.Tree absent =
                success(session.stage(trusted, "missing"));
        assertEquals(NarGhostTreePolicy.State.ABSENT,
                absent.manifest().getState());
        assertTrue(absent.entries().isEmpty());
        assertEquals(NarStagedTree.Error.OK, absent.discard());

        File ghost = directory(fixtureRoot, "ghost");
        File unicode = directory(ghost, "dir-\u96ea");
        byte[] content = new byte[] {0, 1, (byte) 0xfe, (byte) 0xff};
        write(new File(unicode, "nested-\ud83d\ude00.bin"), content);
        write(new File(ghost, "manifest.txt"), new byte[] {7, 8, 9});
        NarStagedTree.Tree tree = success(session.stage(trusted, "ghost"));
        NarGhostTreePolicy.Manifest manifest = tree.manifest();
        assertEquals("ghost", manifest.getTargetId());
        assertEquals(NarGhostTreePolicy.State.PRESENT,
                manifest.getState());
        assertEquals(3, manifest.getEntries().size());
        assertEquals(16, manifest.getStorageRootIdentity().length);
        assertEquals(1, manifest.getFingerprintVersion());
        assertEquals(32, manifest.getFingerprint().length);
        NarStagedTreeInventory.Entry nested =
                entry(tree, "dir-\u96ea/nested-\ud83d\ude00.bin");
        assertEquals(NarGhostTreePolicy.Type.FILE, nested.type());
        assertEquals(content.length, nested.size());
        assertTrue(nested.blobOrdinal() >= 0);
        assertTrue(Arrays.equals(
                MessageDigest.getInstance("SHA-256").digest(content),
                nested.sha256()));

        NarStagedTree.ConsumeResult consumed = session.consume(tree);
        assertTrue(consumed.isSuccess());
        assertEquals(NarStagedTree.Error.CONSUMED,
                session.consume(tree).error());
        assertEquals(NarStagedTree.Error.CONSUMED, tree.discard());
        assertEquals(NarStagedTree.Error.OK,
                consumed.claim().discard());
        assertEquals(NarStagedTree.Error.OK,
                consumed.claim().discard());
        assertEquals(stagingBaseline, children(stagingRoot));
    }

    public void testInodeMismatchFailureRetriesAndMalformedTokenRejects()
            throws Exception {
        File ghost = directory(fixtureRoot, "retry");
        write(new File(ghost, "file"), new byte[] {1});
        NarStagedTree.Session session =
                new NarStagedTree.Stager().session(context);
        NarFilesystemInspector.TrustedRoot trusted =
                new NarFilesystemInspector.TrustedRoot(
                fixtureRoot.getAbsolutePath());
        NarStagedTree.Claim claim = session.consume(
                success(session.stage(trusted, "retry"))).claim();
        File real = onlyNewChild(stagingBaseline);
        File held = new File(stagingRoot, real.getName() + ".held");
        File replacement = new File(stagingRoot, real.getName());
        try {
            assertTrue(real.renameTo(held));
            assertTrue(replacement.mkdir());
            assertEquals(NarStagedTree.Error.TREE_CHANGED,
                    claim.discard());
            assertTrue(replacement.delete());
            assertTrue(held.renameTo(real));
            assertEquals(NarStagedTree.Error.OK, claim.discard());
            assertEquals(NarStagedTree.Error.OK, claim.discard());
        } finally {
            deleteRecursively(replacement);
            if (held.exists() && !real.exists()) {
                assertTrue(held.renameTo(real));
            }
            claim.discard();
            assertEquals(stagingBaseline, children(stagingRoot));
        }

        Method discard = NarStagedTree.class.getDeclaredMethod(
                "nativeDiscard", String.class, byte[].class);
        discard.setAccessible(true);
        assertEquals(100, ((Integer) discard.invoke(
                null, stagingRoot.getAbsolutePath(), new byte[88])).intValue());
    }

    public void testPolicyFailureAutomaticallyCleansNativeSession()
            throws Exception {
        File ghost = directory(fixtureRoot, "collision");
        write(new File(ghost, "\u00e9"), new byte[] {1});
        write(new File(ghost, "e\u0301"), new byte[] {2});
        NarStagedTree.Session session =
                new NarStagedTree.Stager().session(context);
        NarStagedTree.StageResult result = session.stage(
                new NarFilesystemInspector.TrustedRoot(
                fixtureRoot.getAbsolutePath()), "collision");
        assertFalse(result.isSuccess());
        assertEquals(NarStagedTree.Error.POLICY, result.error());
        assertEquals(NarStagedTree.Error.OK,
                result.cleanup().discardError());
        assertEquals(NarStagedTree.Error.OK, result.cleanup().discard());
        assertEquals(stagingBaseline, children(stagingRoot));
    }

    private NarStagedTreeInventory.Entry entry(
            NarStagedTree.Tree tree, String path) {
        for (NarStagedTreeInventory.Entry value : tree.entries()) {
            if (path.equals(value.path())) return value;
        }
        fail("Missing inventory path: " + path);
        return null;
    }

    private File onlyNewChild(Set<String> before) {
        Set<String> added = children(stagingRoot);
        added.removeAll(before);
        assertEquals(1, added.size());
        return new File(stagingRoot, added.iterator().next());
    }

    private static NarStagedTree.Tree success(
            NarStagedTree.StageResult result) {
        assertTrue(result.detail(), result.isSuccess());
        return result.tree();
    }

    private static Set<String> children(File root) {
        String[] names = root.list();
        assertNotNull(names);
        return new TreeSet<String>(Arrays.asList(names));
    }

    private static File directory(File parent, String name) {
        File value = new File(parent, name);
        assertTrue(value.mkdir());
        return value;
    }

    private static void write(File file, byte[] value) throws Exception {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(value);
        } finally {
            output.close();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] values = file.listFiles();
            if (values != null) {
                for (File value : values) deleteRecursively(value);
            }
        }
        assertTrue(file.delete());
    }
}
