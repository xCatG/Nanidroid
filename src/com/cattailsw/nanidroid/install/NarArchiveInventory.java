package com.cattailsw.nanidroid.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, normalized view of every supplied central-directory entry. */
public final class NarArchiveInventory {
    private final List<Entry> entries;
    private final String wrapperDirectory;
    private final int descriptorOrdinal;
    private final long declaredTotalSize;

    NarArchiveInventory(
            List<Entry> entries,
            String wrapperDirectory,
            int descriptorOrdinal,
            long declaredTotalSize) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
        this.wrapperDirectory = wrapperDirectory;
        this.descriptorOrdinal = descriptorOrdinal;
        this.declaredTotalSize = declaredTotalSize;
    }

    public List<Entry> getEntries() { return entries; }
    public String getWrapperDirectory() { return wrapperDirectory; }
    public int getDescriptorOrdinal() { return descriptorOrdinal; }
    public long getDeclaredTotalSize() { return declaredTotalSize; }

    /** Central identity plus safe normalized output mapping for one entry. */
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

        Entry(
                int ordinal,
                String rawName,
                String normalizedArchivePath,
                String relativePath,
                boolean directory,
                long crc,
                int method,
                long declaredSize,
                long compressedSize) {
            this.ordinal = ordinal;
            this.rawName = rawName;
            this.normalizedArchivePath = normalizedArchivePath;
            this.relativePath = relativePath;
            this.directory = directory;
            this.crc = crc;
            this.method = method;
            this.declaredSize = declaredSize;
            this.compressedSize = compressedSize;
        }

        public int getOrdinal() { return ordinal; }
        public String getRawName() { return rawName; }
        public String getNormalizedArchivePath() { return normalizedArchivePath; }
        public String getRelativePath() { return relativePath; }
        public boolean isInstallEntry() { return relativePath != null; }
        public boolean isDirectory() { return directory; }
        public long getCrc() { return crc; }
        public int getMethod() { return method; }
        public long getDeclaredSize() { return declaredSize; }
        public long getCompressedSize() { return compressedSize; }
    }
}
