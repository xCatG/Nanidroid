package com.cattailsw.nanidroid.install;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Package-only extraction authority over one retained ZIP owner. */
final class NarVerifiedInstallSession {
    private final NarInstallPlanValidator.ArchiveIo io;
    private final File stagedFile;
    private final NarInstallPlanValidator.OpenArchive archive;
    private final List<? extends
            NarInstallPlanValidator.ArchiveEntry> entries;
    private final NarInstallPlan plan;
    private boolean closed;

    NarVerifiedInstallSession(
            NarInstallPlanValidator.ArchiveIo io,
            File stagedFile,
            NarInstallPlanValidator.OpenArchive archive,
            List<? extends
                    NarInstallPlanValidator.ArchiveEntry> entries,
            NarInstallPlan plan) {
        this.io = io;
        this.stagedFile = stagedFile;
        this.archive = archive;
        this.entries = entries;
        this.plan = plan;
    }

    NarInstallPlan getPlan() {
        return plan;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized InputStream open(NarInstallPlan.Entry entry)
            throws IOException {
        if (closed) {
            throw new IllegalStateException("session closed");
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
        return archive.open(entries.get(ordinal));
    }

    synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            archive.close();
        } catch (IOException error) {
            failure = error;
        } catch (RuntimeException error) {
            failure = new IOException("archive close", error);
        }
        boolean deleted = false;
        try {
            deleted = io.delete(stagedFile);
        } catch (RuntimeException error) {
            if (failure == null) {
                failure = new IOException(
                        "staging delete", error);
            }
        }
        if (!deleted && failure == null) {
            failure = new IOException("staging delete");
        }
        if (failure != null) {
            throw failure;
        }
    }
}
