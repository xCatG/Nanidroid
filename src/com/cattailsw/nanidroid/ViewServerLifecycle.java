package com.cattailsw.nanidroid;

import android.app.Activity;
import android.os.Build;

import com.android.debug.hv.ViewServer;

/** Keeps the legacy debug ViewServer outside modern Android activity lifecycles. */
final class ViewServerLifecycle {
    interface Backend {
        void addWindow(Activity activity);

        void setFocusedWindow(Activity activity);

        void removeWindow(Activity activity);
    }

    private static final Backend LEGACY_BACKEND = new Backend() {
        @Override
        public void addWindow(Activity activity) {
            ViewServer.get(activity).addWindow(activity);
        }

        @Override
        public void setFocusedWindow(Activity activity) {
            ViewServer.get(activity).setFocusedWindow(activity);
        }

        @Override
        public void removeWindow(Activity activity) {
            ViewServer.get(activity).removeWindow(activity);
        }
    };

    private ViewServerLifecycle() {
    }

    static void onActivityCreated(Activity activity) {
        onActivityCreated(Build.VERSION.SDK_INT, activity, LEGACY_BACKEND);
    }

    static void onActivityResumed(Activity activity) {
        onActivityResumed(Build.VERSION.SDK_INT, activity, LEGACY_BACKEND);
    }

    static void onActivityDestroyed(Activity activity) {
        onActivityDestroyed(Build.VERSION.SDK_INT, activity, LEGACY_BACKEND);
    }

    static void onActivityCreated(int sdkInt, Activity activity, Backend backend) {
        if (usesLegacyViewServer(sdkInt)) {
            backend.addWindow(activity);
        }
    }

    static void onActivityResumed(int sdkInt, Activity activity, Backend backend) {
        if (usesLegacyViewServer(sdkInt)) {
            backend.setFocusedWindow(activity);
        }
    }

    static void onActivityDestroyed(int sdkInt, Activity activity, Backend backend) {
        if (usesLegacyViewServer(sdkInt)) {
            backend.removeWindow(activity);
        }
    }

    private static boolean usesLegacyViewServer(int sdkInt) {
        return sdkInt < Build.VERSION_CODES.HONEYCOMB;
    }
}
