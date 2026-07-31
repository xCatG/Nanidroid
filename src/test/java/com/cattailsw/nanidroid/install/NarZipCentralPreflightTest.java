package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

public final class NarZipCentralPreflightTest {
    private static final long EOCD = 0x06054b50L;
    private static final long ZIP64_EOCD = 0x06064b50L;
    private static final long ZIP64_LOCATOR = 0x07064b50L;
    private static final long CENTRAL = 0x02014b50L;

    @Test
    public void acceptsClassicRecordsAndLegalEocdComment()
            throws Exception {
        byte[] comment = new byte[30];
        put32(comment, 5, EOCD);
        byte[] archive = classic(
                new int[][] {{3, 4, 5}, {1, 2, 3}},
                2,
                0,
                comment);

        NarZipCentralPreflight.Result result =
                inspect(archive);

        assertEquals(2, result.getEntryCount());
        assertFalse(result.isEntryCountOverLimit());
    }

    @Test
    public void acceptsZip64CentralDirectory() throws Exception {
        NarZipCentralPreflight.Result result =
                inspect(zip64(new int[][] {{2, 1, 3}, {0, 4, 0}}, 2));

        assertEquals(2, result.getEntryCount());
        assertFalse(result.isEntryCountOverLimit());
    }

    @Test
    public void acceptsExactLimitAndReturnsEarlySentinelAboveIt()
            throws Exception {
        NarZipCentralPreflight.Result exact =
                inspect(classic(
                        zeroLengthRecords(10000),
                        10000,
                        0,
                        new byte[0]));
        assertEquals(10000, exact.getEntryCount());
        assertFalse(exact.isEntryCountOverLimit());

        NarZipCentralPreflight.Result result =
                inspect(zip64(new int[0][0], 10001));

        assertEquals(10001, result.getEntryCount());
        assertTrue(result.isEntryCountOverLimit());
    }

    @Test
    public void rejectsMissingTruncatedAndTrailingEocd()
            throws Exception {
        assertInvalid(new byte[21]);

        byte[] badComment = classic(
                new int[][] {{0, 0, 0}}, 1, 0, new byte[] {1});
        put16(badComment, badComment.length - 3, 2);
        assertInvalid(badComment);

        byte[] valid = classic(
                new int[][] {{0, 0, 0}}, 1, 0, new byte[0]);
        assertInvalid(Arrays.copyOf(valid, valid.length + 1));
    }

    @Test
    public void rejectsClassicCountDiskAndBoundsMismatches()
            throws Exception {
        assertInvalid(classic(
                new int[][] {{0, 0, 0}}, 2, 0, new byte[0]));
        assertInvalid(classic(
                new int[][] {{0, 0, 0}}, 1, 1, new byte[0]));

        byte[] badOffset = classic(
                new int[][] {{0, 0, 0}}, 1, 0, new byte[0]);
        put32(badOffset, badOffset.length - 6, 0x7fffffffL);
        assertInvalid(badOffset);

        byte[] gap = classic(
                new int[][] {{0, 0, 0}}, 1, 0, new byte[0]);
        put32(gap, gap.length - 6, 1);
        assertInvalid(gap);
    }

    @Test
    public void rejectsMalformedVariableCentralRecords()
            throws Exception {
        byte[] truncated = classic(
                new int[][] {{1, 1, 1}}, 1, 0, new byte[0]);
        put16(truncated, 28, 0xffff);
        assertInvalid(truncated);

        byte[] overflowing = classic(
                new int[][] {{1, 1, 1}}, 1, 0, new byte[0]);
        put16(overflowing, 28, 0xffff);
        put16(overflowing, 30, 0xffff);
        put16(overflowing, 32, 0xffff);
        assertInvalid(overflowing);

        byte[] badSignature = classic(
                new int[][] {{0, 0, 0}}, 1, 0, new byte[0]);
        put32(badSignature, 0, 0);
        assertInvalid(badSignature);

        byte[] splitDiskEntry = classic(
                new int[][] {{0, 0, 0}}, 1, 0, new byte[0]);
        put16(splitDiskEntry, 34, 1);
        assertInvalid(splitDiskEntry);
    }

    @Test
    public void rejectsZip64LocatorDiskAndOffsetErrors()
            throws Exception {
        byte[] locatorDisk = zip64(new int[0][0], 10001);
        put32(locatorDisk, 60, 1);
        assertInvalid(locatorDisk);

        byte[] totalDisks = zip64(new int[0][0], 10001);
        put32(totalDisks, 72, 2);
        assertInvalid(totalDisks);

        byte[] recordOffset = zip64(new int[0][0], 10001);
        put64(recordOffset, 64, 999);
        assertInvalid(recordOffset);
    }

    @Test
    public void rejectsZip64RecordDiskCountAndBoundsErrors()
            throws Exception {
        byte[] recordDisk = zip64(new int[0][0], 10001);
        put32(recordDisk, 16, 1);
        assertInvalid(recordDisk);

        byte[] splitEntries = zip64(new int[0][0], 10001);
        put64(splitEntries, 24, 10000);
        assertInvalid(splitEntries);

        byte[] shortRecord = zip64(new int[0][0], 10001);
        put64(shortRecord, 4, 43);
        assertInvalid(shortRecord);

        byte[] inconsistentClassicField =
                zip64(new int[0][0], 10001);
        put16(inconsistentClassicField, 84, 7);
        assertInvalid(inconsistentClassicField);
    }

    @Test
    public void rejectsUnsignedZip64Overflow() throws Exception {
        byte[] entryCount = zip64(new int[0][0], 10001);
        put64(entryCount, 32, Long.MIN_VALUE);
        assertInvalid(entryCount);

        byte[] centralSize = zip64(new int[0][0], 10001);
        put64(centralSize, 40, Long.MIN_VALUE);
        assertInvalid(centralSize);

        byte[] centralOffset = zip64(new int[0][0], 10001);
        put64(centralOffset, 48, Long.MIN_VALUE);
        assertInvalid(centralOffset);
    }

    @Test
    public void inspectsARealFileWithApi9Io() throws Exception {
        byte[] archive = classic(
                new int[][] {{4, 2, 1}}, 1, 0, new byte[0]);
        File file = File.createTempFile("nanidroid-preflight", ".nar");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(archive);
        } finally {
            if (output != null) {
                output.close();
            }
        }
        try {
            assertEquals(
                    1,
                    NarZipCentralPreflight.inspect(file)
                            .getEntryCount());
        } finally {
            assertTrue(file.delete());
        }
    }

    private static NarZipCentralPreflight.Result inspect(
            byte[] archive) throws IOException {
        return NarZipCentralPreflight.inspect(
                new MemoryRandomAccess(archive));
    }

    private static void assertInvalid(byte[] archive)
            throws Exception {
        try {
            inspect(archive);
            throw new AssertionError("invalid central directory accepted");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static byte[] classic(
            int[][] lengths,
            int declaredRecords,
            int disk,
            byte[] comment) {
        int centralSize = centralSize(lengths);
        byte[] archive = new byte[
                centralSize + 22 + comment.length];
        writeCentral(archive, lengths);
        int eocd = centralSize;
        put32(archive, eocd, EOCD);
        put16(archive, eocd + 4, disk);
        put16(archive, eocd + 6, disk);
        put16(archive, eocd + 8, declaredRecords);
        put16(archive, eocd + 10, declaredRecords);
        put32(archive, eocd + 12, centralSize);
        put32(archive, eocd + 16, 0);
        put16(archive, eocd + 20, comment.length);
        System.arraycopy(
                comment, 0, archive, eocd + 22, comment.length);
        return archive;
    }

    private static byte[] zip64(
            int[][] lengths, long declaredRecords) {
        int centralSize = centralSize(lengths);
        int record = centralSize;
        int locator = record + 56;
        int eocd = locator + 20;
        byte[] archive = new byte[eocd + 22];
        writeCentral(archive, lengths);
        put32(archive, record, ZIP64_EOCD);
        put64(archive, record + 4, 44);
        put64(archive, record + 24, declaredRecords);
        put64(archive, record + 32, declaredRecords);
        put64(archive, record + 40, centralSize);
        put64(archive, record + 48, 0);
        put32(archive, locator, ZIP64_LOCATOR);
        put32(archive, locator + 4, 0);
        put64(archive, locator + 8, record);
        put32(archive, locator + 16, 1);
        put32(archive, eocd, EOCD);
        put16(archive, eocd + 8, 0xffff);
        put16(archive, eocd + 10, 0xffff);
        put32(archive, eocd + 12, 0xffffffffL);
        put32(archive, eocd + 16, 0xffffffffL);
        return archive;
    }

    private static int centralSize(int[][] lengths) {
        int total = 0;
        for (int[] record : lengths) {
            total += 46 + record[0] + record[1] + record[2];
        }
        return total;
    }

    private static int[][] zeroLengthRecords(int count) {
        return new int[count][3];
    }

    private static void writeCentral(
            byte[] archive, int[][] lengths) {
        int cursor = 0;
        for (int[] record : lengths) {
            put32(archive, cursor, CENTRAL);
            put16(archive, cursor + 28, record[0]);
            put16(archive, cursor + 30, record[1]);
            put16(archive, cursor + 32, record[2]);
            cursor += 46 + record[0] + record[1] + record[2];
        }
    }

    private static void put16(
            byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(
            byte[] target, int offset, long value) {
        for (int index = 0; index < 4; index++) {
            target[offset + index] =
                    (byte) (value >>> (8 * index));
        }
    }

    private static void put64(
            byte[] target, int offset, long value) {
        for (int index = 0; index < 8; index++) {
            target[offset + index] =
                    (byte) (value >>> (8 * index));
        }
    }

    private static final class MemoryRandomAccess
            implements NarZipCentralPreflight.RandomAccessSource {
        private final byte[] content;

        private MemoryRandomAccess(byte[] content) {
            this.content = content;
        }

        @Override public long length() {
            return content.length;
        }

        @Override public void readFully(
                long position,
                byte[] target,
                int offset,
                int length) throws IOException {
            if (position < 0
                    || position > content.length
                    || length > content.length - position) {
                throw new IOException("test source bounds");
            }
            System.arraycopy(
                    content, (int) position, target, offset, length);
        }
    }
}
