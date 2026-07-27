package com.cattailsw.nanidroid.install;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.zip.CRC32;

/**
 * Fresh-install-only transactional NAR installer.
 *
 * <p>It snapshots an untrusted source, validates the exact snapshot, writes
 * only validated relative paths to a sibling candidate directory, then
 * publishes that directory with a same-filesystem rename. Existing target
 * directories are deliberately rejected: refresh/overwrite needs a separate
 * authenticated upgrade transaction.
 */
public final class NarTransactionalInstaller {
    private static final String STAGING_DIRECTORY = ".nanidroid-install-staging";
    private static final long MAX_FILE_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 8192;
    private static final Object INSTALL_LOCK = new Object();

    private NarTransactionalInstaller() {}

    public enum Error {
        SOURCE_UNAVAILABLE,
        INSTALL_ROOT_INVALID,
        ARCHIVE_REJECTED,
        TARGET_EXISTS,
        STAGING_FAILED,
        EXTRACTION_FAILED,
        PUBLISH_FAILED
    }

    public static Result install(File archive, File installRoot, String forcedId) {
        synchronized (INSTALL_LOCK) {
            return installLocked(archive, installRoot, forcedId);
        }
    }

    private static Result installLocked(File archive, File installRoot,
            String forcedId) {
        if (archive == null || !archive.isFile()) {
            return failure(Error.SOURCE_UNAVAILABLE,
                    "The selected ghost archive is no longer available.");
        }
        File root;
        try {
            root = installRoot == null ? null : installRoot.getCanonicalFile();
        } catch (IOException error) {
            root = null;
        }
        if (root == null || !root.isDirectory()) {
            return failure(Error.INSTALL_ROOT_INVALID,
                    "Nanidroid cannot access its ghost storage.");
        }
        File staging = new File(root, STAGING_DIRECTORY);
        if ((!staging.exists() && !staging.mkdir()) || !staging.isDirectory()) {
            return failure(Error.STAGING_FAILED,
                    "Nanidroid could not prepare a private install transaction.");
        }

        File transaction = candidate(staging);
        if (transaction == null) {
            return failure(Error.STAGING_FAILED,
                    "Nanidroid could not prepare a private install transaction.");
        }
        File candidate = null;
        NarVerifiedInstallSession session = null;
        Result result = failure(Error.STAGING_FAILED,
                "Nanidroid could not complete the install transaction.");
        try {
            NarStagedSourceCopyResult copied = NarStagedSource.copy(archive, transaction);
            if (!copied.isSuccess()) {
                result = failure(Error.STAGING_FAILED,
                        "Nanidroid could not safely copy the selected ghost archive.");
            } else {
                NarInstallPlanResult validated = new NarInstallPlanValidator()
                        .validateStaged(copied.getSource(), root, forcedId);
                if (!validated.isSuccess()) {
                    result = failure(Error.ARCHIVE_REJECTED,
                            archiveMessage(validated.getError()));
                } else {
                    NarInstallPlan plan = validated.getPlan();
                    File target = plan.getTargetDirectory();
                    if (target.exists()) {
                        closeQuietly(validated.getVerifiedSession());
                        result = failure(Error.TARGET_EXISTS,
                                "This ghost is already installed. Remove it before installing a new copy.");
                    } else {
                        session = validated.getVerifiedSession();
                        candidate = new File(transaction, "tree");
                        if (!candidate.mkdir()) candidate = null;
                        if (candidate == null) {
                            result = failure(Error.STAGING_FAILED,
                                    "Nanidroid could not prepare the new ghost files.");
                        } else {
                            result = extractAndPublish(session, plan, candidate, target);
                            closeQuietly(session);
                            session = null;
                        }
                    }
                }
            }
        } finally {
            if (session != null) closeQuietly(session);
            if (candidate != null && candidate.exists()) deleteTree(candidate);
            if (transaction.exists()) deleteTree(transaction);
            if (staging.exists() && !staging.delete() && resultCleanupNeeded(staging)) {
                // A candidate/session cleanup error is never allowed to turn an
                // otherwise successful publication into a false negative.
            }
        }
        return result;
    }

    private static boolean resultCleanupNeeded(File staging) {
        return staging.list() != null && staging.list().length != 0;
    }

    private static Result extractAndPublish(NarVerifiedInstallSession session,
            NarInstallPlan plan, File candidate, File target) {
        long[] total = new long[] {0L};
        try {
            for (NarInstallPlan.Entry entry : plan.getEntries()) {
                if (!entry.isInstallEntry()) continue;
                File output = child(candidate, entry.getRelativePath());
                if (output == null) return failure(Error.EXTRACTION_FAILED,
                        "The ghost archive contains an unsafe file path.");
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        return failure(Error.EXTRACTION_FAILED,
                                "Nanidroid could not create a ghost directory.");
                    }
                } else if (!copyEntry(session, entry, output, total)) {
                    return failure(Error.EXTRACTION_FAILED,
                            "The ghost archive could not be extracted safely.");
                }
            }
            session.close();
        } catch (IOException error) {
            return failure(Error.EXTRACTION_FAILED,
                    "The ghost archive could not be extracted safely.");
        } catch (RuntimeException error) {
            return failure(Error.EXTRACTION_FAILED,
                    "The ghost archive could not be extracted safely.");
        }
        if (target.exists() || !candidate.renameTo(target)) {
            return failure(Error.PUBLISH_FAILED,
                    "The ghost files were prepared but could not be published. Please try again.");
        }
        return success(target, plan.getDescriptor().getTargetId());
    }

    private static boolean copyEntry(NarVerifiedInstallSession session,
            NarInstallPlan.Entry entry, File output, long[] total) {
        File parent = output.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) return false;
        InputStream input = null;
        FileOutputStream target = null;
        boolean complete = false;
        try {
            input = session.open(entry);
            target = new FileOutputStream(output);
            byte[] buffer = new byte[BUFFER_SIZE];
            CRC32 crc = new CRC32();
            long fileBytes = 0L;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) break;
                    buffer[0] = (byte) single;
                    count = 1;
                }
                fileBytes += count;
                total[0] += count;
                if (fileBytes > MAX_FILE_BYTES || total[0] > MAX_TOTAL_BYTES) {
                    return false;
                }
                crc.update(buffer, 0, count);
                target.write(buffer, 0, count);
            }
            target.getFD().sync();
            complete = entry.getDeclaredSize() < 0
                    || fileBytes == entry.getDeclaredSize();
            if (complete && entry.getCrc() >= 0) {
                complete = crc.getValue() == entry.getCrc();
            }
            return complete;
        } catch (IOException error) {
            return false;
        } finally {
            closeQuietly(input);
            closeQuietly(target);
            if (!complete) output.delete();
        }
    }

    private static File child(File root, String path) throws IOException {
        File child = new File(root, path).getCanonicalFile();
        return root.equals(child.getParentFile()) || child.getPath().startsWith(
                root.getPath() + File.separator) ? child : null;
    }

    private static File candidate(File staging) {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder name = new StringBuilder("candidate-");
        for (byte value : random) name.append(String.format("%02x", value & 0xff));
        File candidate = new File(staging, name.toString());
        return candidate.mkdir() ? candidate : null;
    }

    private static String archiveMessage(NarInstallError error) {
        if (error == NarInstallError.UNSUPPORTED_TYPE
                || error == NarInstallError.UNSUPPORTED_REFRESH
                || error == NarInstallError.UNSUPPORTED_COMPOUND_INSTALL) {
            return "This ghost update is incompatible with Nanidroid.";
        }
        return "This ghost archive is invalid or exceeds Nanidroid's safety limits.";
    }

    private static Result success(File directory, String targetId) {
        return new Result(directory, targetId, null, "");
    }

    private static Result failure(Error error, String message) {
        return new Result(null, null, error, message);
    }

    private static void closeQuietly(NarVerifiedInstallSession value) {
        if (value == null) return;
        try { value.close(); } catch (Throwable ignored) { }
    }

    private static void closeQuietly(InputStream value) {
        if (value == null) return;
        try { value.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(FileOutputStream value) {
        if (value == null) return;
        try { value.close(); } catch (IOException ignored) { }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    public static final class Result {
        private final File installedDirectory;
        private final String targetId;
        private final Error error;
        private final String message;

        private Result(File installedDirectory, String targetId, Error error,
                String message) {
            this.installedDirectory = installedDirectory;
            this.targetId = targetId;
            this.error = error;
            this.message = message;
        }

        public boolean isSuccess() { return installedDirectory != null; }
        public File getInstalledDirectory() { return installedDirectory; }
        public String getTargetId() { return targetId; }
        public Error getError() { return error; }
        public String getMessage() { return message; }
    }
}
