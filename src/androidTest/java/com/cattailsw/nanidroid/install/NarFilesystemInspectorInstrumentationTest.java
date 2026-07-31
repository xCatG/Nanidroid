package com.cattailsw.nanidroid.install;

import android.test.InstrumentationTestCase;
import android.os.Build;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class NarFilesystemInspectorInstrumentationTest
        extends InstrumentationTestCase {
    private static final int BULK_FILES = 96;
    private File fixtureRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        fixtureRoot = new File(
                getInstrumentation().getTargetContext().getCacheDir(),
                "narfs-device-" + System.nanoTime());
        assertTrue("Could not create fixture root", fixtureRoot.mkdirs());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            deleteRecursively(fixtureRoot);
        } finally {
            super.tearDown();
        }
    }

    public void testArm64NativeFilesystemContract() throws Exception {
        assertSelectedAarch64Library();

        File emptyTarget = directory(fixtureRoot, "empty-target");
        File tree = directory(fixtureRoot, "tree");
        directory(tree, "a-empty");
        File unicode = directory(tree, "b-\u96ea");
        write(new File(unicode, "nested-\ud83d\ude00.bin"),
                new byte[] {0, 1, (byte) 0xfe, (byte) 0xff});
        write(new File(tree, "c.bin"), new byte[] {7, 8, 9});
        File bulk = directory(tree, "d-bulk");
        for (int index = 0; index < BULK_FILES; index++) {
            write(new File(bulk, "f" + threeDigits(index) + ".bin"),
                    new byte[] {(byte) index});
        }

        NarFilesystemInspector inspector = new NarFilesystemInspector();
        NarFilesystemInspector.TrustedRoot trusted =
                new NarFilesystemInspector.TrustedRoot(
                        fixtureRoot.getAbsolutePath());

        NarFilesystemInspector.Result absent =
                inspector.inspect(trusted, "missing");
        assertResult(absent, NarFilesystemInspector.State.ABSENT, 0, 0);

        NarFilesystemInspector.Result empty =
                inspector.inspect(trusted, emptyTarget.getName());
        assertResult(empty, NarFilesystemInspector.State.PRESENT, 0, 0);

        NarFilesystemInspector.Result invalid =
                inspector.inspect(trusted, "tree/nested");
        assertEquals(NarFilesystemInspector.State.ERROR, invalid.state());
        assertEquals(
                NarFilesystemInspector.Error.INVALID_TARGET, invalid.error());
        assertEquals(0, invalid.entryCount());

        NarFilesystemInspector.Result present =
                inspector.inspect(trusted, tree.getName());
        assertResult(
                present,
                NarFilesystemInspector.State.PRESENT,
                BULK_FILES + 5,
                BULK_FILES + 7);
        List<NarFilesystemInspector.Entry> entries = present.entries();
        assertEntry(entries.get(0), "a-empty",
                NarFilesystemInspector.Type.DIRECTORY, 0);
        assertEntry(entries.get(1), "b-\u96ea",
                NarFilesystemInspector.Type.DIRECTORY, 0);
        assertEntry(entries.get(2), "b-\u96ea/nested-\ud83d\ude00.bin",
                NarFilesystemInspector.Type.FILE, 4);
        assertEntry(entries.get(3), "c.bin",
                NarFilesystemInspector.Type.FILE, 3);
        assertEntry(entries.get(4), "d-bulk",
                NarFilesystemInspector.Type.DIRECTORY, 0);
        for (int index = 0; index < BULK_FILES; index++) {
            assertEntry(entries.get(index + 5),
                    "d-bulk/f" + threeDigits(index) + ".bin",
                    NarFilesystemInspector.Type.FILE, 1);
        }
        try {
            entries.clear();
            fail("Native result entries must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        for (int repeat = 0; repeat < 64; repeat++) {
            NarFilesystemInspector.Result repeated =
                    inspector.inspect(trusted, tree.getName());
            assertResult(
                    repeated,
                    NarFilesystemInspector.State.PRESENT,
                    BULK_FILES + 5,
                    BULK_FILES + 7);
            assertEquals("a-empty", repeated.entries().get(0).path());
            assertEquals(
                    "d-bulk/f095.bin",
                    repeated.entries().get(BULK_FILES + 4).path());
        }
    }

    private void assertSelectedAarch64Library() throws Exception {
        String abi = Build.SUPPORTED_ABIS[0];
        File apk = new File(getInstrumentation()
                .getTargetContext().getApplicationInfo().sourceDir);
        byte[] header = new byte[20];
        int offset = 0;
        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry library = zip.getEntry("lib/" + abi + "/libnarfs.so");
            assertNotNull("Selected narfs APK entry is missing: " + abi, library);
            InputStream input = zip.getInputStream(library);
            try {
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) break;
                offset += count;
            }
            } finally { input.close(); }
        } finally { zip.close();
        }
        assertEquals(header.length, offset);
        assertEquals(0x7f, header[0] & 0xff);
        assertEquals('E', header[1]);
        assertEquals('L', header[2]);
        assertEquals('F', header[3]);
        assertEquals(2, header[4]);
        assertEquals(1, header[5]);
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        assertEquals(expectedElfMachine(abi), machine);
    }

    private static int expectedElfMachine(String abi) {
        if ("arm64-v8a".equals(abi)) return 183;
        if ("x86_64".equals(abi)) return 62;
        throw new AssertionError("Unsupported runtime ABI: " + abi);
    }

    private static void assertResult(
            NarFilesystemInspector.Result result,
            NarFilesystemInspector.State state,
            int count,
            long total) {
        assertEquals(state, result.state());
        assertEquals(NarFilesystemInspector.Error.OK, result.error());
        assertEquals(
                NarFilesystemInspector.Error.OK, result.cleanupError());
        assertEquals(count, result.entryCount());
        assertEquals(total, result.totalFileSize());
        assertEquals(count, result.entries().size());
    }

    private static void assertEntry(
            NarFilesystemInspector.Entry entry,
            String path,
            NarFilesystemInspector.Type type,
            long size) {
        assertEquals(path, entry.path());
        assertEquals(type, entry.type());
        assertEquals(size, entry.size());
        assertTrue(entry.device() > 0);
        assertTrue(entry.inode() > 0);
    }

    private static File directory(File parent, String name) {
        File value = new File(parent, name);
        assertTrue("Could not create " + value, value.mkdir());
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

    private static String threeDigits(int value) {
        if (value < 10) return "00" + value;
        if (value < 100) return "0" + value;
        return String.valueOf(value);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        assertTrue("Could not delete " + file, file.delete());
    }
}
