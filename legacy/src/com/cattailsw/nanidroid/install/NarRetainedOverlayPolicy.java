package com.cattailsw.nanidroid.install;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
/**
 * Pure diagnostic merge of a staged baseline and a NAR plan.
 *
 * <p>This recipe is not an install authority and performs no I/O. In
 * particular, the diagnostic {@link NarInstallPlan} file fields are never
 * consulted. Later code must retain the verified archive owner independently.
 */
final class NarRetainedOverlayPolicy {
    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_FILE_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    private NarRetainedOverlayPolicy() {}
    static Result build(NarInstallPlan plan, NarGhostTreePolicy.Manifest baseline,
            List<NarStagedTreeInventory.Entry> inventory) {
        try {
            return inspect(plan, baseline, inventory);
        } catch (Rejected rejected) {
            return Result.failure(rejected.error, rejected.getMessage());
        } catch (RuntimeException rejected) {
            return Result.failure(Error.MALFORMED_PLAN, "input");
        }
    }
    private static Result inspect(NarInstallPlan plan, NarGhostTreePolicy.Manifest baseline,
            List<NarStagedTreeInventory.Entry> inventory) throws Rejected {
        require(plan != null, Error.MALFORMED_PLAN, "plan");
        require(baseline != null && inventory != null,
                Error.MALFORMED_BASELINE, "baseline");
        NarInstallDescriptor descriptor = plan.getDescriptor();
        require(descriptor != null, Error.MALFORMED_PLAN, "descriptor");
        require("ghost".equals(descriptor.getType()),
                Error.UNSUPPORTED_TYPE, "type");
        Map<String, String> metadata = descriptor.getMetadata();
        require(metadata != null && "ghost".equals(metadata.get("type")),
                Error.MALFORMED_PLAN, "descriptor type");
        for (Map.Entry<String, String> item : metadata.entrySet()) {
            String key = item.getKey();
            require(!("1".equals(item.getValue())
                            && ("refresh".equals(key)
                                    || (key != null
                                            && key.endsWith(".refresh")))),
                    Error.UNSUPPORTED_REFRESH, "refresh");
        }
        String target = descriptor.getTargetId();
        NarRelativePathPolicy.Result normalized =
                NarRelativePathPolicy.normalize(target);
        require(normalized.isSuccess()
                        && target.equals(normalized.getNormalized())
                        && target.indexOf('/') < 0,
                Error.MALFORMED_PLAN, "target");
        require(target.equals(baseline.getTargetId()),
                Error.TARGET_MISMATCH, "target");
        Map<String, Work> retained =
                prepareBaseline(baseline, inventory);
        Map<String, Work> archive = prepareArchive(plan);
        Map<String, Work> merged = new HashMap<String, Work>(retained);
        List<Work> archiveEntries =
                new ArrayList<Work>(archive.values());
        Collections.sort(archiveEntries, WORK_ORDER);
        for (Work incoming : archiveEntries) {
            Work prior = retained.get(incoming.key);
            if (incoming.type == NarGhostTreePolicy.Type.FILE
                    && prior != null
                    && prior.type == NarGhostTreePolicy.Type.DIRECTORY
                    && hasDescendant(retained, incoming.key)) {
                reject(Error.INCOMPATIBLE_GHOST_UPDATE,
                        incoming.logicalName);
            }
            if (incoming.type == NarGhostTreePolicy.Type.DIRECTORY
                    && prior != null
                    && prior.type == NarGhostTreePolicy.Type.DIRECTORY
                    && !prior.logicalName.equals(incoming.logicalName)
                    && hasSurvivingDescendant(
                            retained, archive, incoming.key)) {
                reject(Error.INCOMPATIBLE_GHOST_UPDATE,
                        incoming.logicalName);
            }
            merged.put(incoming.key, incoming);
        }
        require(merged.size() <= MAX_ENTRIES,
                Error.ENTRY_COUNT_LIMIT, "entry count");
        List<Work> ordered = new ArrayList<Work>(merged.values());
        Collections.sort(ordered, WORK_ORDER);
        ArrayList<Entry> output = new ArrayList<Entry>(ordered.size());
        int fileOrdinal = 0;
        long total = 0;
        boolean unknownTotal = false;
        for (Work item : ordered) {
            if (item.type == NarGhostTreePolicy.Type.FILE) {
                require(item.size >= -1 && item.size <= MAX_FILE_BYTES,
                        Error.FILE_SIZE_LIMIT, item.logicalName);
                if (item.size < 0) {
                    unknownTotal = true;
                } else {
                    require(total <= MAX_TOTAL_BYTES - item.size,
                            Error.TOTAL_SIZE_LIMIT, item.logicalName);
                    total += item.size;
                }
                output.add(item.finish(fileOrdinal++));
            } else {
                output.add(item.finish(-1));
            }
        }
        return Result.success(new Recipe(
                baseline, output, fileOrdinal,
                unknownTotal ? -1 : total));
    }
    private static Map<String, Work> prepareBaseline(NarGhostTreePolicy.Manifest manifest,
            List<NarStagedTreeInventory.Entry> supplied) throws Rejected {
        try {
            return prepareBaselineFacts(manifest, supplied);
        } catch (Rejected rejected) {
            throw rejected;
        } catch (RuntimeException rejected) {
            reject(Error.MALFORMED_BASELINE, "inventory");
            return null;
        }
    }
    private static Map<String, Work> prepareBaselineFacts(NarGhostTreePolicy.Manifest manifest,
            List<NarStagedTreeInventory.Entry> supplied) throws Rejected {
        List<NarGhostTreePolicy.Entry> facts = manifest.getEntries();
        require(facts != null, Error.MALFORMED_BASELINE, "manifest");
        require(manifest.getState() != null,
                Error.MALFORMED_BASELINE, "state");
        Map<String, NarStagedTreeInventory.Entry> inventory =
                new HashMap<String, NarStagedTreeInventory.Entry>();
        List<NarGhostTreePolicy.InputEntry> policyFacts =
                new ArrayList<NarGhostTreePolicy.InputEntry>();
        boolean[] seenOrdinals = new boolean[MAX_ENTRIES];
        int fileCount = 0;
        Iterator<NarStagedTreeInventory.Entry> iterator = supplied.iterator();
        int count = 0;
        while (iterator.hasNext()) {
            require(count++ < MAX_ENTRIES,
                    Error.MALFORMED_BASELINE, "inventory count");
            NarStagedTreeInventory.Entry entry = iterator.next();
            require(entry != null, Error.MALFORMED_BASELINE, "inventory");
            NarRelativePathPolicy.Result normalized =
                    NarRelativePathPolicy.normalize(entry.path());
            require(normalized.isSuccess()
                            && normalized.getNormalized().equals(entry.path()),
                    Error.MALFORMED_BASELINE, "inventory name");
            if (entry.type() == NarGhostTreePolicy.Type.DIRECTORY) {
                require(entry.size() == 0 && entry.blobOrdinal() == -1
                                && entry.sha256() == null,
                        Error.MALFORMED_BASELINE, entry.path());
                policyFacts.add(
                        NarGhostTreePolicy.InputEntry.directory(entry.path()));
            } else {
                int sourceOrdinal = entry.blobOrdinal();
                byte[] digest = entry.sha256();
                require(entry.type() == NarGhostTreePolicy.Type.FILE
                                && entry.size() >= 0
                                && sourceOrdinal >= 0
                                && sourceOrdinal < MAX_ENTRIES
                                && !seenOrdinals[sourceOrdinal]
                                && digest != null && digest.length == 32,
                        Error.MALFORMED_BASELINE, entry.path());
                seenOrdinals[sourceOrdinal] = true;
                fileCount++;
                policyFacts.add(NarGhostTreePolicy.InputEntry.file(
                        entry.path(), entry.size(), digest));
            }
            String key = normalized.getKey();
            require(inventory.put(key, entry) == null,
                    Error.MALFORMED_BASELINE, entry.path());
        }
        for (int index = 0; index < fileCount; index++) {
            require(seenOrdinals[index],
                    Error.MALFORMED_BASELINE, "inventory ordinal");
        }
        NarGhostTreePolicy.Result rebuilt = NarGhostTreePolicy.build(
                manifest.getTargetId(), manifest.getStorageRootIdentity(),
                manifest.getState(), policyFacts);
        require(rebuilt.isSuccess()
                        && ArraysEqual(
                                rebuilt.getManifest().getFingerprint(),
                                manifest.getFingerprint()),
                Error.MALFORMED_BASELINE, "manifest fingerprint");
        require(facts.size() == inventory.size(),
                Error.MALFORMED_BASELINE, "inventory size");
        require(manifest.getState() != NarGhostTreePolicy.State.ABSENT
                        || facts.isEmpty(),
                Error.MALFORMED_BASELINE, "absent inventory");
        Map<String, Work> result = new HashMap<String, Work>();
        for (NarGhostTreePolicy.Entry fact : facts) {
            NarRelativePathPolicy.Result normalized =
                    NarRelativePathPolicy.normalize(fact.getPath());
            require(normalized.isSuccess()
                            && normalized.getNormalized().equals(
                                    fact.getPath()),
                    Error.MALFORMED_BASELINE, "manifest name");
            String key = normalized.getKey();
            NarStagedTreeInventory.Entry source = inventory.get(key);
            require(source != null
                            && fact.getPath().equals(source.path())
                            && fact.getType() == source.type()
                            && fact.getLength() == source.size(),
                    Error.MALFORMED_BASELINE, fact.getPath());
            byte[] expected = fact.getContentDigest();
            byte[] actual = source.sha256();
            require(ArraysEqual(expected, actual),
                    Error.MALFORMED_BASELINE, fact.getPath());
            int sourceOrdinal = fact.getType()
                    == NarGhostTreePolicy.Type.FILE
                    ? source.blobOrdinal() : -1;
            Source kind = fact.getType() == NarGhostTreePolicy.Type.FILE
                    ? Source.RETAINED : Source.DIRECTORY;
            require(result.put(key, new Work(fact.getPath(), key,
                    fact.getType(), kind, sourceOrdinal,
                    fact.getLength(), actual)) == null,
                    Error.MALFORMED_BASELINE, fact.getPath());
        }
        require(result.size() == inventory.size(),
                Error.MALFORMED_BASELINE, "manifest entries");
        return result;
    }
    private static Map<String, Work> prepareArchive(
            NarInstallPlan plan) throws Rejected {
        List<NarInstallPlan.Entry> supplied = plan.getEntries();
        require(supplied != null, Error.MALFORMED_PLAN, "entries");
        Map<String, Work> result = new HashMap<String, Work>();
        int ordinal = 0;
        Iterator<NarInstallPlan.Entry> iterator = supplied.iterator();
        while (iterator.hasNext()) {
            require(ordinal < MAX_ENTRIES,
                    Error.MALFORMED_PLAN, "entry count");
            NarInstallPlan.Entry entry = iterator.next();
            require(entry != null && entry.getOrdinal() == ordinal,
                    Error.MALFORMED_PLAN, "ordinal");
            ordinal++;
            if (!entry.isInstallEntry()) {
                require(entry.isDirectory(),
                        Error.MALFORMED_PLAN, "wrapper");
                continue;
            }
            NarRelativePathPolicy.Result path =
                    NarRelativePathPolicy.normalize(entry.getRelativePath());
            require(path.isSuccess()
                            && path.getNormalized().equals(
                                    entry.getRelativePath()),
                    Error.MALFORMED_PLAN, "entry name");
            Work item;
            if (entry.isDirectory()) {
                require(entry.getDeclaredSize() == 0,
                        Error.MALFORMED_PLAN, path.getNormalized());
                item = Work.directory(path.getNormalized());
            } else {
                require(entry.getDeclaredSize() >= -1,
                        Error.MALFORMED_PLAN, path.getNormalized());
                item = Work.archiveFile(path.getNormalized(),
                        entry.getOrdinal(), entry.getDeclaredSize());
            }
            addArchive(result, item);
            String parent = item.logicalName;
            int slash;
            while ((slash = parent.lastIndexOf('/')) >= 0) {
                parent = parent.substring(0, slash);
                addArchive(result, Work.directory(parent));
            }
        }
        return result;
    }
    private static void addArchive(
            Map<String, Work> entries, Work item) throws Rejected {
        Work prior = entries.get(item.key);
        if (prior == null) {
            entries.put(item.key, item);
            require(entries.size() <= MAX_ENTRIES,
                    Error.ENTRY_COUNT_LIMIT, "archive expansion");
        } else {
            require(prior.type == NarGhostTreePolicy.Type.DIRECTORY
                            && item.type == NarGhostTreePolicy.Type.DIRECTORY
                            && prior.logicalName.equals(item.logicalName),
                    Error.MALFORMED_PLAN, item.logicalName);
        }
        String parent = item.logicalName;
        while (parent.lastIndexOf('/') >= 0) {
            parent = parent.substring(0, parent.lastIndexOf('/'));
            Work owner = entries.get(
                    NarRelativePathPolicy.collisionKey(parent));
            require(owner == null
                            || owner.type
                                    == NarGhostTreePolicy.Type.DIRECTORY,
                    Error.MALFORMED_PLAN, parent);
        }
    }
    private static boolean hasDescendant(
            Map<String, Work> entries, String owner) {
        String prefix = owner + "/";
        for (String key : entries.keySet())
            if (key.startsWith(prefix)) return true;
        return false;
    }
    private static boolean hasSurvivingDescendant(
            Map<String, Work> retained,
            Map<String, Work> archive,
            String owner) {
        String prefix = owner + "/";
        for (String key : retained.keySet())
            if (key.startsWith(prefix) && !archive.containsKey(key))
                return true;
        return false;
    }
    private static boolean ArraysEqual(byte[] left, byte[] right) {
        if (left == null || right == null) return left == right;
        if (left.length != right.length) return false;
        int difference = 0;
        for (int index = 0; index < left.length; index++)
            difference |= left[index] ^ right[index];
        return difference == 0;
    }
    private static void require(boolean condition, Error error, String detail)
            throws Rejected {
        if (!condition) reject(error, detail);
    }
    private static void reject(Error error, String detail) throws Rejected {
        throw new Rejected(error, detail);
    }
    enum Error {
        MALFORMED_PLAN, MALFORMED_BASELINE, UNSUPPORTED_TYPE,
        UNSUPPORTED_REFRESH, TARGET_MISMATCH, INCOMPATIBLE_GHOST_UPDATE,
        ENTRY_COUNT_LIMIT, FILE_SIZE_LIMIT, TOTAL_SIZE_LIMIT
    }
    enum Source { RETAINED, ARCHIVE, DIRECTORY }
    static final class Entry {
        private final String logicalName;
        private final NarGhostTreePolicy.Type type;
        private final Source source;
        private final int finalFileOrdinal, sourceOrdinal;
        private final long size;
        private final byte[] sha256;
        private Entry(String logicalName, NarGhostTreePolicy.Type type,
                Source source, int finalFileOrdinal, int sourceOrdinal,
                long size, byte[] sha256) {
            this.logicalName = logicalName; this.type = type;
            this.source = source; this.size = size;
            this.finalFileOrdinal = finalFileOrdinal;
            this.sourceOrdinal = sourceOrdinal;
            this.sha256 = sha256 == null ? null : sha256.clone();
        }
        String logicalName() { return logicalName; }
        NarGhostTreePolicy.Type type() { return type; }
        Source source() { return source; }
        int finalFileOrdinal() { return finalFileOrdinal; }
        int sourceOrdinal() { return sourceOrdinal; }
        long size() { return size; }
        byte[] sha256() { return sha256 == null ? null : sha256.clone(); }
    }
    static final class Recipe {
        private final NarGhostTreePolicy.Manifest baseline;
        private final byte[] baselineFingerprint;
        private final List<Entry> entries;
        private final int fileCount;
        private final long totalSize;
        private Recipe(NarGhostTreePolicy.Manifest baseline,
                List<Entry> entries, int fileCount, long totalSize) {
            this.baseline = baseline; this.fileCount = fileCount;
            this.totalSize = totalSize;
            baselineFingerprint = baseline.getFingerprint();
            this.entries = Collections.unmodifiableList(
                    new ArrayList<Entry>(entries));
        }
        NarGhostTreePolicy.Manifest baselineManifest() { return baseline; }
        byte[] baselineFingerprint() { return baselineFingerprint.clone(); }
        List<Entry> entries() { return entries; }
        int fileCount() { return fileCount; }
        boolean hasKnownTotalSize() { return totalSize >= 0; }
        long totalSize() { return totalSize; }
    }
    static final class Result {
        private final Recipe recipe;
        private final Error error;
        private final String detail;
        private Result(Recipe recipe, Error error, String detail) {
            this.recipe = recipe; this.error = error; this.detail = detail;
        }
        private static Result success(Recipe recipe) {
            return new Result(recipe, null, "");
        }
        private static Result failure(Error error, String detail) {
            return new Result(null, error,
                    detail == null ? "input" : detail);
        }
        boolean isSuccess() { return recipe != null; }
        Recipe recipe() { return recipe; }
        Error error() { return error; }
        String detail() { return detail; }
    }
    private static final class Work {
        private final String logicalName, key;
        private final NarGhostTreePolicy.Type type;
        private final Source source;
        private final int sourceOrdinal;
        private final long size;
        private final byte[] sha256;
        private Work(String logicalName, String key,
                NarGhostTreePolicy.Type type, Source source,
                int sourceOrdinal, long size, byte[] sha256) {
            this.logicalName = logicalName; this.key = key;
            this.type = type; this.source = source;
            this.sourceOrdinal = sourceOrdinal; this.size = size;
            this.sha256 = sha256 == null ? null : sha256.clone();
        }
        private static Work directory(String name) {
            return new Work(name,
                    NarRelativePathPolicy.collisionKey(name),
                    NarGhostTreePolicy.Type.DIRECTORY,
                    Source.DIRECTORY, -1, 0, null);
        }
        private static Work archiveFile(
                String name, int ordinal, long size) {
            return new Work(name,
                    NarRelativePathPolicy.collisionKey(name),
                    NarGhostTreePolicy.Type.FILE,
                    Source.ARCHIVE, ordinal, size, null);
        }
        private Entry finish(int ordinal) {
            return new Entry(logicalName, type, source,
                    ordinal, sourceOrdinal, size, sha256);
        }
    }
    private static final Comparator<Work> WORK_ORDER = new Comparator<Work>() {
        @Override public int compare(Work left, Work right) {
            return left.logicalName.compareTo(right.logicalName);
        }
    };
    private static final class Rejected extends Exception {
        private final Error error;
        private Rejected(Error error, String detail) {
            super(detail); this.error = error;
        }
    }
}
