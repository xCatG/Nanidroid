package com.cattailsw.nanidroid.install;

import java.io.IOException;
import java.util.List;

/**
 * Binds exact, already-validated archive and retained-tree ownership after
 * the pure overlay policy accepts their immutable facts.
 *
 * <p>This is deliberately not a materializer: it cannot read either source,
 * reach a destination, call native code, or publish an install. A successful
 * candidate owns only eventual cleanup of the two already-consumed sources.
 */
final class NarRetainedOverlayCoordinator {
    private NarRetainedOverlayCoordinator() {}

    enum Error { INPUT, BUSY, POLICY, TRANSFER }

    static Result bind(NarVerifiedInstallSession session,
            NarStagedTree.Claim claim) {
        if (session == null || claim == null) {
            return new Result(null, Error.INPUT, null, "input");
        }
        NarVerifiedInstallSession.Lease archiveLease = session.lease();
        if (archiveLease == null) {
            return new Result(null, Error.BUSY, null, "archive lease");
        }
        NarStagedTree.Claim.Lease treeLease = claim.lease();
        if (treeLease == null) {
            session.release(archiveLease);
            return new Result(null, Error.BUSY, null, "baseline lease");
        }
        boolean archiveConsumed = false;
        boolean treeConsumed = false;
        boolean transferred = false;
        try {
            NarInstallPlan plan = archiveLease.plan();
            NarGhostTreePolicy.Manifest manifest = treeLease.manifest();
            List<NarStagedTreeInventory.Entry> entries = treeLease.entries();
            NarRetainedOverlayPolicy.Result policy =
                    NarRetainedOverlayPolicy.build(plan, manifest, entries);
            if (!policy.isSuccess()) {
                return new Result(null, Error.POLICY, policy.error(),
                        policy.detail());
            }
            Candidate candidate = new Candidate(policy.recipe(),
                    archiveLease, treeLease);
            if (session.consume(archiveLease)
                    != NarVerifiedInstallSession.LeaseError.OK) {
                return new Result(null, Error.TRANSFER, null,
                        "archive transfer");
            }
            archiveConsumed = true;
            if (claim.consume(treeLease) != NarStagedTree.Error.OK) {
                return new Result(null, Error.TRANSFER, null,
                        "baseline transfer");
            }
            treeConsumed = true;
            transferred = true;
            return new Result(candidate, null, null, "");
        } finally {
            if (!transferred) {
                if (!treeConsumed) claim.release(treeLease);
                if (!archiveConsumed) session.release(archiveLease);
                /*
                 * Exact consume cannot fail after accepting its active lease;
                 * these branches are defensive for a future implementation.
                 * No busy lease escapes even if that invariant is broken.
                 */
                if (archiveConsumed) {
                    try {
                        archiveLease.cleanup();
                    } catch (Throwable ignored) {
                        // No candidate escaped; preserve the primary bind result.
                    }
                }
                if (treeConsumed) {
                    try {
                        treeLease.discard();
                    } catch (Throwable ignored) {
                        // No candidate escaped; preserve the primary bind result.
                    }
                }
            }
        }
    }

    static final class Result {
        private final Candidate candidate;
        private final Error error;
        private final NarRetainedOverlayPolicy.Error policyError;
        private final String detail;

        private Result(Candidate candidate, Error error,
                NarRetainedOverlayPolicy.Error policyError,
                String detail) {
            this.candidate = candidate;
            this.error = error;
            this.policyError = policyError;
            this.detail = detail == null ? "input" : detail;
        }

        boolean isSuccess() { return candidate != null; }
        Candidate candidate() { return candidate; }
        Error error() { return error; }
        NarRetainedOverlayPolicy.Error policyError() { return policyError; }
        String detail() { return detail; }
    }

    static final class Candidate {
        private final NarVerifiedInstallSession.Lease archiveLease;
        private final NarStagedTree.Claim.Lease treeLease;
        private final NarRetainedOverlayPolicy.Recipe recipe;
        private final byte[] baselineFingerprint;
        private final int fileCount;
        private final boolean knownTotalSize;
        private final long totalSize;
        private boolean archiveCleaned;
        private boolean treeCleaned;

        private Candidate(NarRetainedOverlayPolicy.Recipe recipe,
                NarVerifiedInstallSession.Lease archiveLease,
                NarStagedTree.Claim.Lease treeLease) {
            this.recipe = recipe;
            baselineFingerprint = recipe.baselineFingerprint();
            fileCount = recipe.fileCount();
            knownTotalSize = recipe.hasKnownTotalSize();
            totalSize = recipe.totalSize();
            this.archiveLease = archiveLease;
            this.treeLease = treeLease;
        }

        byte[] baselineFingerprint() { return baselineFingerprint.clone(); }
        int fileCount() { return fileCount; }
        boolean hasKnownTotalSize() { return knownTotalSize; }
        long totalSize() { return totalSize; }
        NarRetainedOverlayPolicy.Recipe recipe() { return recipe; }
        synchronized boolean isCleaned() {
            return archiveCleaned && treeCleaned;
        }

        synchronized void cleanup() throws IOException {
            Throwable first = null;
            String message = null;
            if (!archiveCleaned) {
                try {
                    archiveLease.cleanup();
                    archiveCleaned = true;
                } catch (Throwable failure) {
                    first = failure;
                    message = "archive cleanup";
                }
            }
            if (!treeCleaned) {
                try {
                    NarStagedTree.Error result = treeLease.discard();
                    if (result == NarStagedTree.Error.OK) {
                        treeCleaned = true;
                    } else if (first == null) {
                        message = "baseline discard " + result.name();
                    }
                } catch (Throwable failure) {
                    if (first == null) {
                        first = failure;
                        message = "baseline discard";
                    }
                }
            }
            if (!archiveCleaned || !treeCleaned) {
                if (first instanceof IOException) {
                    throw (IOException) first;
                }
                if (first instanceof java.lang.Error) {
                    throw (java.lang.Error) first;
                }
                if (first != null) throw new IOException(message, first);
                throw new IOException(message);
            }
        }
    }
}
