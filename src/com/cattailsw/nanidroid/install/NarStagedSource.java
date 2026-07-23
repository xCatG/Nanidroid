package com.cattailsw.nanidroid.install;

import java.io.File;

/**
 * One-shot authority for a future create-new app-private staged copy.
 *
 * <p>E deliberately provides no production minting path. D9b2 may mint only
 * after the writer for a fresh create-new app-private path has closed. The path
 * must remain immutable and unreplaceable under exclusive app ownership from
 * writer close through verified-session close. Separate portable API-9 path
 * opens cannot defeat a malicious same-UID ABA replacement without that
 * precondition.
 *
 * <p>D9b2 acceptance tests must prove no API exposes the writer or replacement
 * access and that exclusive ownership lasts until session close.
 */
final class NarStagedSource {
    private final File file;
    private boolean claimed;

    private NarStagedSource(File file) {
        this.file = file;
    }

    synchronized File claim() {
        if (claimed || file == null) {
            return null;
        }
        claimed = true;
        return file;
    }
}
