package com.cattailsw.nanidroid.install;

import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure structural policy over caller-supplied central-directory records. */
public final class NarArchiveInventoryValidator {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_RAW_NAME_CHARS = 4096;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_PATH_BYTES = 1024;
    private static final int MAX_COMPONENT_BYTES = 255;
    private static final long MAX_DESCRIPTOR_SIZE = 64L * 1024L;
    private static final long MAX_ENTRY_SIZE = 128L * 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE = 512L * 1024L * 1024L;
    private static final long MAX_RATIO = 1000L;

    public NarArchiveInventoryResult validate(
            List<? extends CentralEntry> centralEntries) {
        try {
            return NarArchiveInventoryResult.success(inspect(centralEntries));
        } catch (Rejected rejected) {
            return NarArchiveInventoryResult.failure(
                    rejected.error, rejected.getMessage());
        }
    }

    private NarArchiveInventory inspect(
            List<? extends CentralEntry> centralEntries) throws Rejected {
        if (centralEntries == null) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, "null inventory");
        }
        if (centralEntries.size() > MAX_ENTRIES) {
            reject(NarInstallError.ENTRY_COUNT_LIMIT, "too many entries");
        }

        List<Item> items = new ArrayList<Item>();
        Map<String, Item> entriesByKey = new HashMap<String, Item>();
        Map<String, String> directorySpellings = new HashMap<String, String>();
        Set<String> implicitDirectories = new HashSet<String>();
        for (int index = 0; index < centralEntries.size(); index++) {
            Snapshot source = snapshot(centralEntries.get(index));
            if (source.ordinal != index
                    || source.declaredSize < -1
                    || source.compressedSize < -1
                    || source.crc < -1
                    || source.crc > 0xffffffffL
                    || (source.method != -1
                            && source.method != 0
                            && source.method != 8)) {
                reject(NarInstallError.INVALID_ENTRY_METADATA, "central metadata");
            }
            Path path = normalize(source);
            Item item = new Item(source, path);
            Item previous = entriesByKey.get(path.key);
            if (previous != null) {
                if (previous.source.directory != source.directory) {
                    reject(NarInstallError.FILE_DIRECTORY_COLLISION, path.normalized);
                }
                reject(
                        previous.path.original.equals(path.original)
                                ? NarInstallError.DUPLICATE_ENTRY
                                : NarInstallError.NORMALIZED_COLLISION,
                        path.normalized);
            }

            String ancestor = path.key;
            int slash = ancestor.lastIndexOf('/');
            while (slash >= 0) {
                ancestor = ancestor.substring(0, slash);
                Item parent = entriesByKey.get(ancestor);
                if (parent != null && !parent.source.directory) {
                    reject(NarInstallError.FILE_DIRECTORY_COLLISION, path.normalized);
                }
                implicitDirectories.add(ancestor);
                slash = ancestor.lastIndexOf('/');
            }
            if (!source.directory && implicitDirectories.contains(path.key)) {
                reject(NarInstallError.FILE_DIRECTORY_COLLISION, path.normalized);
            }
            recordDirectorySpellings(item, directorySpellings);
            entriesByKey.put(path.key, item);
            items.add(item);
        }

        Layout layout = layout(items);
        if (layout.descriptor.source.declaredSize > MAX_DESCRIPTOR_SIZE) {
            reject(
                    NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
                    layout.descriptor.path.normalized);
        }
        long totalSize = 0;
        long totalCompressed = 0;
        boolean totalSizeKnown = true;
        boolean totalRatioKnown = true;
        for (Item item : items) {
            long size = item.source.declaredSize;
            long compressed = item.source.compressedSize;
            if (size > MAX_ENTRY_SIZE) {
                reject(
                        NarInstallError.DECLARED_ENTRY_SIZE_LIMIT,
                        item.path.normalized);
            }
            if (size >= 0) {
                if (size > MAX_TOTAL_SIZE - totalSize) {
                    reject(
                            NarInstallError.DECLARED_TOTAL_SIZE_LIMIT,
                            item.path.normalized);
                }
                totalSize += size;
            }
            if (size < 0 || compressed < 0) {
                if (size < 0) {
                    totalSizeKnown = false;
                }
                totalRatioKnown = false;
            } else if (ratioTooHigh(size, compressed)) {
                reject(NarInstallError.DECLARED_RATIO_LIMIT, item.path.normalized);
            } else if (compressed > Long.MAX_VALUE - totalCompressed) {
                totalRatioKnown = false;
            } else {
                totalCompressed += compressed;
            }
        }
        if (totalRatioKnown && ratioTooHigh(totalSize, totalCompressed)) {
            reject(NarInstallError.DECLARED_RATIO_LIMIT, "archive total");
        }

        List<NarArchiveInventory.Entry> output =
                new ArrayList<NarArchiveInventory.Entry>();
        for (Item item : items) {
            String relative = item.path.normalized;
            if (layout.wrapper != null) {
                relative = relative.equals(layout.wrapper)
                        ? null
                        : relative.substring(layout.wrapper.length() + 1);
            }
            Snapshot source = item.source;
            output.add(new NarArchiveInventory.Entry(
                    source.ordinal,
                    source.rawName,
                    item.path.normalized,
                    relative,
                    source.directory,
                    source.crc,
                    source.method,
                    source.declaredSize,
                    source.compressedSize));
        }
        return new NarArchiveInventory(
                output,
                layout.wrapper,
                layout.descriptor.source.ordinal,
                totalSizeKnown ? totalSize : -1);
    }

    private static Snapshot snapshot(CentralEntry entry) throws Rejected {
        if (entry == null) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, "null entry");
        }
        try {
            return new Snapshot(
                    entry.getOrdinal(),
                    entry.getRawName(),
                    entry.isDirectory(),
                    entry.getCrc(),
                    entry.getMethod(),
                    entry.getDeclaredSize(),
                    entry.getCompressedSize());
        } catch (RuntimeException error) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, "central getter");
            return null;
        }
    }

    private static Path normalize(Snapshot entry) throws Rejected {
        String raw = entry.rawName;
        if (raw == null) {
            reject(NarInstallError.INVALID_PATH, "null name");
        }
        if (raw.length() > MAX_RAW_NAME_CHARS) {
            reject(NarInstallError.RAW_NAME_LENGTH_LIMIT, "raw name");
        }
        if (entry.directory != raw.endsWith("/")) {
            reject(NarInstallError.INVALID_ENTRY_METADATA, raw);
        }
        if (raw.startsWith("/")
                || raw.indexOf('\\') >= 0
                || !validUnicode(raw)) {
            reject(NarInstallError.INVALID_PATH, raw);
        }
        String original = entry.directory
                ? raw.substring(0, raw.length() - 1)
                : raw;
        if (original.length() == 0) {
            reject(NarInstallError.INVALID_PATH, raw);
        }
        String[] components = original.split("/", -1);
        if (components.length > MAX_DEPTH) {
            reject(NarInstallError.PATH_DEPTH_LIMIT, raw);
        }
        StringBuilder normalized = new StringBuilder();
        for (String component : components) {
            if (component.length() == 0
                    || ".".equals(component)
                    || "..".equals(component)
                    || component.indexOf(':') >= 0
                    || containsControl(component)) {
                reject(NarInstallError.INVALID_PATH, raw);
            }
            String nfc = Normalizer.normalize(component, Normalizer.Form.NFC);
            if (nfc.getBytes(UTF_8).length > MAX_COMPONENT_BYTES) {
                reject(NarInstallError.COMPONENT_LENGTH_LIMIT, raw);
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(nfc);
        }
        String path = normalized.toString();
        if (path.getBytes(UTF_8).length > MAX_PATH_BYTES) {
            reject(NarInstallError.PATH_LENGTH_LIMIT, raw);
        }
        return new Path(original, path, collisionKey(path));
    }

    private static void recordDirectorySpellings(
            Item item,
            Map<String, String> spellings) throws Rejected {
        String[] raw = item.path.original.split("/", -1);
        String[] normalized = item.path.normalized.split("/", -1);
        int count = item.source.directory ? raw.length : raw.length - 1;
        String rawPrefix = "";
        String normalizedPrefix = "";
        for (int index = 0; index < count; index++) {
            rawPrefix += (index == 0 ? "" : "/") + raw[index];
            normalizedPrefix += (index == 0 ? "" : "/") + normalized[index];
            String key = collisionKey(normalizedPrefix);
            String previous = spellings.get(key);
            if (previous != null && !previous.equals(rawPrefix)) {
                reject(NarInstallError.NORMALIZED_COLLISION, normalizedPrefix);
            }
            spellings.put(key, rawPrefix);
        }
    }

    private static Layout layout(List<Item> items) throws Rejected {
        List<Item> candidates = new ArrayList<Item>();
        boolean deep = false;
        for (Item item : items) {
            String[] components = item.path.normalized.split("/", -1);
            boolean descriptor = !item.source.directory
                    && "install.txt".equals(components[components.length - 1]);
            if (descriptor && components.length <= 2) {
                candidates.add(item);
            } else if (descriptor) {
                deep = true;
            }
        }
        if (candidates.size() > 1) {
            reject(NarInstallError.AMBIGUOUS_LAYOUT, "multiple install.txt");
        }
        if (deep) {
            reject(NarInstallError.INVALID_LAYOUT, "deep install.txt");
        }
        if (candidates.isEmpty()) {
            reject(
                    NarInstallError.MISSING_INSTALL_DESCRIPTOR,
                    "no supported install.txt");
        }
        Item descriptor = candidates.get(0);
        int slash = descriptor.path.normalized.indexOf('/');
        String wrapper = slash < 0
                ? null
                : descriptor.path.normalized.substring(0, slash);
        if (wrapper != null) {
            for (Item item : items) {
                if (!item.path.normalized.equals(wrapper)
                        && !item.path.normalized.startsWith(wrapper + "/")) {
                    reject(NarInstallError.MIXED_LAYOUT, item.path.normalized);
                }
            }
        }
        return new Layout(descriptor, wrapper);
    }

    private static boolean ratioTooHigh(long size, long compressed) {
        if (size <= 0) {
            return false;
        }
        return compressed <= 0
                || (compressed <= Long.MAX_VALUE / MAX_RATIO
                        && size > compressed * MAX_RATIO);
    }

    private static String collisionKey(String value) {
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
        return Normalizer.normalize(
                nfc.toLowerCase(Locale.US),
                Normalizer.Form.NFC);
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean validUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static void reject(NarInstallError error, String detail)
            throws Rejected {
        throw new Rejected(error, detail);
    }

    interface CentralEntry {
        int getOrdinal(); String getRawName(); boolean isDirectory();
        long getCrc(); int getMethod();
        long getDeclaredSize(); long getCompressedSize();
    }

    private static final class Item {
        private final Snapshot source;
        private final Path path;
        private Item(Snapshot source, Path path) {
            this.source = source;
            this.path = path;
        }
    }

    private static final class Snapshot {
        private final int ordinal;
        private final String rawName;
        private final boolean directory;
        private final long crc;
        private final int method;
        private final long declaredSize;
        private final long compressedSize;

        private Snapshot(
                int ordinal,
                String rawName,
                boolean directory,
                long crc,
                int method,
                long declaredSize,
                long compressedSize) {
            this.ordinal = ordinal;
            this.rawName = rawName;
            this.directory = directory;
            this.crc = crc;
            this.method = method;
            this.declaredSize = declaredSize;
            this.compressedSize = compressedSize;
        }
    }

    private static final class Path {
        private final String original;
        private final String normalized;
        private final String key;
        private Path(String original, String normalized, String key) {
            this.original = original;
            this.normalized = normalized;
            this.key = key;
        }
    }

    private static final class Layout {
        private final Item descriptor;
        private final String wrapper;
        private Layout(Item descriptor, String wrapper) {
            this.descriptor = descriptor;
            this.wrapper = wrapper;
        }
    }

    private static final class Rejected extends Exception {
        private final NarInstallError error;
        private Rejected(NarInstallError error, String detail) {
            super(detail);
            this.error = error;
        }
    }
}
