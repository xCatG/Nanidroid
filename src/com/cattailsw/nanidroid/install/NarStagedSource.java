package com.cattailsw.nanidroid.install;

import java.io.File;

/**
 * One-shot authority for a future create-new app-private staged copy.
 *
 * <p>E deliberately provides no production minting path.
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
