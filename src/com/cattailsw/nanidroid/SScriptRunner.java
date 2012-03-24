package com.cattailsw.nanidroid;

import android.content.Context;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SScriptRunner {
    private static final String TAG = "SScriptRunner";

    private static SScriptRunner _self = null;
    private static final ConcurrentLinkedQueue<String> mMsgQueue = new ConcurrentLinkedQueue<String>();

    public static SScriptRunner getInstance(Context ctx) {
	if ( _self == null )
	    _self = new SScriptRunner(ctx);

	return _self;
    }

    SakuraView sakura = null;
    KeroView kero = null;
    Balloon sakuraBalloon = null;
    Balloon keroBalloon = null;
    Context mCtx = null;

    private SScriptRunner(Context ctx) {
	mCtx = ctx.getApplicationContext();
    }

    public void setViews(SakuraView s, KeroView k, Balloon bS, Balloon bK){
	sakura = s;
	kero = k;
	sakuraBalloon = bS;
	keroBalloon = bK;
    }

    public synchronized void addMsgToQueue(String []msgs){
	for( String s : msgs ) 
	    mMsgQueue.add(s);
    }
    
    private boolean isRunning = false;

    private String msg = null;

    public synchronized void run() {
	synchronized( isRunning ) {
	    if ( isRunning )
		return;
	    isRunning = true;
	}

	msg = mMsgQueue.poll();
	if ( msg == null ) {

	}
	else {

	}
    }
}
