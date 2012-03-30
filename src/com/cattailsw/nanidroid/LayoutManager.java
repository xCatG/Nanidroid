package com.cattailsw.nanidroid;

import android.widget.FrameLayout;
import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout.LayoutParams;
import android.util.Log;

public class LayoutManager {
    private static final String TAG = "LayoutManager";
    private SakuraView sv = null;
    private KeroView kv = null;
    private Balloon bSakura = null;
    private Balloon bKero = null;
    private FrameLayout fl = null;
    private Context mCtx = null;

    private static LayoutManager _self = null;

    private LayoutManager(Context ctx) {
	mCtx = ctx.getApplicationContext();
    }

    public static LayoutManager getInstance(Context ctx) {
	if (_self == null )
	    _self = new LayoutManager(ctx);

	return _self;
    }

    public void setViews(FrameLayout f, SakuraView s, KeroView k, Balloon bS, Balloon bK){
	fl = f;
	sv = s;
	kv = k;
	bSakura = bS;
	bKero = bK;
    }

    public void checkAndUpdateLayoutParam() {
	int layoutWidth = fl.getWidth();
	int layoutHeight = fl.getHeight();
	if ( layoutHeight <= 0 || layoutWidth <= 0 )
	    return;

	int sH = sv.currentSurface.origH;
	int sW = sv.currentSurface.origW;
	int kH = kv.currentSurface.origH;
	int kW = kv.currentSurface.origW;

	// need to compute the maximum allowed width and height for both kero and sakura
	// first compare sW + kW and lW
	float wScale = 1.0f;
	if ( sW + kW > layoutWidth ) {
	    wScale = ((float) layoutWidth / (float)(sW + kW) );
	}

	float hScale = 1.0f;
	int vH = Math.max(sH, kH);
	if ( vH > layoutHeight ) {
	    hScale = ((float) layoutHeight / (float)vH );
	}
	
	float scale = Math.min(wScale, hScale);
// 	Log.d(TAG, "wScale:hScale="+wScale+":"+hScale+", [lH:lW]=["+layoutHeight+":"+layoutWidth+"],[sh:sw]="
// 	      + sH + ":" + sW +"]");
	int scaledSakuraHeight = (int) ( sH * scale);
	FrameLayout.LayoutParams lpS = new FrameLayout.LayoutParams((int)(sW * scale),
								    scaledSakuraHeight,
								    Gravity.BOTTOM | Gravity.RIGHT);
	sv.setLayoutParams(lpS);
	int scaledKeroHeight = (int)(kH * scale);
	int scaledKeroWidth = (int) (kW * scale);
	FrameLayout.LayoutParams lpK = new FrameLayout.LayoutParams(scaledKeroWidth,
								    scaledKeroHeight,
								    Gravity.BOTTOM | Gravity.LEFT);
	kv.setLayoutParams(lpK);

	// next compute balloon sizes...
	if ( scaledKeroHeight * 2 < scaledSakuraHeight ) {
	    int kbH = (Math.max( scaledKeroHeight, scaledSakuraHeight - scaledKeroHeight) );

	    FrameLayout.LayoutParams lpBK = new FrameLayout.LayoutParams(scaledKeroWidth, kbH, 
									 Gravity.BOTTOM | Gravity.LEFT);
	    lpBK.bottomMargin = scaledKeroHeight;
	    bKero.setLayoutParams(lpBK);
	    int bH = layoutHeight - scaledSakuraHeight;
	    
	    FrameLayout.LayoutParams lpBS = new FrameLayout.LayoutParams(layoutWidth, bH, 
									 Gravity.TOP | Gravity.RIGHT);
	    bSakura.setLayoutParams(lpBS);

	}
	else {
	    int bH = layoutHeight - scaledSakuraHeight;
	    int bW = layoutWidth / 2;
	    FrameLayout.LayoutParams lpBS = new FrameLayout.LayoutParams(bW, bH, Gravity.TOP | Gravity.RIGHT);
	    bSakura.setLayoutParams(lpBS);
	    FrameLayout.LayoutParams lpBK = new FrameLayout.LayoutParams(bW, bH, Gravity.TOP | Gravity.LEFT);
	    bKero.setLayoutParams(lpBK);
	}

	fl.invalidate();
    }

}
