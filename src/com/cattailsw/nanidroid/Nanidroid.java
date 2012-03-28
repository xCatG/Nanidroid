package com.cattailsw.nanidroid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.os.Environment;
import java.io.File;
import android.widget.TextView;
import android.os.SystemClock;
import android.graphics.drawable.AnimationDrawable;
import android.view.View;
import java.util.Set;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Color;
import java.util.Collections;
import java.util.Arrays;
import android.util.Log;

public class Nanidroid extends Activity
{
    private static final String TAG = "Nanidroid";
    //private ImageView sv = null;
    private SakuraView sv = null;
    private KeroView kv = null;
    private Balloon bSakura = null;
    private Balloon bKero = null;

    AnimationDrawable anime = null;
    SurfaceManager mgr = null;

    SScriptRunner runner = null;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	sv = (SakuraView) findViewById(R.id.sakura_display);
	kv = (KeroView) findViewById(R.id.kero_display);
	bSakura = (Balloon) findViewById(R.id.bSakura);
	bKero = (Balloon) findViewById(R.id.bKero);


	mgr = SurfaceManager.getInstance();
	runner = SScriptRunner.getInstance(this);
	runner.setViews(sv, kv, bSakura, bKero);
	sv.setMgr(mgr);
	kv.setMgr(mgr);
	// need to get a list of ghosts on sd card
	if ( Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED) == false ) {
	    bSakura.setText("sd card error");
	    return;
	    // need to prompt SD card issue
	}

	// use /sdcard/Android/data/com.cattailsw.nanidroid/ghost/yohko for the time being
	String ghost_path = Environment.getExternalStorageDirectory().getPath() + 
	    //"/Android/data/com.cattailsw.nanidroid/ghost/2elf";
	    "/Android/data/com.cattailsw.nanidroid/ghost/yohko";
	//"/Android/data/com.cattailsw.nanidroid/ghost/first";
	// read the ghost shell
	//
	String master_shell_path = ghost_path + "/shell/master";

	// read master data
	String master_ghost_path = ghost_path + "/ghost/master";

	String shell_desc_path = master_shell_path + "/descript.txt";
	String shell_surface_path = master_shell_path + "/surfaces.txt";

	File shell_desc = new File(shell_desc_path);
	if ( shell_desc.exists() == false ) {
	    bSakura.setText("shell desc error.");
	    return;
	}
	long starttime = SystemClock.uptimeMillis();
	DescReader shellDescReader = new DescReader(shell_desc);
	//DescReader shellSurfaceReader = 
	File shellSurface = new File(shell_surface_path);
	sr = new SurfaceReader(shellSurface);
	long dur = SystemClock.uptimeMillis() - starttime;

	int keycount = mgr.getTotalSurfaceCount();
	bKero.setText("shell desc size=" + shellDescReader.table.size() + ", ghost name=" + shellDescReader.table.get("name") 
		   + "\nshell surface count=" + keycount + ",parsing time:" + (float)dur/1000.0f + "s"  );

	surfaceKeys = new String[keycount];
	Set<String> k = mgr.getSurfaceKeys();
	surfaceKeys = k.toArray(surfaceKeys);
	Arrays.sort(surfaceKeys);
	currentSurfaceKey = surfaceKeys[0];
	currentSurface = mgr.getSakuraSurface(currentSurfaceKey);
	checkAndLoadAnimation();
    }


    private void checkAndLoadAnimation() {
	sv.changeSurface(currentSurfaceKey);
	kv.changeSurface("10");

	if (sv.hasAnimation() == false ){
	    findViewById(R.id.btn2).setEnabled(false);
	}
	else {
	    //iv.loadFirstAvailableAnimation();
	    animeIndex = currentSurface.getFirstAnimationIndex();
	    sv.loadAnimation(""+animeIndex);
	    /*anime = (AnimationDrawable) currentSurface.getAnimation(animeIndex, getResources());
	    anime.setVisible(true, true);
	    iv.setImageDrawable(anime);*/
	    findViewById(R.id.btn2).setEnabled(true);
	}

    }

    SurfaceReader sr = null;
    String[] surfaceKeys = null;
    int keyindex = 0;
    //Set<String> surfaceKeys = null;
    String currentSurfaceKey = null;
    ShellSurface currentSurface = null;

    public void onNextSurface(View v){
	if ( keyindex < surfaceKeys.length - 1 )
	    keyindex++;
	else
	    keyindex = 0;
	
	currentSurfaceKey = surfaceKeys[keyindex];
	Log.d(TAG, "loading surface:" + currentSurfaceKey);
	currentSurface = mgr.getSakuraSurface(currentSurfaceKey);
	bSakura.setText("current drawable key: " + currentSurfaceKey + 
		   ", animation count: " + currentSurface.getAnimationCount() +
		   ", collision count: " + currentSurface.getCollisionCount()
		   );
	checkAndLoadAnimation();
    }
    int animeIndex = 0;

    public void onAnimate(View v) {
	//iv.setImageDrawable(anime);
// 	anime.stop(); // stop previous animation?
// 	anime.start();
	sv.startAnimation();
	
    }

    private void pickNextAnimation() {
	if ( currentSurface.getAnimationCount() > 1 ) {
	    animeIndex++;
	    if ( animeIndex >= currentSurface.getAnimationCount() )
		animeIndex = 0;

	    sv.loadAnimation(""+animeIndex);
	}
    }

    public void onShowCollision(View v){
	showCollisionAreaOnImageView();
    }


    public void ivClick(View v) {
	//anime.start();
	//showCollisionAreaOnImageView();
	pickNextAnimation();
	//runner.run();
    }

    private void showCollisionAreaOnImageView() {
	sv.showCollisionArea();
    }

    public void runClick(View v){
	String cmd ="\\habcdefghijklmnop\\uponmlkjihgfedcba\\e";
	runner.addMsgToQueue(new String[]{cmd});
	runner.run();
    }

}
