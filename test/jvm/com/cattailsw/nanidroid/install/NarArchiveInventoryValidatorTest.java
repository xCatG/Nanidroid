package com.cattailsw.nanidroid.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

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

    @Test
    public void kotlinInventoryResultKeepsJavaDiagnosticFactorySemantics() {
        NarArchiveInventoryResult failure = NarArchiveInventoryResult.failure(
                NarInstallError.INVALID_ENTRY_METADATA, "central getter");

        assertFalse(failure.isSuccess());
        assertNull(failure.getInventory());
        assertEquals(NarInstallError.INVALID_ENTRY_METADATA, failure.getError());
        assertEquals("central getter", failure.getDetail());
    }
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
                Arrays.asList(null, "install.txt", "ghost/master/file.txt"),
                relativePaths(inventory));
        assertEquals("bundle/", inventory.getEntries().get(0).getRawName());
        assertFalse(inventory.getEntries().get(0).isInstallEntry());
        assertTrue(inventory.getEntries().get(1).isInstallEntry());
    }

    @Test
    public void rejectsUnsupportedDescriptorLayouts() {
        rejectsNames(NarInstallError.MISSING_INSTALL_DESCRIPTOR, "readme.txt");
        rejectsNames(NarInstallError.MISSING_INSTALL_DESCRIPTOR, "INSTALL.TXT");
        rejectsNames(
                NarInstallError.AMBIGUOUS_LAYOUT,
                "install.txt", "bundle/install.txt");
        rejectsNames(
                NarInstallError.AMBIGUOUS_LAYOUT,
                "one/install.txt", "two/install.txt");
        rejectsNames(
                NarInstallError.MIXED_LAYOUT,
                "bundle/install.txt", "sibling.txt");
        rejectsNames(NarInstallError.INVALID_LAYOUT, "one/two/install.txt");
        rejectsNames(
                NarInstallError.INVALID_LAYOUT,
                "install.txt", "one/two/install.txt");
        rejectsNames(
                NarInstallError.MISSING_INSTALL_DESCRIPTOR, "install.txt/");
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
            rejectsNames(NarInstallError.INVALID_PATH, "install.txt", path);
        }
    }

    @Test
    public void distinguishesDuplicatesNormalizationAndImplicitCollisions() {
        rejectsNames(
                NarInstallError.DUPLICATE_ENTRY,
                "install.txt", "ghost/file", "ghost/file");
        rejectsNames(
                NarInstallError.NORMALIZED_COLLISION,
                "install.txt", "Ghost/File", "ghost/file");

        String composed = Normalizer.normalize("caf\u00e9", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        rejectsNames(
                NarInstallError.NORMALIZED_COLLISION,
                "install.txt", composed + "/one", decomposed + "/two");
        rejectsNames(
                NarInstallError.NORMALIZED_COLLISION,
                "install.txt", "Ghost/one", "ghost/two");
    }

    @Test
    public void lowerCaseThenNfcCollisionIsRejectedInBothOrders() {
        String decomposedAfterLowerCase = "J\u030C";
        String composedLowerCase = "\u01F0";
        String[][] orders = {
            {decomposedAfterLowerCase, composedLowerCase},
            {composedLowerCase, decomposedAfterLowerCase},
        };
        for (String[] order : orders) {
            rejectsNames(
                    NarInstallError.NORMALIZED_COLLISION,
                    "install.txt", order[0], order[1]);
            rejectsNames(
                    NarInstallError.NORMALIZED_COLLISION,
                    "install.txt", order[0] + "/one", order[1] + "/two");
        }
    }

    @Test
    public void snapshotsEachCentralGetterExactlyOnce() {
        OneShotEntry descriptor = new OneShotEntry(-1);

        NarArchiveInventoryResult result =
                new NarArchiveInventoryValidator().validate(
                        Arrays.asList(descriptor));

        assertTrue(result.isSuccess());
        assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1, 1}, descriptor.reads);
        NarArchiveInventory.Entry entry = result.getInventory().getEntries().get(0);
        assertEquals("install.txt", entry.getRawName());
        assertEquals(0x1234L, entry.getCrc());
        assertEquals(8, entry.getMethod());
        assertEquals(10, entry.getDeclaredSize());
        assertEquals(6, entry.getCompressedSize());
    }

    @Test
    public void mapsCentralGetterRuntimeFailureToTypedError() {
        NarArchiveInventoryResult result =
                new NarArchiveInventoryValidator().validate(
                        Arrays.asList(new OneShotEntry(1)));

        assertError(NarInstallError.INVALID_ENTRY_METADATA, result);
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
        rejectsNames(
                NarInstallError.FILE_DIRECTORY_COLLISION,
                "install.txt", "ghost", "ghost/master/file");
        rejectsNames(
                NarInstallError.FILE_DIRECTORY_COLLISION,
                "install.txt", "ghost/master/file", "ghost");
        rejectsNames(
                NarInstallError.FILE_DIRECTORY_COLLISION,
                "install.txt", "ghost", "ghost/");
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
        rejectsPath(NarInstallError.PATH_DEPTH_LIMIT, repeatedPath(33, 1));
        assertTrue(validateWithPath(asciiPath(1024)).isSuccess());
        rejectsPath(NarInstallError.PATH_LENGTH_LIMIT, asciiPath(1025));
        assertTrue(validateWithPath(repeat('a', 255)).isSuccess());
        rejectsPath(NarInstallError.COMPONENT_LENGTH_LIMIT, repeat('a', 256));

        String malformedAtLimit = repeat('a', 4095) + "\uD800";
        rejectsPath(NarInstallError.INVALID_PATH, malformedAtLimit);
        rejectsPath(NarInstallError.RAW_NAME_LENGTH_LIMIT, malformedAtLimit + "a");
    }

    @Test
    public void enforcesDescriptorAndDeclaredSizeRatioBoundaries() {
        Record descriptor = record("install.txt");
        descriptor.size = 64 * 1024;
        descriptor.compressedSize = -1;
        assertTrue(validate(descriptor).isSuccess());
        descriptor.size++;
        rejects(NarInstallError.INSTALL_DESCRIPTOR_LIMIT, descriptor);

        descriptor = record("install.txt");
        Record payload = sized("payload", 128 * MIB, -1);
        assertTrue(validate(descriptor, payload).isSuccess());
        payload.size++;
        rejects(NarInstallError.DECLARED_ENTRY_SIZE_LIMIT, descriptor, payload);

        List<Record> total = new ArrayList<Record>();
        total.add(descriptor);
        for (int index = 0; index < 4; index++) {
            total.add(sized("part-" + index, 128 * MIB, -1));
        }
        assertTrue(validate(total).isSuccess());
        total.add(sized("overflow", 1, -1));
        assertError(NarInstallError.DECLARED_TOTAL_SIZE_LIMIT, validate(total));

        assertTrue(validate(descriptor, sized("ratio", 1000, 1)).isSuccess());
        rejects(
                NarInstallError.DECLARED_RATIO_LIMIT,
                descriptor, sized("ratio", 1001, 1));
        rejects(
                NarInstallError.DECLARED_RATIO_LIMIT,
                descriptor, sized("ratio", 1, 0));
        assertTrue(validate(descriptor, sized("unknown", -1, -1)).isSuccess());
    }

    @Test
    public void rejectsInvalidCentralRecordsAndAllowsDocumentedUnknowns() {
        List<Record> nullRecord = new ArrayList<Record>();
        nullRecord.add(null);
        assertError(
                NarInstallError.INVALID_ENTRY_METADATA,
                validateWithoutOrdinalAssignment(nullRecord));
        rejects(NarInstallError.INVALID_PATH, new Record(null, false));
        rejects(NarInstallError.INVALID_PATH, record(""));
        rejects(NarInstallError.INVALID_ENTRY_METADATA, new Record("file/", false));
        rejects(
                NarInstallError.INVALID_ENTRY_METADATA,
                new Record("directory", true));

        Record descriptor = record("install.txt");
        assertError(
                NarInstallError.INVALID_ENTRY_METADATA,
                validateWithoutOrdinalAssignment(descriptor));
        descriptor.ordinal = 7;
        assertError(
                NarInstallError.INVALID_ENTRY_METADATA,
                validateWithoutOrdinalAssignment(descriptor));
        Record gap = record("payload");
        descriptor.ordinal = 0;
        gap.ordinal = 2;
        assertError(
                NarInstallError.INVALID_ENTRY_METADATA,
                validateWithoutOrdinalAssignment(descriptor, gap));
        gap.ordinal = 0;
        assertError(
                NarInstallError.INVALID_ENTRY_METADATA,
                validateWithoutOrdinalAssignment(descriptor, gap));

        for (int field = 0; field < 6; field++) {
            Record invalid = record("install.txt");
            invalid.ordinal = 0;
            if (field == 0) invalid.size = -2;
            if (field == 1) invalid.compressedSize = -2;
            if (field == 2) invalid.crc = -2;
            if (field == 3) invalid.crc = 0x100000000L;
            if (field == 4) invalid.method = -2;
            if (field == 5) invalid.method = 7;
            assertError(
                    NarInstallError.INVALID_ENTRY_METADATA,
                    validateWithoutOrdinalAssignment(invalid));
        }
        Record unknown = sized("install.txt", -1, -1);
        unknown.crc = -1;
        unknown.method = -1;
        NarArchiveInventoryResult unknownResult = validate(unknown);
        assertTrue(unknownResult.isSuccess());
        assertEquals(-1, unknownResult.getInventory().getDeclaredTotalSize());
    }

    @Test
    public void deterministicFuzzMutatesValidPathsWithExactErrors() {
        Random random = new Random(FUZZ_SEED);
        for (int index = 0; index < 512; index++) {
            String baseline = "root" + random.nextInt(100000)
                    + "/leaf" + index + ".txt";
            assertTrue(validateWithPath(baseline).isSuccess());
            String[] mutations = {
                "../" + baseline,
                "/" + baseline,
                baseline.replace("/", "//"),
                baseline.replace("/", "\\"),
                baseline + "\u0001",
                baseline.substring(0, baseline.indexOf('/') + 1) + repeat('a', 256),
                repeatedPath(31, 1) + "/" + baseline,
                baseline + "/" + asciiPath(
                        1025 - baseline.getBytes(UTF_8).length - 1),
                baseline + "/" + repeat('a', 4097 - baseline.length() - 1),
                baseline + "\uD800",
            };
            NarInstallError[] errors = {
                NarInstallError.INVALID_PATH,
                NarInstallError.INVALID_PATH,
                NarInstallError.INVALID_PATH,
                NarInstallError.INVALID_PATH,
                NarInstallError.INVALID_PATH,
                NarInstallError.COMPONENT_LENGTH_LIMIT,
                NarInstallError.PATH_DEPTH_LIMIT,
                NarInstallError.PATH_LENGTH_LIMIT,
                NarInstallError.RAW_NAME_LENGTH_LIMIT,
                NarInstallError.INVALID_PATH,
            };
            int mutation = index % mutations.length;
            assertError(errors[mutation], validateWithPath(mutations[mutation]));
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

    private static void rejects(NarInstallError error, Record... records) {
        assertError(error, validate(records));
    }

    private static void rejectsNames(NarInstallError error, String... names) {
        Record[] records = new Record[names.length];
        for (int index = 0; index < names.length; index++) {
            records[index] = record(names[index]);
        }
        rejects(error, records);
    }

    private static void rejectsPath(NarInstallError error, String path) {
        assertError(error, validateWithPath(path));
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

        @Override public int getOrdinal() { return ordinal; }
        @Override public String getRawName() { return rawName; }
        @Override public boolean isDirectory() { return directory; }
        @Override public long getCrc() { return crc; }
        @Override public int getMethod() { return method; }
        @Override public long getDeclaredSize() { return size; }
        @Override public long getCompressedSize() { return compressedSize; }
    }

    private static final class OneShotEntry
            implements NarArchiveInventoryValidator.CentralEntry {
        private final int throwOnFirst;
        private final int[] reads = new int[7];

        private OneShotEntry(int throwOnFirst) {
            this.throwOnFirst = throwOnFirst;
        }

        private Object read(int field, Object value) {
            reads[field]++;
            if (field == throwOnFirst || reads[field] != 1) {
                throw new IllegalStateException("getter " + field);
            }
            return value;
        }

        @Override public int getOrdinal() {
            return ((Integer) read(0, Integer.valueOf(0))).intValue();
        }
        @Override public String getRawName() {
            return (String) read(1, "install.txt");
        }
        @Override public boolean isDirectory() {
            return ((Boolean) read(2, Boolean.FALSE)).booleanValue();
        }
        @Override public long getCrc() {
            return ((Long) read(3, Long.valueOf(0x1234L))).longValue();
        }
        @Override public int getMethod() {
            return ((Integer) read(4, Integer.valueOf(8))).intValue();
        }
        @Override public long getDeclaredSize() {
            return ((Long) read(5, Long.valueOf(10))).longValue();
        }
        @Override public long getCompressedSize() {
            return ((Long) read(6, Long.valueOf(6))).longValue();
        }
    }
}
