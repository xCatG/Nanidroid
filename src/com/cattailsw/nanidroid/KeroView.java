package com.cattailsw.nanidroid;

import android.content.Context;
import android.util.AttributeSet;

public class KeroView extends SakuraView {
    public KeroView(Context ctx){
	super(ctx);
    }
    public KeroView(Context ctx, AttributeSet attr){
	super(ctx, attr);
    }

    public KeroView(Context ctx, AttributeSet attr, int defStyle){
	super(ctx, attr, defStyle);
    }

    public void changeSurface(String surfaceid){
	currentSurface = mgr.getKeroSurface(surfaceid);
    }


}
