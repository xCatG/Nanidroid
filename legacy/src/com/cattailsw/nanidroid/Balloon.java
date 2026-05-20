package com.cattailsw.nanidroid;

import android.widget.TextView;
import android.content.Context;
import android.util.AttributeSet;
import android.text.util.Linkify;
import android.text.method.ScrollingMovementMethod;
import android.text.Layout;
import android.util.Log;

public class Balloon extends TextView {
    private static final String TAG = "Balloon";
    public Balloon(Context ctx) {
	super(ctx);
    }

    public Balloon(Context ctx, AttributeSet attrs){
	super(ctx, attrs);
    }

    public Balloon(Context ctx, AttributeSet attrs, int defStyle) {
	super(ctx, attrs, defStyle);
	//setMovementMethod(new ScrollingMovementMethod());

    }

    int vPaddingSize = 0;
    private ScrollingMovementMethod s = new ScrollingMovementMethod();
    public void setText(String str){
	scrollTo(0,0);
	super.setText(str);
	Linkify.addLinks(this, Linkify.ALL);
	vPaddingSize = getCompoundPaddingBottom() + getCompoundPaddingTop();
	//Log.d(TAG, "vPaddingSize:"+vPaddingSize);
	final Layout l = getLayout();
	if ( l != null ) {
	    int scrollDelta = l.getLineBottom(l.getLineCount() - 1) - getScrollY() - (getHeight() - vPaddingSize);
	    if ( scrollDelta > 0 ){
		//Log.d(TAG, "btm="+l.getLineBottom(l.getLineCount() - 1)+",YS:"+getScrollY()+",H:"+getHeight()
		//+",pad:"+getCompoundPaddingBottom()+",d:"+scrollDelta +",vp:"+vPaddingSize);
		//awakenScrollBars(0, true);
		setMovementMethod(s);
		scrollBy(0, scrollDelta);	    
	    }
	    else
		setMovementMethod(null);
	}
	
    }

}
