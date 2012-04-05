package com.cattailsw.nanidroid;

import android.widget.TextView;
import android.content.Context;
import android.util.AttributeSet;
import android.text.util.Linkify;

public class Balloon extends TextView {

    public Balloon(Context ctx) {
	super(ctx);
    }

    public Balloon(Context ctx, AttributeSet attrs){
	super(ctx, attrs);
    }

    public Balloon(Context ctx, AttributeSet attrs, int defStyle) {
	super(ctx, attrs, defStyle);
    }

    public void setText(String str){
	super.setText(str);
	Linkify.addLinks(this, Linkify.ALL);
    }

}
