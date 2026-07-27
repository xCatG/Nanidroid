package com.cattailsw.nanidroid.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure immutable adapter from native staged-tree facts to the reviewed policy.
 *
 * <p>Fingerprint v1 authenticates target, storage-root identity, logical paths
 * and copied-byte digests, but intentionally excludes blob ordinals. The
 * immutable manifest and ordinal inventory must remain inseparable in the
 * later capability. D9b3 must treat process-death orphans as discard-only.
 */
final class NarStagedTreeInventory {
    private static final int MAX_ENTRIES = 10000;
    private static final int DIGEST_BYTES = 32;

    private NarStagedTreeInventory() {}

    enum Error { NATIVE, POLICY }

    static final class Description {
        private final long storageDevice;
        private final long storageInode;
        private final String[] paths;
        private final int[] types;
        private final long[] sizes;
        private final int[] ordinals;
        private final byte[] digests;

        Description(long storageDevice, long storageInode,
                String[] paths, int[] types, long[] sizes,
                int[] ordinals, byte[] digests) {
            this.storageDevice = storageDevice;
            this.storageInode = storageInode;
            this.paths = paths == null ? null : paths.clone();
            this.types = types == null ? null : types.clone();
            this.sizes = sizes == null ? null : sizes.clone();
            this.ordinals = ordinals == null ? null : ordinals.clone();
            this.digests = digests == null ? null : digests.clone();
        }
    }

    static final class Entry {
        private final String path;
        private final NarGhostTreePolicy.Type type;
        private final long size;
        private final int blobOrdinal;
        private final byte[] sha256;

        private Entry(String path, NarGhostTreePolicy.Type type,
                long size, int blobOrdinal, byte[] sha256) {
            this.path = path;
            this.type = type;
            this.size = size;
            this.blobOrdinal = blobOrdinal;
            this.sha256 = sha256 == null ? null : sha256.clone();
        }

        String path() { return path; }
        NarGhostTreePolicy.Type type() { return type; }
        long size() { return size; }
        int blobOrdinal() { return blobOrdinal; }
        byte[] sha256() {
            return sha256 == null ? null : sha256.clone();
        }
    }

    static final class Result {
        private final NarGhostTreePolicy.Manifest manifest;
        private final List<Entry> entries;
        private final Error error;
        private final String detail;

        private Result(NarGhostTreePolicy.Manifest manifest,
                List<Entry> entries, Error error, String detail) {
            this.manifest = manifest;
            this.entries = entries;
            this.error = error;
            this.detail = detail;
        }

        private static Result success(
                NarGhostTreePolicy.Manifest manifest,
                List<Entry> entries) {
            return new Result(manifest, Collections.unmodifiableList(
                    new ArrayList<Entry>(entries)), null, "");
        }

        private static Result failure(Error error, String detail) {
            return new Result(null, Collections.<Entry>emptyList(),
                    error, detail);
        }

        boolean isSuccess() { return manifest != null; }
        NarGhostTreePolicy.Manifest manifest() { return manifest; }
        List<Entry> entries() { return entries; }
        Error error() { return error; }
        String detail() { return detail; }
    }

    static Result absent(
            String target, long storageDevice, long storageInode) {
        return build(target, storageDevice, storageInode,
                NarGhostTreePolicy.State.ABSENT, null);
    }

    static Result present(String target, Description description) {
        if (description == null) {
            return Result.failure(Error.NATIVE, "description");
        }
        return build(target, description.storageDevice,
                description.storageInode,
                NarGhostTreePolicy.State.PRESENT, description);
    }

    private static Result build(String target,
            long storageDevice, long storageInode,
            NarGhostTreePolicy.State state,
            Description description) {
        try {
            Prepared prepared = state == NarGhostTreePolicy.State.ABSENT
                    ? Prepared.absent() : prepare(description);
            if (prepared == null) {
                return Result.failure(Error.NATIVE, "inventory");
            }
            NarGhostTreePolicy.Result policy = NarGhostTreePolicy.build(
                    target, identity(storageDevice, storageInode),
                    state, prepared.policyEntries);
            if (!policy.isSuccess()) {
                return Result.failure(
                        Error.POLICY, policy.getError().name());
            }
            ArrayList<Entry> entries = new ArrayList<Entry>();
            for (NarGhostTreePolicy.Entry entry
                    : policy.getManifest().getEntries()) {
                Entry item = prepared.byPath.get(entry.getPath());
                if (item == null) {
                    return Result.failure(
                            Error.NATIVE, "inventory mapping");
                }
                entries.add(item);
            }
            return Result.success(policy.getManifest(), entries);
        } catch (RuntimeException error) {
            return Result.failure(Error.NATIVE, "inventory");
        }
    }

    private static final class Prepared {
        private final List<NarGhostTreePolicy.InputEntry> policyEntries;
        private final Map<String, Entry> byPath;

        private Prepared(
                List<NarGhostTreePolicy.InputEntry> policyEntries,
                Map<String, Entry> byPath) {
            this.policyEntries = policyEntries;
            this.byPath = byPath;
        }

        private static Prepared absent() {
            return new Prepared(
                    Collections.<NarGhostTreePolicy.InputEntry>emptyList(),
                    Collections.<String, Entry>emptyMap());
        }
    }

    private static Prepared prepare(Description value) {
        if (value.paths == null || value.types == null
                || value.sizes == null || value.ordinals == null
                || value.digests == null) {
            return null;
        }
        int count = value.paths.length;
        if (count > MAX_ENTRIES || value.types.length != count
                || value.sizes.length != count
                || value.ordinals.length != count
                || value.digests.length != count * DIGEST_BYTES) {
            return null;
        }
        ArrayList<NarGhostTreePolicy.InputEntry> policy =
                new ArrayList<NarGhostTreePolicy.InputEntry>(count);
        HashMap<String, Entry> inventory =
                new HashMap<String, Entry>();
        boolean[] seen = new boolean[count];
        int files = 0;
        for (int index = 0; index < count; index++) {
            String path = value.paths[index];
            NarRelativePathPolicy.Result normalized =
                    NarRelativePathPolicy.normalize(path);
            if (!normalized.isSuccess()) return null;
            if (value.types[index] == 2) {
                if (value.sizes[index] != 0
                        || value.ordinals[index] != -1
                        || !zeroDigest(value.digests, index)) {
                    return null;
                }
                Entry entry = new Entry(normalized.getNormalized(),
                        NarGhostTreePolicy.Type.DIRECTORY,
                        0, -1, null);
                inventory.put(entry.path, entry);
                policy.add(
                        NarGhostTreePolicy.InputEntry.directory(path));
            } else if (value.types[index] == 1
                    && value.sizes[index] >= 0
                    && value.ordinals[index] >= 0
                    && value.ordinals[index] < count
                    && !seen[value.ordinals[index]]) {
                seen[value.ordinals[index]] = true;
                files++;
                byte[] digest = new byte[DIGEST_BYTES];
                System.arraycopy(value.digests,
                        index * DIGEST_BYTES, digest, 0, DIGEST_BYTES);
                Entry entry = new Entry(normalized.getNormalized(),
                        NarGhostTreePolicy.Type.FILE,
                        value.sizes[index],
                        value.ordinals[index], digest);
                inventory.put(entry.path, entry);
                policy.add(NarGhostTreePolicy.InputEntry.file(
                        path, value.sizes[index], digest));
            } else {
                return null;
            }
        }
        for (int ordinal = 0; ordinal < files; ordinal++) {
            if (!seen[ordinal]) return null;
        }
        for (int ordinal = files; ordinal < seen.length; ordinal++) {
            if (seen[ordinal]) return null;
        }
        return new Prepared(policy, inventory);
    }

    private static boolean zeroDigest(byte[] values, int index) {
        int start = index * DIGEST_BYTES;
        for (int offset = 0; offset < DIGEST_BYTES; offset++) {
            if (values[start + offset] != 0) return false;
        }
        return true;
    }

    private static byte[] identity(long device, long inode) {
        byte[] result = new byte[16];
        putLong(result, 0, device);
        putLong(result, 8, inode);
        return result;
    }

    private static void putLong(
            byte[] target, int offset, long value) {
        for (int index = 7; index >= 0; index--) {
            target[offset + index] = (byte) value;
            value >>>= 8;
        }
    }
}
