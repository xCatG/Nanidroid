package com.cattailsw.nanidroid;

import java.util.Set;

import com.cattailsw.nanidroid.util.AnalyticsUtils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

public class SakuraView extends ImageView {
    private static final String TAG = "SakuraView";

    public interface UIEventCallback {
	public static final int TYPE_SINGLE_CLICK = 0;
	public static final int TYPE_DOUBLE_CLICK = 1;
	public static final int TYPE_WHEEL = 2;
	public static final int TYPE_MOVE = 3;
	// type can be TYPE_SINGLE_CLICK
	// TYPE_DOUBLE_CLICK
	// TYPE_WHEEL
	// x, y are local X,Y coordinates
	// orientation can be -1, 0, 1; -1 means wheel down, 1 means wheel up, 0 means no wheel
	// collId is collision area id defined in shellsurface
	// buttonid is BTN_LEFT, BTN_RIGHT, BTN_WHEEL ?
	public void onHit(int type, int x, int y, int orientation, int collId, int buttonId);
    }

    SurfaceManager mgr = null;
    String currentSurfaceId = null;
    ShellSurface currentSurface = null;
    Context mCtx = null;
    AnimationDrawable animation = null;
    String currentAnimationId = null;
    UIEventCallback mUCB = null;

    public SakuraView(Context ctx){
	super(ctx);
	mCtx = ctx.getApplicationContext();
    }

    public SakuraView(Context ctx, AttributeSet attrs){
	super(ctx, attrs);
	mCtx = ctx.getApplicationContext();
    }

    public SakuraView(Context ctx, AttributeSet attrs, int defStyle) {
	super(ctx, attrs, defStyle);
	mCtx = ctx.getApplicationContext();
    }

    public void setUiEventCallback(UIEventCallback cb) {
	mUCB = cb;
    }

    public void setMgr(SurfaceManager m) {
	mgr = m;
	currentSurfaceId = null;
    }

    protected void loadSurface(String sid) {
	currentSurface = mgr.getSakuraSurface(sid);
    }
    
    public void changeSurface(String surfaceid){
	if ( surfaceid.equalsIgnoreCase("-1")) {
	    // hide self
	    this.setVisibility(View.INVISIBLE);
	    return;
	}

	if ( surfaceid.equalsIgnoreCase(currentSurfaceId) == false ){
	    /*Log.d(TAG, "loading new surface:" + surfaceid);*/
		try {
	    currentSurfaceId = surfaceid;
	    loadSurface(surfaceid);
	    setImageDrawable(currentSurface.getSurfaceDrawable(mCtx.getResources()));
	    animation = null;
	    currentAnimationId = null;
	    populateColRz();
		}
		catch(Exception e) {
			String msg = mgr.ghostId + ":" + currentSurfaceId ;
			if ( e != null )
				msg += ":" + e.getMessage();
			AnalyticsUtils.getInstance(mCtx).trackEvent(Setup.ANA_ERR, "surface load", msg , -1);
		}
	}
	this.setVisibility(View.VISIBLE);

	if ( mUCB != null ) {

	}
    }

    public boolean hasAnimation() {
	return (currentSurface.getAnimationCount() > 0);
    }

    // this will not really have any use in real product... :X
    public int loadFirstAvailableAnimation() {
	int animeIndex = currentSurface.getFirstAnimationIndex();
	loadAnimation( "" + animeIndex );
	return animeIndex;
    }

    public void loadAnimation(String id) {
	if ( animation == null || id.equalsIgnoreCase(currentAnimationId) == false ){
	    Log.d(TAG, "loading animation:" + id);
	    //animation = (AnimationDrawable)currentSurface.getAnimation(id, mCtx.getResources());
	    animation = (AnimationDrawable)currentSurface.getAnimation(id, mCtx.getResources(), mgr);
	    currentAnimationId = id;
	}
	if ( animation != null ) {
	animation.setVisible(true, true);
	setImageDrawable( animation );
	}
    }

    public void startAnimation(){
    	if ( animation == null) return;
	animation.stop();
	animation.start();
    }

    public void startRarelyAnimation() {
	startAnimation(ShellSurface.A_TYPE_RARELY);
	invalidate();
    }

    public void startSometimesAnimation() {
	startAnimation(ShellSurface.A_TYPE_SOMETIMES);
	invalidate();
    }

    public void startTalkingAnimation() {
	//	Log.d(TAG, "startTalkingAnimation called");
	startAnimation(ShellSurface.A_TYPE_TALK);
	invalidate();
    }

    public void startAnimation(int type) {
	// for talk type animation I guess
	// do nothing for the time being
	String id = currentSurface.getAnimationIdByType(type);
	if ( id == null ) { // no such type animation
	    if ( type!=ShellSurface.A_TYPE_TALK) Log.d(TAG, "no animation of type:" + type);
	    return;
	}

	if ( id.equalsIgnoreCase(currentAnimationId) == false )
	    loadAnimation(id);
	else
	    animation.setVisible(true, true);

	startAnimation();
    }

    private Rect[] colRz = null;
    private int[] colKeyz = null;

    private void populateColRz() {
	int colSize = currentSurface.getCollisionCount();
	if ( colSize == 0 ) {
	    colRz = null; // set it to null and return
	    return;
	}

	colRz = new Rect[colSize];
	colKeyz = new int[colSize];

	Set<Integer> colKey = currentSurface.collisionAreas.keySet();
	int i = 0;
	for ( Integer k : colKey ) {
	    colRz[i] = currentSurface.collisionAreas.get(k).rect;
	    colKeyz[i] = k;
	    //Log.d(TAG, "col " + i + colRz[i]);
	    i++;
	}
	//Log.d(TAG, "col data populated with " + colSize + " areas");
    }

    public void showCollisionArea() {
	int colsize = currentSurface.getCollisionCount();
	if ( colsize == 0 ) return;
	Rect[] rz = new Rect[colsize];
	Set<Integer> colKey = currentSurface.collisionAreas.keySet();
	int i = 0;
	for ( Integer k : colKey ) {
	    rz[i] = currentSurface.collisionAreas.get(k).rect;
	    i++;
	}
	
	try {
	BitmapDrawable b = (BitmapDrawable) currentSurface.getSurfaceDrawable(mCtx.getResources());
	Bitmap bmpcopy = b.getBitmap().copy(Bitmap.Config.ARGB_8888, true);
	Canvas c = new Canvas(bmpcopy);
	Paint p = new Paint();
	p.setAntiAlias(true);
	p.setStrokeWidth(1);
	p.setStyle(Style.STROKE);
	p.setColor(Color.rgb(254, 0, 1));

	for ( Rect re : rz )
	    c.drawRect(re, p);

	BitmapDrawable nb = new BitmapDrawable( bmpcopy );

	setImageDrawable( nb );
	}
	catch(Exception e) {
		
	}
    }


 void printSamples(MotionEvent ev) {
     final int historySize = ev.getHistorySize();
     final int pointerCount = ev.getPointerCount();
     String s = null;
     for (int h = 0; h < historySize; h++) {
         System.out.printf("At time %d:", ev.getHistoricalEventTime(h));
         for (int p = 0; p < pointerCount; p++) {
             s = String.format("  pointer %d: (%f,%f)",
                 ev.getPointerId(p), ev.getHistoricalX(p, h), ev.getHistoricalY(p, h));
	     Log.d(TAG, s);
         }
     }
     Log.d(TAG, String.format("At time %d:", ev.getEventTime()));
     for (int p = 0; p < pointerCount; p++) {
         s = String.format("  pointer %d: (%f,%f)",
             ev.getPointerId(p), ev.getX(p), ev.getY(p));
	 Log.d(TAG, s);
     }
 }
 
    int testColDect(int x, int y) {
	if ( colRz == null )
	    return -1;

	for ( int i = 0; i < colRz.length; i++ ) {
	    if ( colRz[i].contains(x, y) ) {
		return colKeyz[i];
	    }
	}

	return -1;
    }

    public final boolean onTouchEvent(final MotionEvent motionEvent) {
	Log.d(TAG, "onTouchEvent");

	//printSamples(motionEvent);

	int cid = testColDect((int)motionEvent.getX(0), (int)motionEvent.getY(0));
	if ( cid > -1) {
	Log.d(TAG, "test col at: " + cid);

	if ( mUCB != null ) {
	    mUCB.onHit(UIEventCallback.TYPE_DOUBLE_CLICK, 
		       (int)motionEvent.getX(0),
		       (int)motionEvent.getY(0), 0, cid, 0);
	    
	}
	}
	return super.onTouchEvent(motionEvent);
    }

}
