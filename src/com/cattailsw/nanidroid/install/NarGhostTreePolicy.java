package com.cattailsw.nanidroid.install;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Pure immutable baseline policy for a live ghost tree.
 *
 * <p>Hard links are deliberately flattened: a future filesystem walker copies
 * and hashes each relative file path independently before calling this policy.
 */
final class NarGhostTreePolicy {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final byte[] DOMAIN =
            "Nanidroid/GhostTreeFingerprint".getBytes(UTF_8);
    private static final int FINGERPRINT_VERSION = 1;
    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_FILE_BYTES =
            128L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES =
            512L * 1024L * 1024L;

    private NarGhostTreePolicy() {}

    static Result build(
            String validatedTargetId,
            byte[] storageRootIdentity,
            State state,
            List<InputEntry> suppliedEntries) {
        try {
            return inspect(
                    validatedTargetId,
                    storageRootIdentity,
                    state,
                    suppliedEntries);
        } catch (RuntimeException error) {
            return Result.failure(
                    Error.ENTRY_INVALID, "input");
        }
    }

    private static Result inspect(
            String validatedTargetId,
            byte[] storageRootIdentity,
            State state,
            List<InputEntry> suppliedEntries) {
        NarRelativePathPolicy.Result target =
                NarRelativePathPolicy.normalize(validatedTargetId);
        if (!target.isSuccess()
                || target.getNormalized().indexOf('/') >= 0
                || !target.getNormalized().equals(
                        validatedTargetId)) {
            return Result.failure(
                    Error.TARGET_ID_INVALID, "target id");
        }
        if (storageRootIdentity == null) {
            return Result.failure(
                    Error.STORAGE_ROOT_ID_INVALID,
                    "storage root identity");
        }
        if (state == null || suppliedEntries == null) {
            return Result.failure(
                    Error.STATE_INVALID, "baseline state");
        }
        List<InputEntry> snapshot =
                new ArrayList<InputEntry>(MAX_ENTRIES);
        Iterator<InputEntry> iterator =
                suppliedEntries.iterator();
        while (iterator.hasNext()) {
            InputEntry entry = iterator.next();
            if (snapshot.size() == MAX_ENTRIES) {
                return Result.failure(
                        Error.ENTRY_COUNT_LIMIT, "entry count");
            }
            snapshot.add(entry);
        }
        if (state == State.ABSENT
                && !snapshot.isEmpty()) {
            return Result.failure(
                    Error.STATE_INVALID,
                    "absent baseline has entries");
        }

        List<Entry> entries = new ArrayList<Entry>();
        Map<String, Entry> byKey = new HashMap<String, Entry>();
        long total = 0;
        for (InputEntry supplied : snapshot) {
            if (supplied == null || supplied.type == null) {
                return Result.failure(
                        Error.ENTRY_INVALID, "null entry");
            }
            NarRelativePathPolicy.Result path =
                    NarRelativePathPolicy.normalize(
                            supplied.path);
            if (!path.isSuccess()) {
                return Result.failure(
                        pathError(path.getError()),
                        supplied.path);
            }
            if (supplied.type == Type.FILE) {
                if (supplied.length < 0
                        || supplied.length > MAX_FILE_BYTES) {
                    return Result.failure(
                            Error.FILE_SIZE_LIMIT,
                            path.getNormalized());
                }
                if (supplied.contentDigest == null
                        || supplied.contentDigest.length != 32) {
                    return Result.failure(
                            Error.CONTENT_DIGEST_INVALID,
                            path.getNormalized());
                }
                if (total > MAX_TOTAL_BYTES - supplied.length) {
                    return Result.failure(
                            Error.TOTAL_SIZE_LIMIT,
                            path.getNormalized());
                }
                total += supplied.length;
            }
            Entry entry = new Entry(
                    path.getNormalized(),
                    supplied.type,
                    supplied.length,
                    supplied.contentDigest);
            Entry previous = byKey.put(
                    path.getKey(), entry);
            if (previous != null) {
                return Result.failure(
                        previous.type == entry.type
                                ? Error.NORMALIZED_COLLISION
                                : Error.FILE_DIRECTORY_COLLISION,
                        path.getNormalized());
            }
            entries.add(entry);
        }

        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                return left.path.compareTo(right.path);
            }
        });
        for (Entry entry : entries) {
            int slash = entry.path.lastIndexOf('/');
            if (slash < 0) {
                continue;
            }
            String parent = entry.path.substring(0, slash);
            Entry owner = byKey.get(
                    NarRelativePathPolicy.collisionKey(parent));
            if (owner == null) {
                return Result.failure(
                        Error.MISSING_DIRECTORY, parent);
            }
            if (!owner.path.equals(parent)) {
                return Result.failure(
                        Error.NORMALIZED_COLLISION, parent);
            }
            if (owner.type != Type.DIRECTORY) {
                return Result.failure(
                        Error.FILE_DIRECTORY_COLLISION, parent);
            }
        }

        try {
            byte[] root = storageRootIdentity.clone();
            byte[] fingerprint = fingerprint(
                    validatedTargetId, root, state, entries);
            return Result.success(new Manifest(
                    validatedTargetId,
                    root,
                    state,
                    entries,
                    fingerprint));
        } catch (NoSuchAlgorithmException error) {
            return Result.failure(
                    Error.FINGERPRINT_FAILED, "SHA-256");
        } catch (RuntimeException error) {
            return Result.failure(
                    Error.FINGERPRINT_FAILED, "fingerprint");
        }
    }

    private static Error pathError(
            NarRelativePathPolicy.Error error) {
        switch (error) {
            case PATH_DEPTH_LIMIT:
                return Error.PATH_DEPTH_LIMIT;
            case PATH_LENGTH_LIMIT:
                return Error.PATH_LENGTH_LIMIT;
            case COMPONENT_LENGTH_LIMIT:
                return Error.COMPONENT_LENGTH_LIMIT;
            default:
                return Error.INVALID_PATH;
        }
    }

    private static byte[] fingerprint(
            String target,
            byte[] root,
            State state,
            List<Entry> entries)
            throws NoSuchAlgorithmException {
        Encoder encoder = new Encoder(
                MessageDigest.getInstance("SHA-256"));
        encoder.bytes(DOMAIN);
        encoder.integer(FINGERPRINT_VERSION);
        encoder.bytes(target.getBytes(UTF_8));
        encoder.bytes(root);
        encoder.one(state == State.ABSENT ? 0 : 1);
        encoder.integer(entries.size());
        for (Entry entry : entries) {
            encoder.bytes(entry.path.getBytes(UTF_8));
            encoder.one(entry.type == Type.DIRECTORY ? 1 : 2);
            if (entry.type == Type.FILE) {
                encoder.longValue(entry.length);
                encoder.bytes(entry.contentDigest);
            }
        }
        return encoder.finish();
    }

    enum State { ABSENT, PRESENT }
    enum Type { FILE, DIRECTORY }

    enum Error {
        TARGET_ID_INVALID,
        STORAGE_ROOT_ID_INVALID,
        STATE_INVALID,
        ENTRY_INVALID,
        INVALID_PATH,
        NORMALIZED_COLLISION,
        FILE_DIRECTORY_COLLISION,
        MISSING_DIRECTORY,
        PATH_DEPTH_LIMIT,
        COMPONENT_LENGTH_LIMIT,
        PATH_LENGTH_LIMIT,
        ENTRY_COUNT_LIMIT,
        FILE_SIZE_LIMIT,
        TOTAL_SIZE_LIMIT,
        CONTENT_DIGEST_INVALID,
        FINGERPRINT_FAILED
    }

    static final class InputEntry {
        private final String path;
        private final Type type;
        private final long length;
        private final byte[] contentDigest;

        private InputEntry(
                String path,
                Type type,
                long length,
                byte[] contentDigest) {
            this.path = path;
            this.type = type;
            this.length = length;
            this.contentDigest = contentDigest == null
                    ? null : contentDigest.clone();
        }

        static InputEntry directory(String path) {
            return new InputEntry(
                    path, Type.DIRECTORY, 0, null);
        }

        static InputEntry file(
                String path, long length, byte[] digest) {
            return new InputEntry(
                    path, Type.FILE, length, digest);
        }

        String getPath() { return path; }
    }

    static final class Entry {
        private final String path;
        private final Type type;
        private final long length;
        private final byte[] contentDigest;

        private Entry(
                String path,
                Type type,
                long length,
                byte[] contentDigest) {
            this.path = path;
            this.type = type;
            this.length = length;
            this.contentDigest = contentDigest == null
                    ? null : contentDigest.clone();
        }

        String getPath() { return path; }
        Type getType() { return type; }
        long getLength() { return length; }
        byte[] getContentDigest() {
            return contentDigest == null
                    ? null : contentDigest.clone();
        }
    }

    static final class Manifest {
        private final String targetId;
        private final byte[] storageRootIdentity;
        private final State state;
        private final List<Entry> entries;
        private final byte[] fingerprint;

        private Manifest(
                String targetId,
                byte[] storageRootIdentity,
                State state,
                List<Entry> entries,
                byte[] fingerprint) {
            this.targetId = targetId;
            this.storageRootIdentity =
                    storageRootIdentity.clone();
            this.state = state;
            this.entries = Collections.unmodifiableList(
                    new ArrayList<Entry>(entries));
            this.fingerprint = fingerprint.clone();
        }

        String getTargetId() { return targetId; }
        byte[] getStorageRootIdentity() {
            return storageRootIdentity.clone();
        }
        State getState() { return state; }
        List<Entry> getEntries() { return entries; }
        int getFingerprintVersion() {
            return FINGERPRINT_VERSION;
        }
        byte[] getFingerprint() {
            return fingerprint.clone();
        }
    }

    static final class Result {
        private final Manifest manifest;
        private final Error error;
        private final String detail;

        private Result(
                Manifest manifest, Error error, String detail) {
            this.manifest = manifest;
            this.error = error;
            this.detail = detail;
        }

        private static Result success(Manifest manifest) {
            return new Result(manifest, null, "");
        }

        private static Result failure(
                Error error, String detail) {
            return new Result(null, error, detail);
        }

        boolean isSuccess() { return manifest != null; }
        Manifest getManifest() { return manifest; }
        Error getError() { return error; }
        String getDetail() { return detail; }
    }

    private static final class Encoder {
        private final MessageDigest digest;

        private Encoder(MessageDigest digest) {
            this.digest = digest;
        }

        private void one(int value) {
            digest.update((byte) value);
        }

        private void integer(int value) {
            one(value >>> 24);
            one(value >>> 16);
            one(value >>> 8);
            one(value);
        }

        private void longValue(long value) {
            one((int) (value >>> 56));
            one((int) (value >>> 48));
            one((int) (value >>> 40));
            one((int) (value >>> 32));
            one((int) (value >>> 24));
            one((int) (value >>> 16));
            one((int) (value >>> 8));
            one((int) value);
        }

        private void bytes(byte[] value) {
            integer(value.length);
            digest.update(value);
        }

        private byte[] finish() {
            return digest.digest();
        }
    }
}
