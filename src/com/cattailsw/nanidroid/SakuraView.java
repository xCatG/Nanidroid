package com.cattailsw.nanidroid;

import android.widget.ImageView;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.Rect;
import java.util.Set;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.view.View;
import android.util.Log;

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
	    currentSurfaceId = surfaceid;
	    loadSurface(surfaceid);
	    setImageDrawable(currentSurface.getSurfaceDrawable(mCtx.getResources()));
	    animation = null;
	    currentAnimationId = null;
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
	    animation = (AnimationDrawable)currentSurface.getAnimation(id, mCtx.getResources());
	    currentAnimationId = id;
	}
	animation.setVisible(true, true);
	setImageDrawable( animation );
    }

    public void startAnimation(){
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

}
