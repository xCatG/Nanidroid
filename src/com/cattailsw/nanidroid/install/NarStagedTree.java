package com.cattailsw.nanidroid.install;

import android.content.Context;
import java.util.List;

/**
 * Package-private capability boundary for a staged ghost tree.
 *
 * <p>The Context selects the app-owned staging root inside the backend. The
 * opaque handle and its validated inventory move together from {@link Tree}
 * to {@link Claim}. Neither type exposes a destination, path, token, stream,
 * or raw handle. D9b3 must treat process-death orphans as discard-only.
 */
final class NarStagedTree {
    private NarStagedTree() {}
    enum Error {
        OK, INVALID_OPTIONS, INVALID_TARGET, ROOT_TYPE, TARGET_TYPE, SYMLINK,
        SPECIAL_TYPE, INVALID_NAME, COMPONENT_LIMIT, PATH_LIMIT, DEPTH_LIMIT,
        ENTRY_COUNT_LIMIT, FILE_SIZE_LIMIT, TOTAL_SIZE_LIMIT, CYCLE,
        TREE_CHANGED, PERMISSION, RESOURCE, IO, VISITOR, CLOSE,
        INPUT, NATIVE, POLICY, CLOSED, CONSUMED, FOREIGN, WRONG_SESSION
    }
    interface Handle {}
    interface Backend {
        BeginResult begin(Context context,
                NarFilesystemInspector.TrustedRoot root,
                CharSequence target);
        NarStagedTreeInventory.Description describe(Handle handle);
        Error discard(Context context, Handle handle);
    }
    static final class BeginResult {
        private static final int ABSENT = 1;
        private static final int PRESENT = 2;
        private static final int FAILURE = 3;

        private final int kind;
        private final long storageDevice;
        private final long storageInode;
        private final Handle handle;
        private final Error primaryError;
        private final Error cleanupError;
        private BeginResult(int kind, long storageDevice,
                long storageInode, Handle handle,
                Error primaryError, Error cleanupError) {
            this.kind = kind;
            this.storageDevice = storageDevice;
            this.storageInode = storageInode;
            this.handle = handle;
            this.primaryError = primaryError;
            this.cleanupError = cleanupError;
        }
        static BeginResult absent(long storageDevice, long storageInode) {
            return new BeginResult(ABSENT, storageDevice, storageInode,
                    null, null, Error.OK);
        }
        static BeginResult present(Handle handle) {
            return new BeginResult(PRESENT, 0, 0, handle, null, Error.OK);
        }

        static BeginResult failure(
                Error primaryError, Error cleanupError, Handle handle) {
            return new BeginResult(FAILURE, 0, 0, handle,
                    primaryError, cleanupError);
        }
    }
    static final class Cleanup {
        private final Error nativeError;
        private Error discardError;
        private DiscardOwner recovery;

        private Cleanup(Error nativeError, Error discardError,
                DiscardOwner recovery) {
            this.nativeError = normalize(nativeError);
            this.discardError = normalize(discardError);
            this.recovery = recovery;
        }
        Error nativeError() { return nativeError; }
        Error discardError() { return discardError; }
        Error discard() {
            return recovery == null ? Error.OK : recovery.discard();
        }
    }
    static final class StageResult {
        private final Tree tree;
        private final Error error;
        private final Cleanup cleanup;
        private final String detail;

        private StageResult(Tree tree, Error error,
                Cleanup cleanup, String detail) {
            this.tree = tree;
            this.error = error;
            this.cleanup = cleanup;
            this.detail = detail == null ? "" : detail;
        }
        private static StageResult success(Tree tree) {
            return new StageResult(tree, Error.OK,
                    new Cleanup(Error.OK, Error.OK, null), "");
        }
        private static StageResult failure(
                Error error, Error nativeCleanup, String detail) {
            return new StageResult(null, normalizeFailure(error),
                    new Cleanup(nativeCleanup, Error.OK, null), detail);
        }
        boolean isSuccess() { return tree != null; }
        Tree tree() { return tree; }
        Error error() { return error; }
        Cleanup cleanup() { return cleanup; }
        String detail() { return detail; }
    }

    static final class Stager {
        private final Backend backend;

        Stager(Backend backend) {
            if (backend == null) throw new NullPointerException("backend");
            this.backend = backend;
        }

        Session session(Context context) {
            if (context == null) throw new NullPointerException("context");
            return new Session(this, context);
        }
    }

    static final class Session {
        private final Stager owner;
        private final Context context;

        private Session(Stager owner, Context context) {
            this.owner = owner;
            this.context = context;
        }

        StageResult stage(
                NarFilesystemInspector.TrustedRoot root, String target) {
            if (root == null || target == null) {
                return StageResult.failure(
                        Error.INPUT, Error.OK, "input");
            }
            Handle pending = null;
            DiscardOwner pendingOwner = null;
            StageResult result = null;
            try {
                BeginResult begun =
                        owner.backend.begin(context, root, target);
                if (begun == null) {
                    result = StageResult.failure(
                            Error.NATIVE, Error.OK, "begin");
                } else {
                    pending = begun.handle;
                    if (pending != null) {
                        pendingOwner = new DiscardOwner(
                                owner.backend, context, pending);
                    }
                    if (begun.kind == BeginResult.FAILURE) {
                        result = StageResult.failure(
                                begun.primaryError, begun.cleanupError,
                                "begin");
                    } else if (begun.kind == BeginResult.ABSENT
                            && pending == null) {
                        result = fromInventory(owner, this, context,
                                NarStagedTreeInventory.absent(
                                target, begun.storageDevice,
                                begun.storageInode), null);
                    } else if (begun.kind == BeginResult.PRESENT
                            && pending != null) {
                        NarStagedTreeInventory.Result inventory =
                                NarStagedTreeInventory.present(target,
                                        owner.backend.describe(pending));
                        result = fromInventory(owner, this, context,
                                inventory, pending);
                        if (result.isSuccess()) {
                            pending = null;
                            pendingOwner = null;
                        }
                    } else {
                        result = StageResult.failure(
                                Error.NATIVE, Error.OK, "begin shape");
                    }
                }
            } catch (RuntimeException error) {
                result = StageResult.failure(
                        Error.NATIVE, Error.OK, "backend");
            } catch (LinkageError error) {
                result = StageResult.failure(
                        Error.NATIVE, Error.OK, "backend");
            } finally {
                if (pendingOwner != null) {
                    if (result != null) {
                        result.cleanup.recovery = pendingOwner;
                    }
                    Error discarded;
                    try { discarded = pendingOwner.discard(); }
                    catch (OutOfMemoryError cleanup) { discarded = Error.NATIVE; }
                    if (result != null) {
                        result.cleanup.discardError = discarded;
                        if (discarded == Error.OK) {
                            result.cleanup.recovery = null;
                        }
                    }
                } else if (pending != null) {
                    try { Resource.discard(owner.backend, context, pending); }
                    catch (OutOfMemoryError cleanup) { /* preserve primary */ }
                }
            }
            return result;
        }

        ConsumeResult consume(Tree tree) {
            if (tree == null) return ConsumeResult.failure(Error.INPUT);
            synchronized (tree) {
                if (tree.owner != owner) {
                    return ConsumeResult.failure(Error.FOREIGN);
                }
                if (tree.session != this) {
                    return ConsumeResult.failure(Error.WRONG_SESSION);
                }
                if (tree.consumed) {
                    return ConsumeResult.failure(Error.CONSUMED);
                }
                if (tree.resource.isClosed()) {
                    return ConsumeResult.failure(Error.CLOSED);
                }
                Claim claim = new Claim(tree.resource);
                ConsumeResult transferred =
                        ConsumeResult.success(claim);
                tree.consumed = true;
                return transferred;
            }
        }
    }

    static final class Tree {
        private final Stager owner;
        private final Session session;
        private final Resource resource;
        private boolean consumed;

        private Tree(Stager owner, Session session, Resource resource) {
            this.owner = owner;
            this.session = session;
            this.resource = resource;
        }

        NarGhostTreePolicy.Manifest manifest() {
            return resource.inventory.manifest();
        }

        List<NarStagedTreeInventory.Entry> entries() {
            return resource.inventory.entries();
        }

        synchronized Error discard() {
            if (consumed) return Error.CONSUMED;
            return resource.discard();
        }
    }

    static final class Claim {
        private final Resource resource;

        private Claim(Resource resource) {
            this.resource = resource;
        }

        Error discard() {
            return resource.discard();
        }
    }

    static final class ConsumeResult {
        private final Claim claim;
        private final Error error;

        private ConsumeResult(Claim claim, Error error) {
            this.claim = claim;
            this.error = error;
        }

        private static ConsumeResult success(Claim claim) {
            return new ConsumeResult(claim, Error.OK);
        }

        private static ConsumeResult failure(Error error) {
            return new ConsumeResult(null, normalize(error));
        }

        boolean isSuccess() { return claim != null; }
        Claim claim() { return claim; }
        Error error() { return error; }
    }

    private static final class Resource {
        private final Backend backend;
        private final Context context;
        private final NarStagedTreeInventory.Result inventory;
        private Handle handle;
        private boolean closed;

        private Resource(
                Backend backend, Context context, Handle handle,
                NarStagedTreeInventory.Result inventory) {
            this.backend = backend;
            this.context = context;
            this.handle = handle;
            this.inventory = inventory;
            this.closed = false;
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        private synchronized Error discard() {
            if (closed) return Error.OK;
            if (handle == null) {
                closed = true;
                return Error.OK;
            }
            Error result = discard(backend, context, handle);
            if (result == Error.OK) {
                handle = null;
                closed = true;
            }
            return result;
        }

        private static Error discard(
                Backend backend, Context context, Handle handle) {
            try {
                return normalize(backend.discard(context, handle));
            } catch (RuntimeException error) {
                return Error.NATIVE;
            } catch (LinkageError error) {
                return Error.NATIVE;
            }
        }
    }

    private static final class DiscardOwner {
        private final Backend backend;
        private final Context context;
        private Handle handle;

        private DiscardOwner(
                Backend backend, Context context, Handle handle) {
            this.backend = backend;
            this.context = context;
            this.handle = handle;
        }

        private synchronized Error discard() {
            if (handle == null) return Error.OK;
            Error result = Resource.discard(backend, context, handle);
            if (result == Error.OK) handle = null;
            return result;
        }
    }

    private static Error normalize(Error error) {
        return error == null ? Error.NATIVE : error;
    }
    private static Error normalizeFailure(Error error) {
        return error == null || error == Error.OK ? Error.NATIVE : error;
    }

    private static StageResult fromInventory(
            Stager owner, Session session, Context context,
            NarStagedTreeInventory.Result inventory, Handle handle) {
        if (!inventory.isSuccess()) {
            Error error = inventory.error()
                    == NarStagedTreeInventory.Error.POLICY
                    ? Error.POLICY : Error.NATIVE;
            return StageResult.failure(
                    error, Error.OK, inventory.detail());
        }
        Resource owned = new Resource(
                owner.backend, context, handle, inventory);
        return StageResult.success(new Tree(owner, session, owned));
    }
}
