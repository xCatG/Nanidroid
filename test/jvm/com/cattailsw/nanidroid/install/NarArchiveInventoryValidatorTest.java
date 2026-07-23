package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public final class NarArchiveInventoryValidatorTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long MIB = 1024L * 1024L;
    private static final long FUZZ_SEED = 0x4e415244396231L;

    @Test
    public void inventoriesRootLayoutAndPreservesCentralIdentity() {
        Record descriptor = record("install.txt");
        Record payload = record("ghost/master/file.bin");
        payload.crc = 0x1234abcdL;
        payload.method = 8;
        payload.size = 321;
        payload.compressedSize = 123;

        NarArchiveInventoryResult result = validate(descriptor, payload);

        assertTrue(result.isSuccess());
        NarArchiveInventory inventory = result.getInventory();
        assertNull(inventory.getWrapperDirectory());
        assertEquals(0, inventory.getDescriptorOrdinal());
        assertEquals(2, inventory.getEntries().size());
        NarArchiveInventory.Entry planned = inventory.getEntries().get(1);
        assertEquals(1, planned.getOrdinal());
        assertEquals(payload.rawName, planned.getRawName());
        assertEquals(payload.rawName, planned.getNormalizedArchivePath());
        assertEquals(payload.rawName, planned.getRelativePath());
        assertEquals(payload.crc, planned.getCrc());
        assertEquals(payload.method, planned.getMethod());
        assertEquals(payload.size, planned.getDeclaredSize());
        assertEquals(payload.compressedSize, planned.getCompressedSize());
        try {
            inventory.getEntries().clear();
            throw new AssertionError("inventory entries must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void stripsOneUniformWrapperButRetainsEveryRawEntry() {
        NarArchiveInventoryResult result = validate(
                directory("bundle/"),
                record("bundle/install.txt"),
                record("bundle/ghost/master/file.txt"));

        assertTrue(result.isSuccess());
        NarArchiveInventory inventory = result.getInventory();
        assertEquals("bundle", inventory.getWrapperDirectory());
        assertEquals(1, inventory.getDescriptorOrdinal());
        assertEquals(
                Arrays.asList("", "install.txt", "ghost/master/file.txt"),
                relativePaths(inventory));
        assertEquals("bundle/", inventory.getEntries().get(0).getRawName());
    }

    @Test
    public void rejectsUnsupportedDescriptorLayouts() {
        assertError(
                NarInstallError.MISSING_INSTALL_DESCRIPTOR,
                validate(record("readme.txt")));
        assertError(
                NarInstallError.MISSING_INSTALL_DESCRIPTOR,
                validate(record("INSTALL.TXT")));
        assertError(
                NarInstallError.AMBIGUOUS_LAYOUT,
                validate(record("install.txt"), record("bundle/install.txt")));
        assertError(
                NarInstallError.AMBIGUOUS_LAYOUT,
                validate(record("one/install.txt"), record("two/install.txt")));
        assertError(
                NarInstallError.MIXED_LAYOUT,
                validate(record("bundle/install.txt"), record("sibling.txt")));
        assertError(
                NarInstallError.INVALID_LAYOUT,
                validate(record("one/two/install.txt")));
    }

    @Test
    public void rejectsHostilePaths() {
        String[] hostile = {
            "../escape",
            "/absolute",
            "nested\\file",
            "C:drive",
            "\\\\server\\share",
            "bad\u0000name",
            "bad\u0001name",
            "./file",
            "one/../file",
            "one//file",
            "bad\uD800name",
        };
        for (String path : hostile) {
            assertError(
                    NarInstallError.INVALID_PATH,
                    validate(record("install.txt"), record(path)));
        }
    }

    @Test
    public void distinguishesDuplicatesNormalizationAndImplicitCollisions() {
        assertError(
                NarInstallError.DUPLICATE_ENTRY,
                validate(
                        record("install.txt"),
                        record("ghost/file"),
                        record("ghost/file")));
        assertError(
                NarInstallError.NORMALIZED_COLLISION,
                validate(
                        record("install.txt"),
                        record("Ghost/File"),
                        record("ghost/file")));

        String composed = Normalizer.normalize("caf\u00e9", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        assertError(
                NarInstallError.NORMALIZED_COLLISION,
                validate(
                        record("install.txt"),
                        record(composed + "/one"),
                        record(decomposed + "/two")));
        assertError(
                NarInstallError.NORMALIZED_COLLISION,
                validate(
                        record("install.txt"),
                        record("Ghost/one"),
                        record("ghost/two")));
    }

    @Test
    public void mapsRawNfdNameToNfcOutputWithoutLosingIdentity() {
        String normalized = "ghost/"
                + Normalizer.normalize("caf\u00e9.txt", Normalizer.Form.NFC);
        String raw = Normalizer.normalize(normalized, Normalizer.Form.NFD);

        NarArchiveInventoryResult result =
                validate(record("install.txt"), record(raw));

        assertTrue(result.isSuccess());
        NarArchiveInventory.Entry entry = result.getInventory().getEntries().get(1);
        assertEquals(raw, entry.getRawName());
        assertEquals(normalized, entry.getNormalizedArchivePath());
        assertEquals(normalized, entry.getRelativePath());
    }

    @Test
    public void rejectsFileDirectoryCollisionsInEveryOrdering() {
        assertError(
                NarInstallError.FILE_DIRECTORY_COLLISION,
                validate(
                        record("install.txt"),
                        record("ghost"),
                        record("ghost/master/file")));
        assertError(
                NarInstallError.FILE_DIRECTORY_COLLISION,
                validate(
                        record("install.txt"),
                        record("ghost/master/file"),
                        record("ghost")));
        assertError(
                NarInstallError.FILE_DIRECTORY_COLLISION,
                validate(
                        record("install.txt"),
                        record("ghost"),
                        directory("ghost/")));
    }

    @Test
    public void enforcesExactCountDepthPathComponentAndRawNameBoundaries() {
        List<Record> entries = new ArrayList<Record>();
        entries.add(record("install.txt"));
        for (int index = 1; index < 10000; index++) {
            entries.add(record("payload/" + index));
        }
        assertTrue(validate(entries).isSuccess());
        entries.add(record("payload/overflow"));
        assertError(NarInstallError.ENTRY_COUNT_LIMIT, validate(entries));

        assertTrue(validateWithPath(repeatedPath(32, 1)).isSuccess());
        assertError(
                NarInstallError.PATH_DEPTH_LIMIT,
                validateWithPath(repeatedPath(33, 1)));
        assertTrue(validateWithPath(asciiPath(1024)).isSuccess());
        assertError(
                NarInstallError.PATH_LENGTH_LIMIT,
                validateWithPath(asciiPath(1025)));
        assertTrue(validateWithPath(repeat('a', 255)).isSuccess());
        assertError(
                NarInstallError.COMPONENT_LENGTH_LIMIT,
                validateWithPath(repeat('a', 256)));

        String malformedAtLimit = repeat('a', 4095) + "\uD800";
        assertError(
                NarInstallError.INVALID_PATH,
                validateWithPath(malformedAtLimit));
        assertError(
                NarInstallError.RAW_NAME_LENGTH_LIMIT,
                validateWithPath(malformedAtLimit + "a"));
    }

    @Test
    public void enforcesDescriptorAndDeclaredSizeRatioBoundaries() {
        Record descriptor = record("install.txt");
        descriptor.size = 64 * 1024;
        descriptor.compressedSize = -1;
        assertTrue(validate(descriptor).isSuccess());
        descriptor.size++;
        assertError(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, validate(descriptor));

        descriptor = record("install.txt");
        Record payload = sized("payload", 128 * MIB, -1);
        assertTrue(validate(descriptor, payload).isSuccess());
        payload.size++;
        assertError(
                NarInstallError.DECLARED_ENTRY_SIZE_LIMIT,
                validate(descriptor, payload));

        List<Record> total = new ArrayList<Record>();
        total.add(descriptor);
        for (int index = 0; index < 4; index++) {
            total.add(sized("part-" + index, 128 * MIB, -1));
        }
        assertTrue(validate(total).isSuccess());
        total.add(sized("overflow", 1, -1));
        assertError(NarInstallError.DECLARED_TOTAL_SIZE_LIMIT, validate(total));

        assertTrue(validate(descriptor, sized("ratio", 1000, 1)).isSuccess());
        assertError(
                NarInstallError.DECLARED_RATIO_LIMIT,
                validate(descriptor, sized("ratio", 1001, 1)));
        assertTrue(validate(descriptor, sized("unknown", -1, -1)).isSuccess());
    }

    @Test
    public void rejectsNonContiguousCentralOrdinals() {
        Record descriptor = record("install.txt");
        descriptor.ordinal = 7;
        assertError(
                NarInstallError.INVALID_ENTRY_METADATA,
                validateWithoutOrdinalAssignment(descriptor));
    }

    @Test
    public void deterministicBoundedFuzzRejectsHostilePaths() {
        Random random = new Random(FUZZ_SEED);
        for (int index = 0; index < 512; index++) {
            String suffix = repeat(
                    (char) ('a' + random.nextInt(26)),
                    random.nextInt(65));
            String[] attacks = {
                "../" + suffix,
                "/" + suffix,
                suffix + "\\file",
                "C:" + suffix,
                suffix + "//file",
                suffix + "/./file",
                suffix + "/\u0001file",
                suffix + "/\uD800file",
            };
            NarArchiveInventoryResult result = validate(
                    record("install.txt"),
                    record(attacks[index % attacks.length]));
            assertFalse("fuzz case accepted at " + index, result.isSuccess());
        }
    }

    private static NarArchiveInventoryResult validate(Record... records) {
        return validate(new ArrayList<Record>(Arrays.asList(records)));
    }

    private static NarArchiveInventoryResult validate(List<Record> records) {
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).ordinal < 0) {
                records.get(index).ordinal = index;
            }
        }
        return validateWithoutOrdinalAssignment(records);
    }

    private static NarArchiveInventoryResult validateWithoutOrdinalAssignment(
            Record... records) {
        return validateWithoutOrdinalAssignment(Arrays.asList(records));
    }

    private static NarArchiveInventoryResult validateWithoutOrdinalAssignment(
            List<Record> records) {
        return new NarArchiveInventoryValidator().validate(records);
    }

    private static NarArchiveInventoryResult validateWithPath(String path) {
        return validate(record("install.txt"), record(path));
    }

    private static void assertError(
            NarInstallError expected,
            NarArchiveInventoryResult result) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.getError());
        assertNull(result.getInventory());
    }

    private static List<String> relativePaths(NarArchiveInventory inventory) {
        List<String> paths = new ArrayList<String>();
        for (NarArchiveInventory.Entry entry : inventory.getEntries()) {
            paths.add(entry.getRelativePath());
        }
        return paths;
    }

    private static Record record(String name) {
        return new Record(name, name.endsWith("/"));
    }

    private static Record directory(String name) {
        return new Record(name, true);
    }

    private static Record sized(String name, long size, long compressedSize) {
        Record result = record(name);
        result.size = size;
        result.compressedSize = compressedSize;
        return result;
    }

    private static String repeatedPath(int count, int componentLength) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append('/');
            }
            result.append(repeat('a', componentLength));
        }
        return result.toString();
    }

    private static String asciiPath(int byteCount) {
        int letters = byteCount - 4;
        int base = letters / 5;
        int remainder = letters % 5;
        StringBuilder result = new StringBuilder(byteCount);
        for (int index = 0; index < 5; index++) {
            if (index > 0) {
                result.append('/');
            }
            result.append(repeat('a', base + (index < remainder ? 1 : 0)));
        }
        assertEquals(byteCount, result.toString().getBytes(UTF_8).length);
        return result.toString();
    }

    private static String repeat(char value, int count) {
        char[] content = new char[count];
        Arrays.fill(content, value);
        return new String(content);
    }

    private static final class Record
            implements NarArchiveInventoryValidator.CentralEntry {
        private int ordinal = -1;
        private final String rawName;
        private final boolean directory;
        private long crc = -1;
        private int method = -1;
        private long size = 0;
        private long compressedSize = 0;

        private Record(String rawName, boolean directory) {
            this.rawName = rawName;
            this.directory = directory;
        }

        @Override
        public int getOrdinal() {
            return ordinal;
        }

        @Override
        public String getRawName() {
            return rawName;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public long getCrc() {
            return crc;
        }

        @Override
        public int getMethod() {
            return method;
        }

        @Override
        public long getDeclaredSize() {
            return size;
        }

        @Override
        public long getCompressedSize() {
            return compressedSize;
        }
    }
}
