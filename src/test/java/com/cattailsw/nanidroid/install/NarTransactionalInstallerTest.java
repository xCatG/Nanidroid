package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

/** End-to-end contract for the fresh-install-only NAR transaction. */
public final class NarTransactionalInstallerTest {
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");

    @Test
    public void installsValidatedArchiveAsOneNewGhostDirectory()
            throws Exception {
        File root = temporaryDirectory("transaction-root");
        File archive = zip(
                "bundle/install.txt", descriptor("ignored"),
                "bundle/ghost/master.txt", bytes("hello"),
                "bundle/shell/master.txt", bytes("world"));

        NarTransactionalInstaller.Result result =
                NarTransactionalInstaller.install(archive, root, "forced-id");

        assertTrue(result.isSuccess());
        assertEquals("forced-id", result.getTargetId());
        assertEquals(new File(root, "forced-id").getCanonicalFile(),
                result.getInstalledDirectory());
        assertArrayEquals(bytes("hello"), read(
                new File(root, "forced-id/ghost/master.txt")));
        assertArrayEquals(bytes("world"), read(
                new File(root, "forced-id/shell/master.txt")));
        assertFalse(new File(root, ".nanidroid-install-staging").exists());
    }

    @Test
    public void rejectsExistingTargetWithoutChangingItOrCreatingStaging()
            throws Exception {
        File root = temporaryDirectory("transaction-existing");
        File existing = new File(root, "ghost-id");
        assertTrue(existing.mkdir());
        write(new File(existing, "keep.txt"), bytes("keep"));
        File archive = zip(
                "install.txt", descriptor("ghost-id"),
                "ghost/master.txt", bytes("replacement"));

        NarTransactionalInstaller.Result result =
                NarTransactionalInstaller.install(archive, root, null);

        assertFalse(result.isSuccess());
        assertEquals(NarTransactionalInstaller.Error.TARGET_EXISTS,
                result.getError());
        assertArrayEquals(bytes("keep"), read(new File(existing, "keep.txt")));
        assertFalse(new File(root, ".nanidroid-install-staging").exists());
    }

    @Test
    public void invalidArchiveLeavesNoTargetOrStagingResidue()
            throws Exception {
        File root = temporaryDirectory("transaction-invalid");
        File archive = zip(
                "install.txt", descriptor("ghost-id"),
                "../outside.txt", bytes("bad"));

        NarTransactionalInstaller.Result result =
                NarTransactionalInstaller.install(archive, root, null);

        assertFalse(result.isSuccess());
        assertEquals(NarTransactionalInstaller.Error.ARCHIVE_REJECTED,
                result.getError());
        assertFalse(new File(root, "ghost-id").exists());
        assertFalse(new File(root, ".nanidroid-install-staging").exists());
    }

    @Test
    public void corruptLocalArchiveLeavesNoPartialStateAndValidRetrySucceeds()
            throws Exception {
        File root = temporaryDirectory("transaction-retry");
        File interrupted = File.createTempFile("nar-interrupted", ".nar");
        write(interrupted, bytes("incomplete archive transfer"));

        NarTransactionalInstaller.Result rejected =
                NarTransactionalInstaller.install(interrupted, root, "retry-id");

        assertFalse(rejected.isSuccess());
        assertEquals(NarTransactionalInstaller.Error.ARCHIVE_REJECTED,
                rejected.getError());
        assertFalse(new File(root, "retry-id").exists());
        assertFalse(new File(root, ".nanidroid-install-staging").exists());

        File retry = zip(
                "install.txt", descriptor("retry-id"),
                "ghost/master.txt", bytes("recovered"));
        NarTransactionalInstaller.Result installed =
                NarTransactionalInstaller.install(retry, root, null);

        assertTrue(installed.isSuccess());
        assertEquals("retry-id", installed.getTargetId());
        assertArrayEquals(bytes("recovered"), read(
                new File(root, "retry-id/ghost/master.txt")));
        assertFalse(new File(root, ".nanidroid-install-staging").exists());
    }

    @Test
    public void insufficientSpaceDuringExtractionLeavesNoPartialStateAndRetrySucceeds()
            throws Exception {
        File root = temporaryDirectory("transaction-no-space");
        File archive = zip(
                "install.txt", descriptor("space-id"),
                "ghost/master.txt", bytes("payload"));

        NarTransactionalInstaller.Result failed = NarTransactionalInstaller.install(
                archive, root, null, failingOutput("no space left on device"));

        assertFailureLeavesNoPartialState(
                failed, NarTransactionalInstaller.Error.EXTRACTION_FAILED, root, "space-id");
        assertSuccessfulRetry(archive, root, "space-id");
    }

    @Test
    public void extractionIoFailureLeavesNoPartialStateAndRetrySucceeds()
            throws Exception {
        File root = temporaryDirectory("transaction-io");
        File archive = zip(
                "install.txt", descriptor("io-id"),
                "ghost/master.txt", bytes("payload"));

        NarTransactionalInstaller.Result failed = NarTransactionalInstaller.install(
                archive, root, null, failingOutput("simulated write failure"));

        assertFailureLeavesNoPartialState(
                failed, NarTransactionalInstaller.Error.EXTRACTION_FAILED, root, "io-id");
        assertSuccessfulRetry(archive, root, "io-id");
    }

    @Test
    public void publishFailureLeavesNoPartialStateAndRetrySucceeds()
            throws Exception {
        File root = temporaryDirectory("transaction-publish");
        File archive = zip(
                "install.txt", descriptor("publish-id"),
                "ghost/master.txt", bytes("payload"));

        NarTransactionalInstaller.Result failed = NarTransactionalInstaller.install(
                archive, root, null, refusingPublish());

        assertFailureLeavesNoPartialState(
                failed, NarTransactionalInstaller.Error.PUBLISH_FAILED, root, "publish-id");
        assertSuccessfulRetry(archive, root, "publish-id");
    }

    @Test
    public void failureIsCategorizedForUserFacingErrorMapping() throws Exception {
        File root = temporaryDirectory("transaction-missing");
        NarTransactionalInstaller.Result result =
                NarTransactionalInstaller.install(
                        new File(root, "missing.nar"), root, null);

        assertFalse(result.isSuccess());
        assertEquals(NarTransactionalInstaller.Error.SOURCE_UNAVAILABLE,
                result.getError());
        assertNull(result.getInstalledDirectory());
        assertTrue(result.getMessage().length() > 0);
    }

    private static File temporaryDirectory(String label) throws IOException {
        File file = File.createTempFile(label, "");
        if (!file.delete() || !file.mkdir()) throw new IOException("temporary root");
        return file;
    }

    private static NarTransactionalInstaller.FileOperations failingOutput(
            final String message) {
        return new NarTransactionalInstaller.FileOperations() {
            @Override
            public FileOutputStream openOutput(File file) throws IOException {
                throw new IOException(message);
            }

            @Override
            public boolean rename(File source, File destination) {
                return source.renameTo(destination);
            }
        };
    }

    private static NarTransactionalInstaller.FileOperations refusingPublish() {
        return new NarTransactionalInstaller.FileOperations() {
            @Override
            public FileOutputStream openOutput(File file) throws IOException {
                return new FileOutputStream(file);
            }

            @Override
            public boolean rename(File source, File destination) {
                return false;
            }
        };
    }

    private static void assertFailureLeavesNoPartialState(
            NarTransactionalInstaller.Result result,
            NarTransactionalInstaller.Error error,
            File root,
            String targetId) {
        assertFalse(result.isSuccess());
        assertEquals(error, result.getError());
        assertFalse(new File(root, targetId).exists());
        assertFalse(new File(root, ".nanidroid-install-staging").exists());
    }

    private static void assertSuccessfulRetry(File archive, File root, String targetId) {
        NarTransactionalInstaller.Result retry =
                NarTransactionalInstaller.install(archive, root, null);
        assertTrue(retry.isSuccess());
        assertEquals(targetId, retry.getTargetId());
        assertFalse(new File(root, ".nanidroid-install-staging").exists());
    }

    private static File zip(Object... values) throws IOException {
        File archive = File.createTempFile("nar-transaction", ".nar");
        ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive));
        try {
            for (int index = 0; index < values.length; index += 2) {
                ZipEntry entry = new ZipEntry((String) values[index]);
                output.putNextEntry(entry);
                byte[] content = (byte[]) values[index + 1];
                if (content != null) output.write(content);
                output.closeEntry();
            }
        } finally {
            output.close();
        }
        return archive;
    }

    private static byte[] descriptor(String id) {
        return ("type,ghost\nname,Test Ghost\ndirectory," + id + "\n")
                .getBytes(SHIFT_JIS);
    }

    private static byte[] bytes(String value) { return value.getBytes(SHIFT_JIS); }

    private static void write(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("parent");
        FileOutputStream output = new FileOutputStream(target);
        try { output.write(content); } finally { output.close(); }
    }

    private static byte[] read(File source) throws IOException {
        java.io.FileInputStream input = new java.io.FileInputStream(source);
        try {
            byte[] content = new byte[(int) source.length()];
            int offset = 0;
            while (offset < content.length) {
                int count = input.read(content, offset, content.length - offset);
                if (count < 0) throw new IOException("unexpected EOF");
                offset += count;
            }
            return content;
        } finally { input.close(); }
    }
}
