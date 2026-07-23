package com.cattailsw.nanidroid.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot authority for a fresh app-private staged NAR snapshot.
 *
 * <p>The production factory creates a new path below a caller-supplied trusted
 * staging root, copies at most 544 MiB, syncs and closes the writer, and only
 * then mints this capability. Neither the path nor its writer escapes from the
 * factory. The existing {@link #claim()} method remains the sole raw-file
 * handoff to the verified-session validator.
 *
 * <p>The app must retain exclusive ownership of the staging root from
 * create-new through verified-session close. Portable API-9 file APIs cannot
 * prevent a malicious same-UID ABA replacement. In particular,
 * {@code File.setReadOnly()} neither provides that guarantee nor composes with
 * reliable session cleanup on Windows, so capability discipline is the
 * portable control.
 */
final class NarStagedSource {
    private static final long MAX_ARCHIVE_BYTES =
            544L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 8192;
    private static final int NAME_ATTEMPTS = 16;
    private final File file;
    private boolean claimed;

    private NarStagedSource(File file) {
        this.file = file;
    }

    static NarStagedSourceCopyResult copy(
            File externalArchive, File trustedStagingRoot) {
        return copy(
                externalArchive,
                trustedStagingRoot,
                new FileStageIo(),
                new RandomNameSource());
    }

    static NarStagedSourceCopyResult copy(
            File externalArchive,
            File trustedStagingRoot,
            StageIo io,
            NameSource names) {
        if (externalArchive == null) {
            return failure(
                    NarStagedSourceCopyError.SOURCE_INVALID,
                    "source is null");
        }
        if (trustedStagingRoot == null || io == null) {
            return failure(
                    NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                    "staging root is null");
        }

        File root;
        try {
            root = io.canonical(trustedStagingRoot);
            if (root == null || !io.isDirectory(root)) {
                return failure(
                        NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                        "staging root is not a directory");
            }
        } catch (IOException error) {
            return failure(
                    NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                    "cannot canonicalize staging root");
        } catch (RuntimeException error) {
            return failure(
                    NarStagedSourceCopyError.STAGING_ROOT_INVALID,
                    "cannot inspect staging root");
        }

        File staged = null;
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String name;
            try {
                name = names == null ? null : names.nextName();
            } catch (RuntimeException error) {
                return failure(
                        NarStagedSourceCopyError.STAGING_NAME_INVALID,
                        "cannot generate staging name");
            }
            if (!isSafeName(name)) {
                return failure(
                        NarStagedSourceCopyError.STAGING_NAME_INVALID,
                        "invalid staging name");
            }

            File candidate;
            try {
                candidate = io.canonical(new File(root, name));
                if (candidate == null
                        || !root.equals(candidate.getParentFile())) {
                    return failure(
                            NarStagedSourceCopyError.STAGING_NAME_INVALID,
                            "staging path escapes root");
                }
            } catch (IOException error) {
                return failure(
                        NarStagedSourceCopyError.STAGING_NAME_INVALID,
                        "cannot canonicalize staging path");
            } catch (RuntimeException error) {
                return failure(
                        NarStagedSourceCopyError.STAGING_NAME_INVALID,
                        "cannot inspect staging path");
            }

            try {
                if (io.createNew(candidate)) {
                    staged = candidate;
                    break;
                }
            } catch (IOException error) {
                return failure(
                        NarStagedSourceCopyError.STAGING_CREATE_FAILED,
                        "cannot create staging file");
            } catch (RuntimeException error) {
                return failure(
                        NarStagedSourceCopyError.STAGING_CREATE_FAILED,
                        "cannot create staging file");
            }
        }
        if (staged == null) {
            return failure(
                    NarStagedSourceCopyError
                            .STAGING_NAME_COLLISION_LIMIT,
                    "staging name collision limit");
        }

        return copyIntoCreated(
                externalArchive, staged, io);
    }

    private static NarStagedSourceCopyResult copyIntoCreated(
            File externalArchive, File staged, StageIo io) {
        InputStream source = null;
        StageOutput target = null;
        NarStagedSourceCopyError primary = null;
        String detail = "";
        List<NarStagedSourceCopyError> cleanup =
                new ArrayList<NarStagedSourceCopyError>();

        try {
            source = io.openSource(externalArchive);
        } catch (IOException error) {
            primary = NarStagedSourceCopyError.SOURCE_OPEN_FAILED;
            detail = "cannot open source";
        } catch (RuntimeException error) {
            primary = NarStagedSourceCopyError.SOURCE_OPEN_FAILED;
            detail = "cannot open source";
        }

        if (primary == null) {
            try {
                target = io.openTarget(staged);
            } catch (IOException error) {
                primary =
                        NarStagedSourceCopyError.STAGING_OPEN_FAILED;
                detail = "cannot open staging writer";
            } catch (RuntimeException error) {
                primary =
                        NarStagedSourceCopyError.STAGING_OPEN_FAILED;
                detail = "cannot open staging writer";
            }
        }

        if (primary == null) {
            CopyFailure copied = copyBytes(source, target);
            primary = copied.error;
            detail = copied.detail;
        }
        if (primary == null) {
            try {
                target.sync();
            } catch (IOException error) {
                primary =
                        NarStagedSourceCopyError.STAGING_SYNC_FAILED;
                detail = "cannot sync staging writer";
            } catch (RuntimeException error) {
                primary =
                        NarStagedSourceCopyError.STAGING_SYNC_FAILED;
                detail = "cannot sync staging writer";
            }
        }

        if (target != null) {
            try {
                target.close();
            } catch (IOException error) {
                primary = record(
                        primary,
                        NarStagedSourceCopyError.STAGING_CLOSE_FAILED,
                        cleanup);
                if (detail.length() == 0) {
                    detail = "cannot close staging writer";
                }
            } catch (RuntimeException error) {
                primary = record(
                        primary,
                        NarStagedSourceCopyError.STAGING_CLOSE_FAILED,
                        cleanup);
                if (detail.length() == 0) {
                    detail = "cannot close staging writer";
                }
            }
        }
        if (source != null) {
            try {
                source.close();
            } catch (IOException error) {
                primary = record(
                        primary,
                        NarStagedSourceCopyError.SOURCE_CLOSE_FAILED,
                        cleanup);
                if (detail.length() == 0) {
                    detail = "cannot close source";
                }
            } catch (RuntimeException error) {
                primary = record(
                        primary,
                        NarStagedSourceCopyError.SOURCE_CLOSE_FAILED,
                        cleanup);
                if (detail.length() == 0) {
                    detail = "cannot close source";
                }
            }
        }

        if (primary != null) {
            boolean deleted;
            try {
                deleted = io.delete(staged);
            } catch (RuntimeException error) {
                deleted = false;
            }
            if (!deleted) {
                cleanup.add(
                        NarStagedSourceCopyError.STAGING_DELETE_FAILED);
            }
            return NarStagedSourceCopyResult.failure(
                    primary, detail, cleanup);
        }
        return NarStagedSourceCopyResult.success(
                new NarStagedSource(staged));
    }

    private static CopyFailure copyBytes(
            InputStream source, StageOutput target) {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        while (true) {
            int limit = (int) Math.min(
                    (long) buffer.length,
                    MAX_ARCHIVE_BYTES - total + 1);
            int count;
            try {
                count = source.read(buffer, 0, limit);
                if (count == 0) {
                    int one = source.read();
                    if (one == -1) {
                        break;
                    }
                    if (one < -1 || one > 255) {
                        return new CopyFailure(
                                NarStagedSourceCopyError
                                        .SOURCE_READ_FAILED,
                                "invalid single-byte read");
                    }
                    buffer[0] = (byte) one;
                    count = 1;
                } else if (count == -1) {
                    break;
                } else if (count < -1 || count > limit) {
                    return new CopyFailure(
                            NarStagedSourceCopyError
                                    .SOURCE_READ_FAILED,
                            "invalid source read count");
                }
            } catch (IOException error) {
                return new CopyFailure(
                        NarStagedSourceCopyError.SOURCE_READ_FAILED,
                        "cannot read source");
            } catch (RuntimeException error) {
                return new CopyFailure(
                        NarStagedSourceCopyError.SOURCE_READ_FAILED,
                        "cannot read source");
            }

            int writable = (int) Math.min(
                    (long) count, MAX_ARCHIVE_BYTES - total);
            if (writable > 0) {
                try {
                    target.write(buffer, 0, writable);
                } catch (IOException error) {
                    return new CopyFailure(
                            NarStagedSourceCopyError
                                    .STAGING_WRITE_FAILED,
                            "cannot write staging file");
                } catch (RuntimeException error) {
                    return new CopyFailure(
                            NarStagedSourceCopyError
                                    .STAGING_WRITE_FAILED,
                            "cannot write staging file");
                }
            }
            total += count;
            if (total > MAX_ARCHIVE_BYTES) {
                return new CopyFailure(
                        NarStagedSourceCopyError.ARCHIVE_SIZE_LIMIT,
                        "archive exceeds 544 MiB");
            }
        }
        return new CopyFailure(null, "");
    }

    private static NarStagedSourceCopyError record(
            NarStagedSourceCopyError primary,
            NarStagedSourceCopyError next,
            List<NarStagedSourceCopyError> cleanup) {
        if (primary == null) {
            return next;
        }
        cleanup.add(next);
        return primary;
    }

    private static boolean isSafeName(String name) {
        if (name == null
                || name.length() == 0
                || name.length() > 128
                || !isAsciiLetterOrDigit(name.charAt(0))) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char value = name.charAt(index);
            if (!isAsciiLetterOrDigit(value)
                    && value != '.'
                    && value != '_'
                    && value != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private static NarStagedSourceCopyResult failure(
            NarStagedSourceCopyError error, String detail) {
        return NarStagedSourceCopyResult.failure(
                error,
                detail,
                new ArrayList<NarStagedSourceCopyError>());
    }

    synchronized File claim() {
        if (claimed || file == null) {
            return null;
        }
        claimed = true;
        return file;
    }

    interface StageIo {
        File canonical(File file) throws IOException;
        boolean isDirectory(File directory);
        boolean createNew(File file) throws IOException;
        InputStream openSource(File file) throws IOException;
        StageOutput openTarget(File file) throws IOException;
        boolean delete(File file);
    }

    interface StageOutput {
        void write(byte[] buffer, int offset, int length)
                throws IOException;
        void sync() throws IOException;
        void close() throws IOException;
    }

    interface NameSource {
        String nextName();
    }

    private static final class FileStageIo implements StageIo {
        @Override
        public File canonical(File file) throws IOException {
            return file.getCanonicalFile();
        }

        @Override
        public boolean isDirectory(File directory) {
            return directory.isDirectory();
        }

        @Override
        public boolean createNew(File file) throws IOException {
            return file.createNewFile();
        }

        @Override
        public InputStream openSource(File file) throws IOException {
            return new FileInputStream(file);
        }

        @Override
        public StageOutput openTarget(File file)
                throws IOException {
            return new FileStageOutput(new FileOutputStream(file));
        }

        @Override
        public boolean delete(File file) {
            return file.delete();
        }
    }

    private static final class FileStageOutput
            implements StageOutput {
        private final FileOutputStream output;

        private FileStageOutput(FileOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(byte[] buffer, int offset, int length)
                throws IOException {
            output.write(buffer, offset, length);
        }

        @Override
        public void sync() throws IOException {
            output.getFD().sync();
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    private static final class RandomNameSource
            implements NameSource {
        private SecureRandom random;

        @Override
        public String nextName() {
            if (random == null) {
                random = new SecureRandom();
            }
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            StringBuilder name = new StringBuilder("staged-");
            for (byte value : bytes) {
                int unsigned = value & 0xff;
                if (unsigned < 16) {
                    name.append('0');
                }
                name.append(Integer.toHexString(unsigned));
            }
            return name.append(".nar").toString();
        }
    }

    private static final class CopyFailure {
        private final NarStagedSourceCopyError error;
        private final String detail;

        private CopyFailure(
                NarStagedSourceCopyError error, String detail) {
            this.error = error;
            this.detail = detail;
        }
    }
}
