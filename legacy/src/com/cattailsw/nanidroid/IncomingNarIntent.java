package com.cattailsw.nanidroid;

import android.content.Intent;
import android.net.Uri;

/** Frozen Java security gate restored only inside the disposable Ant build. */
final class IncomingNarIntent {
    private IncomingNarIntent() {
    }

    static boolean isApprovedDownload(Intent intent) {
        return intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction())
                && isApprovedDownload(intent.getData());
    }

    static boolean isApprovedDownload(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getHost() == null || uri.getHost().length() == 0) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase(java.util.Locale.US);
        return lowerPath.endsWith(".nar") || lowerPath.endsWith(".zip");
    }
}
