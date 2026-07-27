package com.cattailsw.nanidroid;

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

import com.cattailsw.nanidroid.util.AnalyticsUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Java-only copy used by the frozen Ant build, which cannot compile Kotlin. */
public class SakuraView extends ImageView {
    private static final String TAG = "SakuraView";

    public interface UIEventCallback {
        int TYPE_SINGLE_CLICK = 0;
        int TYPE_DOUBLE_CLICK = 1;
        int TYPE_WHEEL = 2;
        int TYPE_MOVE = 3;
        void onHit(int type, int x, int y, int orientation, int collId, int buttonId);
    }

    SurfaceManager mgr;
    String currentSurfaceId;
    ShellSurface currentSurface;
    Context mCtx;
    AnimationDrawable animation;
    String currentAnimationId;
    UIEventCallback mUCB;
    private Rect[] colRz;
    private int[] colKeyz;

    public SakuraView(Context ctx) { super(ctx); mCtx = ctx.getApplicationContext(); }
    public SakuraView(Context ctx, AttributeSet attrs) { super(ctx, attrs); mCtx = ctx.getApplicationContext(); }
    public SakuraView(Context ctx, AttributeSet attrs, int defStyle) { super(ctx, attrs, defStyle); mCtx = ctx.getApplicationContext(); }

    public void setUiEventCallback(UIEventCallback cb) { mUCB = cb; }
    public void setMgr(SurfaceManager m) { mgr = m; currentSurfaceId = null; }
    protected void loadSurface(String sid) { currentSurface = mgr.getSakuraSurface(sid); }

    public void changeSurface(String surfaceid) {
        if (surfaceid.equalsIgnoreCase("-1")) { setVisibility(View.INVISIBLE); return; }
        if (!surfaceid.equalsIgnoreCase(currentSurfaceId)) {
            try {
                currentSurfaceId = surfaceid;
                loadSurface(surfaceid);
                setImageDrawable(currentSurface.getSurfaceDrawable(mCtx.getResources()));
                animation = null;
                currentAnimationId = null;
                populateColRz();
            } catch (Exception e) {
                String msg = mgr.ghostId + ":" + currentSurfaceId;
                if (e != null) msg += ":" + e.getMessage();
                AnalyticsUtils.getInstance(mCtx).trackEvent(Setup.ANA_ERR, "surface load", msg, -1);
            }
        }
        setVisibility(View.VISIBLE);
    }

    public boolean hasAnimation() { return currentSurface.getAnimationCount() > 0; }
    public int loadFirstAvailableAnimation() { int id = currentSurface.getFirstAnimationIndex(); loadAnimation("" + id); return id; }
    public void loadAnimation(String id) {
        if (animation == null || !id.equalsIgnoreCase(currentAnimationId)) {
            Log.d(TAG, "loading animation:" + id);
            animation = (AnimationDrawable) currentSurface.getAnimation(id, mCtx.getResources(), mgr);
            currentAnimationId = id;
        }
        if (animation != null) { animation.setVisible(true, true); setImageDrawable(animation); }
    }
    public void startAnimation() { if (animation == null) return; animation.stop(); animation.start(); }
    public void startRarelyAnimation() { startAnimation(ShellSurface.A_TYPE_RARELY); invalidate(); }
    public void startSometimesAnimation() { startAnimation(ShellSurface.A_TYPE_SOMETIMES); invalidate(); }
    public void startTalkingAnimation() { startAnimation(ShellSurface.A_TYPE_TALK); invalidate(); }
    public void startAnimation(int type) {
        String id = currentSurface.getAnimationIdByType(type);
        if (id == null) return;
        if (!id.equalsIgnoreCase(currentAnimationId)) loadAnimation(id);
        else if (animation != null) animation.setVisible(true, true);
        startAnimation();
    }

    private void populateColRz() {
        int colSize = currentSurface.getCollisionCount();
        if (colSize == 0) { colRz = null; return; }
        colRz = new Rect[colSize]; colKeyz = new int[colSize];
        Set<Integer> keys = currentSurface.collisionAreas.keySet(); int i = 0;
        for (Integer key : keys) { colRz[i] = currentSurface.collisionAreas.get(key).rect; colKeyz[i] = key; i++; }
    }

    public void showCollisionArea() {
        int colsize = currentSurface.getCollisionCount(); if (colsize == 0) return;
        Rect[] rz = new Rect[colsize]; Set<Integer> keys = currentSurface.collisionAreas.keySet(); int i = 0;
        for (Integer key : keys) rz[i++] = currentSurface.collisionAreas.get(key).rect;
        try {
            BitmapDrawable b = (BitmapDrawable) currentSurface.getSurfaceDrawable(mCtx.getResources());
            Bitmap bmpcopy = b.getBitmap().copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(bmpcopy); Paint p = new Paint();
            p.setAntiAlias(true); p.setStrokeWidth(1); p.setStyle(Style.STROKE); p.setColor(Color.rgb(254, 0, 1));
            for (Rect re : rz) c.drawRect(re, p);
            setImageDrawable(new BitmapDrawable(bmpcopy));
        } catch (Exception ignored) { }
    }

    int testColDect(int x, int y) {
        if (colRz == null) return -1;
        for (int i = 0; i < colRz.length; i++) if (colRz[i].contains(x, y)) return colKeyz[i];
        return -1;
    }

    public boolean onTouchEvent(final MotionEvent event) {
        Log.d(TAG, "onTouchEvent");
        int cid = testColDect((int) event.getX(0), (int) event.getY(0));
        Log.d(TAG, "test col at: " + cid);
        if (mUCB != null) mUCB.onHit(UIEventCallback.TYPE_DOUBLE_CLICK, (int) event.getX(0), (int) event.getY(0), 0, cid, 0);
        return super.onTouchEvent(event);
    }

    public void surfaceExercise() {
        try {
            List<String> keys = new ArrayList<String>(mgr.getSurfaceKeys()); Collections.sort(keys);
            for (String key : keys) { changeSurface(key); for (int i = ShellSurface.A_TYPE_SOMETIMES; i < ShellSurface.A_TYPE_LAST; i++) startAnimation(i); }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
