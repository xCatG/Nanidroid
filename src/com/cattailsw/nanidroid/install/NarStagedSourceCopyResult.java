package com.cattailsw.nanidroid.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable internal result that exposes only the staged capability. */
final class NarStagedSourceCopyResult {
    private final NarStagedSource source;
    private final NarStagedSourceCopyError error;
    private final String detail;
    private final List<NarStagedSourceCopyError> cleanupErrors;

    private NarStagedSourceCopyResult(
            NarStagedSource source,
            NarStagedSourceCopyError error,
            String detail,
            List<NarStagedSourceCopyError> cleanupErrors) {
        this.source = source;
        this.error = error;
        this.detail = detail;
        this.cleanupErrors = Collections.unmodifiableList(
                new ArrayList<NarStagedSourceCopyError>(
                        cleanupErrors));
    }

    static NarStagedSourceCopyResult success(
            NarStagedSource source) {
        return new NarStagedSourceCopyResult(
                source,
                null,
                "",
                Collections
                        .<NarStagedSourceCopyError>emptyList());
    }

    static NarStagedSourceCopyResult failure(
            NarStagedSourceCopyError error,
            String detail,
            List<NarStagedSourceCopyError> cleanupErrors) {
        return new NarStagedSourceCopyResult(
                null, error, detail, cleanupErrors);
    }

    boolean isSuccess() {
        return source != null;
    }

    NarStagedSource getSource() {
        return source;
    }

    NarStagedSourceCopyError getError() {
        return error;
    }

    String getDetail() {
        return detail;
    }

    List<NarStagedSourceCopyError> getCleanupErrors() {
        return cleanupErrors;
    }
}
