package com.cattailsw.nanidroid.install;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only orchestration for identity-bound diagnostic NAR plans. */
public final class NarInstallPlanValidator {
    private static final long MAX_ARCHIVE_BYTES =
            544L * 1024L * 1024L;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final int ENTRY_LIMIT =
            NarZipCentralPreflight.MAX_ENTRIES;
    private static final int ENTRY_LIMIT_PLUS_ONE = ENTRY_LIMIT + 1;
    private static final int BUFFER_SIZE = 8192;
    private final ArchiveIo io;

    public NarInstallPlanValidator() {
        this(new FileArchiveIo());
    }

    NarInstallPlanValidator(ArchiveIo io) {
        this.io = io;
    }

    /**
     * Produces a diagnostic plan; it does not authorize extraction.
     */
    public NarInstallPlanResult validate(
            File archive, File installRoot, String forcedId) {
        try {
            validateArguments(archive, installRoot);
            IdentityRead before = readIdentity(archive);
            requireCleanClose(before);
            NarInstallPlan plan = planArchive(
                    archive,
                    installRoot,
                    forcedId,
                    before.identity,
                    false).plan;
            IdentityRead after = readIdentity(archive);
            requireSameIdentity(before.identity, after.identity);
            requireCleanClose(after);
            return NarInstallPlanResult.success(plan);
        } catch (Failure failure) {
            return result(failure);
        }
    }

    /**
     * Verifies a diagnostic plan; it does not authorize extraction.
     */
    public NarInstallPlanResult verify(
            File archive, NarInstallPlan plan) {
        try {
            if (archive == null || plan == null) {
                fail(
                        NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                        "missing identity");
            }
            IdentityRead before = readIdentity(archive);
            requirePlanIdentity(plan, before.identity);
            requireCleanClose(before);
            verifyCentral(archive, plan, false);
            IdentityRead after = readIdentity(archive);
            requirePlanIdentity(plan, after.identity);
            requireCleanClose(after);
            return NarInstallPlanResult.success(plan);
        } catch (Failure failure) {
            return result(failure);
        }
    }

    NarInstallPlanResult validateStaged(
            NarStagedSource staged,
            File installRoot,
            String forcedId) {
        File archive = staged == null ? null : staged.claim();
        if (archive == null) {
            return NarInstallPlanResult.failure(
                    NarInstallError.STAGED_SOURCE_INVALID,
                    "staged source already claimed");
        }
        RetainedArchive retained = null;
        boolean transferred = false;
        try {
            validateArguments(archive, installRoot);
            IdentityRead before = readIdentity(archive);
            requireCleanClose(before);
            retained = planArchive(
                    archive,
                    installRoot,
                    forcedId,
                    before.identity,
                    true);
            IdentityRead after = readIdentity(archive);
            requireSameIdentity(before.identity, after.identity);
            requireCleanClose(after);
            NarVerifiedInstallSession session =
                    new NarVerifiedInstallSession(
                            io,
                            archive,
                            retained.archive,
                            retained.entries,
                            retained.plan);
            NarInstallPlanResult success =
                    NarInstallPlanResult.stagedSuccess(
                            retained.plan, session);
            transferred = true;
            return success;
        } catch (Failure failure) {
            return result(failure);
        } catch (RuntimeException error) {
            return result(archiveRead("staged validation"));
        } finally {
            if (!transferred) {
                cleanup(retained, archive);
            }
        }
    }

    NarInstallPlanResult verifyStaged(
            NarStagedSource staged, NarInstallPlan plan) {
        File archive = staged == null ? null : staged.claim();
        if (archive == null) {
            return NarInstallPlanResult.failure(
                    NarInstallError.STAGED_SOURCE_INVALID,
                    "staged source already claimed");
        }
        RetainedArchive retained = null;
        boolean transferred = false;
        try {
            if (plan == null) {
                fail(
                        NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                        "missing plan");
            }
            IdentityRead before = readIdentity(archive);
            requirePlanIdentity(plan, before.identity);
            requireCleanClose(before);
            retained = verifyCentral(archive, plan, true);
            IdentityRead after = readIdentity(archive);
            requirePlanIdentity(plan, after.identity);
            requireCleanClose(after);
            NarVerifiedInstallSession session =
                    new NarVerifiedInstallSession(
                            io,
                            archive,
                            retained.archive,
                            retained.entries,
                            plan);
            NarInstallPlanResult success =
                    NarInstallPlanResult.stagedSuccess(
                            plan, session);
            transferred = true;
            return success;
        } catch (Failure failure) {
            return result(failure);
        } catch (RuntimeException error) {
            return result(archiveRead("staged verification"));
        } finally {
            if (!transferred) {
                cleanup(retained, archive);
            }
        }
    }

    private static NarInstallPlanResult result(Failure failure) {
        return NarInstallPlanResult.failure(
                failure.error, failure.getMessage());
    }

    private static void validateArguments(
            File archive, File installRoot) throws Failure {
        if (archive == null) {
            fail(NarInstallError.ARCHIVE_READ_FAILED, "null archive");
        }
        if (installRoot == null) {
            fail(
                    NarInstallError.INSTALL_ROOT_INVALID,
                    "null install root");
        }
    }

    private RetainedArchive planArchive(
            File archive,
            File installRoot,
            String forcedId,
            SourceIdentity identity,
            boolean retain) throws Failure {
        int preflightCount = inspectBeforeZip(archive);
        OpenArchive zip = null;
        List<? extends ArchiveEntry> entries = null;
        NarInstallPlan plan = null;
        Failure failure = null;
        try {
            zip = io.openArchive(archive);
            entries = zip.entries(ENTRY_LIMIT_PLUS_ONE);
            failure = validateEnumeration(preflightCount, entries);
            if (failure == null) {
                NarArchiveInventoryResult inventoryResult =
                        new NarArchiveInventoryValidator().validate(entries);
                if (!inventoryResult.isSuccess()) {
                    failure = new Failure(
                            inventoryResult.getError(),
                            inventoryResult.getDetail());
                } else {
                    NarArchiveInventory inventory =
                            inventoryResult.getInventory();
                    ArchiveEntry descriptor = entries.get(
                            inventory.getDescriptorOrdinal());
                    DescriptorRead read =
                            readDescriptor(zip, descriptor);
                    NarDescriptorResult descriptorResult =
                            new NarDescriptorParser().parse(
                                    read.bytes, forcedId);
                    if (!descriptorResult.isSuccess()) {
                        failure = new Failure(
                                descriptorResult.getError(),
                                descriptorResult.getDetail());
                    } else if (read.closeFailed) {
                        failure = new Failure(
                                NarInstallError.DESCRIPTOR_READ_FAILED,
                                "descriptor close");
                    } else {
                        plan = buildPlan(
                                installRoot,
                                identity,
                                inventory,
                                descriptorResult.getDescriptor());
                    }
                }
            }
        } catch (DescriptorFailure descriptor) {
            failure = descriptor.failure;
        } catch (Failure semantic) {
            failure = semantic;
        } catch (IOException error) {
            failure = archiveRead("archive read");
        } catch (RuntimeException error) {
            failure = archiveRead("archive runtime");
        } finally {
            if (!retain || failure != null) {
                failure = closeArchive(zip, failure);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new RetainedArchive(
                plan,
                retain ? zip : null,
                retain ? entries : null);
    }

    private RetainedArchive verifyCentral(
            File archive,
            NarInstallPlan plan,
            boolean retain) throws Failure {
        int preflightCount = inspectBeforeZip(archive);
        OpenArchive zip = null;
        List<? extends ArchiveEntry> actual = null;
        Failure failure = null;
        try {
            zip = io.openArchive(archive);
            actual = zip.entries(ENTRY_LIMIT_PLUS_ONE);
            failure = validateEnumeration(preflightCount, actual);
            if (failure == null) {
                List<NarInstallPlan.Entry> expected =
                        plan.getEntries();
                if (actual.size() != expected.size()) {
                    failure = identityMismatch("central count");
                } else {
                    for (int index = 0;
                            index < actual.size();
                            index++) {
                        if (!expected.get(index).sameCentral(
                                actual.get(index))) {
                            failure =
                                    identityMismatch("central record");
                            break;
                        }
                    }
                }
            }
        } catch (IOException error) {
            failure = archiveRead("archive read");
        } catch (RuntimeException error) {
            failure = archiveRead("central metadata");
        } finally {
            if (!retain || failure != null) {
                failure = closeArchive(zip, failure);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new RetainedArchive(
                plan,
                retain ? zip : null,
                retain ? actual : null);
    }

    private NarInstallPlan buildPlan(
            File installRoot,
            SourceIdentity identity,
            NarArchiveInventory inventory,
            NarInstallDescriptor descriptor) throws Failure {
        File root;
        try {
            root = io.canonical(installRoot);
        } catch (IOException error) {
            throw new Failure(
                    NarInstallError.INSTALL_ROOT_INVALID,
                    "install root");
        } catch (RuntimeException error) {
            throw new Failure(
                    NarInstallError.INSTALL_ROOT_INVALID,
                    "install root");
        }
        if (root == null) {
            fail(
                    NarInstallError.INSTALL_ROOT_INVALID,
                    "null canonical root");
        }
        File target = new File(root, descriptor.getTargetId());
        if (!root.equals(target.getParentFile())) {
            fail(
                    NarInstallError.INVALID_TARGET_ID,
                    "target parent");
        }
        return new NarInstallPlan(
                identity.length,
                identity.digest,
                inventory,
                descriptor,
                root,
                target);
    }

    private int inspectBeforeZip(File archive) throws Failure {
        int count;
        try {
            count = io.preflight(archive);
        } catch (IOException error) {
            throw archiveRead("central preflight");
        } catch (RuntimeException error) {
            throw archiveRead("central preflight");
        }
        if (count < 0) {
            throw archiveRead("negative central count");
        }
        if (count > ENTRY_LIMIT) {
            fail(
                    NarInstallError.ENTRY_COUNT_LIMIT,
                    "entry count exceeds 10000");
        }
        return count;
    }

    private static Failure validateEnumeration(
            int preflightCount,
            List<? extends ArchiveEntry> entries) {
        if (entries.size() > ENTRY_LIMIT) {
            return new Failure(
                    NarInstallError.ENTRY_COUNT_LIMIT,
                    "entry count exceeds 10000");
        }
        if (entries.size() != preflightCount) {
            return identityMismatch("preflight count");
        }
        return null;
    }

    private IdentityRead readIdentity(File archive) throws Failure {
        try {
            if (io.length(archive) > MAX_ARCHIVE_BYTES) {
                fail(
                        NarInstallError.ARCHIVE_SIZE_LIMIT,
                        "archive exceeds 544 MiB");
            }
        } catch (IOException error) {
            fail(NarInstallError.ARCHIVE_READ_FAILED, "archive length");
        } catch (RuntimeException error) {
            fail(NarInstallError.ARCHIVE_READ_FAILED, "archive length");
        }

        InputStream input = null;
        Failure failure = null;
        SourceIdentity identity = null;
        boolean closeFailed = false;
        try {
            input = io.openSource(archive);
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            while (true) {
                int limit = (int) Math.min(
                        (long) buffer.length,
                        MAX_ARCHIVE_BYTES - total + 1);
                int count = input.read(buffer, 0, limit);
                if (count == 0) {
                    int one = input.read();
                    if (one < 0) {
                        break;
                    }
                    buffer[0] = (byte) one;
                    count = 1;
                } else if (count < 0) {
                    break;
                } else if (count > limit) {
                    throw new IOException("invalid read count");
                }
                total += count;
                if (total > MAX_ARCHIVE_BYTES) {
                    failure = new Failure(
                            NarInstallError.ARCHIVE_SIZE_LIMIT,
                            "archive exceeds 544 MiB");
                    break;
                }
                digest.update(buffer, 0, count);
            }
            if (failure == null) {
                identity =
                        new SourceIdentity(total, digest.digest());
            }
        } catch (IOException error) {
            failure = archiveRead("archive stream");
        } catch (NoSuchAlgorithmException error) {
            failure = archiveRead("SHA-256");
        } catch (RuntimeException error) {
            failure = archiveRead("archive stream");
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException close) {
                    closeFailed = true;
                } catch (RuntimeException close) {
                    closeFailed = true;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new IdentityRead(identity, closeFailed);
    }

    private static DescriptorRead readDescriptor(
            OpenArchive zip, ArchiveEntry entry)
            throws DescriptorFailure {
        InputStream input = null;
        Failure failure = null;
        byte[] bytes = null;
        boolean closeFailed = false;
        try {
            input = zip.open(entry);
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            while (true) {
                int limit = Math.min(
                        buffer.length,
                        MAX_DESCRIPTOR_BYTES - total + 1);
                int count = input.read(buffer, 0, limit);
                if (count == 0) {
                    int one = input.read();
                    if (one < 0) {
                        break;
                    }
                    buffer[0] = (byte) one;
                    count = 1;
                } else if (count < 0) {
                    break;
                } else if (count > limit) {
                    throw new IOException("invalid read count");
                }
                total += count;
                if (total > MAX_DESCRIPTOR_BYTES) {
                    failure = new Failure(
                            NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
                            "actual descriptor exceeds 64 KiB");
                    break;
                }
                output.write(buffer, 0, count);
            }
            if (failure == null) {
                bytes = output.toByteArray();
            }
        } catch (IOException error) {
            failure = new Failure(
                    NarInstallError.DESCRIPTOR_READ_FAILED,
                    "descriptor read");
        } catch (RuntimeException error) {
            failure = new Failure(
                    NarInstallError.DESCRIPTOR_READ_FAILED,
                    "descriptor runtime");
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException close) {
                    closeFailed = true;
                } catch (RuntimeException close) {
                    closeFailed = true;
                }
            }
        }
        if (failure != null) {
            throw new DescriptorFailure(failure);
        }
        return new DescriptorRead(bytes, closeFailed);
    }

    private static void requireSameIdentity(
            SourceIdentity expected,
            SourceIdentity actual) throws Failure {
        if (!expected.same(actual)) {
            throw identityMismatch("source bytes changed");
        }
    }

    private static void requirePlanIdentity(
            NarInstallPlan plan,
            SourceIdentity actual) throws Failure {
        if (actual.length != plan.getSourceLength()
                || !Arrays.equals(
                        actual.digest, plan.getSourceSha256())) {
            throw identityMismatch("source bytes changed");
        }
    }

    private static void requireCleanClose(IdentityRead read)
            throws Failure {
        if (read.closeFailed) {
            throw archiveRead("source close");
        }
    }

    private static Failure closeArchive(
            OpenArchive archive, Failure failure) {
        if (archive == null) {
            return failure;
        }
        try {
            archive.close();
        } catch (IOException close) {
            if (failure == null) {
                return archiveRead("archive close");
            }
        } catch (RuntimeException close) {
            if (failure == null) {
                return archiveRead("archive close");
            }
        }
        return failure;
    }

    private void cleanup(
            RetainedArchive retained, File stagedFile) {
        if (retained != null) {
            closeArchive(retained.archive, null);
        }
        try {
            io.delete(stagedFile);
        } catch (RuntimeException ignored) {
            // The primary staged failure remains authoritative.
        }
    }

    private static Failure archiveRead(String detail) {
        return new Failure(
                NarInstallError.ARCHIVE_READ_FAILED, detail);
    }

    private static Failure identityMismatch(String detail) {
        return new Failure(
                NarInstallError.ARCHIVE_IDENTITY_MISMATCH,
                detail);
    }

    private static void fail(
            NarInstallError error, String detail) throws Failure {
        throw new Failure(error, detail);
    }

    interface ArchiveIo {
        long length(File file) throws IOException;
        InputStream openSource(File file) throws IOException;
        int preflight(File file) throws IOException;
        OpenArchive openArchive(File file) throws IOException;
        File canonical(File file) throws IOException;
        boolean delete(File file);
    }

    interface OpenArchive {
        List<? extends ArchiveEntry> entries(int limit)
                throws IOException;
        InputStream open(ArchiveEntry entry) throws IOException;
        void close() throws IOException;
    }

    interface ArchiveEntry
            extends NarArchiveInventoryValidator.CentralEntry {}

    private static final class FileArchiveIo implements ArchiveIo {
        @Override public long length(File file) {
            return file.length();
        }

        @Override public InputStream openSource(File file)
                throws IOException {
            return new FileInputStream(file);
        }

        @Override public int preflight(File file)
                throws IOException {
            return NarZipCentralPreflight.inspect(file)
                    .getEntryCount();
        }

        @Override public OpenArchive openArchive(File file)
                throws IOException {
            return new ZipArchive(file);
        }

        @Override public File canonical(File file)
                throws IOException {
            return file.getCanonicalFile();
        }

        @Override public boolean delete(File file) {
            return file.delete();
        }
    }

    private static final class ZipArchive implements OpenArchive {
        private final ZipFile zip;

        private ZipArchive(File file) throws IOException {
            zip = new ZipFile(file);
        }

        @Override public List<? extends ArchiveEntry> entries(
                int limit) {
            List<ZipArchiveEntry> entries =
                    new ArrayList<ZipArchiveEntry>();
            Enumeration<? extends ZipEntry> source =
                    zip.entries();
            int ordinal = 0;
            while (source.hasMoreElements()
                    && entries.size() < limit) {
                entries.add(new ZipArchiveEntry(
                        this, ordinal++, source.nextElement()));
            }
            return entries;
        }

        @Override public InputStream open(ArchiveEntry entry)
                throws IOException {
            if (!(entry instanceof ZipArchiveEntry)
                    || ((ZipArchiveEntry) entry).owner != this) {
                throw new IOException("foreign ZIP entry");
            }
            return zip.getInputStream(
                    ((ZipArchiveEntry) entry).entry);
        }

        @Override public void close() throws IOException {
            zip.close();
        }
    }

    private static final class ZipArchiveEntry
            implements ArchiveEntry {
        private final ZipArchive owner;
        private final int ordinal;
        private final ZipEntry entry;

        private ZipArchiveEntry(
                ZipArchive owner, int ordinal, ZipEntry entry) {
            this.owner = owner;
            this.ordinal = ordinal;
            this.entry = entry;
        }

        @Override public int getOrdinal() { return ordinal; }
        @Override public String getRawName() {
            return entry.getName();
        }
        @Override public boolean isDirectory() {
            return entry.isDirectory();
        }
        @Override public long getCrc() { return entry.getCrc(); }
        @Override public int getMethod() {
            return entry.getMethod();
        }
        @Override public long getDeclaredSize() {
            return entry.getSize();
        }
        @Override public long getCompressedSize() {
            return entry.getCompressedSize();
        }
    }

    private static final class RetainedArchive {
        private final NarInstallPlan plan;
        private final OpenArchive archive;
        private final List<? extends ArchiveEntry> entries;

        private RetainedArchive(
                NarInstallPlan plan,
                OpenArchive archive,
                List<? extends ArchiveEntry> entries) {
            this.plan = plan;
            this.archive = archive;
            this.entries = entries;
        }
    }

    private static final class SourceIdentity {
        private final long length;
        private final byte[] digest;

        private SourceIdentity(long length, byte[] digest) {
            this.length = length;
            this.digest = digest;
        }

        private boolean same(SourceIdentity other) {
            return length == other.length
                    && Arrays.equals(digest, other.digest);
        }
    }

    private static final class IdentityRead {
        private final SourceIdentity identity;
        private final boolean closeFailed;

        private IdentityRead(
                SourceIdentity identity, boolean closeFailed) {
            this.identity = identity;
            this.closeFailed = closeFailed;
        }
    }

    private static final class DescriptorRead {
        private final byte[] bytes;
        private final boolean closeFailed;

        private DescriptorRead(
                byte[] bytes, boolean closeFailed) {
            this.bytes = bytes;
            this.closeFailed = closeFailed;
        }
    }

    private static final class DescriptorFailure extends Exception {
        private final Failure failure;

        private DescriptorFailure(Failure failure) {
            this.failure = failure;
        }
    }

    private static final class Failure extends Exception {
        private final NarInstallError error;

        private Failure(
                NarInstallError error, String detail) {
            super(detail);
            this.error = error;
        }
    }
}
