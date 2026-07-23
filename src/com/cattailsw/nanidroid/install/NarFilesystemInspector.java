package com.cattailsw.nanidroid.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NarFilesystemInspector {
    enum State { ERROR, ABSENT, PRESENT }
    enum Type { FILE, DIRECTORY }
    enum Error {
        OK, INVALID_OPTIONS, INVALID_TARGET, ROOT_TYPE, TARGET_TYPE, SYMLINK,
        SPECIAL_TYPE, INVALID_NAME, COMPONENT_LIMIT, PATH_LIMIT, DEPTH_LIMIT,
        ENTRY_COUNT_LIMIT, FILE_SIZE_LIMIT, TOTAL_SIZE_LIMIT, CYCLE,
        TREE_CHANGED, PERMISSION, RESOURCE, IO, VISITOR, CLOSE,
        INPUT, NATIVE, LINKAGE, SECURITY;

        static Error fromCode(int code) {
            Error[] values = values();
            if (code >= 0 && code <= 20) return values[code];
            if (code == 100) return INPUT;
            if (code == 101) return NATIVE;
            if (code == 102) return LINKAGE;
            if (code == 103) return SECURITY;
            return NATIVE;
        }
    }

    static final class TrustedRoot {
        private final String value;
        TrustedRoot(String value) {
            if (value == null) throw new NullPointerException("trusted root");
            this.value = value;
        }
    }

    static final class Entry {
        private final String path;
        private final Type type;
        private final long size;
        private final long device;
        private final long inode;

        Entry(String path, Type type, long size, long device, long inode) {
            this.path = path;
            this.type = type;
            this.size = size;
            this.device = device;
            this.inode = inode;
        }
        String path() { return path; }
        Type type() { return type; }
        long size() { return size; }
        long device() { return device; }
        long inode() { return inode; }
    }

    static final class Result {
        private final State state;
        private final Error error;
        private final Error cleanupError;
        private final int entryCount;
        private final long totalFileSize;
        private final List<Entry> entries;

        Result(State state, Error error, Error cleanupError, int entryCount,
                long totalFileSize, List<Entry> entries) {
            this.state = state;
            this.error = error;
            this.cleanupError = cleanupError;
            this.entryCount = entryCount;
            this.totalFileSize = totalFileSize;
            this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
        }
        State state() { return state; }
        Error error() { return error; }
        Error cleanupError() { return cleanupError; }
        int entryCount() { return entryCount; }
        long totalFileSize() { return totalFileSize; }
        List<Entry> entries() { return entries; }
    }

    interface Loader { void load(); }
    interface Backend { Result inspect(String trustedRoot, String target); }

    private final Loader loader;
    private final Backend backend;
    private boolean loaded;

    NarFilesystemInspector() {
        this(() -> System.loadLibrary("narfs"), NarFilesystemInspector::nativeInspect);
    }

    NarFilesystemInspector(Loader loader, Backend backend) {
        if (loader == null || backend == null) throw new NullPointerException("backend");
        this.loader = loader;
        this.backend = backend;
    }

    Result inspect(TrustedRoot root, String target) {
        if (root == null || target == null) return failure(Error.INPUT);
        try {
            ensureLoaded();
            Result result = backend.inspect(root.value, target);
            return result == null ? failure(Error.NATIVE) : result;
        } catch (LinkageError error) {
            return failure(Error.LINKAGE);
        } catch (SecurityException error) {
            return failure(Error.SECURITY);
        }
    }

    private synchronized void ensureLoaded() {
        if (!loaded) {
            loader.load();
            loaded = true;
        }
    }

    private static Result failure(Error error) {
        return new Result(State.ERROR, error, Error.OK, 0, 0,
                Collections.<Entry>emptyList());
    }

    static Result fromNative(int stateCode, int errorCode, int cleanupCode,
            int count, long total, String[] paths, int[] types, long[] facts) {
        if (paths == null || types == null || facts == null
                || count < 0 || count > 10000 || paths.length != count
                || types.length != count || facts.length != count * 3) {
            return failure(Error.NATIVE);
        }
        ArrayList<Entry> entries = new ArrayList<Entry>(count);
        for (int index = 0; index < count; index++) {
            Type type = types[index] == 1 ? Type.FILE
                    : types[index] == 2 ? Type.DIRECTORY : null;
            if (paths[index] == null || type == null) return failure(Error.NATIVE);
            int fact = index * 3;
            entries.add(new Entry(paths[index], type, facts[fact],
                    facts[fact + 1], facts[fact + 2]));
        }
        State state = stateCode == 1 ? State.ABSENT
                : stateCode == 2 ? State.PRESENT : State.ERROR;
        return new Result(state, Error.fromCode(errorCode),
                Error.fromCode(cleanupCode), count, total, entries);
    }

    private static native Result nativeInspect(String trustedRoot, String target);
}
