package com.cattailsw.nanidroid;

import android.content.Context;
import android.util.AttributeSet;

/** Java-only copy used by the frozen Ant build, which cannot compile Kotlin. */
public class KeroView extends SakuraView {
    public KeroView(Context ctx) { super(ctx); }
    public KeroView(Context ctx, AttributeSet attr) { super(ctx, attr); }
    public KeroView(Context ctx, AttributeSet attr, int defStyle) { super(ctx, attr, defStyle); }

    protected void loadSurface(String surfaceid) {
        currentSurface = mgr.getKeroSurface(surfaceid);
    }
}
