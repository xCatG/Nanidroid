package com.cattailsw.nanidroid;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

/** Frozen Ant-only copy of the pre-Kotlin layout adapter. */
public class LayoutManager {
    private SakuraView sv = null;
    private KeroView kv = null;
    private Balloon bSakura = null;
    private Balloon bKero = null;
    private FrameLayout fl = null;
    private static LayoutManager self = null;

    private LayoutManager(Context ctx) {
        ctx.getApplicationContext();
    }

    public static LayoutManager getInstance(Context ctx) {
        if (self == null) self = new LayoutManager(ctx);
        return self;
    }

    public void setViews(FrameLayout f, SakuraView s, KeroView k, Balloon bS, Balloon bK) {
        fl = f;
        sv = s;
        kv = k;
        bSakura = bS;
        bKero = bK;
    }

    public void checkAndUpdateLayoutParam() {
        int layoutWidth = fl.getWidth();
        int layoutHeight = fl.getHeight();
        if (layoutHeight <= 0 || layoutWidth <= 0) return;

        int sH = sv.currentSurface.origH;
        int sW = sv.currentSurface.origW;
        int kH = kv.currentSurface.origH;
        int kW = kv.currentSurface.origW;
        float wScale = sW + kW > layoutWidth ? (float) layoutWidth / (sW + kW) : 1.0f;
        int vH = Math.max(sH, kH);
        float hScale = vH > layoutHeight ? (float) layoutHeight / vH : 1.0f;
        float scale = Math.min(wScale, hScale);
        int scaledSakuraHeight = (int) (sH * scale);
        int scaledSakuraWidth = (int) (sW * scale);
        sv.setLayoutParams(new FrameLayout.LayoutParams(
                scaledSakuraWidth, scaledSakuraHeight, Gravity.BOTTOM | Gravity.RIGHT));
        int scaledKeroHeight = (int) (kH * scale);
        int scaledKeroWidth = (int) (kW * scale);
        kv.setLayoutParams(new FrameLayout.LayoutParams(
                scaledKeroWidth, scaledKeroHeight, Gravity.BOTTOM | Gravity.LEFT));

        if (scaledKeroHeight * 2 < scaledSakuraHeight) {
            int kbH = Math.max(scaledKeroHeight, scaledSakuraHeight - scaledKeroHeight);
            int kbW = scaledKeroWidth;
            if (scaledKeroWidth < layoutWidth - scaledSakuraWidth) kbW = layoutWidth - scaledSakuraWidth;
            FrameLayout.LayoutParams lpBK = new FrameLayout.LayoutParams(kbW, kbH, Gravity.BOTTOM | Gravity.LEFT);
            lpBK.bottomMargin = scaledKeroHeight;
            bKero.setLayoutParams(lpBK);
            bSakura.setLayoutParams(new FrameLayout.LayoutParams(
                    layoutWidth, layoutHeight - scaledSakuraHeight, Gravity.TOP | Gravity.RIGHT));
        } else {
            int bH = layoutHeight - scaledSakuraHeight;
            int bW = layoutWidth / 2;
            bSakura.setLayoutParams(new FrameLayout.LayoutParams(bW, bH, Gravity.TOP | Gravity.RIGHT));
            bKero.setLayoutParams(new FrameLayout.LayoutParams(bW, bH, Gravity.TOP | Gravity.LEFT));
        }
        fl.invalidate();
    }
}
