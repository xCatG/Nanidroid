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

public class SakuraView extends ImageView {
    private static final String TAG = "SakuraView";
    SurfaceManager mgr = null;
    ShellSurface currentSurface = null;
    Context mCtx = null;
    AnimationDrawable animation = null;
    String currentAnimationId = null;

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

    public void setMgr(SurfaceManager m) {
	mgr = m;
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

	loadSurface(surfaceid);
	setImageDrawable(currentSurface.getSurfaceDrawable(mCtx.getResources()));
	this.setVisibility(View.VISIBLE);
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
	if ( animation == null || id.equalsIgnoreCase(currentAnimationId) == false )
	    animation = (AnimationDrawable)currentSurface.getAnimation(id, mCtx.getResources());
	animation.setVisible(true, true);
	setImageDrawable( animation );
    }

    public void startAnimation(){
	animation.stop();
	animation.start();
    }

    public void startTalkingAnimation() {
	startAnimation(ShellSurface.A_TYPE_TALK);
    }

    public void startAnimation(int type) {
	// for talk type animation I guess
	// do nothing for the time being
	String id = currentSurface.getAnimationIdByType(type);
	if ( id == null ) // no such type animation
	    return;

	if ( currentAnimationId.equalsIgnoreCase(id) == false )
	    loadAnimation(id);
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
