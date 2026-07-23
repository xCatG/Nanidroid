package com.cattailsw.nanidroid.install;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable identity-bound diagnostic plan.
 *
 * <p>This describes validated content but does not authorize extraction.
 */
public final class NarInstallPlan {
    private final long sourceLength;
    private final byte[] sourceSha256;
    private final List<Entry> entries;
    private final NarInstallDescriptor descriptor;
    private final String wrapperDirectory;
    private final File installRoot;
    private final File targetDirectory;

    NarInstallPlan(
            long sourceLength,
            byte[] sourceSha256,
            NarArchiveInventory inventory,
            NarInstallDescriptor descriptor,
            File installRoot,
            File targetDirectory) {
        this.sourceLength = sourceLength;
        this.sourceSha256 = sourceSha256.clone();
        List<Entry> copied = new ArrayList<Entry>();
        for (NarArchiveInventory.Entry entry : inventory.getEntries()) {
            copied.add(new Entry(entry));
        }
        entries = Collections.unmodifiableList(copied);
        this.descriptor = descriptor;
        wrapperDirectory = inventory.getWrapperDirectory();
        this.installRoot = installRoot;
        this.targetDirectory = targetDirectory;
    }

    public long getSourceLength() { return sourceLength; }
    public byte[] getSourceSha256() { return sourceSha256.clone(); }
    public List<Entry> getEntries() { return entries; }
    public NarInstallDescriptor getDescriptor() { return descriptor; }
    public String getWrapperDirectory() { return wrapperDirectory; }
    public File getInstallRoot() { return installRoot; }
    public File getTargetDirectory() { return targetDirectory; }

    /** Exact central identity plus normalized diagnostic mapping. */
    public static final class Entry {
        private final int ordinal;
        private final String rawName;
        private final String normalizedArchivePath;
        private final String relativePath;
        private final boolean directory;
        private final long crc;
        private final int method;
        private final long declaredSize;
        private final long compressedSize;

        private Entry(NarArchiveInventory.Entry entry) {
            ordinal = entry.getOrdinal();
            rawName = entry.getRawName();
            normalizedArchivePath = entry.getNormalizedArchivePath();
            relativePath = entry.getRelativePath();
            directory = entry.isDirectory();
            crc = entry.getCrc();
            method = entry.getMethod();
            declaredSize = entry.getDeclaredSize();
            compressedSize = entry.getCompressedSize();
        }

        public int getOrdinal() { return ordinal; }
        public String getRawName() { return rawName; }
        public String getNormalizedArchivePath() {
            return normalizedArchivePath;
        }
        public String getRelativePath() { return relativePath; }
        public boolean isInstallEntry() { return relativePath != null; }
        public boolean isDirectory() { return directory; }
        public long getCrc() { return crc; }
        public int getMethod() { return method; }
        public long getDeclaredSize() { return declaredSize; }
        public long getCompressedSize() { return compressedSize; }

        boolean sameCentral(
                NarArchiveInventoryValidator.CentralEntry other) {
            return ordinal == other.getOrdinal()
                    && rawName.equals(other.getRawName())
                    && directory == other.isDirectory()
                    && crc == other.getCrc()
                    && method == other.getMethod()
                    && declaredSize == other.getDeclaredSize()
                    && compressedSize == other.getCompressedSize();
        }
    }
}
