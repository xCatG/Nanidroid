package com.cattailsw.nanidroid.install;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Bounded, API-9-compatible structural preflight for a ZIP central directory.
 *
 * <p>This rejects unsupported split archives and malformed central metadata
 * before a caller allocates {@code ZipFile}. It does not validate entries or
 * authorize extraction.
 */
final class NarZipCentralPreflight {
    static final int MAX_ENTRIES = 10000;
    private static final int OVER_LIMIT = MAX_ENTRIES + 1;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long ZIP64_EOCD_SIGNATURE = 0x06064b50L;
    private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;

    private NarZipCentralPreflight() {}

    static Result inspect(File file) throws IOException {
        if (file == null) {
            throw new IOException("null ZIP source");
        }
        RandomAccessFile random = null;
        IOException failure = null;
        Result result = null;
        try {
            random = new RandomAccessFile(file, "r");
            result = inspect(new FileRandomAccess(random));
        } catch (IOException error) {
            failure = error;
        } finally {
            if (random != null) {
                try {
                    random.close();
                } catch (IOException close) {
                    if (failure == null) {
                        failure = close;
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    static Result inspect(RandomAccessSource source)
            throws IOException {
        if (source == null) {
            throw new IOException("null ZIP source");
        }
        long length = source.length();
        if (length < 22) {
            throw new IOException("missing EOCD");
        }
        int tailLength = (int) Math.min(length, 65557L);
        long tailOffset = length - tailLength;
        byte[] tail = new byte[tailLength];
        source.readFully(tailOffset, tail, 0, tail.length);

        int eocdInTail = findEocd(tail);
        if (eocdInTail < 0) {
            throw new IOException("invalid EOCD tail");
        }
        long eocdOffset = tailOffset + eocdInTail;
        byte[] eocd = slice(tail, eocdInTail, 22);
        int disk = u16(eocd, 4);
        int centralDisk = u16(eocd, 6);
        long entriesOnDisk = u16(eocd, 8);
        long entries = u16(eocd, 10);
        long centralSize = u32(eocd, 12);
        long centralOffset = u32(eocd, 16);
        long centralBoundary = eocdOffset;

        boolean zip64 = entriesOnDisk == 0xffffL
                || entries == 0xffffL
                || centralSize == 0xffffffffL
                || centralOffset == 0xffffffffL;
        if (zip64) {
            Zip64Directory directory = readZip64(
                    source, eocdOffset, disk, centralDisk);
            if (entriesOnDisk != 0xffffL
                    && entriesOnDisk != directory.entries
                    || entries != 0xffffL
                    && entries != directory.entries
                    || centralSize != 0xffffffffL
                    && centralSize != directory.size
                    || centralOffset != 0xffffffffL
                    && centralOffset != directory.offset) {
                throw new IOException("inconsistent ZIP64 EOCD");
            }
            entriesOnDisk = directory.entries;
            entries = directory.entries;
            centralSize = directory.size;
            centralOffset = directory.offset;
            centralBoundary = directory.recordOffset;
        } else if (disk != 0
                || centralDisk != 0
                || entriesOnDisk != entries) {
            throw new IOException("multi-disk archive");
        }

        requireRange(
                centralOffset, centralSize, centralBoundary);
        if (centralSize != centralBoundary - centralOffset) {
            throw new IOException("central directory gap");
        }
        if (entries > MAX_ENTRIES) {
            return new Result(OVER_LIMIT);
        }
        return new Result(walkCentral(
                source,
                centralOffset,
                centralSize,
                (int) entries));
    }

    private static int findEocd(byte[] tail) {
        for (int index = tail.length - 22; index >= 0; index--) {
            if (u32(tail, index) == EOCD_SIGNATURE) {
                int commentLength = u16(tail, index + 20);
                if ((long) index + 22L + commentLength
                        == tail.length) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static Zip64Directory readZip64(
            RandomAccessSource source,
            long eocdOffset,
            int disk,
            int centralDisk) throws IOException {
        if (disk != 0 || centralDisk != 0 || eocdOffset < 20) {
            throw new IOException("invalid ZIP64 locator");
        }
        long locatorOffset = eocdOffset - 20;
        byte[] locator = new byte[20];
        source.readFully(
                locatorOffset, locator, 0, locator.length);
        if (u32(locator, 0) != ZIP64_LOCATOR_SIGNATURE
                || u32(locator, 4) != 0
                || u32(locator, 16) != 1) {
            throw new IOException("multi-disk ZIP64");
        }

        long recordOffset = u64(locator, 8);
        requireRange(recordOffset, 56, locatorOffset);
        byte[] record = new byte[56];
        source.readFully(
                recordOffset, record, 0, record.length);
        if (u32(record, 0) != ZIP64_EOCD_SIGNATURE) {
            throw new IOException("invalid ZIP64 EOCD signature");
        }
        long recordSize = u64(record, 4);
        if (recordSize < 44
                || recordSize
                        != locatorOffset - recordOffset - 12
                || u32(record, 16) != 0
                || u32(record, 20) != 0) {
            throw new IOException("invalid ZIP64 EOCD");
        }

        long entriesOnDisk = u64(record, 24);
        long entries = u64(record, 32);
        if (entriesOnDisk != entries) {
            throw new IOException("multi-disk ZIP64 entries");
        }
        return new Zip64Directory(
                recordOffset,
                entries,
                u64(record, 40),
                u64(record, 48));
    }

    private static int walkCentral(
            RandomAccessSource source,
            long offset,
            long size,
            int declaredEntries) throws IOException {
        long end = offset + size;
        long cursor = offset;
        int count = 0;
        byte[] header = new byte[46];
        while (cursor < end) {
            if (count >= OVER_LIMIT
                    || end - cursor < header.length) {
                throw new IOException(
                        "central entry limit or truncation");
            }
            source.readFully(
                    cursor, header, 0, header.length);
            if (u32(header, 0) != CENTRAL_SIGNATURE
                    || u16(header, 34) != 0) {
                throw new IOException("invalid central record");
            }
            long variable = (long) u16(header, 28)
                    + u16(header, 30)
                    + u16(header, 32);
            long recordLength = 46L + variable;
            if (recordLength > end - cursor) {
                throw new IOException(
                        "central variable fields");
            }
            cursor += recordLength;
            count++;
        }
        if (cursor != end || count != declaredEntries) {
            throw new IOException("central count mismatch");
        }
        return count;
    }

    private static void requireRange(
            long offset, long size, long boundary)
            throws IOException {
        if (offset < 0
                || size < 0
                || boundary < 0
                || offset > boundary
                || size > boundary - offset) {
            throw new IOException("ZIP bounds");
        }
    }

    private static byte[] slice(
            byte[] source, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, offset, result, 0, length);
        return result;
    }

    private static int u16(byte[] source, int offset) {
        return (source[offset] & 0xff)
                | (source[offset + 1] & 0xff) << 8;
    }

    private static long u32(byte[] source, int offset) {
        return (long) u16(source, offset)
                | (long) u16(source, offset + 2) << 16;
    }

    private static long u64(byte[] source, int offset)
            throws IOException {
        long low = u32(source, offset);
        long high = u32(source, offset + 4);
        if ((high & 0x80000000L) != 0) {
            throw new IOException("ZIP64 value overflow");
        }
        return low | high << 32;
    }

    interface RandomAccessSource {
        long length() throws IOException;
        void readFully(
                long position,
                byte[] target,
                int offset,
                int length) throws IOException;
    }

    static final class Result {
        private final int entryCount;

        private Result(int entryCount) {
            this.entryCount = entryCount;
        }

        int getEntryCount() {
            return entryCount;
        }

        boolean isEntryCountOverLimit() {
            return entryCount > MAX_ENTRIES;
        }
    }

    private static final class FileRandomAccess
            implements RandomAccessSource {
        private final RandomAccessFile random;

        private FileRandomAccess(RandomAccessFile random) {
            this.random = random;
        }

        @Override public long length() throws IOException {
            return random.length();
        }

        @Override public void readFully(
                long position,
                byte[] target,
                int offset,
                int length) throws IOException {
            random.seek(position);
            random.readFully(target, offset, length);
        }
    }

    private static final class Zip64Directory {
        private final long recordOffset;
        private final long entries;
        private final long size;
        private final long offset;

        private Zip64Directory(
                long recordOffset,
                long entries,
                long size,
                long offset) {
            this.recordOffset = recordOffset;
            this.entries = entries;
            this.size = size;
            this.offset = offset;
        }
    }
}
