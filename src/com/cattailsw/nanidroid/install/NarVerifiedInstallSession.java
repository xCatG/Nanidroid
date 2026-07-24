package com.cattailsw.nanidroid.install;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Package-only extraction authority over one retained ZIP owner. */
final class NarVerifiedInstallSession {
    enum State { READY, BUSY, CONSUMED }
    enum LeaseError { OK, BUSY, CONSUMED, FOREIGN, STALE }

    private final List<? extends
            NarInstallPlanValidator.ArchiveEntry> entries;
    private final NarInstallPlan plan;
    private final Resource resource;
    private State state = State.READY;
    private Lease current;
    private boolean directCleanup;

    NarVerifiedInstallSession(
            NarInstallPlanValidator.ArchiveIo io,
            File stagedFile,
            NarInstallPlanValidator.OpenArchive archive,
            List<? extends
                    NarInstallPlanValidator.ArchiveEntry> entries,
            NarInstallPlan plan) {
        this.entries = entries;
        this.plan = plan;
        resource = new Resource(io, stagedFile, archive);
    }

    NarInstallPlan getPlan() { return plan; }
    synchronized State state() { return state; }
    boolean isClosed() { return resource.isComplete(); }

    synchronized Lease lease() {
        if (state != State.READY) return null;
        Lease leased = new Lease(this, resource);
        current = leased;
        state = State.BUSY;
        return leased;
    }

    synchronized LeaseError release(Lease lease) {
        LeaseError checked = check(lease);
        if (checked != LeaseError.OK) return checked;
        lease.active = false;
        current = null;
        state = State.READY;
        return LeaseError.OK;
    }

    synchronized LeaseError consume(Lease lease) {
        LeaseError checked = check(lease);
        if (checked != LeaseError.OK) return checked;
        lease.active = false;
        lease.consumed = true;
        current = null;
        state = State.CONSUMED;
        return LeaseError.OK;
    }

    private LeaseError check(Lease lease) {
        if (lease == null || lease.owner != this) {
            return LeaseError.FOREIGN;
        }
        if (lease.consumed) return LeaseError.CONSUMED;
        if (!lease.active) return LeaseError.STALE;
        if (state == State.CONSUMED) return LeaseError.CONSUMED;
        if (state != State.BUSY || current != lease) {
            return LeaseError.BUSY;
        }
        return LeaseError.OK;
    }

    private synchronized NarInstallPlan leasedPlan(Lease lease) {
        if (check(lease) != LeaseError.OK) {
            throw new IllegalStateException("stale archive lease");
        }
        return plan;
    }

    synchronized InputStream open(NarInstallPlan.Entry entry)
            throws IOException {
        if (state != State.READY) {
            throw new IllegalStateException("session unavailable");
        }
        if (entry == null
                || entry.isDirectory()
                || !entry.isInstallEntry()) {
            throw new IllegalArgumentException(
                    "entry not extractable");
        }
        int ordinal = entry.getOrdinal();
        if (ordinal < 0
                || ordinal >= entries.size()
                || ordinal >= plan.getEntries().size()
                || plan.getEntries().get(ordinal) != entry) {
            throw new IllegalArgumentException("foreign plan entry");
        }
        return resource.open(entries.get(ordinal));
    }

    void close() throws IOException {
        synchronized (this) {
            if (state == State.BUSY) {
                throw new IllegalStateException("session busy");
            }
            if (state == State.READY) {
                state = State.CONSUMED;
                directCleanup = true;
            } else if (!directCleanup) {
                throw new IllegalStateException("session consumed");
            }
        }
        resource.cleanup();
    }

    static final class Lease {
        private final NarVerifiedInstallSession owner;
        private final Resource resource;
        private boolean active = true;
        private boolean consumed;

        private Lease(
                NarVerifiedInstallSession owner, Resource resource) {
            this.owner = owner;
            this.resource = resource;
        }

        NarInstallPlan plan() { return owner.leasedPlan(this); }

        void cleanup() throws IOException {
            synchronized (owner) {
                if (!consumed) {
                    throw new IllegalStateException(
                            "archive lease not consumed");
                }
            }
            resource.cleanup();
        }
    }

    private static final class Resource {
        private final NarInstallPlanValidator.ArchiveIo io;
        private final File stagedFile;
        private final NarInstallPlanValidator.OpenArchive archive;
        private boolean archiveClosed;
        private boolean deleted;

        private Resource(
                NarInstallPlanValidator.ArchiveIo io,
                File stagedFile,
                NarInstallPlanValidator.OpenArchive archive) {
            this.io = io;
            this.stagedFile = stagedFile;
            this.archive = archive;
        }

        private synchronized InputStream open(
                NarInstallPlanValidator.ArchiveEntry entry)
                throws IOException {
            return archive.open(entry);
        }

        private synchronized boolean isComplete() {
            return archiveClosed && deleted;
        }

        private synchronized void cleanup() throws IOException {
            boolean failed = false;
            Throwable first = null;
            String firstMessage = null;
            if (!archiveClosed) {
                try {
                    archive.close();
                    archiveClosed = true;
                } catch (Throwable failure) {
                    failed = true;
                    first = failure;
                    firstMessage = "archive close";
                }
            }
            if (!deleted) {
                try {
                    if (io.delete(stagedFile)) {
                        deleted = true;
                    } else if (!failed) {
                        failed = true;
                        firstMessage = "staging delete";
                    }
                } catch (Throwable failure) {
                    if (!failed) {
                        failed = true;
                        first = failure;
                        firstMessage = "staging delete";
                    }
                }
            }
            if (failed) rethrow(first, firstMessage);
        }

        private static void rethrow(
                Throwable failure, String message)
                throws IOException {
            if (failure == null) throw new IOException(message);
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            if (failure instanceof Error) throw (Error) failure;
            throw new IOException(message, failure);
        }
    }
}
